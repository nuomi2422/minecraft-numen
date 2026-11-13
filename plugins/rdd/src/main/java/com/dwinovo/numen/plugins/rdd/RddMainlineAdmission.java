package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.core.TaskChain;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Detector 观测后的原准入/停车恢复消费者；宿主只适配 Minecraft 副作用。
 * <p>2026-11-13 V05：pending 停机留下 REPLANNING，回调隔离不等于业务恢复。
 * V06 已证超时回退可达；抽取只补回归能力，不变策略、不承诺重启后固定时限。
 * 测试调用生产同一消费者，传原计时表和可控墙钟；不用仿制 controller 或构造整个游戏。
 * capturedStatus 沿用 Detector 观测前的本拍快照，宿主回调后也不重新读链状态。
 */
final class RddMainlineAdmission {
    private static final long REPLAN_TIMEOUT_MS = 5 * 60 * 1000L;

    interface Host {
        boolean sideActive();
        void activeProposal();
        boolean negotiate();
        void watchParked();
        void recovering();
        void timeoutResumed(long waitedMs);
        void timeoutFailed(long waitedMs, RuntimeException failure);
    }

    private RddMainlineAdmission() {}

    /** true = 原前返处结束本拍；false = 继续 Detector 原下游，仍沿用 capturedStatus。 */
    static boolean stopAfterObservation(UUID companionId, TaskChain chain, PrimaryGoalStatus capturedStatus,
                                        Map<UUID, Long> replanEnteredAt, LongSupplier clock, Host host) {
        if (host.sideActive()) return true;
        if (capturedStatus == PrimaryGoalStatus.ACTIVE) host.activeProposal();
        // 2026-10-31：非 REPLANNING 清原起点，避免下一轮沿用上轮墙钟。
        if (capturedStatus != PrimaryGoalStatus.REPLANNING) replanEnteredAt.remove(companionId);
        if (host.negotiate()) return true;
        if (capturedStatus == PrimaryGoalStatus.AWAITING_SUPERVISOR
                || capturedStatus == PrimaryGoalStatus.REPLANNING
                || capturedStatus == PrimaryGoalStatus.COMPLETED
                || capturedStatus == PrimaryGoalStatus.FAILED) {
            if (capturedStatus == PrimaryGoalStatus.REPLANNING || capturedStatus == PrimaryGoalStatus.FAILED) {
                if (capturedStatus == PrimaryGoalStatus.REPLANNING
                        && replanTimedOut(companionId, chain, replanEnteredAt, clock, host)) return true;
                host.watchParked();
            }
            return true;
        }
        if (capturedStatus == PrimaryGoalStatus.RECOVERING) host.recovering();
        return false;
    }

    /** 原五分钟回退：走到此分支才读钟/记起点，沿用原异常边界。 */
    private static boolean replanTimedOut(UUID companionId, TaskChain chain,
                                          Map<UUID, Long> replanEnteredAt, LongSupplier clock, Host host) {
        long now = clock.getAsLong();
        Long since = replanEnteredAt.get(companionId);
        if (since == null) {
            replanEnteredAt.put(companionId, now);
            return false;
        }
        if (now - since < REPLAN_TIMEOUT_MS) return false;
        replanEnteredAt.remove(companionId);
        try {
            chain.resumeFromReplanning();
            host.timeoutResumed(now - since);
        } catch (RuntimeException ex) {
            host.timeoutFailed(now - since, ex);
        }
        return true;
    }
}
