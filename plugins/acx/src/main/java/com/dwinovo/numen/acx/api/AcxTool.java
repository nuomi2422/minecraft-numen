package com.dwinovo.numen.acx.api;

import java.util.Map;

/**
 * ═══ INTERFACE CONTRACT: ACX-T1 积木 ═══
 * <p>语义：AC 的最小可执行单元。执行器保证传入的 params 已经完成 {@code $} 引用解析，
 * 并保证熔断阈值与停滞检测在调用前后生效。</p>
 * <p>方向：{@code AcxRunner} → {@code AcxTool.execute}</p>
 * <p>消费：一切 AC 脚本</p>
 * <p>违反：积木自己读世界不走 {@link AcxGameThreadGate} → 跨线程崩溃（见 ACX-G1）</p>
 * <p>违反：积木长时间阻塞 → 撞 wall-clock 熔断，整条 AC 记 TIMEOUT</p>
 */
public interface AcxTool {

    String name();

    AcxToolSchema schema();

    AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx);

    /**
     * ═══ AC-B18 · 在飞查询（2026-10-21 新增，owner 批准的契约扩展）═══
     *
     * <p>语义：**这一步还挂着一次「未被消费」的交付吗？** 已交付、runner 还没消费掉 → {@code true}
     * （**包括已经做完但还没被推进的情况** —— 那种要回 {@code true} + {@code inFlightStatus=SUCCESS}，
     * 否则 resume 会把做完的动作再做一遍）。
     *
     * <p>为什么必须加：修之前 runner 在 resume 时只看到「上一步是 PAUSED」，不知道它是
     * <b>「已受理、还在跑」</b>还是<b>「没开始/被主动暂停」</b>，于是从同一步重跑 —— 动作被再发一次
     * （重复挖、重复走、重复消耗资源）。
     *
     * <p>为什么不能让 runner 「一律不重跑已 PAUSED 的步」：适配层的恢复路径**正是**靠
     * 「重跑这一步 → 命中挂账 → 查完成探针」实现的（{@link AcxCompletionProbe}）。
     * 两条路共用同一个入口，只有积木自己知道该走哪条。
     *
     * <p><b>默认 false</b>：不实现本方法的积木行为与以前完全一致（不会突然变成「永不重试」）。
     */
    default boolean inFlight(AcxCallContext ctx) {
        return false;
    }

    /**
     * 在飞动作的当前状态：{@code SUCCESS}=已完成（runner 可推进）、{@code FAIL}=已失败、
     * {@code PAUSED}=仍在进行（runner 保持暂停、**不重发**）。默认 {@code PAUSED}。
     */
    default AcxStatus inFlightStatus(AcxCallContext ctx) {
        return AcxStatus.PAUSED;
    }

    /** 调用上下文：给积木回填取消检查、执行身份、事件上报。 */
    interface AcxCallContext {

        String runId();

        String stepId();

        /** 熔断已触发时为 {@code true}。长耗时积木应在循环里轮询它并提前收手。 */
        boolean isCancelRequested();

        /**
         * 顶层 AC 名。
         *
         * <p>适配层的「已受理」挂账不能拿 {@link #runId()} 当键 —— resume 每次
         * {@code runFrom} 都生成新 runId，用它会永远对不上，resume 退化成重发命令。
         * 默认空串是为了老实现不用改（引擎自己会填）。</p>
         */
        default String acName() {
            return "";
        }

        /**
         * 本次调用是否处于「断点续跑」路径。
         *
         * <p>resume 从断点步骤重跑时，前序步骤的输出没有持久化，{@code $prev.x} 这类
         * 引用解析不出来（会保留字面串）。适配层的挂账签名不能因此判「换了目标」把
         * 已受理的任务丢掉 —— 定义变了的话 {@code resume} 的指纹校验已经拦掉了。
         * 默认 {@code false}。</p>
         */
        default boolean isResuming() {
            return false;
        }

        /** 上报一条与本次调用相关的结构化事件。 */
        void emitDetail(String key, Object value);

        /**
         * 当前所在的循环迭代号（0 = 不在循环里 / 第一轮）。
         *
         * <p><b>为什么要它</b>（AC-B12b）：for 体里的异步步，每一轮**stepId 相同、参数不同**。
         * 挂账键只按 {@code AC名|stepId} 会把第 2 轮的账算到第 1 轮头上 → 第 2 轮不发任务却拿到
         * 「已完成」→ AC 报成功而身体没动（V-20261021-09）。迭代号是这类「同一步骤重复执行」的唯一可靠身份。
         */
        default int loopIter() {
            return 0;
        }
    }
}