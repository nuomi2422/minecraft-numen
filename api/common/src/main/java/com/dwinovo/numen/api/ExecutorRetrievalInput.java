package com.dwinovo.numen.api;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 044 B0/B1 (2026-11-11): bounded query projection, not a second task store.
 * Reads one already-rendered per-companion snapshot; never evaluates contributors or reads the world.
 */
public final class ExecutorRetrievalInput {
    public static final int MAX_CHARS = 400;
    private ExecutorRetrievalInput() {}

    public static String build(List<StateFragment> fragments, String coreGoal, String body, String rationale) {
        List<StateFragment> snapshot = fragments == null ? List.of() : fragments;
        String rdd = snapshot.stream().filter(f -> f.owner().equals("rdd") && f.id().equals("runtime"))
                .map(StateFragment::text).findFirst().orElse(null);
        String objective = "", phase = "", step = "", why = "";
        if (rdd == null) {
            objective = coreGoal;
            why = tag(rationale, "why");
        } else if ("true".equals(tag(rdd, "active")) && !"true".equals(tag(rdd, "decomposing"))) {
            if ("SIDE".equals(tag(rdd, "scope"))) {
                // Use the existing side projection, not the suspended main chain or its rationale.
                step = plain(tag(rdd, "side_task"));
            } else if (!List.of("COMPLETED", "FAILED").contains(tag(rdd, "primary_status"))) {
                objective = tag(rdd, "goal_objective");
                phase = tag(rdd, "current_phase");
                step = tag(rdd, "current_task");
                if (!step.isBlank()) why = tag(rationale, "why");
            }
        }
        StringBuilder out = new StringBuilder();
        // Keep applicability facts intact, not a truncated XML opening tag.
        String facts = NumenPlugins.stateFactsOf(snapshot).trim();
        if (!facts.isEmpty() && facts.length() <= 110) out.append(facts);
        add(out, "goal", objective, 70);
        add(out, "phase", phase, 60);
        add(out, "step", step, 70);
        add(out, "body", body, 35);
        add(out, "why", why, 25);
        return out.toString(); // 110 + 70+60+70+35+25 + labels/separators <= 400
    }
    private static void add(StringBuilder out, String label, String value, int allowance) {
        String text = plain(value);
        if (text.isBlank()) return;
        if (out.length() > 0) out.append(' ');
        out.append(label).append(':').append(text, 0, Math.min(allowance, text.length()));
    }
    private static String tag(String xml, String name) {
        if (xml == null) return "";
        Matcher match = Pattern.compile("<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return match.find() ? match.group(1) : "";
    }
    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", " ").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }
}
