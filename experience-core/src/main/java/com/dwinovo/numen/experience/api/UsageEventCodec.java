package com.dwinovo.numen.experience.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LC3（78 号工作单 §7 判据「日常样本能复算」）：五态样本 ⇄ monitor 事件的编解码器。
 *
 * <h2>它补的是哪个洞</h2>
 * {@code TrialRuntime} 的类注释一直承诺「真账随时可以从 monitor 事件重算」（C2 口径），
 * 但这个承诺此前是<b>假的</b>：五态账只进内存 {@code USAGE} 表，从没发过带全字段的事件
 * —— {@code trial_used} 只带三态归因的 ref/outcome，缺 version/artifact/attempt/时间戳，
 * 重算在结构上不可能。本类 + {@code TrialRuntime} 的 {@code usage_recorded} 事件一起把承诺变真：
 * <b>事件是唯一真账，内存只是缓存</b>。
 *
 * <h2>键名契约（与 usage_recorded 事件逐字一致）</h2>
 * {@code ref / version / artifact / state / attempt / at} —— 小写短名，与 monitor
 * 既有事件风格一致。改键名 = 断重算，两边必须一起动（本类是唯一真源）。
 *
 * <h2>容错纪律（与 UsageState.parse 同款）</h2>
 * 坏行不抛、不猜：认不出的 state 落 {@link UsageState#UNKNOWN}（IC11：宁记不知道）；
 * 缺 ref 的行直接跳过（没有归因对象的样本重算出来只会污染账）。
 * 跳过数由调用方从返回的列表长度差自己算 —— 编码器不做 IO、不持有状态。
 */
public final class UsageEventCodec {

    private UsageEventCodec() {}

    /** 样本 → 事件 data 段（扁平 map，可直接进 monitor envelope）。 */
    public static Map<String, Object> toEvent(UsageSample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            return m;
        }
        m.put("ref", s.entryRef());
        m.put("version", s.version());
        m.put("artifact", s.artifactId());
        m.put("state", s.state().name());
        m.put("attempt", s.attempt());
        m.put("at", s.atMillis());
        return m;
    }

    /**
     * 事件 data 段 → 样本。null/缺 ref/坏 attempt 跳过（返回列表里没有它）；
     * 认不出的 state 落 UNKNOWN —— 与写入侧 {@code UsageState.parse} 同一容错方向。
     */
    public static UsageSample fromEvent(Map<String, Object> e) {
        if (e == null) {
            return null;
        }
        String ref = str(e.get("ref"));
        if (ref.isBlank()) {
            return null;
        }
        String version = str(e.get("version"));
        String artifact = str(e.get("artifact"));
        UsageState state = UsageState.parse(str(e.get("state")));
        int attempt = (int) numberOf(e.get("attempt"), 1);
        long at = numberOf(e.get("at"), 0L).longValue();
        return new UsageSample(ref, attempt, "", artifact, version, state, at);
    }

    /** 一串事件 → 样本列表（保持输入顺序；跳过的行不出现在结果里）。 */
    public static List<UsageSample> fromEvents(List<Map<String, Object>> events) {
        List<UsageSample> out = new ArrayList<>();
        if (events == null) {
            return out;
        }
        for (Map<String, Object> e : events) {
            UsageSample s = fromEvent(e);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Number numberOf(Object v, Number dflt) {
        if (v instanceof Number n) {
            return n;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }
}
