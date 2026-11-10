package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-10：主人停止的"真实消费判定"——空闲停、急件、重复停、明确恢复、隔离。
 * 这些断言直接钉住 {@code ownerStop}/{@code maybeDrain} 实际调用的纯函数，
 * 不是只测枚举值。
 */
class OwnerStopLatchTest {

    @Test void idleStopAlsoLatches() {
        // COV-r1 实机：was_busy=false 时只清了队列、没上闩 → 急件把回合拉起来。
        assertEquals(AgentTurnPause.OWNER_INTERRUPT, OwnerStopLatch.afterOwnerStop(AgentTurnPause.NONE));
    }

    @Test void urgentEventsCannotStartTurnsWhileLatched() {
        AgentTurnPause latched = OwnerStopLatch.afterOwnerStop(AgentTurnPause.NONE);
        // 急件走 afterWakeEvent：只把 RECOVERABLE_FAILURE 复位，OWNER_INTERRUPT 保持不变。
        AgentTurnPause afterUrgent = latched.afterWakeEvent(true);
        assertEquals(AgentTurnPause.OWNER_INTERRUPT, afterUrgent);
        assertFalse(OwnerStopLatch.allowsTurnStart(afterUrgent), "急件不得自动开模型轮");
    }

    @Test void repeatedStopIsIdempotent() {
        AgentTurnPause once = OwnerStopLatch.afterOwnerStop(AgentTurnPause.NONE);
        AgentTurnPause twice = OwnerStopLatch.afterOwnerStop(once);
        assertEquals(once, twice);
        assertFalse(OwnerStopLatch.allowsTurnStart(twice));
    }

    @Test void ownerResumeClearsTheLatch() {
        AgentTurnPause latched = OwnerStopLatch.afterOwnerStop(AgentTurnPause.NONE);
        assertEquals(AgentTurnPause.NONE, OwnerStopLatch.afterOwnerResume(latched));
        assertTrue(OwnerStopLatch.allowsTurnStart(OwnerStopLatch.afterOwnerResume(latched)));
        // 没闩着时"恢复"不该改变别的暂停原因（例如端点被拒）。
        assertEquals(AgentTurnPause.BLOCKED, OwnerStopLatch.afterOwnerResume(AgentTurnPause.BLOCKED));
    }

    @Test void resumeDoesNotSilentlyClearEndpointBlock() {
        // 收窄后的语义：/stop resume 只解主人叫停，不顺手解端点熔断。
        assertEquals(AgentTurnPause.BLOCKED, OwnerStopLatch.afterOwnerResume(AgentTurnPause.BLOCKED));
        assertEquals(AgentTurnPause.RECOVERABLE_FAILURE,
                OwnerStopLatch.afterOwnerResume(AgentTurnPause.RECOVERABLE_FAILURE));
    }

    @Test void ownerInterruptRemainsRecoverableThroughResume() {
        // 用户不会被永久卡住：主人叫停一定可通过 resume 解除。
        AgentTurnPause latched = OwnerStopLatch.afterOwnerStop(AgentTurnPause.NONE);
        assertEquals(AgentTurnPause.NONE, OwnerStopLatch.afterOwnerResume(latched));
    }

    @Test void latchIsPerCallerStateNotGlobal() {
        AgentTurnPause other = OwnerStopLatch.afterOwnerStop(AgentTurnPause.NONE);
        // 另一个同伴拿着自己的状态：不受影响（纯函数无全局量）。
        assertTrue(OwnerStopLatch.allowsTurnStart(AgentTurnPause.NONE));
        assertFalse(OwnerStopLatch.allowsTurnStart(other));
    }
}
