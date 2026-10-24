package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2 · 状态机不变量（RL-15「凡状态必须活得过重启」的<b>合法性</b>那一半）。
 *
 * <h2>为什么与 T1 分开</h2>
 *
 * <p>{@link TaskChainRestartRoundTripTest} 盯的是「恢复出来的东西还对不对」；
 * 本类盯的是「<b>根本不该被恢复出来</b>的那些东西，一出现就必须大声拒绝」。
 * 两者的失败后果完全不同：T1 挂 = 丢状态、AI 重做；本类挂 = 带着非法状态进执行器，
 * 变成<b>静默卡死</b>或<b>假完成</b>——那是最难查的一类事故。
 *
 * <h2>立场更新（2026-10-24 主人拍板 1，rdd.audit-sweep→repair-sweep）</h2>
 * <p>恢复语义已定为「<b>清理脏状态为规范，但清理必须留结构化账</b>」（拒绝语义每次逼人手动清场，
 * 违背无人值守目标；f24b7c752 起生效）。本类随之改判：损坏数据不再期待抛异常，
 * 而是期待「被清掉 + {@code lastSanitization()} 账上点名」。<b>「清理了但账是 null」= 本类要抓的红</b>——
 * 那才是「悄悄丢进 /dev/null」在新时代的形态。仍保留抛异常的是结构性损坏
 * （重复 id / 指针越界 / 整表缺失）：那些不是「脏数据」是「坏结构」，清理救不了。
 *
 * <p>本类零生产改动、零构建改动、零 Minecraft。
 */
class TaskChainInvariantsTest {

    private static Subtask hc(String id) {
        return Subtask.hardCoded(id, "do " + id, Map.of("asset_key", "minecraft:stone", "minimum", 1));
    }

    private static TaskChain chain(PrimaryGoal... primaries) {
        return new TaskChain(new Goal("g", "goal", List.of(primaries)));
    }

    private static String jsonOf(PrimaryGoal... primaries) {
        return chain(primaries).toJson();
    }

    private static TaskChain restore(String json) {
        return TaskChain.fromJson(json);
    }

    // ── ① 状态表必须与 goal 的二级集合<b>恰好相等</b> ─────────────────

    @Test
    void statusTableExactlyMatchesGoalSubtaskIds() {
        var c = chain(new PrimaryGoal("p1", "阶段一", List.of(hc("s1"), hc("s2"))),
                new PrimaryGoal("p2", "阶段二", List.of(hc("s3"))));
        assertEquals(java.util.Set.of("s1", "s2", "s3"), c.subtaskStatuses().keySet(),
                "钉住前自检：构造后状态表必须覆盖全部二级");
    }

