package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 B2 · 生存抢占 → 重规划 → 恢复主线：<b>真实宿主消费者反例</b>（test-only，冻结于 2026-11-13）。
 *
 * <p><b>V03 原件</b>：{@code D:/rdd/workspace/.tmp/attack-core-V03/latest.log:15906-15927} 与
 * {@code events.jsonl:17834}。t9(goto) 在 02:24:49 被内部取消（CANCELLED，不是外部 close），
 * 同一刻 {@code RddDetector.survivalPreempt} 判定 SEEK_FOOD_FIRST hunger=9 hp=20，
 * 随后 {@code RddPlugin} 打印「无法进入重规划 … current subtask is not stuck: RUNNING」，之后没有新任务。
 *
 * <p><b>调用链</b>（ef5ad8ec）：
 * {@code RddDetector.survivalPreempt}（L380 先记 60s 冷却 → L382 task_stop → L387 requestReplan）
 * → {@code RddPlugin.requestReplan}（L1416 先 merge REPLAN_COUNTS → L1425 enterReplanningFromStuck）
 * → {@code TaskChain.enterReplanningFromStuck}（L996 要求 FAILED/STALLED，RUNNING 直接抛）
 * → 被 L1427 捕获吞掉、返回 false。于是：链仍 ACTIVE、二级仍 RUNNING、身体已停、没有重规划、没有新任务。
 *
 * <p>相邻的死亡结算（RddPlugin L1613-1618）已经因为「人不是卡住，是计划所依据的事实变了」而走
 * 允许 RUNNING 的入口；生存抢占是同一类「事实变化/主动抢占」，不是 STUCK。
 *
 * <p>本测试只断言<b>宿主消费者必须满足的最小契约</b>，不测 TaskChain 独立方法，也不改任何产品代码：
 * <ol>
 *   <li>RUNNING 上的生存抢占请求必须被宿主接受（不能靠伪标 FAILED/STALLED 或删 guard 换绿）；</li>
 *   <li>被拒绝的重规划请求不得先花掉 failure 预算（旧代码在进入状态机前就计数）；</li>
 *   <li>已在 REPLANNING 的链不得被迟到的重复抢占再开一次。</li>
 * </ol>
 *
 * <p>现状（ef5ad8ec）第一条与第二条断言为红，即本批要冻结的真实反例；产品修复必须让它们变绿，
 * 且不要求修改本文件。
 */
class RddSurvivalPreemptReplanHostTest {

    private static final String SURVIVAL_REASON = "survival SEEK_FOOD_FIRST hunger=9 hp=20 free=16";

    @Test
    void survivalPreemptionFromRunningIsAcceptedByHost() throws Exception {
        Harness h = new Harness();
        try {
            assertEquals(SubtaskStatus.RUNNING, h.chain.currentSubtaskStatus(),
                    "前提：二级在跑时被生存抢占（V03 t9 正在 goto）");
            boolean accepted = RddPlugin.requestReplan(h.id, SURVIVAL_REASON);
            assertTrue(accepted,
                    "生存抢占从 RUNNING 进入重规划必须被宿主接受；现状返回 false，链停在 ACTIVE、无新任务");
            assertNotEquals(SubtaskStatus.FAILED, h.chain.currentSubtaskStatus(),
                    "抢占不是失败：不得为了进重规划把当前二级伪造成 FAILED");
            assertNotEquals(SubtaskStatus.STALLED, h.chain.currentSubtaskStatus(),
                    "抢占不是卡死：不得为了进重规划把当前二级伪造成 STALLED");
            assertNotEquals(PrimaryGoalStatus.ACTIVE, h.chain.primaryStatus(),
                    "被接受的重规划必须真的离开 ACTIVE；本夹具无服务端，宿主按既有路径回落到 PENDING");
        } finally {
            h.close();
        }
    }

    @Test
    void refusedReplanMustNotBurnFailureBudget() throws Exception {
        Harness h = new Harness();
        try {
            h.chain.enterReplanningFromNegotiation("soldier COUNTER: 计划前提不成立");
            assertEquals(PrimaryGoalStatus.REPLANNING, h.chain.primaryStatus());
            boolean accepted = RddPlugin.requestReplan(h.id, SURVIVAL_REASON);
            assertFalse(accepted, "已经在 REPLANNING 的链不得被迟到的重复抢占再开一次");
            assertEquals(0, h.failureBudgetUsed(),
                    "被拒绝的重规划请求不得先花掉 failure 预算（RddPlugin L1416 在状态转换前就 merge）");
        } finally {
            h.close();
        }
    }

    /** 真 TaskChain + 真 RddRuntime；只通过反射装到 RddPlugin 的私有注册表上，不新建状态机。 */
    private static final class Harness {
        final UUID id = UUID.randomUUID();
        final TaskChain chain = new TaskChain(new Goal("goal-b2",
                "044 B2 生存抢占→重规划→恢复主线",
                List.of(PrimaryGoal.unexpanded("primary-0", "先弄到吃的", List.of()))));

        Harness() throws Exception {
            chain.expandCurrentPrimary(List.of(
                    Subtask.hardCoded("s0", "走到熔炉边", Map.of("asset_key", "minecraft:furnace"), null)));
            chain.startCurrent();
            installRuntime(id, new RddRuntime(chain, new AssetRegistry()));
        }

        String budgetKey() {
            return id + "|" + chain.currentPrimary().id();
        }

        int failureBudgetUsed() throws Exception {
            return budget("REPLAN_COUNTS", budgetKey());
        }

        void close() throws Exception {
            clearRuntime(id);
            budgetMap("REPLAN_COUNTS").remove(budgetKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static void installRuntime(UUID id, RddRuntime rt) throws Exception {
        Field f = RddPlugin.class.getDeclaredField("RUNTIMES");
        f.setAccessible(true);
        ((Map<UUID, RddRuntime>) f.get(null)).put(id, rt);
    }

    @SuppressWarnings("unchecked")
    private static void clearRuntime(UUID id) throws Exception {
        Field f = RddPlugin.class.getDeclaredField("RUNTIMES");
        f.setAccessible(true);
        ((Map<UUID, RddRuntime>) f.get(null)).remove(id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> budgetMap(String field) throws Exception {
        Field f = RddPlugin.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(null);
    }

    private static int budget(String field, String key) throws Exception {
        return budgetMap(field).getOrDefault(key, 0);
    }
}
