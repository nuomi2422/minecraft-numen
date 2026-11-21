package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 身体动作受理身份与查账证据；只供原端口及适配器使用。
 * RUNNING 仅证明占槽；不靠提示文本猜进展、路由或定向取消。
 * 宿主在服务端核对原 TaskRecord；无事实的字段保留 UNKNOWN。
 */
public final class AcxBodyAction {
    private AcxBodyAction() { }

    public record Ref(String taskId, String callId, String tool, String companion,
                      String world, String serverSession, long epoch) {
        public boolean complete() {
            return present(taskId) && present(callId) && present(tool) && present(companion)
                    && present(world) && present(serverSession) && epoch > 0;
        }

        private static boolean present(String s) { return s != null && !s.isBlank(); }
    }

    public enum State { UNKNOWN, ACTIVE, DONE, FAILED, TIMEOUT }
    public enum Progress { UNKNOWN, VERIFIED }
    public enum Route { UNKNOWN, NOT_USABLE }

    /** Reserved data key: the action owner's bounded, fresh progress sample (never a verdict). */
    public static final String PROGRESS_FACTS = "progress_facts";

    public record Snapshot(Ref action, State state, Progress progress, Route route, Map<String, Object> data) {
        public Snapshot(Ref action, State state, Progress progress, Route route) {
            this(action, state, progress, route, Map.of());
        }

        public Snapshot {
            data = data == null || data.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }

        public static Snapshot unknown() {
            return new Snapshot(null, State.UNKNOWN, Progress.UNKNOWN, Route.UNKNOWN);
        }

        public boolean matches(Ref wanted) {
            return wanted != null && wanted.complete() && wanted.equals(action);
        }

        public Map<String, Object> output() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", state.name());
            out.put("progress", progress.name());
            out.put("route", route.name());
            // 2026-11-19: bounded producer facts travel with the action state; never a verdict.
            Object facts = data.get(PROGRESS_FACTS);
            if (facts instanceof Map<?, ?> map && !map.isEmpty()) {
                out.put(PROGRESS_FACTS, map);
            }
            return out;
        }
    }

    /** terminalState 仅来自原终态表；null 表示按占槽观察，不是终态。 */
    public static Snapshot fromTask(Ref wanted, Ref actual, String terminalState) {
        if (wanted == null || !wanted.complete() || !wanted.equals(actual)) return Snapshot.unknown();
        State state = switch (terminalState) {
            case null -> State.ACTIVE;
            case "SUCCESS" -> State.DONE;
            case "FAILED" -> State.FAILED;
            case "TIMEOUT" -> State.TIMEOUT;
            default -> State.UNKNOWN; // CANCELLED 没有可信的定向让位原因
        };
        return new Snapshot(actual, state, Progress.UNKNOWN, Route.UNKNOWN);
    }
}