    @Test
    void statusTableWithExtraKeyIsCleanedAndAccounted() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"statuses\":{\"s1\":\"PENDING\"}", "\"statuses\":{\"s1\":\"PENDING\",\"ghost\":\"PENDING\"}");
        var c = restore(json);
        assertNull(c.subtaskStatuses().get("ghost"), "幽灵条目必须被清掉（拍板 1：清理为规范）");
        var report = c.lastSanitization();
        assertNotNull(report, "★ 清理必须留账（拍板 1）——账是 null 就是悄悄丢进 /dev/null");
        assertTrue(report.droppedStatuses().contains("ghost"), "账必须点名被丢弃的幽灵 id");
    }

    @Test
    void statusTableWithMissingKeyIsRegisteredAndAccounted() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))))
                .replaceAll("\"statuses\":\\{[^}]*\\}", "\"statuses\":{\"s1\":\"PENDING\"}");
        var c = restore(json);
        assertEquals(SubtaskStatus.PENDING, c.subtaskStatuses().get("s2"),
                "缺登记的二级补成 PENDING（不再拒绝——拒绝语义逼人手动清场）");
        var report = c.lastSanitization();
        assertNotNull(report, "★ 补登记也必须留账（拍板 1）");
        assertTrue(report.registeredAsPending().contains("s2"), "账必须点名补登记的 id");
    }

    @Test
    void missingStatusTableEntirelyIsRejected() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replaceAll("\"statuses\":\\{.*?\\},", "");
        assertThrows(IllegalArgumentException.class, () -> restore(json),
                "整个 status 表缺失必须拒绝（不是「当作全 PENDING」——那等于伪造状态）");
    }

    @Test
    void duplicateSubtaskIdInGoalIsRejected() {
        // 两个二级共用同一个 id：状态表会塌成一个条目，于是「期望集合」与「实际集合」看起来相等，
        // 只有显式的重名检查才能抓住。
        var dup = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s1"))));
        assertThrows(IllegalArgumentException.class, () -> restore(dup.toJson()),
                "★ 同一条链里两个二级共用 id 必须拒绝：回执/跳过/基线全靠 id 定位，重名 = 张冠李戴");
    }

    // ── ② reason 表只允许挂在对应的状态上 ──────────────────────────

    @Test
    void skipReasonOnNonSkippedIsCleanedAndAccounted() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"skipReasons\":{}", "\"skipReasons\":{\"s1\":\"nope\"}");
        var c = restore(json);
        assertNull(c.skipReason("s1"), "不成立的 skipReason 清掉（挂在非 SKIPPED 上）");
        var report = c.lastSanitization();
        assertNotNull(report, "★ 清理必须留账");
        assertTrue(report.droppedSkipReasons().contains("s1"));
    }

    @Test
    void pauseReasonOnNonPausedIsCleanedAndAccounted() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"pauseReasons\":{}", "\"pauseReasons\":{\"s1\":\"nope\"}");
        var c = restore(json);
        assertNull(c.pauseReason("s1"), "不成立的 pauseReason 清掉");
        var report = c.lastSanitization();
        assertNotNull(report, "★ 清理必须留账");
        assertTrue(report.droppedPauseReasons().contains("s1"));
    }

    @Test
    void reasonReferencingUnknownSubtaskIsCleanedAndAccounted() {
        String skip = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"skipReasons\":{}", "\"skipReasons\":{\"ghost\":\"nope\"}");
        var c1 = restore(skip);
        assertNull(c1.skipReason("ghost"), "幽灵 skipReason 清掉");
        assertNotNull(c1.lastSanitization(), "skipReason 清理必须留账");
        assertTrue(c1.lastSanitization().droppedSkipReasons().contains("ghost"));

        String pause = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"pauseReasons\":{}", "\"pauseReasons\":{\"ghost\":\"nope\"}");
        var c2 = restore(pause);
        assertNull(c2.pauseReason("ghost"), "幽灵 pauseReason 清掉");
        assertNotNull(c2.lastSanitization(), "pauseReason 清理必须留账");
        assertTrue(c2.lastSanitization().droppedPauseReasons().contains("ghost"));
    }

    @Test
    void attemptsReferencingUnknownSubtaskIsCleanedAndAccounted() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"attempts\":{}", "\"attempts\":{\"ghost\":3}");
        var c = restore(json);
        assertEquals(0, c.attempts("ghost"), "孤儿 attempts 清零");
        var report = c.lastSanitization();
        assertNotNull(report, "★ attempts 清理必须留账（预算账本不许有静默孤儿）");
        assertTrue(report.droppedAttempts().contains("ghost"));
    }

    @Test
    void acquireBaselineReferencingUnknownSubtaskIsCleanedAndAccounted() {
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                .replace("\"acquireBaselines\":{}", "\"acquireBaselines\":{\"ghost\":{\"minecraft:stone\":1}}");
        var c = restore(json);
        assertNull(c.acquireBaseline("ghost"), "幽灵基线清掉");
        var report = c.lastSanitization();
        assertNotNull(report, "★ 基线清理必须留账（拍板 1）");
        assertTrue(report.droppedAcquireBaselines().contains("ghost"));
    }

    @Test
    void acquireBaselineMayOutliveTheRunItWasCapturedIn() {
        // 重试/暂停期间基线继续存在是<b>合法</b>的：所以这里只校验引用完整性，不校验「必须正在跑」。
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(
                Subtask.hardCoded("s1", "gather", Map.of(
                        "asset_key", "minecraft:stone", "minimum", 1, "mode", "acquire")))));
        c.startCurrentWithCounts(Map.of("minecraft:stone", 2));
        c.markFailed("s1", "boom");
        var r = restore(c.toJson());
        assertNotNull(r.acquireBaseline("s1"),
                "重试/暂停期间基线必须继续存在（不许因为「现在没在跑」就清掉 —— "
                        + "清了之后重试的净增判据立刻变恒假，AI 做对了也被判失败）");
    }

    // ── ③ ACTIVE + 未展开 = 静默卡死守卫 ───────────────────────────

    @Test
    void activeUnexpandedPrimaryIsRejected() {
        var unexpanded = new PrimaryGoal("p", "等展开", List.of(), List.of(), true);
        var c = new TaskChain(new Goal("g", "goal", List.of(unexpanded)));
        String json = c.toJson().replace("\"primaryStatus\":\"PENDING\"", "\"primaryStatus\":\"ACTIVE\"");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> restore(json),
                "★ ACTIVE 的当前一级却没有二级 = 状态分支全部落空 = 永远 return 的静默卡死。必须拒绝。");
        assertTrue(e.getMessage().contains("unexpanded"), "报错要点名「未展开」这个真正的病根");
    }

    @Test
    void unexpandedPrimaryWithNonZeroSubtaskIndexIsRejected() {
        var unexpanded = new PrimaryGoal("p", "等展开", List.of(), List.of(), true);
        var c = new TaskChain(new Goal("g", "goal", List.of(unexpanded)));
        String json = c.toJson().replace("\"subtaskIndex\":0", "\"subtaskIndex\":3");
        assertThrows(IllegalArgumentException.class, () -> restore(json),
                "未展开一级不可能有第 4 个二级：subtaskIndex 必须为 0");
    }

    @Test
    void unexpandedPrimaryIsLegallyPending() {
        var unexpanded = new PrimaryGoal("p", "等展开", List.of(), List.of(), true);
        var c = new TaskChain(new Goal("g", "goal", List.of(unexpanded)));
        assertEquals(PrimaryGoalStatus.PENDING, c.primaryStatus(), "钉住前自检：未展开一级应当是 PENDING");
        assertNull(c.currentSubtask());
        var r = restore(c.toJson());
        assertEquals(PrimaryGoalStatus.PENDING, r.primaryStatus(), "未展开 + PENDING 是合法组合，必须能恢复");
    }

    // ── ④ 指针范围 ─────────────────────────────────────────────────

    @Test
    void primaryIndexOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> restore(jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                        .replace("\"primaryIndex\":0", "\"primaryIndex\":5")),
                "primaryIndex 越界必须在恢复时就拒绝（否则 currentPrimary() 会 IndexOutOfBounds 在深处炸）");
    }

    @Test
    void subtaskIndexOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> restore(jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))))
                        .replace("\"subtaskIndex\":0", "\"subtaskIndex\":4")),
                "subtaskIndex 越界必须在恢复时就拒绝");
    }

    // ── ⑤ 完成事实层与当前指针的自洽 ───────────────────────────────

    @Test
    void factSatisfiedCurrentPrimaryMustAlsoBeChainCompleted() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        String json = c.toJson().replace("\"satisfiedStages\":[]", "\"satisfiedStages\":[\"阶段一\"]");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> restore(json),
                "★ 当前一级已被『完成事实』命中，链却不是 COMPLETED = 永远停在一个已达成阶段上死等。必须拒绝。");
        assertTrue(e.getMessage().contains("fact-satisfied"), "报错要点名是事实层命中导致的自相矛盾");
    }

    @Test
    void factSatisfiedCurrentPrimaryIsFineWhenChainIsCompleted() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        c.startCurrent();
        c.applyHardCodedResult("s1", true);
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, c.primaryStatus(), "钉住前自检");
        c.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p", "ok"));
        String json = c.toJson().replace("\"satisfiedStages\":[]", "\"satisfiedStages\":[\"阶段一\"]");
        var r = restore(json);
        assertEquals(PrimaryGoalStatus.COMPLETED, r.primaryStatus(),
                "COMPLETED + 事实命中当前阶段是<b>自洽</b>的组合，必须能恢复");
    }

    // ── ⑥ 运行期不变式（不只恢复期）──────────────────────────────

    @Test
    void everyMutationStaysInsideTheDeclaredStatusSet() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"), hc("s3"))));
        var expected = java.util.Set.of("s1", "s2", "s3");
        c.startCurrent();
        assertEquals(expected, c.subtaskStatuses().keySet(), "startCurrent 不许增删条目");
        c.applyHardCodedResult("s1", true);
        assertEquals(expected, c.subtaskStatuses().keySet(), "推进不许增删条目");
        c.startCurrent();
        c.markStalled("s2", "stuck");
        assertEquals(expected, c.subtaskStatuses().keySet(), "停滞不许增删条目");
        c.resumeFromStalled("s2");
        c.markFailed("s2", "dead");
        assertEquals(expected, c.subtaskStatuses().keySet(), "失败不许增删条目");
        c.retrySubtask("s2");
        c.startCurrent();
        c.pauseSubtask("s2", "pause please");
        assertEquals(expected, c.subtaskStatuses().keySet(), "暂停不许增删条目");
        c.resumeFromPaused("s2");
        c.skipSubtask("s2", "skip it");
        assertEquals(expected, c.subtaskStatuses().keySet(), "跳过不许增删条目");
        assertEquals(SubtaskStatus.SKIPPED, c.subtaskStatuses().get("s2"));
    }

    @Test
    void skippedIsNeverPresentedAsCompleted() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))));
        c.startCurrent();
        c.skipSubtask("s1", "no villagers nearby");
        assertEquals(SubtaskStatus.SKIPPED, c.subtaskStatuses().get("s1"));
        assertNotEquals(SubtaskStatus.COMPLETED, c.subtaskStatuses().get("s1"),
                "★ 跳过绝不可冒充完成（RL-13「工具契约不做假承诺」在状态层的同一条纪律）。"
                        + "AI 从 rdd_status 读到 COMPLETED 就会以为那件事做过了。");
    }

    @Test
    void staleSubtaskIdIsRejectedByEveryResultApplier() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))));
        c.startCurrent();
        assertThrows(IllegalArgumentException.class, () -> c.applyHardCodedResult("s2", true),
                "对还没成为当前项的二级回报结果 = 陈旧回执，必须拒绝（深审/codex P1-1 的隔离前提）");
        assertThrows(IllegalArgumentException.class, () -> c.skipSubtask("s2", "x"),
                "跳过陈旧 id 必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> c.pauseSubtask("s9", "x"),
                "暂停不存在的 id 必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> c.markFailed("s9", "x"),
                "失败不存在的 id 必须拒绝");
    }

    @Test
    void resultCannotBeAppliedTwiceToTheSameSubtask() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))));
        c.startCurrent();
        assertTrue(c.applyHardCodedResult("s1", true));
        // s1 已完成，指针已移到 s2：再拿 s1 的旧回执来必须炸
        assertThrows(IllegalArgumentException.class, () -> c.applyHardCodedResult("s1", true),
                "★ 同一个二级的结果只许应用一次。否则 AI 重放回执就能凭空完成一整串步骤");
    }

    @Test
    void terminalSubtaskIndexNeverMovesPastTheEnd() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        c.startCurrent();
        c.applyHardCodedResult("s1", true);
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, c.primaryStatus(),
                "★ 最后一个二级完成必须停在 AWAITING_SUPERVISOR 等监督拍板，"
                        + "而不是自作主张跳到 COMPLETED（那是 M4/M5 的接线点）");
        assertNull(c.currentSubtaskStatus() == SubtaskStatus.RUNNING ? "still running" : null);
    }

    // ── ④ 拍板 1 护栏：清理必须留账（2026-10-24 修复轮新增）─────────

    @Test
    void 干净恢复时账为null而不是空对象() {
        var c = restore(jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1")))));
        assertNull(c.lastSanitization(),
                "干净恢复 = null（「没清理」和「清理了没事」语义不同，不许用空对象冒充）");
    }

    @Test
    void 混合脏数据的账必须逐项点名() {
        // 一次恢复同时有：幽灵 status、缺登记、幽灵 attempts、不成立 skipReason——账要全记
        String json = jsonOf(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))))
                .replace("\"statuses\":{\"s1\":\"PENDING\",\"s2\":\"PENDING\"}",
                         "\"statuses\":{\"s1\":\"PENDING\",\"ghost\":\"PENDING\"}")
                .replace("\"attempts\":{}", "\"attempts\":{\"ghost2\":2}")
                .replace("\"skipReasons\":{}", "\"skipReasons\":{\"s2\":\"nope\"}");
        var c = restore(json);
        var r = c.lastSanitization();
        assertNotNull(r, "有清理就必须有账");
        assertTrue(r.droppedStatuses().contains("ghost"), "幽灵 status 点名");
        assertTrue(r.registeredAsPending().contains("s2"), "补登记点名");
        assertTrue(r.droppedAttempts().contains("ghost2"), "幽灵 attempts 点名");
        assertTrue(r.droppedSkipReasons().contains("s2"), "不成立 skipReason 点名");
        assertEquals(SubtaskStatus.PENDING, c.subtaskStatuses().get("s2"), "s2 补成 PENDING");
    }
}
