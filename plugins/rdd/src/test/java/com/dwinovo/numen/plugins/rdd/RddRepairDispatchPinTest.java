package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 死亡恢复顺序契约：备份优先，再评估距离、遮挡、威胁与剩余窗口，安全才回收。
 * rdd-core 在测试运行期不可用，因此直接断言生产源码中的步骤串与 nudge 措辞。
 */
class RddRepairDispatchPinTest {

    // ── 读源码 ─────────────────────────────────────────────────────

    private static Path pluginSourceDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path src = cur.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
            if (Files.isDirectory(src)) return src;
        }
        return cwd.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
    }

    private static String dispatch() {
        Path p = pluginSourceDir().resolve("RddRepairDispatch.java");
        assertTrue(Files.isRegularFile(p), "读不到源文件：" + p
                + "（本类靠源码文本定位，文件被挪位置/改名就会退化成空断言 —— 那比没有测试更危险）");
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读源文件失败：" + p + " → " + e);
            return "";
        }
    }

    /** 从 {@code fromIndex} 起找第一个 '{' 并做括号配平，返回该块内部文本（不含这对大括号）。 */
    private static String blockAt(String src, int fromIndex) {
        int open = src.indexOf('{', fromIndex);
        if (open < 0) {
            fail("从 " + fromIndex + " 起找不到 '{'");
            return "";
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open + 1, i);
            }
        }
        fail("括号没配平（从 " + open + " 起）");
        return "";
    }

    /** 抽 {@code static boolean onDeath(} 的方法体。 */
    private static String onDeath() {
        String src = dispatch();
        int sig = src.indexOf("static boolean onDeath(");
        assertTrue(sig >= 0, "自检：找不到 onDeath 的声明");
        return blockAt(src, sig);
    }

    /** 抽一个 {@code private static final String NAME = "....";} 的字面量值。 */
    private static String lit(String constName) {
        String src = dispatch();
        Matcher m = Pattern.compile(Pattern.quote(constName) + "\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(src);
        assertTrue(m.find(), "自检：抽不到常量 " + constName + " 的字面量（它可能被改名或挪走）");
        return m.group(1);
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** 断言 needle 在 body 里按给定顺序出现；任一缺失都点名报错。 */
    private static void assertOrder(String body, String what, String... needlesInOrder) {
        int prev = -1;
        String prevName = null;
        for (String needle : needlesInOrder) {
            int at = body.indexOf(needle, prev + 1);
            assertTrue(at >= 0, "★ " + what + "：找不到 '" + needle + "'"
                    + (prevName == null ? "" : "（在 '" + prevName + "' 之后）")
                    + " —— 这一段被删掉或挪走了");
            assertTrue(at > prev, "★ " + what + "：'" + needle + "' 必须排在 '" + prevName + "' 之后");
            prev = at;
            prevName = needle;
        }
    }

    private static void assertHas(String body, String needle, String why) {
        assertTrue(body.contains(needle), "★ " + why + " —— 找不到 '" + needle + "'");
    }

    // ═══════════════════════════════════════════════════════════════
    //  机制自检：先证明这套读法真的抓到了东西
    // ═══════════════════════════════════════════════════════════════

    @Test
    void extractionMechanismItselfWorks() {
        assertTrue(dispatch().contains("class RddRepairDispatch"), "自检：必须真的读到 RddRepairDispatch 源码");
        String body = onDeath();
        assertTrue(body.length() > 800, "自检：onDeath 方法体应远超 800 字符，实际 " + body.length());
        assertTrue(body.contains("repeatDeath") && body.contains("RddPlugin.nudge("),
                "自检：onDeath 方法体必须同时含 repeatDeath 与 nudge（说明抓对了方法而不是抓到别的块）");
        // 两条 nudge 必须都在（一条重复死亡、一条普通死亡），否则下面的分支断言是空转
        assertEquals(2, countOf(body, "RddPlugin.nudge("),
                "自检：onDeath 里应恰好 2 条 nudge（重复死亡 / 普通死亡），实际 "
                        + countOf(body, "RddPlugin.nudge("));
    }

    // ═══════════════════════════════════════════════════════════════
    // 普通死亡：先备份，再评估，安全才回收
    // ═══════════════════════════════════════════════════════════════

    @Test
    void normalDeathFetchesBackupBeforeAssessingAndRecovering() {
        assertOrder(lit("DEFAULT_STEPS"), "非重复死亡的支线步骤",
                "先回基地取齐", "再评估死亡点", "仅安全可达才回收");
        assertOrder(lit("DEFAULT_GOAL"), "非重复死亡的支线目标", "备份优先", "再评估安全回收");
        assertHas(dispatch(), "\"fetch_backup_gear\", \"assess_recovery\", \"recover_drops_if_safe\"",
                "机读步骤必须按备份、评估、安全回收排序");
    }

    @Test
    void normalDeathNudgeRepeatsTheSameOrder() {
        String body = onDeath();
        int tail = body.indexOf("你刚死一次");
        assertTrue(tail >= 0, "★ 找不到普通死亡那条 nudge（它必须把顺序讲给 AI 听，"
                + "而不是只写在常量里 —— 常量是给规划器看的，nudge 是给她看的）");
        assertOrder(body.substring(tail), "普通死亡的 nudge 措辞",
                "先回基地取齐", "再评估", "仅安全可达才回收");
        assertHas(body.substring(tail), "夜间或危险先睡觉/等到白天",
                "夜间或危险必须先等待，不能盲冲回收");
    }

    // ═══════════════════════════════════════════════════════════════
    //  RL-7 硬约束 · 死亡坐标必传 + 5 分钟窗口必须在眼前
    // ═══════════════════════════════════════════════════════════════

    @Test
    void deathCoordinateIsAlwaysHandedOver() {
        for (String c : new String[]{"DEFAULT_STEPS", "REPEAT_DEATH_STEPS"}) {
            assertHas(lit(c), "{at}", c + " 必须留 {at} 占位符并在派发时替换成死亡坐标 —— "
                    + "漏传坐标 = 她不知道去哪捡（RL-7 的头号失败形态）");
        }
        String body = onDeath();
        assertHas(body, "String at = (deathAt == null || deathAt.isBlank()) ? \"死亡点\" : deathAt;",
                "★ 坐标为空时必须兜底成「死亡点」而不是留空串 —— 空串会让 nudge 说"
                        + "「掉落物在  附近」，她无从判断该往哪走");
        // ⚠️ 刻意**不断言这两个 replace 的先后**：源码是三元
        //    `repeatDeath ? REPEAT_DEATH_STEPS.replace(...) : DEFAULT_STEPS.replace(...)`，
        //    REPEAT 天然写在前面；书写顺序不是语义。「哪个串配哪个分支」由
        //    repeatDeathReversesTheOrderButKeepsTheCountdown 钉整条三元。
        assertHas(body, "REPEAT_DEATH_STEPS.replace(\"{at}\", at)", "重复死亡串必须替换 {at}");
        assertHas(body, "DEFAULT_STEPS.replace(\"{at}\", at)", "普通死亡串必须替换 {at}");

        // 两条 nudge 各自都必须把坐标拼进正文。⚠️ 刻意**不数全方法体总数**：
        // 我第一版断言「全方法体 2 处」红了，实际 3 处（普通那条把坐标说了两遍）。
        // 改成「总数 3」来迁就更糟 —— 断言总数会让「删掉一处重复」也变红，而重复不是红线。
        // 正确写法：按块断言。
        String repeat = body.substring(body.indexOf("回去拿掉落物不是现在该做的事"));
        String normal = body.substring(body.indexOf("你刚死一次"));
        assertHas(repeat, "+ at +",
                "★ 重复死亡那条 nudge 必须带死亡坐标（它的重点是「先离开」，最容易把「去哪」漏掉）");
        assertHas(normal, "+ at +", "★ 普通死亡那条 nudge 必须带死亡坐标");
        assertEquals(2, countOf(normal, "+ at +"),
                "普通死亡那条 nudge 应把坐标说两遍：先说明掉落位置，再说明要评估的死亡点。"
                        + "两遍是给她自己定位用的（东西在哪 vs 要去哪），只剩一处她会分不清");
    }

    @Test
    void fiveMinuteDropWindowStaysVisibleAndIsTheTaskDeadline() {
        assertHas(lit("DEFAULT_STEPS"), "5 分钟", "支线步骤必须写明掉落物约 5 分钟消失（限时优先的理由）");
        String body = onDeath();
        int tail = body.indexOf("你刚死一次");
        assertHas(body.substring(tail), "5 分钟",
                "★ 普通死亡的 nudge 必须把「约 5 分钟就会消失」讲在她眼前 —— "
                        + "她不知道窗口有多短，就会顺手先去把别的事做完");
        assertHas(body, "5 * 60 * 20",
                "★ 支线自身的到期 tick 必须仍是 5 分钟（20 tick/s）。"
                        + "改短 = 她还在路上支线就过期；改长 = 白占一条支线的时限");
    }

    // ═══════════════════════════════════════════════════════════════
    //  RL-16 分支 · 同点连死：顺序反转，但倒计时不许消失
    // ═══════════════════════════════════════════════════════════════

    @Test
    void repeatDeathLeavesAndResuppliesBeforeAssessingRecovery() {
        assertOrder(lit("REPEAT_DEATH_STEPS"), "重复死亡的支线步骤",
                "立刻离开", "回基地补血", "评估距离", "仅安全可达才回收");
        assertHas(lit("REPEAT_DEATH_STEPS"), "窗口",
                "★ 反转顺序不等于取消倒计时（R07 已冻结：剩余秒数必须在它眼前）");
        assertOrder(lit("REPEAT_DEATH_GOAL"), "重复死亡的支线目标", "先撤离、补给并评估", "再安全回收掉落物");

        String body = onDeath();
        int head = body.indexOf("回去拿掉落物不是现在该做的事");
        assertTrue(head >= 0, "★ 找不到重复死亡那条 nudge");
        assertOrder(body.substring(head), "重复死亡的 nudge 措辞",
                "立刻离开", "取齐穿好备用", "评估死亡点", "仅安全可达才回去捡");
        assertHas(body.substring(head), "+ timeline",
                "★ 重复死亡也必须带上 dropTimeline 台账（连死两次时更要看得见上一批还剩多少秒）");
        // 两条分支都由同一个 flag 选择，不许某条被写死成某一种
        assertHas(body, "repeatDeath ? REPEAT_DEATH_STEPS.replace(\"{at}\", at) : DEFAULT_STEPS.replace(\"{at}\", at)",
                "★ 两条步骤串必须由 repeatDeath 选择（写死任何一条 = 另一条分支从此不可达）");
        assertHas(body, "repeatDeath ? REPEAT_DEATH_GOAL : DEFAULT_GOAL",
                "★ 两条目标串必须由 repeatDeath 选择");
    }

    // ═══════════════════════════════════════════════════════════════
    //  兜底 · 护栏否决时不得留下任何一条 nudge
    // ═══════════════════════════════════════════════════════════════

    @Test
    void guardSuppressionHappensBeforeAnyNudge() {
        String body = onDeath();
        int gate = body.indexOf("d != LocalRepairTask.Guard.Decision.ALLOW");
        assertTrue(gate >= 0, "自检：找不到护栏判定（连续失败会压掉后续死亡支线，"
                + "钉死它是为了防止「压制」被改成「照发不误」）");
        int firstNudge = body.indexOf("RddPlugin.nudge(");
        assertTrue(gate < firstNudge,
                "★ 护栏否决必须发生在任何 nudge 之前（gate=" + gate + " vs nudge=" + firstNudge
                        + "）。晚一步 = 被压制的死亡仍然提示她「去捡」，"
                        + "连续失败机制就变成了反复骚扰");
        assertOrder(body, "护栏否决分支",
                "d != LocalRepairTask.Guard.Decision.ALLOW", "repair_task_suppressed", "return false;");
    }
}
