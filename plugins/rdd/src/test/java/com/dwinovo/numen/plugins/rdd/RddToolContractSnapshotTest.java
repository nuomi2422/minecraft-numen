package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T6 · 工具契约快照（RL-13「工具契约不做假承诺」+ RL-21「不得为省 token 截断 description/schema」）。
 *
 * <h2>为什么不能直接 new 出一个工具来测</h2>
 *
 * <p>本模块的 {@code build.gradle} 用 {@code compileOnly files(...apiJar)} <b>故意</b>封死跨插件 import，
 * 而 {@code compileOnly} 不会传播到测试运行期。因此工具类一旦实现 {@code NumenTool}
 * （带 {@code NumenApi} 参数），在模块测试里就会：编译期报「无法访问 NumenPlugin」、
 * 或者 {@code Class.forName} 在运行期 {@code NoClassDefFoundError}。
 *
 * <p>这条路走不通（改 build.gradle 属于工程规矩禁手写的 gradle 改动）。
 * 已有先例 {@code RedlineContractPinTest} 的 javadoc 已经把这件事讲清楚了，
 * 本类沿用它的做法：<b>读源码文本 + 正则取字面量</b>。
 *
 * <h2>这个类在防什么</h2>
 *
 * <ol>
 *   <li><b>RL-21 的复发</b>：为了省 token 把 {@code description} 截短。2026-10-02 已经因此回退过 3 刀，
 *       依据是原作者云端分支的 {@code BuildTool.java} / {@code ToolDisclosure.java} 一字不差。
 *       截短的直接后果是 AI 不知道这个工具「什么时候用哪个」→ 用错工具 / 不调用。</li>
 *   <li><b>工具名被悄悄改掉</b>：名字是协议的一部分，改了等于换了个工具，
 *       而 AI 的历史回执里还写着老名字。</li>
 *   <li><b>工具被静默注销</b>：注册行被挪掉，工具不在目录里了，但没有任何测试会红。</li>
 * </ol>
 *
 * <p>零生产改动、零构建改动、零 Minecraft。
 */
class RddToolContractSnapshotTest {

    // ── 读源码的基础设施（沿用 RedlineContractPinTest 的方法论）──────

