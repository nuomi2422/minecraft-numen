package com.dwinovo.numen.plugins.worldstate.probe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次只读探针的结果：数据 + 覆盖 + 失败原因。
 *
 * <p><b>失败不是空值</b>：{@code reason != null} 时 {@code data} 必须为 null，调用方
 * 不得把 {@code false}/{@code 0} 当成「世界就是这样」。这是 worldstate 的「未知 ≠ 零」
 * 纪律在探针面上的延续。
 */
public record ProbeResult(Map<String, Object> data, String coverage, String reason, String message) {

    public static ProbeResult ok(Map<String, Object> data, String coverage) {
        return new ProbeResult(data == null ? Map.of() : data, coverage, null, null);
    }

    public static ProbeResult complete(Map<String, Object> data) {
        return ok(data, "COMPLETE");
    }

    public static ProbeResult partial(Map<String, Object> data) {
        return ok(data, "PARTIAL");
    }

    public static ProbeResult failed(String reason, String message) {
        return new ProbeResult(null, "UNKNOWN", reason == null ? "PROVIDER_ERROR" : reason, message);
    }

    public boolean ok() {
        return reason == null;
    }

    /** 给回执用的信封字段；不把 data 再包一层，避免模型多读一层键。 */
    public Map<String, Object> envelope() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", ok() ? "OK" : "FAILED");
        out.put("coverage", coverage);
        if (reason != null) out.put("reason", reason);
        if (message != null) out.put("message", message);
        if (data != null) out.put("data", data);
        return out;
    }
}
