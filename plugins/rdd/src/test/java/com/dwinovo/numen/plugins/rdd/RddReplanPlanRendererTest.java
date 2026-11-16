package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.api.SupervisorDecision;
import com.dwinovo.numen.rdd.api.SupervisorDecisionType;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-level compatibility examples for the extracted plan-history renderer.
 * Use real TaskChain transitions or its public persistence format, not a mock host.
 * Why not copy the old renderer as an oracle: two matching algorithms can share a bug;
 * the expected UTF-8 text below is explicit, including indentation and missing final LF.
 */
class RddReplanPlanRendererTest {
    @Test
    void unexpandedPrimaryHasNoPreviousRemainingOrDoneText() {
        TaskChain chain = chain(PrimaryGoal.unexpanded("p", "待展开阶段", List.of()));
        String before = chain.toJson();

        assertBytes("", RddReplanPlanRenderer.renderPreviousPlanForReplan(chain));
        assertBytes("", RddReplanPlanRenderer.renderRemainingPlanForReplan(chain));
        assertBytes("", RddReplanPlanRenderer.renderDoneForReplan(chain));
        assertEquals(before, chain.toJson());
    }

    @Test
    void previousPlanKeepsListOrderTrimsDescriptionsAndOnlyEchoesConditionKeys() {
        TaskChain chain = chain(new PrimaryGoal("p", "当前阶段", List.of(
                task("z", "  先找木头  "),
                Subtask.hardCoded("a", "\t再收集铁\n", Map.of("item", "minecraft:iron_ingot")),
                task("m", "  最后返回  "))));
        String before = chain.toJson();

        // One key is deliberate: Subtask copies maps, whose multi-key order is unspecified.
        assertBytes("1. 先找木头\n  2. 再收集铁  [条件 [item]]\n  3. 最后返回",
                RddReplanPlanRenderer.renderPreviousPlanForReplan(chain));
        assertEquals(before, chain.toJson());
    }

    @Test
    void previousPlanAtTwelveHasNoNoticeAndOverflowKeepsOnlyFirstTwelve() {
        List<Subtask> tasks = numberedTasks(14);
        String twelve = "1. 步骤1\n  2. 步骤2\n  3. 步骤3\n  4. 步骤4"
                + "\n  5. 步骤5\n  6. 步骤6\n  7. 步骤7\n  8. 步骤8"
                + "\n  9. 步骤9\n  10. 步骤10\n  11. 步骤11\n  12. 步骤12";

        assertBytes(twelve, RddReplanPlanRenderer.renderPreviousPlanForReplan(
                chain(new PrimaryGoal("p", "当前阶段", tasks.subList(0, 12)))));
        assertBytes(twelve + "\n  …（另有 1 条未列）",
                RddReplanPlanRenderer.renderPreviousPlanForReplan(
                        chain(new PrimaryGoal("p", "当前阶段", tasks.subList(0, 13)))));
        assertBytes(twelve + "\n  …（另有 2 条未列）",
                RddReplanPlanRenderer.renderPreviousPlanForReplan(
                        chain(new PrimaryGoal("p", "当前阶段", tasks))));
    }

    @Test
    void previousDescriptionsAt120StayWholeAnd121AreTruncatedAfterTrim() {
        String at120 = "甲乙丙丁戊己庚辛壬癸".repeat(12);
        TaskChain chain = chain(new PrimaryGoal("p", "当前阶段", List.of(
                task("a", "  " + "甲".repeat(119) + "  "),
                task("b", "\t" + at120 + "\n"),
                task("c", "  " + at120 + "尾  "))));

        assertBytes("1. " + "甲".repeat(119) + "\n  2. " + at120 + "\n  3. " + at120 + "…",
                RddReplanPlanRenderer.renderPreviousPlanForReplan(chain));
    }

    @Test
    void remainingPlanStartsAfterCurrentAndKeepsStageAndRequirementOrder() {
        TaskChain chain = chain(
                new PrimaryGoal("past", "过去阶段", List.of(task("past-task", "旧任务"))),
                new PrimaryGoal("current", "当前阶段", List.of(task("current-task", "眼前任务"))),
                PrimaryGoal.unexpanded("z", "  下一阶段  ", List.of(
                        new AssetRequirement("minecraft:iron_ingot", 3),
                        new AssetRequirement("minecraft:oak_log", 2))),
                PrimaryGoal.unexpanded("a", "终局  ", List.of()));
        chain.startCurrent();
        chain.applyAiAssistedResult("past-task", "CONFIRMED");
        confirmPrimary(chain);
        String before = chain.toJson();

        assertBytes("1.   下一阶段  （进入前需持有：minecraft:iron_ingot×3、minecraft:oak_log×2）\n  2. 终局",
                RddReplanPlanRenderer.renderRemainingPlanForReplan(chain));
        assertEquals(before, chain.toJson());
    }

