package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 169 T6 / 170 §5.1 冻结判据：重启主线后
 * <ul>
 *   <li>{@code satisfiedStages} 必须为空（历史完成不能替代新世界的当前事实）；</li>
 *   <li>{@code planRevision} 与 {@code stateVersion} 必须各 +1（旧 id 作废、安全提交）；</li>
 *   <li>刚由 Stage-A 排好的 primary 必须保留（只清完成继承，不丢计划）；</li>
 *   <li>所有二级回到 PENDING，一级回到 PENDING，能从第一步重新开跑。</li>
 * </ul>
 */
class TaskChainRestartResetTest {

    private static Subtask hc(String id) {
        return Subtask.hardCoded(id, "do " + id, Map.of("asset_key", "minecraft:stone", "minimum", 1));
    }

    private static Goal goal(String objective, String secondStage) {
        return new Goal("goal-test", objective, List.of(
                new PrimaryGoal("primary-0", "阶段一", List.of(hc("s1"))),
                new PrimaryGoal("primary-1", secondStage, List.of(hc("s2")))));
    }

    @Test
    void restartClearsInheritedStagesAndBumpsRevisions() {
        String inherited = StageKeyNormalizer.normalize("阶段一");
        TaskChain chain = new TaskChain(goal("旧目标：再进下界取烈焰棒", "旧阶段二"), Set.of(inherited));
        assertEquals(Set.of(inherited), chain.satisfiedStages(), "钉住前自检：继承事实必须已注入");

        int planBefore = chain.planRevision();
        long stateBefore = chain.stateVersion();

        chain.resetForRestart("新目标：先补铁甲与食物，再回主世界基地");

        assertTrue(chain.satisfiedStages().isEmpty(),
                "重启后不得再拿历史完成当当前事实，否则又把旧阶段跳过、继续送死");
        assertEquals(planBefore + 1, chain.planRevision(), "planRevision 必须 +1（旧 id 全部作废）");
        assertEquals(stateBefore + 1, chain.stateVersion(), "stateVersion 必须 +1（安全提交点）");
        assertEquals("新目标：先补铁甲与食物，再回主世界基地", chain.goal().description());
        assertEquals(2, chain.goal().primaryGoals().size(),
                "只清完成继承，不丢刚绑定的 Stage-A 计划");
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertEquals(SubtaskStatus.PENDING, chain.subtaskStatuses().get("s1"));
        assertEquals(SubtaskStatus.PENDING, chain.subtaskStatuses().get("s2"));
    }

    @Test
    void restartCanStartFromFirstStepAgain() {
        TaskChain chain = new TaskChain(goal("旧目标", "旧阶段二"),
                Set.of(StageKeyNormalizer.normalize("阶段一"), StageKeyNormalizer.normalize("旧阶段二")));
        assertEquals(PrimaryGoalStatus.COMPLETED, chain.primaryStatus(), "钉住前自检：旧链应因继承而直接完成");

        chain.resetForRestart("新目标");
        chain.startCurrentWithCounts(Map.of());

        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus(), "重置后必须能重新开始第一步");
        assertEquals("primary-0", chain.currentPrimary().id());
    }
}
