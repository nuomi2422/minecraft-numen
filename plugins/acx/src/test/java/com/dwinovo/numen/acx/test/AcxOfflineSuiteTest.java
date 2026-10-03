package com.dwinovo.numen.acx.test;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ★ 把 ACX 的 206 项离线自检接成 <b>真正的门禁</b>（2026-10-03 收口第 1 步）。
 *
 * <h3>为什么需要这个类</h3>
 * <p>{@code AcxTestMain}（3503 行 / 206 项检查）是一个 {@code main()} 手工台，
 * 靠 Gradle 的 {@code JavaExec}（{@code acxOfflineTest}）跑，而那个任务写着
 * {@code ignoreExitValue = true} ⇒ <b>它的失败不会让构建失败</b>，
 * 而且结果只打在控制台、<b>不落在测试报告里</b>。
 *
 * <p>后果是两条都很严重：
 * <ol>
 *   <li>本模块的 {@code :plugins:acx:test}（JUnit）<b>一个测试都没有</b>，
 *       Gradle 9 遇到「有测试源码但一个都没发现」直接<b>让任务失败</b>
 *       ⇒ 模块的 test 任务本来就是红的。</li>
 *   <li>更坏的是：任何「本模块测试全绿」的结论都<b>不包含这 206 项</b>
 *       —— 我们按惯例「从测试报告 XML 汇总数字」时永远看不到它。</li>
 * </ol>
 *
 * <h3>本类做的事</h3>
 * <p>在一个<b>子进程</b>里真跑那 206 项（必须子进程：{@code main} 结尾是
 * {@code System.exit}，同进程跑会把测试 JVM 一起带走），
 * 然后用 {@code -Dacx.resultFile} 让它写出机器可读的 JSON，再断言三件事：
 * <ol>
 *   <li><b>总数不许悄悄变</b>（少了说明有人删了检查项）。</li>
 *   <li><b>失败数必须恰好等于已知的 2 项</b> —— 多了说明出了新问题（这才是关键：
 *       原来的 {@code ignoreExitValue} 让新问题也照样静默）。</li>
 *   <li><b>那 2 项必须还是那两个已知缺陷</b>（循环变量跨断点看不见）——
 *       修好了要改这里，但<b>必须显式改</b>，不许它悄悄变红或悄悄变绿。</li>
 * </ol>
 *
 * <h3>已知的 2 项红（build.gradle 注释里写的 TODO(AC-B12)）</h3>
 * <p>{@code for} 循环里的变量，在<b>断点暂停后 resume</b> 之后看不到了
 * （期望拿到成功、实际拿到失败；另一个是「应绑定的变量为 null」）。
 * 这是<b>引擎真缺陷</b>，不是测试写错。
 */
class AcxOfflineSuiteTest {

    /** 已知红项数。修好那个 for/resume 缺陷后可以改小，但必须同时改下面的两条身份断言。 */
    private static final int KNOWN_RED = 2;

    /** 已知的两条红的判定片段 —— 「for 循环」+「断点」。 */
    private static final String[] KNOWN_RED_MARKERS = {"for", "断点"};