    @Test
    void remainingPlanDoesNotUsePreviousPlanCountOrDescriptionBounds() {
        List<PrimaryGoal> stages = new ArrayList<>();
        stages.add(new PrimaryGoal("current", "当前阶段", List.of(task("current-task", "眼前任务"))));
        stages.add(PrimaryGoal.unexpanded("long", "长".repeat(121), List.of()));
        for (int i = 2; i <= 13; i++) {
            stages.add(PrimaryGoal.unexpanded("p" + i, "阶段" + i, List.of()));
        }

        assertBytes("1. " + "长".repeat(121) + "\n  2. 阶段2\n  3. 阶段3\n  4. 阶段4"
                        + "\n  5. 阶段5\n  6. 阶段6\n  7. 阶段7\n  8. 阶段8"
                        + "\n  9. 阶段9\n  10. 阶段10\n  11. 阶段11\n  12. 阶段12\n  13. 阶段13",
                RddReplanPlanRenderer.renderRemainingPlanForReplan(chain(stages.toArray(PrimaryGoal[]::new))));
    }

    @Test
    void doneTextUsesRealCompletionSkipPauseFailureAndRetryCountsWithoutMutation() {
        TaskChain chain = chain(new PrimaryGoal("p", "当前阶段", List.of(
                task("z", "  已采集  "), task("a", " 已放弃 "),
                task("m", " 暂缓再试 "), task("pending", "还没开始"))));
        chain.startCurrent();
        chain.markFailed("z", "首次没找到");
        chain.retrySubtask("z");
        chain.startCurrent();
        chain.applyAiAssistedResult("z", "CONFIRMED");
        chain.startCurrent();
        chain.skipSubtask("a", "已有替代路线");
        chain.startCurrent();
        chain.pauseSubtask("m", "先等待安全窗口");
        String beforePauseRender = chain.toJson();

        assertBytes("- [COMPLETED] 已采集（试了 2 次）\n  - [SKIPPED] 已放弃（试了 1 次）"
                        + "\n  - [PAUSED] 暂缓再试（试了 1 次）",
                RddReplanPlanRenderer.renderDoneForReplan(chain));
        assertEquals(beforePauseRender, chain.toJson());

        chain.resumeFromPaused("m");
        chain.markFailed("m", "路径不通");
        chain.retrySubtask("m");
        chain.startCurrent();
        chain.markFailed("m", "再次不通");
        String beforeFailureRender = chain.toJson();
        assertBytes("- [COMPLETED] 已采集（试了 2 次）\n  - [SKIPPED] 已放弃（试了 1 次）"
                        + "\n  - [FAILED] 暂缓再试（试了 2 次）",
                RddReplanPlanRenderer.renderDoneForReplan(chain));
        assertEquals(beforeFailureRender, chain.toJson());
    }

    @Test
    void doneRestoredHistoryFiltersEveryOtherStatusAndOmitsZeroOrMissingAttempts() {
        // A real persisted chain accepts these historical statuses; no private fields are patched.
        TaskChain source = chain(new PrimaryGoal("p", "当前阶段", List.of(
                task("pending", "待做"), task("running", "正在做"), task("stalled", "卡住"),
                task("cooldown", "冷却"), task("invalidated", "过期"),
                task("completed", " 已完成 "), task("skipped", " 已跳过 "),
                task("paused", " 已暂停 "), task("failed", " 已失败 "))));
        JsonObject saved = JsonParser.parseString(source.toJson()).getAsJsonObject();
        JsonObject statuses = new JsonObject();
        // Reverse map insertion order so output must follow the goal list, not the status map.
        statuses.addProperty("failed", SubtaskStatus.FAILED.name());
        statuses.addProperty("paused", SubtaskStatus.PAUSED.name());
        statuses.addProperty("skipped", SubtaskStatus.SKIPPED.name());
        statuses.addProperty("completed", SubtaskStatus.COMPLETED.name());
        statuses.addProperty("invalidated", SubtaskStatus.INVALIDATED.name());
        statuses.addProperty("cooldown", SubtaskStatus.COOLDOWN.name());
        statuses.addProperty("stalled", SubtaskStatus.STALLED.name());
        statuses.addProperty("running", SubtaskStatus.RUNNING.name());
        statuses.addProperty("pending", SubtaskStatus.PENDING.name());
        saved.add("statuses", statuses);
        JsonObject attempts = new JsonObject();
        attempts.addProperty("completed", 0);
        attempts.addProperty("paused", 2);
        attempts.addProperty("failed", 3);
        saved.add("attempts", attempts);
        TaskChain chain = TaskChain.fromJson(saved.toString());
        String before = chain.toJson();

        assertBytes("- [COMPLETED] 已完成\n  - [SKIPPED] 已跳过\n  - [PAUSED] 已暂停（试了 2 次）"
                        + "\n  - [FAILED] 已失败（试了 3 次）",
                RddReplanPlanRenderer.renderDoneForReplan(chain));
        assertEquals(before, chain.toJson());
    }

