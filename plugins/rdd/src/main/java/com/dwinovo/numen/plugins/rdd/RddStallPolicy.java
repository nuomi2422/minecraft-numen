package com.dwinovo.numen.plugins.rdd;

/** Pure supervision policy; activity evidence is separate from merely having a task in flight. */
final class RddStallPolicy {
    static final int IDLE_GRACE_CHECKS = 15;
    /** One check per second. A busy slot alone cannot suppress supervision forever. */
    static final int WORK_GRACE_CHECKS = 120;

    /**
     * LLM 空转提前拍醒：身体<b>一次工具都没调</b>持续到这么多次检测（1 次/秒）就提前喊。
     *
     * <p>为什么要有这条独立于 {@link #IDLE_GRACE_CHECKS} 的更早信号（2026-09-29 实机）：
     * 一次真实死亡后，日志里出现 {@code chat done in 35625ms, finish=stop, tool_calls=[]}——
     * 整整 35 秒模型只在空想、零工具调用；再往后它 {@code find_tools} 查完 {@code collect_items}
     * 却没有重试，反而跑去 {@code build}/{@code decompose_goal}。
     * 而现有 idle 判据要等 {@link #IDLE_GRACE_CHECKS}=15 秒才响，且响完只换措辞重拍，
     * <b>拍完它照样跑偏</b>。
     *
     * <p><b>阈值 10 而不是用户初提的 5（2026-09-29 GLM 外脑审稿后调整）</b>：
     * 实测分布是 5.5s→有产出 / 6.5s→有产出 / 14.3s→有产出 / 35.6s→空转 / 44.0s→有产出。
     * 取 5 会低于分布下沿，等于对每一轮都拍一次。
     * <b>但要诚实记下：GLM 同时指出「空转 35.6s &lt; 有效 44.0s」，
     * 因此任何纯时间阈值都无法区分「卡住」与「慢」——这只是把假阳性压低，不是把问题解决。</b>
     * 真正的判据在响应本身（{@code finish=stop && tool_calls=[]}），那要改 {@code :ai} 层
     * （跨模块，2026-09-29 未做，见统一版 34 号页第六节）。
     *
     * <p>为什么只"拍醒"不"改状态"：时间阈值必然有假阳性。
     * 若据此 markStalled，会把"正在思考"误杀成 FAILED/STALLED，
     * 触发重试与失败升级——那才是真伤害。拍醒的代价只是一句话，可以承受。
     */
    static final int LLM_IDLE_NUDGE_AFTER_CHECKS = 10;

    /**
     * 只有 {@code source=idle} 才算「LLM 没在调工具」。
     *
     * <p>{@code body_task:*} 表示它已经在跑一个多步身体任务（walk/mine 内部会连跑几十秒，
     * 中间本就不发新工具调用）——那是<b>正常干活</b>，拿 5 秒去拍它等于打断正常工作。
     * {@code furnace_production} 同样在推进。判据必须与「有没有进展」分开。
     */
    static boolean llmIdle(String source) {
        return "idle".equals(source);
    }

    /**
     * 是否该在这一个检查点拍醒。纯函数便于单测锁住两条边界：
     * 干活的 source 永不触发；空转到阈值的那一次触发（不重复触发）。
     *
     * <p><b>两参重载 = 旧行为</b>（假定宿主给不出「在飞」）。给得出就必须用三参那版。
     */
    static boolean shouldNudgeLlmIdle(int unchanged, String source) {
        return shouldNudgeLlmIdle(unchanged, source, false);
    }

    /**
     * 同上，多一个 {@code llmInFlight} 闸（2026-09-30 加，见统一版 38 号页 §2 盲点①）。
     *
     * <p><b>这个闸为什么必须加</b>：{@link #LLM_IDLE_NUDGE_AFTER_CHECKS} 是<b>纯时间</b>阈值，
     * 而实测分布里空转 35.6s <b>&lt;</b> 有效 44.0s —— 也就是说
     * <b>「有效但慢」比「空转」还慢，纯阈值天生就会误拍正在思考的模型</b>。
     * 拍醒的措辞会覆盖原计划（见 {@code RddStallWatcher} 的三条话术铁律），
     * 所以误拍不是「多喊一句」，是<b>把模型从正确的轨道上拽下来</b>。
     *
     * <p><b>闸是 fail-open 的</b>：宿主给不出在飞状态（{@code llmInFlight=false}）时，
     * 行为与加闸之前<b>逐字相同</b> —— 加闸只允许<b>少</b>误拍，不许引入新误判。
     * 反向的 stale 保护在 {@code LlmActivity.Snapshot.stale} 那一侧：
     * 在飞太久（future 挂死）就不认在飞，<b>宁可误催，不可静默失明</b>。
     */
    static boolean shouldNudgeLlmIdle(int unchanged, String source, boolean llmInFlight) {
        return llmIdle(source) && !llmInFlight && unchanged == LLM_IDLE_NUDGE_AFTER_CHECKS;
    }

    /**
     * 坐标量化成 8 格粗桶（纯函数，无 MC 依赖，便于单测锁住"区域内抖动同桶、跨区域换桶"）。
     *
     * <p>2026-09-27 实机教训：停车守望原用**精确坐标**判进展，AI 在两点之间横跳（寻路打转、
     * 到不了目标）时坐标每 tick 变化 -> "无进展"窗口被无限重置 -> 催工永不触发，
     * 表现为"它自己一直重复走、任务永远完不成、也没人拍醒"。粗桶让区域内抖动不再重置窗口。
     */
    static String parkedBucket(int x, int y, int z) {
        return Math.floorDiv(x, 8) + "," + Math.floorDiv(y, 8) + "," + Math.floorDiv(z, 8);
    }

    record Observation(String assetsAndPosition, String workProgress, boolean waiting, String source) {
        String fingerprint() { return assetsAndPosition + "|work=" + workProgress; }
    }

    record Check(String fingerprint, int unchanged, int limit, boolean changed) {
        boolean stalled() { return unchanged >= limit; }
        int remaining() { return Math.max(0, limit - unchanged); }
    }

    static Check check(String previous, int unchanged, Observation observation) {
        String fingerprint = observation.fingerprint();
        boolean changed = !fingerprint.equals(previous);
        int next = changed ? 0 : unchanged + 1;
        return new Check(fingerprint, next,
                observation.waiting() ? WORK_GRACE_CHECKS : IDLE_GRACE_CHECKS, changed);
    }
}
