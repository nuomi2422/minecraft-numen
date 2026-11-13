package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 B2 · 生存抢占 → 重规划 → 恢复主线：真实消费者（{@link RddReplanTransition}）契约组。
 * 2026-11-13 第二版：从 RddPlugin 反射改为直接调生产提取类，纯 JVM，不加载 MC 宿主。
 *
 * <p><b>V03 原件</b>：{@code attack-core-V03/latest.log:15906-15927}、{@code events.jsonl:17834}。
 * t9(goto) 02:24:49 内部取消（CANCELLED，不是外部 close），同刻 SEEK_FOOD_FIRST hunger=9 hp=20，
 * 随后「无法进入重规划 … current subtask is not stuck: RUNNING」，之后没有新任务。
 *
 * <p><b>同窗补证（rdd.jsonl:1304-1316）</b>：该窗只有一条 {@code survival_preempt}；
 * 02:25:10 sleep 支线 created+activated，02:25:50/02:26:50 stalled。此时链仍是 ACTIVE
 * （requestReplan 已失败），所以“sleep 在 REPLANNING 期间接管”只是代码路径可能，不是该窗实机证明。
 *
 * <p><b>本组契约</b>（f82 红基线：5 红 2 绿，见
 * mutation-20261113-111822-5f3451/attempt-e1a6105…；修复批后应 7+1 全绿，且不改任何原断言）：
 * <ol>
 *   <li>原因文本只解释：FAILURE 触发在 RUNNING 上被拒，预算不增；</li>
 *   <li>普通原因同样被拒且不花预算；</li>
 *   <li>已 REPLANNING 的重复请求被拒且不花；</li>
 *   <li>旧 STUCK 正控：FAILED 接受、恰好花 1 次；</li>
 *   <li>接受即作废旧执行身份；迟到重复被拒且不再花；</li>
 *   <li>接受回调只在 ACCEPTED 触发一次、拒绝零次（宿主红基线传空回调，不证明 MC 派发被清）；</li>
 *   <li>SURVIVAL 触发从 RUNNING 必须能进重规划（修复批要求绿）；</li>
 *   <li>规划器 attempt：FAILURE=1，SURVIVAL=0（不得伪造“上一版不可执行”）。</li>
 * </ol>
 */
class RddSurvivalPreemptReplanHostTest {

    private static final int LIMIT = 3;
    private static final String SURVIVAL_REASON = "survival SEEK_FOOD_FIRST hunger=9 hp=20 free=16";

