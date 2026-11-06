package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.PlanningQuery;
import com.dwinovo.numen.api.observation.WorldObservationService;

import java.util.List;
import java.util.Map;

/** 显式短期焦点的上下文投影：只读已有缓存，缺失/过期写 UNKNOWN；总长有界。 */
final class WorldFocusProjector {
    private WorldFocusProjector() {}

    static final int MAX_CHARS = 400;

    static String render(WorldStateService service, WorldObservationService observations, PlanningQuery query) {
        if (service == null || observations == null || query == null || query.companion() == null) return "";
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return "";
            String world = WorldStateService.worldId(server);
            String session = service.sessionId(server);
            long now = System.currentTimeMillis();
            List<com.dwinovo.numen.plugins.worldstate.focus.FocusPin> pins =
                    service.focusPins().active(query.companion(), world, session, now);
            if (pins.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("<focus>");
            int shown = 0;
            for (var pin : pins) {
                if (sb.length() >= MAX_CHARS) break;
                String line;
                try {
                    var resp = observations.read(new WorldObservationService.Request(
                            "rdd-focus", pin.dimension(), pin.topic(), Map.of(), 0L, 1, 0));
                    if (resp == null || resp.status() != WorldObservationService.Status.OK || resp.items().isEmpty()) {
                        line = "UNKNOWN";
                    } else {
                        var m = resp.metadata();
                        boolean current = m != null && m.freshness() == com.dwinovo.numen.api.observation.ObservationMetadata.Freshness.CURRENT
                                && (m.validUntilMs() <= 0 || m.validUntilMs() > now);
                        line = current ? summarize(resp.items().get(0).values()) : "UNKNOWN";
                    }
                } catch (RuntimeException | LinkageError noProvider) {
                    line = "UNKNOWN";
                }
                sb.append(shown == 0 ? "" : "；").append(pin.topic()).append("=").append(line);
                shown++;
            }
            int omitted = pins.size() - shown;
            sb.append("</focus>");
            String out = sb.toString();
            if (omitted > 0) out = out.replace("</focus>", "（省略 " + omitted + " 项）</focus>");
            return out.length() <= MAX_CHARS + 40 ? out : out.substring(0, MAX_CHARS) + "…</focus>";
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }

    private static String summarize(Map<String, Object> v) {
        StringBuilder sb = new StringBuilder();
        for (var e : v.entrySet()) {
            if (sb.length() > 120) break;
            if (e.getValue() instanceof Number || e.getValue() instanceof String || e.getValue() instanceof Boolean) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.length() == 0 ? "UNKNOWN" : sb.toString();
    }
}
