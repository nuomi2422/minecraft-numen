package com.dwinovo.numen.client.agent;

/**
 * 2026-11-10（COV-r1 实机）：主人按停止后，回合仍被"低血量预警/重生"这类急件拉起来，
 * 停后 60 秒派发 10 条 LLM 请求。根因是"上闩"只写在 abort 的**忙**分支里：
 * 空闲时按停止只清了队列，没有进入 {@link AgentTurnPause#OWNER_INTERRUPT}。
 *
 * <p>这里把判定抽成纯函数，让"空闲停也要上闩""急件不解闩""只有主人继续才解闩"
 * 这些语义可以被单测直接钉住，而不是只靠读代码。
 *
 * <p>边界（别扩成全冻结）：
 * <ul>
 *   <li>急件（alarm/respawn）**不得**自动开 LLM 轮；身体避险动作不经过这里，照常可做；</li>
 *   <li>只有主人明确继续（{@code /stop resume} 或主人再说一句话）才解除；</li>
 *   <li>闩是**每个同伴一份状态**（由调用方持有），本类不存全局量，不会误停其他同伴。</li>
 * </ul>
 */
public final class OwnerStopLatch {
    private OwnerStopLatch() {}

    /** 主人叫停：与忙闲无关，一律上闩。重复叫停幂等（还是同一个值）。 */
    public static AgentTurnPause afterOwnerStop(AgentTurnPause current) {
        return AgentTurnPause.OWNER_INTERRUPT;
    }

    /**
     * 主人明确继续（/stop resume 或主人再说一句）。
     *
     * <p>只解除 OWNER_INTERRUPT；端点被拒（BLOCKED）、可恢复失败等其他暂停原因**保持不动**——
     * 旧实现是一律置 NONE，等于"主人说继续"顺手把端点熔断也解了，语义更宽。这里收窄，
     * 属有意变更（2026-11-10）。
     */
    public static AgentTurnPause afterOwnerResume(AgentTurnPause current) {
        return current == AgentTurnPause.OWNER_INTERRUPT ? AgentTurnPause.NONE : current;
    }

    /** 急件/普通事件进来时是否允许开新轮：主人叫停期间一律不允许（含急件）。 */
    public static boolean allowsTurnStart(AgentTurnPause current) {
        return current != AgentTurnPause.OWNER_INTERRUPT;
    }
}
