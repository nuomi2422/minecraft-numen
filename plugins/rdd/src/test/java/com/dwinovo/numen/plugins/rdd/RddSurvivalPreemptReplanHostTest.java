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
 * 044 B2 · 生存抢占 → 重规划 → 恢复主线：真实宿主控制组（test-only，2026-11-13）。
 *
 * <p><b>V03 原件</b>：{@code attack-core-V03/latest.log:15906-15927}、{@code events.jsonl:17834}。
 * t9(goto) 02:24:49 内部取消（CANCELLED，不是外部 close），同刻 SEEK_FOOD_FIRST hunger=9 hp=20，
 * 随后「无法进入重规划 … current subtask is not stuck: RUNNING」，之后没有新任务。
 *
 * <p><b>同窗补证（rdd.jsonl:1304-1316，2026-11-13 02:55Z 回读）</b>：该窗 rdd.jsonl 只有一条
 * {@code survival_preempt}（1304，02:24:49.597）。02:25:10.470 sleep 支线 created+activated
 * （1306/1307，SEEKING_BED，mainlineSubtaskId=primary-a43d753e-8-r28-3），02:25:50 与 02:26:50 两次
 * {@code stalled}（1312/1316）。所以“之后没有新任务”不能全归因于 60s 冷却或 RUNNING guard：
 * 支线 active 后 {@code RddDetector} L363-364 让位，主线被冻结到停游。本批不修 sleep 优先级、
 * 不改“饥饿是否可中断支线”，只把这段作为“恢复主线仍不可证”的边界记在这里。
 *
 * <p><b>调用链</b>（ef5ad8ec）：{@code RddDetector.survivalPreempt} L380 记冷却 → L382 task_stop →
 * L387 {@code RddPlugin.requestReplan(uuid, reason)}（两参＝失败驱动）→ L1416 先 merge REPLAN_COUNTS →
 * L1425 {@code enterReplanningFromStuck} → {@code TaskChain} L996 要求 FAILED/STALLED → RUNNING 抛 →
 * L1427 捕获吞掉、返回 false。
 *
 * <p><b>按指挥官 2026-11-13 决定</b>：原因文本只解释、不作状态/授权来源；不新增无限抢占额度、
 * 不改上限/park 政策；只有被合法宿主转换接受的请求才花原有有界预算，被拒绝一律不花；
 * 不复制 TaskChain 同形方法、不删 STUCK guard。因此本文件不按 reason 前缀断言抢占放行，
 * 只冻结这些控制；typed 入口由产品授权后再补对应用例。
 *
 * <p>现状（ef5ad8ec）红：拒绝仍花预算（用例 1/2/4）；进入重规划后旧身体派发记录仍冒充在飞（用例 6）。
 * 绿：旧 STUCK 正控（3）、旧执行身份在链层被作废（5）。
 */
class RddSurvivalPreemptReplanHostTest {

    /** 与 V03 现场一致的抢占原因文本（只作说明，不作授权）。 */
    private static final String SURVIVAL_REASON = "survival SEEK_FOOD_FIRST hunger=9 hp=20 free=16";

    @Test
    void reasonTextDoesNotAuthorizePreemptionAndRefusalIsFree() throws Exception {
        Harness h = new Harness();
        try {
            assertEquals(SubtaskStatus.RUNNING, h.chain.currentSubtaskStatus(),
                    "前提：二级在跑时被生存抢占（V03 t9 正在 goto）");
            boolean accepted = RddPlugin.requestReplan(h.id, SURVIVAL_REASON);
            assertFalse(accepted,
                    "原因文本只解释：两参失败驱动不得因为 reason 写着 survival 就改走允许 RUNNING 的状态路径");
            assertEquals(SubtaskStatus.RUNNING, h.chain.currentSubtaskStatus(), "被拒绝后不得伪造 FAILED/STALLED");
            assertEquals(PrimaryGoalStatus.ACTIVE, h.chain.primaryStatus(), "被拒绝就该保持原状");
            assertEquals(0, h.failureBudgetUsed(),
                    "被拒绝的重规划不得先花 failure 预算（RddPlugin L1416 在状态转换前就 merge）");
        } finally {
            h.close();
        }
    }

    @Test
    void typedSurvivalSourceFromRunningMustBeAccepted() throws Exception {
        Harness h = new Harness();
        try {
            boolean accepted = RddPlugin.requestReplanForSurvival(h.id,
                    RddSurvivalGate.Decision.SEEK_FOOD_FIRST, 9, 20.0, 16);
            assertTrue(accepted,
                    "程序来源（typed）的生存抢占从 RUNNING 必须能进重规划；首阶段接缝原样委托旧入口，故本断言真红");
            assertNotEquals(SubtaskStatus.FAILED, h.chain.currentSubtaskStatus(), "抢占不是失败，不得伪标");
            assertNotEquals(SubtaskStatus.STALLED, h.chain.currentSubtaskStatus(), "抢占不是卡死，不得伪标");
        } finally {
            h.close();
        }
    }