    @Test
    void theOfflineSuiteRunsAndItsKnownRedStaysVisible() throws Exception {
        // 与 Gradle 的 acxOfflineTest 用同一个工作目录：套件是按 ./ac-lib 找脚本库的
        Path workDir = Path.of("build", "acx-fixture").toAbsolutePath();
        assertTrue(Files.isDirectory(workDir),
                "找不到 acx-fixture=" + workDir + "；test 任务必须 dependsOn acxFixture，否则测的是不存在的东西");

        Path result = Files.createTempFile("acx-result", ".json");

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dacx.resultFile=" + result.toAbsolutePath());
        cmd.add("com.dwinovo.numen.acx.test.AcxTestMain");

        // ★★★ 输出必须重定向到**文件**，不能走管道。
        //   我第一版写的是 redirectErrorStream(true) + 退出后才 readAllBytes()
        //   ⇒ 经典的管道缓冲死锁：子进程把 64KB 管道写满就阻塞在 write，
        //   而我在等它退出才去读 ⇒ 双方互等。
        //   实测症状：子进程只烧了 1 秒 CPU 却 500 秒不退出（卡住，不是慢）。
        //   206 项每项一行输出早就超过 64KB，所以这个死锁是必然的，不是偶发。
        //   重定向到文件没有缓冲区上限，代价是要落一个临时文件（跑完删掉）。
        Path outFile = Files.createTempFile("acx-out", ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(outFile.toFile());

        Process proc = pb.start();
        // 时限按实测给，不按直觉给：同一套件经 gradle JavaExec 跑约 20 秒，
        // 这里是在 JUnit 内部再起一个 JVM（多一层 classpath 扫描 + 无 daemon 复用）。
        boolean finished = proc.waitFor(300, TimeUnit.SECONDS);
        // ⚠️ 输出文件按**字节**读，不按字符读。
        //   子进程的 stdout 走 Windows 控制台代码页（GBK），而 readString(UTF_8) 遇到
        //   非法字节序列会抛 MalformedInputException —— 我第一版就踩了（"Input length = 1"）。
        //   而这份输出**只用于失败时给人看**，机器判据一律走 -Dacx.resultFile 那个 JSON。
        //   所以这里不解释编码、按 ISO-8859-1 无损映射成字符即可，绝不能让它把测试搞挂。
        String out = Files.isRegularFile(outFile)
                ? new String(Files.readAllBytes(outFile), StandardCharsets.ISO_8859_1)
                : "";
        if (!finished) {
            // 超时必须杀掉，否则子进程会一直挂在后台（今天已经吃过一次「后台进程残留」的亏）
            proc.destroyForcibly();
            Files.deleteIfExists(result);
            fail("ACX 离线自检 300 秒没跑完。子进程已杀掉，结果文件已清理。\n"
                    + "它不该需要这么久 —— 这通常是死锁而不是变慢。输出尾部：\n" + tail(out));
        }
        // 临时输出文件用完即删（它是诊断用的，不该留在 build 目录里变成第二份历史）
        Files.deleteIfExists(outFile);
        // 子进程退出码不可信：AcxTestMain 结尾是 System.exit(T.summary())，
        //   而 summary() 只在「零失败」时返 0 ⇒ 有已知红项时退出码就是 1。
        //   所以判据走结果文件，不走退出码（这一点写在这里，免得下一个人又去信退出码）。

        assertTrue(Files.isRegularFile(result),
                "自检没有写出结果文件，说明它没跑到 summary() 就退出了。输出尾部：\n" + tail(out));

        String json = Files.readString(result, StandardCharsets.UTF_8);
        int total = intField(json);
        int failed = failedField(json);

        // ① 总数不许悄悄变 —— 有人删检查项时这里会红
        assertEquals(206, total, "离线自检的检查项数变了。少项多半是有人删了检查项，"
                + "那等于把覆盖度悄悄拿走了。结果文件：" + json);

        // ② ★ 最关键的一条：失败数必须「恰好」是已知的 2 项。
        //    原来 ignoreExitValue=true 的后果就是：新问题也一样静默。
        assertEquals(KNOWN_RED, failed,
                "离线自检的失败数不是已知的 " + KNOWN_RED + " 项。"
                        + "多了 = 出了新问题（这正是原来被静默掉的那类）；"
                        + "少了 = 那个 for/断点缺陷被修好了或被跳过了，两种都要显式处理。\n"
                        + "失败明细：" + failures(json) + "\n结果文件：" + json);

        // ③ 那 2 项必须还是「for 循环跨断点」那一个缺陷
        List<String> fails = failures(json);
        assertEquals(KNOWN_RED, fails.size(), "失败条数与 failed 字段不一致：" + json);
        for (String f : fails) {
            boolean isTheKnownOne = false;
            for (String marker : KNOWN_RED_MARKERS) {
                if (f.contains(marker)) {
                    isTheKnownOne = true;
                }
            }
            assertTrue(isTheKnownOne,
                    "出现了一条不是已知缺陷的失败项：" + f
                            + "\n★ 已知缺陷只有「for 循环里的变量跨断点看不见」这一个。"
                            + "新的失败项要先当真问题查，别直接改 KNOWN_RED。");
        }
    }

    // ---- 极简 JSON 取值（这机器可读文件是我们自己写的，形状固定，不必引依赖） ----

    private static int intField(String json) {
        return readInt(json, "\"total\":", "total");
    }

    private static int failedField(String json) {
        return readInt(json, "\"failed\":", "failed");
    }

    /**
     * 从固定形状的结果 JSON 里取一个整数字段。
     *
     * <p>★ 缺字段就抛，<b>不返 0</b> ——「0」与「没有」长得一样是本工程反复踩的坑
     * （见 60 号 §6.2「0 的三种含义」）。取不到就崩，比给个假的 0 好。
     */
    private static int readInt(String json, String key, String label) {
        int i = json.indexOf(key);
        if (i < 0) {
            throw new AssertionError("结果文件里没有 " + label + " 字段：" + json);
        }
        int from = i + key.length();
        int to = from;
        while (to < json.length() && Character.isDigit(json.charAt(to))) {
            to++;
        }
        if (to == from) {
            throw new AssertionError("结果文件里 " + label + " 后面不是数字：" + json);
        }
        return Integer.parseInt(json.substring(from, to));
    }

    /** 取出 failures 数组里的每一条（够用即可：这是我们自己写的固定形状）。 */
    private static List<String> failures(String json) {
        List<String> out = new ArrayList<>();
        int start = json.indexOf("\"failures\":[");
        if (start < 0) {
            return out;
        }
        int end = json.indexOf(']', start);
        if (end < 0) {
            return out;
        }
        String body = json.substring(start + 12, end);
        for (String part : body.split("\",\"")) {
            String s = part.replace("[\"", "").replace("\"", "").replace("\\\\", "\\");
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String tail(String s) {
        return s.length() <= 1200 ? s : s.substring(s.length() - 1200);
    }
}
