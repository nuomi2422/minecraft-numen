package com.dwinovo.numen.plugins.learner.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure directory formatting, not an execution or delivery authorization. */
public final class CatalogEntryRenderer {
    private static final int MAX_ENTRIES = 3, MAX_ENTRY_CHARS = 320;
    private static final String HEAD = "<retrieved_knowledge>", TAIL = "</retrieved_knowledge>";
    private CatalogEntryRenderer() {}

    /** verified means caller checked provenance, complete body and location binding.
     * It is not execution authorization, readiness, or evidence conditions were shown. */
    public record Candidate(String ref, String name, String purpose, String audience, String status,
                            boolean bodyReadable, boolean conditionsKnown, boolean verified) {}
    public record Decision(int inputIndex, String ref, boolean included, String reason) {}
    public record Result(String fragment, List<String> entries, List<Decision> decisions, String reason) {}

    public static Result render(List<Candidate> candidates, String view, int budget) {
        if (candidates == null || candidates.isEmpty()) return new Result("", List.of(), List.of(), "no-match");
        Decision[] decisions = new Decision[candidates.size()];
        List<Integer> ordered = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            String rejected = qualification(c, view);
            if (rejected != null) decisions[i] = new Decision(i, c == null ? "" : safe(c.ref()), false, rejected);
            else ordered.add(i);
        }
        ordered.sort((a, b) -> Boolean.compare(!candidates.get(a).conditionsKnown(), !candidates.get(b).conditionsKnown()));
        boolean includedUnknown = false;
        List<String> entries = new ArrayList<>();
        int used = HEAD.length() + TAIL.length();
        for (int index : ordered) {
            Candidate c = candidates.get(index);
            if (entries.size() >= MAX_ENTRIES || (!c.conditionsKnown() && includedUnknown)) {
                decisions[index] = new Decision(index, c.ref(), false, "over-limit"); continue;
            }
            String item = "<entry ref=\"" + xml(c.ref()) + "\"" +
                    " conditions=\"UNKNOWN\">" +
                    "<name>" + xml(c.name()) + "</name><purpose>" + xml(c.purpose()) + "</purpose>" +
                    "<note>条件未展示；按 ref 展开，执行前必核</note></entry>";
            if (item.length() > MAX_ENTRY_CHARS) {
                decisions[index] = new Decision(index, c.ref(), false, "over-limit"); continue;
            }
            if (budget < 0 || used + item.length() > budget) {
                decisions[index] = new Decision(index, c.ref(), false, "over-budget"); continue;
            }
            entries.add(item); used += item.length();
            if (!c.conditionsKnown()) includedUnknown = true;
            decisions[index] = new Decision(index, c.ref(), true, c.conditionsKnown() ? "included" : "condition-unknown");
        }
        String fragment = entries.isEmpty() ? "" : HEAD + String.join("", entries) + TAIL;
        return new Result(fragment, List.copyOf(entries), List.of(decisions), entries.isEmpty() ? "no-qualified-entry" : "included");
    }
    private static String qualification(Candidate c, String view) {
        if (c == null || c.ref() == null || c.name() == null || c.name().isBlank()
                || c.purpose() == null || c.purpose().isBlank() || !validXml(c.name()) || !validXml(c.purpose())) return "not-verified";
        try { com.dwinovo.numen.api.LearnedSkillBodyResolver.Reference.parse(c.ref()); }
        catch (IllegalArgumentException invalid) { return "not-verified"; }
        if (!c.bodyReadable()) return "body-missing";
        if (!c.verified() || !("TRIAL".equals(c.status()) || "PUBLISHED".equals(c.status()))) return "not-verified";
        if (view == null || view.isBlank()) return "audience-mismatch";
        if (c.audience() != null && !c.audience().isBlank()) {
            boolean match = false;
            for (String audience : c.audience().split(",")) if (audience.trim().equalsIgnoreCase(view.trim())) match = true;
            if (!match) return "audience-mismatch";
        }
        // An AC JSON script copied into purpose is not a verified plain-language purpose.
        String purpose = c.purpose().stripLeading();
        if (purpose.startsWith("{") || purpose.startsWith("[")) return "not-verified";
        return null;
    }
    private static boolean validXml(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (!(cp == 9 || cp == 10 || cp == 13 || cp >= 0x20 && cp <= 0xD7FF
                    || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;").replace("\r", "&#13;");
    }
}
