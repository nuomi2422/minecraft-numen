package com.dwinovo.numen.worldstate.core.changes.v2;

/**
 * 消费者侧游标状态机（041 §18.2 / 042 R3-5）：把「RESET → 重同步」这件事收成一个可测的小类。
 *
 * <h2>为什么单独抽出来</h2>
 * 退避与活锁熔断写在消费者里面就没法离线测——上一轮的经验是「只测纯函数会漏」，
 * 而这里恰恰是纯逻辑：给定页序列，游标怎么走、什么时候停、什么时候告警，全都能断言。
 *
 * <h2>三条规矩</h2>
 * <ul>
 *   <li>{@code nextCursor} 只前进不后退（除非被 RESET 重置到 watermark）；</li>
 *   <li>连续 RESET 达 {@code maxResets} 次 → {@link #livelocked()} 为真，调用方必须停自动重同步、
 *       转有界轮询并告警（否则空日志会每 tick 触发一次重同步）；</li>
 *   <li>一次正常读到变化就把计数清零——活锁判的是"连续失败"，不是"历史失败总数"。</li>
 * </ul>
 *
 * <p>线程：本类是可变状态，**只能单线程使用**（消费者自己的 tick）。
 */
public final class ChangeCursorState {

    /** 退避上限（毫秒）。 */
    public static final long MAX_BACKOFF_MS = 30_000L;
    public static final int DEFAULT_MAX_RESETS = 3;

    private final int maxResets;
    private long cursor = 0L;
    private String epoch = "";
    private int consecutiveResets = 0;
    private long backoffMs = 0L;
    private boolean livelocked = false;

    public ChangeCursorState() {
        this(DEFAULT_MAX_RESETS);
    }

    public ChangeCursorState(int maxResets) {
        this.maxResets = Math.max(1, maxResets);
    }

    /**
     * 消化一页结果。
     *
     * @return true 表示需要调用方执行一次**有界重同步**（重读快照 + 重新取 watermark）
     */
    public boolean onPage(ChangesPage page) {
        if (page == null) return false;
        boolean firstUse = epoch == null || epoch.isBlank();
        boolean epochChanged = !firstUse && page.streamEpoch() != null && !page.streamEpoch().isBlank()
                && !page.streamEpoch().equals(epoch);
        if (firstUse) {
            // 第一次使用：采纳页里的 epoch，不算"换流"——否则消费者开局就被当成断档
            epoch = page.streamEpoch();
        }
        if (epochChanged) {
            // 换流 = 游戏重启，是**正常事件**，不是失败：立刻重同步、退避清零、不计入熔断计数。
            // （审计实测：之前每重启一次 +1，三次正常重启就误判活锁；一天重启十几次是常态。）
            epoch = page.streamEpoch();
            cursor = page.watermark();
            consecutiveResets = 0;
            backoffMs = 0L;
            livelocked = false;
            return true;
        }
        if (page.resetRequired()) {
            cursor = page.watermark();
            epoch = page.streamEpoch();
            consecutiveResets++;
            backoffMs = nextBackoff();
            livelocked = consecutiveResets >= maxResets;
            return true;
        }
        cursor = page.nextCursor();
        epoch = page.streamEpoch();
        if (!page.events().isEmpty()) {
            // 真的读到变化 = 链路健康，活锁计数清零
            consecutiveResets = 0;
            backoffMs = 0L;
            livelocked = false;
        }
        return false;
    }

    /** 重同步失败时调用：退避翻倍（上限 30 秒）。 */
    public long onResyncFailed() {
        consecutiveResets++;
        backoffMs = nextBackoff();
        livelocked = consecutiveResets >= maxResets;
        return backoffMs;
    }

    public long cursor() {
        return cursor;
    }

    public String epoch() {
        return epoch;
    }

    public int consecutiveResets() {
        return consecutiveResets;
    }

    public long backoffMs() {
        return backoffMs;
    }

    /** 连续 RESET 过多：调用方应停自动重同步、转有界轮询并告警。 */
    public boolean livelocked() {
        return livelocked;
    }

    /** 人工/场景恢复后清空熔断（例如 epoch 变化后重新开始）。 */
    public void clearLivelock() {
        consecutiveResets = 0;
        backoffMs = 0L;
        livelocked = false;
    }

    private long nextBackoff() {
        if (backoffMs <= 0L) return 1_000L;
        return Math.min(MAX_BACKOFF_MS, backoffMs * 2L);
    }
}
