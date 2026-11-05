package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「一天写几条待办」的硬上限（2026-11-05）。
 *
 * <p>为什么需要：工具描述里早就写了「三十条是判断力出问题」，但 2026-11-05 16:34–16:46 一轮里，
 * 同伴 68 次调用中有 **36 次** 是 learner_note（超过一半的回合在写待办）。文字自律没有生效，
 * 于是加一道进程内的闸：同一同伴同一游戏日最多 {@link #MAX_PER_DAY} 条，超了明确拒收。
 *
 * <p>纯逻辑、无 Minecraft 依赖，可离线测。
 */
public final class MemoThrottle {

    /** 8 条/游戏日：足够记下真卡点，挡不住才写的人。 */
    public static final int MAX_PER_DAY = 8;

    private static final Map<UUID, long[]> STATE = new ConcurrentHashMap<>(); // uuid -> {day, count}

    private MemoThrottle() {}

    /** @return true = 还允许写；false = 今天已经写满 */
    public static boolean allow(UUID companion, long gameDay) {
        if (companion == null) return true;
        long[] s = STATE.computeIfAbsent(companion, k -> new long[] { gameDay, 0L });
        synchronized (s) {
            if (s[0] != gameDay) { s[0] = gameDay; s[1] = 0L; }
            if (s[1] >= MAX_PER_DAY) return false;
            s[1]++;
            return true;
        }
    }

    /** 单测用：清空计数。 */
    public static void reset() { STATE.clear(); }
}
