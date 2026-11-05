package com.dwinovo.numen.plugins.learner.core;

/**
 * 「复盘额度用完时别再收待办」的闸（2026-11-05）。
 *
 * <p>背景：复盘要调 LLM，账号周额度用尽后每次都是 HTTP 429（GoUsageLimitError）。
 * 复盘失败会 restore 整批，队列只会更满；此时再收新待办等于往一个堵住的口子里塞东西。
 * 这里记一个到期时间：额度失败后的窗口内，learner_note 直接拒收并说清原因。
 *
 * <p>纯逻辑，可离线测。
 */
public final class ReviewQuotaGate {

    /** 429 之后沉默多久：够用户看到并换号，又不至于一整局都不收待办。 */
    public static final long BLOCK_MS = 30L * 60L * 1000L;

    private static volatile long blockedUntil = 0L;

    private ReviewQuotaGate() {}

    /** 复盘因额度失败时调用。 */
    public static void blockQuota(long nowMillis) {
        blockedUntil = Math.max(blockedUntil, nowMillis + BLOCK_MS);
    }

    /** @return true = 现在不收待办（额度窗口内） */
    public static boolean isBlocked(long nowMillis) {
        return nowMillis < blockedUntil;
    }

    public static void reset() {
        blockedUntil = 0L;
    }
}