    private static Path pluginSourceDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path src = cur.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
            if (Files.isDirectory(src)) return src;
        }
        return cwd.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
    }

    private static String read(String fileName) {
        Path p = pluginSourceDir().resolve(fileName);
        assertTrue(Files.isRegularFile(p), "读不到源文件：" + p);
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读源文件失败：" + p + " → " + e);
            return "";
        }
    }

    /** 取 {@code name()} 方法返回的字符串字面量。 */
    private static String nameLiteralOf(String fileName) {
        String src = read(fileName);
        Matcher m = Pattern.compile("String\\s+name\\(\\)\\s*\\{[^}]*?return\\s+\"([^\"]*)\"").matcher(src);
        assertTrue(m.find(), fileName + " 里找不到 name() 的字面量返回");
        return m.group(1);
    }

    /**
     * 取 description 的字符数：把 {@code description()} 方法体里的字符串字面量拼起来数。
     * 多段拼接（{@code "..." + "\n" + "..."}）也能覆盖。
     */
    private static int descriptionLengthOf(String fileName) {
        String src = read(fileName);
        Matcher open = Pattern.compile("String\\s+description\\(\\)\\s*\\{").matcher(src);
        assertTrue(open.find(), fileName + " 里找不到 description() 方法");
        int start = open.end();
        int end = braceEnd(src, start);
        String body = src.substring(start, end);
        Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        StringBuilder joined = new StringBuilder();
        while (lit.find()) joined.append(lit.group(1));
        return joined.length();
    }

    /** 取 description 全文（用于关键句断言）。 */
    private static String descriptionTextOf(String fileName) {
        String src = read(fileName);
        Matcher open = Pattern.compile("String\\s+description\\(\\)\\s*\\{").matcher(src);
        assertTrue(open.find(), fileName + " 里找不到 description() 方法");
        int start = open.end();
        int end = braceEnd(src, start);
        String body = src.substring(start, end);
        Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        StringBuilder joined = new StringBuilder();
        while (lit.find()) joined.append(lit.group(1));
        return joined.toString();
    }

    /** 从 from '{' 起做括号配平，返回配对 '}' 之后的下标。 */
    private static int braceEnd(String src, int fromIndex) {
        int depth = 0;
        for (int i = fromIndex; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        fail("括号没配平（从 " + fromIndex + " 起）");
        return -1;
    }

    // ── ① 工具名是协议的一部分 ───────────────────────────────────────

    /** 七个已注册工具的名字。改名 = 换了个工具，AI 的历史回执会失效。 */
    private static final Map<String, String> PINNED_NAMES = new LinkedHashMap<>();

    static {
        PINNED_NAMES.put("RddStatusTool.java", "rdd_status");
        PINNED_NAMES.put("RddSubmitTool.java", "rdd_submit");
        PINNED_NAMES.put("RddSkipTool.java", "rdd_skip_optional");
        PINNED_NAMES.put("RddAssetsTool.java", "rdd_assets");
        PINNED_NAMES.put("RddConcernTool.java", "report_task_concern");
        PINNED_NAMES.put("RddStandbyTool.java", "rdd_standby");
        PINNED_NAMES.put("RddDebugKillTool.java", "debug_kill");
        // G2b：死亡台账只读查询（V-20261020-17 债②：超时结案在事件里有、可回读的查询面没有）
        PINNED_NAMES.put("RddDeathLedgerTool.java", "rdd_death_ledger");
        // G2c：验证钩子（用户 2026-10-20 批准）——清掉落物以构造超时分支
        PINNED_NAMES.put("RddDebugClearDropsTool.java", "debug_clear_drops");
        // G3d：计划补丁入口（CAS 提交；只碰当前指针及之后的 PENDING 节点）
        PINNED_NAMES.put("RddPatchTool.java", "rdd_patch");
        // 2026-10-23：恢复支线调试入口——点名一条 recovery 支线，验证"丢了东西→插支线→回主线"
        // 这条链在 8776 上看不到（side_task_* 不进程经验链），没有它就只能干等真实场景。
        PINNED_NAMES.put("RddDebugRecoveryTool.java", "rdd_debug_recovery");
        // 2026-10-27（主人指示）：代码层直发 Supervisor——实验台跑超长线任务的注册口，
        // 与 /goal 同一条规划真路（Stage-A），source 标 rdd_supervisor_submit 供监测台区分。
        PINNED_NAMES.put("RddSupervisorSubmitTool.java", "rdd_supervisor_submit");
        // 2026-10-27: read-only diagnosis for the reported dimension-change invulnerability.
        PINNED_NAMES.put("RddDebugVulnerabilityTool.java", "debug_vulnerability");
        // 2026-10-28（主人第一点）：现场数量回报入口 —— 不知道村庄有多少干草块时，
        // 规划只能给最低数；到了现场数清后由她把这步的目标抬到实际值（只许上调）。
        PINNED_NAMES.put("RddReportYieldTool.java", "rdd_report_yield");
        // 2026-10-28（主人第三点）：支线可以拒绝 —— 支线文案此前写着"最高优先"且没有出口，
        // 有了它，"太危险/没工具/不可能"才是一个她真能做的决定，而不是只能照着办。
        PINNED_NAMES.put("RddDeclineSideTool.java", "rdd_decline_side");
        // 2026-10-28（主人第二/四点）：后面几级整段重写 —— 丢了核心装备、后面整条路不成立时，
        // 规划器终于有地方落笔（当前一级及已完成的一字不动）。
        PINNED_NAMES.put("RddReplanChainTool.java", "rdd_replan_chain");
        // 2026-10-28（主人：「提前收口这种东西是规划者决定的」）：一级被"事实达成/不再需要"时，
        // 规划者用它收口（记阶段事实、当前级立即推进）。代码不给物品表 —— 判断权在人。
        PINNED_NAMES.put("RddSettleStageTool.java", "rdd_settle_stage");
    }

    @Test
    void toolNamesArePinned() {
        for (Map.Entry<String, String> e : PINNED_NAMES.entrySet()) {
            assertEquals(e.getValue(), nameLiteralOf(e.getKey()),
                    "★ 工具名 " + e.getValue() + " 被改了。名字是协议的一部分：改了等于换了个工具，"
                            + "而 AI 的历史回执 / 观测日志里还写着老名字");
        }
    }

    @Test
    void everyPinnedToolIsStillRegistered() {
        String plugin = read("RddPlugin.java");
        for (String className : PINNED_NAMES.keySet()) {
            String simple = className.replace(".java", "");
            assertTrue(plugin.contains("new " + simple + "("),
                    "★ " + simple + " 已不在 RddPlugin 的注册列表里。静默注销工具比改坏工具更隐蔽："
                            + "代码还在、能编译，但 AI 的工具目录里根本没有它");
        }
    }

    @Test
    void registrationListHasNotGrownOrShrunkSilently() {
        String plugin = read("RddPlugin.java");
        List<String> registered = new ArrayList<>();
        Matcher m = Pattern.compile("new\\s+(Rdd\\w+Tool)\\(").matcher(plugin);
        while (m.find()) registered.add(m.group(1));
        registered.sort(String::compareTo);
        List<String> expected = new ArrayList<>(PINNED_NAMES.keySet().stream()
                .map(s -> s.replace(".java", "")).sorted().toList());
        assertEquals(expected, registered,
                "★ 已注册工具集合与本类钉住的不一致。加工具请同步本类并说明它解决什么问题；"
                        + "少工具请说明为什么撤（工具目录缩小 = AI 少一条路可走）。当前：" + registered);
    }

    // ── ② description 长度下限（RL-21 的直接护栏）───────────────────

    /**
     * 各工具 description 的当前字符数（2026-10-02 实测）。
     * 下限取「当前值 − 25%」，既能抓住「截短了」又不会因为正常补充措辞而误报。
     */
    private static final Map<String, Integer> DESCRIPTION_FLOOR = new LinkedHashMap<>();

    static {
        DESCRIPTION_FLOOR.put("RddStatusTool.java", 250);
        DESCRIPTION_FLOOR.put("RddSubmitTool.java", 200);
        DESCRIPTION_FLOOR.put("RddSkipTool.java", 150);
        DESCRIPTION_FLOOR.put("RddAssetsTool.java", 120);
        DESCRIPTION_FLOOR.put("RddConcernTool.java", 150);
        DESCRIPTION_FLOOR.put("RddStandbyTool.java", 60);
        DESCRIPTION_FLOOR.put("RddDebugKillTool.java", 40);
        // G2b 新工具：三档状态的措辞纪律是契约本体，下限按当前长度的 ~75% 取
        DESCRIPTION_FLOOR.put("RddDeathLedgerTool.java", 300);
        // G2c 验证钩子：必须继续写明「只删 ItemEntity、不碰台账/任务」与 confirm 要求
        DESCRIPTION_FLOOR.put("RddDebugClearDropsTool.java", 280);
        DESCRIPTION_FLOOR.put("RddPatchTool.java", 400);
        // 2026-10-28：三条新工具的下限按当前长度的 ~75% 取。它们的 description 就是"什么时候用"
        // 的全部依据（尤其 rdd_decline_side：写不清"可以拒绝"就退化成没有这个工具）。
        DESCRIPTION_FLOOR.put("RddReportYieldTool.java", 380);
        DESCRIPTION_FLOOR.put("RddDeclineSideTool.java", 380);
        DESCRIPTION_FLOOR.put("RddReplanChainTool.java", 275);
        DESCRIPTION_FLOOR.put("RddSettleStageTool.java", 550);
    }

    @Test
    void descriptionsHaveNotBeenTruncated() {
        for (Map.Entry<String, Integer> e : DESCRIPTION_FLOOR.entrySet()) {
            int actual = descriptionLengthOf(e.getKey());
            assertTrue(actual >= e.getValue(),
                    "★ " + e.getKey() + " 的 description 被截短了：当前 " + actual + " 字符，下限 "
                            + e.getValue() + "。RL-21 明确禁止为省 token 截断 description/schema —— "
                            + "2026-10-02 已经因此回退过 3 刀（判据是原作者云端 BuildTool.java / "
                            + "ToolDisclosure.java 一字不差）。截短的直接后果：AI 不知道"
                            + "「什么时候该用哪个」，于是用错工具或不调用。");
        }
    }

    @Test
    void descriptionHasNoUnexplainedPlaceholder() {
        for (String file : PINNED_NAMES.keySet()) {
            String d = descriptionTextOf(file);
            assertFalse(d.contains("TODO"), file + " 的 description 里留着 TODO：发给模型的是占位文字");
            assertFalse(d.contains("...") && d.length() < 60,
                    file + " 的 description 像是被省略号掐断的短句：" + d);
        }
    }

    // ── ③ 关键承诺句必须逐字还在（这是工具契约的本体）───────────────

    @Test
    void skipToolStillPromisesItRecordsSkippedNotCompleted() {
        String d = descriptionTextOf("RddSkipTool.java");
        assertTrue(d.contains("Records SKIPPED, never COMPLETED"),
                "★ rdd_skip_optional 必须继续承诺「记的是 SKIPPED，不是 COMPLETED」。"
                        + "AI 是靠这句话才不会以为那件事做过了；删掉它 = 工具开始做假承诺（RL-13）。"
                        + "当前全文：" + d);
    }

    @Test
    void skipToolStillRefusesNonFoodAndNonOptionalSteps() {
        String d = descriptionTextOf("RddSkipTool.java");
        assertTrue(d.contains("optional=false"),
                "必须继续告诉模型：显式标了 optional=false 的步不许跳过。当前全文：" + d);
        assertTrue(d.contains("Refuses") || d.contains("refuses") || d.contains("拒绝"),
                "必须继续说明这个工具会拒绝哪些步（否则模型会拿它跳装备/进度步）。当前全文：" + d);
    }

    @Test
    void skipToolStillTellsTheModelToReadStatusFirst() {
        String d = descriptionTextOf("RddSkipTool.java");
        assertTrue(d.contains("Read rdd_status") && d.contains("expected_subtask_id"),
                "★ 必须继续要求「先读 rdd_status 拿 expected_subtask_id」。"
                        + "这是防张冠李戴的唯一一道提示：删掉它，模型就会拿上一轮的 id 来跳本轮的步。"
                        + "当前全文：" + d);
    }

    @Test
    void statusToolStillWarnsAgainstReplacingTheWholeChain() {
        String d = descriptionTextOf("RddStatusTool.java");
        assertTrue(d.contains("do not replace the whole chain"),
                "★ rdd_status 必须继续警告「不要用状态工具替换整条链」。当前全文：" + d);
    }

    @Test
    void submitToolStillListsItsRequiredFields() {
        String d = descriptionTextOf("RddSubmitTool.java");
        for (String field : List.of("goal", "primary_goal", "subtask", "asset_key", "minimum")) {
            assertTrue(d.contains(field),
                    "rdd_submit 的必填字段清单必须包含 " + field + "。少写一个字段 = 模型提交后建链失败。当前全文：" + d);
        }
    }

    @Test
    void concernToolStillExplainsTheFourKinds() {
        String d = descriptionTextOf("RddConcernTool.java");
        for (String kind : List.of("ACCEPT", "REJECT", "COUNTER", "PAUSE")) {
            assertTrue(d.contains(kind),
                    "report_task_concern 的 description 必须列出 " + kind
                            + " 的含义。少写一种 = 模型只能靠猜（深审 R05 的根因就是「不认识的枚举值被静默降级」）");
        }
    }

    @Test
    void standbyToolStillSaysItIsOwnerOnly() {
        String d = descriptionTextOf("RddStandbyTool.java");
        assertTrue(d.contains("Owner-only"),
                "★ rdd_standby 必须继续标明「仅主人」。它是一个会直接改变同伴行为（停在原地）的工具，"
                        + "如果同伴自己觉得可以用，主人就失去了「按住它」的能力。当前全文：" + d);
    }

    // ── ④ 机制自检 ─────────────────────────────────────────────────

    @Test
    void sourceReadingMechanismItselfWorks() {
        // 如果上面全是因为「读不到文件 / 正则不匹配」而 vacuous pass，那本类等于没写。
        assertTrue(read("RddPlugin.java").contains("class RddPlugin"),
                "自检：必须真的读到了 RddPlugin 源码");
        assertTrue(descriptionLengthOf("RddStatusTool.java") > 0, "自检：description 提取必须能算出长度");
        assertEquals("rdd_status", nameLiteralOf("RddStatusTool.java"), "自检：name 提取必须可用");
        // "{ a { b } }" 的配对 '}' 在下标 10（下标 8 那个只闭合内层）
        assertEquals(10, braceEnd("{ a { b } }", 0), "自检：括号配平函数必须可用");
    }

    @Test
    void multiLineDescriptionConcatenationIsCountedNotTruncated() {
        // RddSubmitTool 的 description 是多段拼接，这里确认我们数的是拼起来的全量
        String body = read("RddSubmitTool.java");
        assertTrue(body.contains("+"),
                "自检：rdd_submit 的 description 应是多段拼接（若不是，本类的拼接提取需要复核）");
        assertTrue(descriptionLengthOf("RddSubmitTool.java")
                        > descriptionTextOf("RddSubmitTool.java").length() * 0.5,
                "自检：拼接后的长度不应远小于首段长度");
    }
}