    @Test
    void nonSurvivalReasonOnRunningIsStillRefusedWithoutSpending() throws Exception {
        Harness h = new Harness();
        try {
            boolean accepted = RddPlugin.requestReplan(h.id, "挖不到");
            assertFalse(accepted, "普通原因在 RUNNING 上同样必须被拒：不得顺带放宽旧路由");
            assertEquals(SubtaskStatus.RUNNING, h.chain.currentSubtaskStatus());
            assertEquals(0, h.failureBudgetUsed(), "拒绝不花预算，与原因文本无关");
        } finally {
            h.close();
        }
    }

    @Test
    void duplicateRequestWhileReplanningIsRefusedWithoutSpending() throws Exception {
        Harness h = new Harness();
        try {
            h.chain.enterReplanningFromNegotiation("soldier COUNTER: 计划前提不成立");
            assertEquals(PrimaryGoalStatus.REPLANNING, h.chain.primaryStatus());
            boolean accepted = RddPlugin.requestReplan(h.id, SURVIVAL_REASON);
            assertFalse(accepted, "已在 REPLANNING 的链不得被迟到的重复请求再开一次");
            assertEquals(0, h.failureBudgetUsed(), "重复/旧身份请求被拒时不得花预算");
        } finally {
            h.close();
        }
    }

    @Test
    void stuckFailureRouteStillAcceptedWithExactlyOneBudget() throws Exception {
        Harness h = new Harness();
        try {
            h.chain.markFailed("s0", "挖不到");
            assertEquals(SubtaskStatus.FAILED, h.chain.currentSubtaskStatus());
            boolean accepted = RddPlugin.requestReplan(h.id, "挖不到");
            assertTrue(accepted, "旧正常 STUCK 重规划路由不得回归");
            assertEquals(1, h.failureBudgetUsed(), "被接受的一次重规划恰好花 1 次 failure 预算");
            assertNotEquals(SubtaskStatus.FAILED, h.chain.currentSubtaskStatus(),
                    "进入重规划后旧二级不该再被当作当前 FAILED（无服务端时宿主回落到 PENDING）");
        } finally {
            h.close();
        }
    }

    @Test
    void acceptedReplanVoidsOldExecutionIdentityAndLateDuplicateStaysFree() throws Exception {
        Harness h = new Harness();
        try {
            h.chain.bindExecution("s0", "exec-v03-t9");
            assertEquals("exec-v03-t9", h.chain.activeExecutionId());
            h.chain.markFailed("s0", "挖不到");
            assertTrue(RddPlugin.requestReplan(h.id, "挖不到"));
            assertNull(h.chain.activeExecutionId(), "进入重规划即作废旧执行身份（TaskChain.clearExecutionMetadata）");
            int used = h.failureBudgetUsed();
            boolean late = RddPlugin.requestReplan(h.id, "挖不到");
            assertFalse(late, "旧身份/重复事件不得再开一次重规划");
            assertEquals(used, h.failureBudgetUsed(), "被拒的迟到请求不得再花预算");
        } finally {
            h.close();
        }
    }

    /**
     * 身体派发记录（RddPlugin.BODY，非同一条链的 execution id）在进入重规划后仍指着旧二级。
     * 若新计划复用同一二级 id，{@code RddBodyDispatcher.maybeSubmit} L42-44 会因
     * {@code state.subtaskId().equals(current.id())} 直接 return —— 新计划永远不派发。
     */
    @Test
    void acceptedReplanMustNotLeaveOldBodyDispatchStanding() throws Exception {
        Harness h = new Harness();
        try {
            RddPlugin.rememberBody(h.id, "s0", 1);
            assertEquals("s0", RddPlugin.bodyState(h.id).subtaskId());
            h.chain.markFailed("s0", "挖不到");
            assertTrue(RddPlugin.requestReplan(h.id, "挖不到"));
            assertNull(RddPlugin.bodyState(h.id),
                    "宿主接受重规划后，旧身体派发记录必须清掉，不能继续冒充 s0 在飞");
        } finally {
            h.close();
        }
    }

    /** 真 TaskChain + 真 RddRuntime；只通过反射装到 RddPlugin 私有注册表，不新建状态机。 */
    private static final class Harness {
        final UUID id = UUID.randomUUID();
        final TaskChain chain = new TaskChain(new Goal("goal-b2",
                "044 B2 生存抢占→重规划→恢复主线",
                List.of(PrimaryGoal.unexpanded("primary-0", "先弄到吃的", List.of()))));

        Harness() throws Exception {
            chain.expandCurrentPrimary(List.of(
                    Subtask.hardCoded("s0", "走到熔炉边", Map.of("asset_key", "minecraft:furnace"))));
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
            RddPlugin.clearBody(id);
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