    @Test
    void reasonTextDoesNotAuthorizePreemptionAndRefusalIsFree() {
        Fixture f = new Fixture();
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus(), "前提：V03 t9 正在 goto");
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, SURVIVAL_REASON);
        assertFalse(r.accepted(), "原因文本只解释：失败驱动不得因 reason 写着 survival 就放行 RUNNING");
        assertEquals(RddReplanTransition.Outcome.NOT_STUCK, r.outcome());
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus(), "拒绝后不得伪标 FAILED/STALLED");
        assertEquals(0, f.used(), "被拒绝的重规划不得先花 failure 预算");
    }

    @Test
    void nonSurvivalReasonOnRunningIsStillRefusedWithoutSpending() {
        Fixture f = new Fixture();
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, "挖不到");
        assertFalse(r.accepted(), "普通原因在 RUNNING 上同样被拒，不得顺带放宽旧路由");
        assertEquals(0, f.used());
    }

    @Test
    void duplicateRequestWhileReplanningIsRefusedWithoutSpending() {
        Fixture f = new Fixture();
        f.chain.enterReplanningFromNegotiation("soldier COUNTER: 计划前提不成立");
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, SURVIVAL_REASON);
        assertFalse(r.accepted());
        assertEquals(RddReplanTransition.Outcome.NOT_ACTIVE, r.outcome());
        assertEquals(0, f.used(), "重复/非 ACTIVE 请求被拒时不得花预算");
    }

    @Test
    void stuckFailureRouteStillAcceptedWithExactlyOneBudgetAndOneCallback() {
        Fixture f = new Fixture();
        f.chain.markFailed("s0", "挖不到");
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, "挖不到");
        assertTrue(r.accepted(), "旧正常 STUCK 重规划路由不得回归");
        assertEquals(1, f.used(), "被接受的一次重规划恰好花 1 次 failure 预算");
        assertEquals(1, f.acceptedCallbacks(), "接受后宿主回调恰好一次");
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus(),
                "接受即进入 REPLANNING（真实宿主在有服务端时才继续换计划/回落）");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus(),
                "旧二级保持原状态：修复批也不伪标 RUNNING/STALLED");
    }

    @Test
    void acceptedReplanVoidsOldExecutionIdentityAndLateDuplicateStaysFree() {
        Fixture f = new Fixture();
        f.chain.bindExecution("s0", "exec-v03-t9");
        f.chain.markFailed("s0", "挖不到");
        assertTrue(f.request(RddReplanTransition.Trigger.FAILURE, "挖不到").accepted());
        assertNull(f.chain.activeExecutionId(), "进入重规划即作废旧执行身份");
        int used = f.used();
        RddReplanTransition.Result late = f.request(RddReplanTransition.Trigger.FAILURE, "挖不到");
        assertFalse(late.accepted(), "旧身份/重复事件不得再开一次重规划");
        assertEquals(used, f.used(), "被拒的迟到请求不得再花预算");
        assertEquals(1, f.acceptedCallbacks(), "被拒的请求不得触发接受回调");
    }

    /**
     * 回调契约：ACCEPTED 恰好一次，拒绝零次。
     *
     * <p>红基线里真实宿主传空回调（机械提取不夹带净新增）；产品修复批才把它接成
     * {@code clearBody(companionId)} 并单独注明。这里只证明“接受才回调、只回调一次”，
     * **不证明** MC 侧身体派发真的被清掉——那需要带 MC 的运行环境，本模块单测没有。
     */
    @Test
    void acceptedCallbackRunsOnceAndNeverOnRefusal() {
        Fixture refused = new Fixture();
        assertFalse(refused.request(RddReplanTransition.Trigger.FAILURE, "挖不到").accepted());
        assertEquals(0, refused.acceptedCallbacks(), "拒绝时不得触发接受回调");

        Fixture accepted = new Fixture();
        accepted.chain.markFailed("s0", "挖不到");
        assertTrue(accepted.request(RddReplanTransition.Trigger.FAILURE, "挖不到").accepted());
        assertEquals(1, accepted.acceptedCallbacks());
    }

    @Test
    void survivalTriggerFromRunningMustBeAccepted() {
        Fixture f = new Fixture();
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.SURVIVAL, SURVIVAL_REASON);
        assertTrue(r.accepted(),
                "程序来源的生存抢占从 RUNNING 必须能进重规划（f82 红基线时为 false，修复批必须为 true）");
        assertNotEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus(), "抢占不是失败，不得伪标");
        assertNotEquals(SubtaskStatus.STALLED, f.chain.currentSubtaskStatus(), "抢占不是卡死，不得伪标");
    }

    /** 捕获的 owner 身份判定：同一原对象才停；被替换或没捕获到一律不停（无第二本账）。 */
    @Test
    void capturedOwnerIdentityDecidesStop() {
        Object captured = new Object();
        assertTrue(RddReplanTransition.ownsCapturedBody(captured, captured), "同一原对象才允许停");
        assertFalse(RddReplanTransition.ownsCapturedBody(captured, new Object()), "被替换的任务不得停");
        assertFalse(RddReplanTransition.ownsCapturedBody(null, captured), "没捕获到原动作时只重规划、不停体");
    }

    /** 规划器 attempt：只有普通失败是 1；生存/协商是事实变化，必须 0，不能伪造"上一版不可执行"。 */
    @Test
    void planningAttemptIsZeroForSurvivalAndOneForFailure() {
        assertEquals(1, RddReplanTransition.planningAttempt(RddReplanTransition.Trigger.FAILURE),
                "普通失败：上一版确实被判不可执行");
        assertEquals(0, RddReplanTransition.planningAttempt(RddReplanTransition.Trigger.SURVIVAL),
                "生存抢占是事实变化：不得给规划器伪造上一版不可执行");
    }

    /** 真 TaskChain + 真预算表；触发源显式传入，不靠 reason 文本。 */
    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final Map<String, Integer> counts = new HashMap<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final TaskChain chain = new TaskChain(new Goal("goal-b2",
                "044 B2 生存抢占→重规划→恢复主线",
                List.of(PrimaryGoal.unexpanded("primary-0", "先弄到吃的", List.of()))));

        Fixture() {
            chain.expandCurrentPrimary(List.of(
                    Subtask.hardCoded("s0", "走到熔炉边", Map.of("asset_key", "minecraft:furnace"))));
            chain.startCurrent();
        }

        String key() {
            return id + "|" + chain.currentPrimary().id();
        }

        RddReplanTransition.Result request(RddReplanTransition.Trigger trigger, String reason) {
            return RddReplanTransition.request(chain, trigger, reason, counts, key(), LIMIT,
                    callbacks::incrementAndGet);
        }

        int used() {
            return counts.getOrDefault(key(), 0);
        }

        int acceptedCallbacks() {
            return callbacks.get();
        }
    }
}