    @Test
    void doneDescriptionsKeep120AndTruncate121() {
        String at120 = "甲乙丙丁戊己庚辛壬癸".repeat(12);
        TaskChain chain = chain(new PrimaryGoal("p", "当前阶段", List.of(
                task("a", " " + at120 + " "), task("b", " " + at120 + "尾 "))));
        chain.startCurrent();
        chain.skipSubtask("a", "字节边界样例");
        chain.startCurrent();
        chain.skipSubtask("b", "字节边界样例");

        assertBytes("- [SKIPPED] " + at120 + "（试了 1 次）\n  - [SKIPPED] " + at120 + "…（试了 1 次）",
                RddReplanPlanRenderer.renderDoneForReplan(chain));
    }

    @Test
    void doneStopsAtTwentyAcrossPrimariesWithoutOverflowNotice() {
        List<Subtask> tasks = numberedTasks(22);
        TaskChain chain = chain(
                new PrimaryGoal("p1", "第一阶段", tasks.subList(0, 10)),
                new PrimaryGoal("p2", "第二阶段", tasks.subList(10, 21)),
                new PrimaryGoal("p3", "第三阶段", tasks.subList(21, 22)));
        for (int stage = 0; stage < 3; stage++) {
            for (Subtask task : chain.currentPrimary().subtasks()) {
                chain.startCurrent();
                chain.skipSubtask(task.id(), "保留已有替代路线");
            }
            confirmPrimary(chain);
        }
        String before = chain.toJson();

        assertBytes("- [SKIPPED] 步骤1（试了 1 次）\n  - [SKIPPED] 步骤2（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤3（试了 1 次）\n  - [SKIPPED] 步骤4（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤5（试了 1 次）\n  - [SKIPPED] 步骤6（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤7（试了 1 次）\n  - [SKIPPED] 步骤8（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤9（试了 1 次）\n  - [SKIPPED] 步骤10（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤11（试了 1 次）\n  - [SKIPPED] 步骤12（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤13（试了 1 次）\n  - [SKIPPED] 步骤14（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤15（试了 1 次）\n  - [SKIPPED] 步骤16（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤17（试了 1 次）\n  - [SKIPPED] 步骤18（试了 1 次）"
                        + "\n  - [SKIPPED] 步骤19（试了 1 次）\n  - [SKIPPED] 步骤20（试了 1 次）",
                RddReplanPlanRenderer.renderDoneForReplan(chain));
        assertBytes("", RddReplanPlanRenderer.renderRemainingPlanForReplan(chain));
        assertEquals(before, chain.toJson());
    }

    @Test
    void nullChainRuntimeExceptionsKeepAllThreeEmptyStringFallbacks() {
        // The previous-plan method still warns; remaining/done retain their silent fallback.
        assertBytes("", RddReplanPlanRenderer.renderPreviousPlanForReplan(null));
        assertBytes("", RddReplanPlanRenderer.renderRemainingPlanForReplan(null));
        assertBytes("", RddReplanPlanRenderer.renderDoneForReplan(null));
    }

    private static TaskChain chain(PrimaryGoal... primaries) {
        return new TaskChain(new Goal("g", "测试重规划输入", List.of(primaries)));
    }

    private static Subtask task(String id, String description) {
        return Subtask.aiAssisted(id, description, 1, 1, false);
    }

    private static List<Subtask> numberedTasks(int count) {
        List<Subtask> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) tasks.add(task("s" + i, "步骤" + i));
        return tasks;
    }

    private static void confirmPrimary(TaskChain chain) {
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM,
                chain.currentPrimary().id(), "本测试阶段已结算"));
    }

    private static void assertBytes(String expected, String actual) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
