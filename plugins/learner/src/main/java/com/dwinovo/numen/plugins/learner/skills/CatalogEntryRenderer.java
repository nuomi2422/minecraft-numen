package com.dwinovo.numen.plugins.learner.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure directory formatting, not an execution or delivery authorization. */
public final class CatalogEntryRenderer {
    private static final int MAX_ENTRIES = 3, MAX_ENTRY_CHARS = 320;
    private static final String HEAD = "<retrieved_knowledge><note>按 ref 展开；条件未知，执行前须以当前观测复核</note>", TAIL = "</retrieved_knowledge>";
    private CatalogEntryRenderer() {}

    /** bodyReadable means read and location binding passed; verified only denotes provenance evidence.
     * Neither is execution authorization or evidence conditions were shown. */
    public record Candidate(String ref, String name, String purpose, String audience, String status,
                            boolean bodyReadable, boolean conditionsKnown, boolean verified, String availability) {
        public Candidate(String ref, String name, String purpose, String audience, String status,
                         boolean bodyReadable, boolean conditionsKnown, boolean verified) {
            this(ref, name, purpose, audience, status, bodyReadable, conditionsKnown, verified, "");
        }
    }
    public record Decision(int inputIndex, String ref, boolean included, String reason, List<String> differences) {
        public Decision(int inputIndex, String ref, boolean included, String reason) {
            this(inputIndex, ref, included, reason, List.of());
        }
    }
    public record Result(String fragment, List<String> entries, List<Decision> decisions, String reason) {
        /** Complete independent XML wrappers; formatting only, not delivery proof. */
        public String primary() { return entries.isEmpty() ? "" : HEAD + entries.get(0) + TAIL; }
        public String remainder() { return entries.size() < 2 ? "" : HEAD + String.join("", entries.subList(1, entries.size())) + TAIL; }
    }

    public static Result render(List<Candidate> candidates, String view, int budget) {
        if (candidates == null || candidates.isEmpty()) return new Result("", List.of(), List.of(), "no-match");
        Decision[] decisions = new Decision[candidates.size()];
        List<Integer> ordered = new ArrayList<>();
        java.util.Map<String, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c != null && c.ref() != null) groups.computeIfAbsent(c.ref(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> group : groups.values()) {
            Candidate first = candidates.get(group.get(0));
            java.util.Set<String> differences = new java.util.LinkedHashSet<>();
            for (int index : group) differences.addAll(differences(first, candidates.get(index)));
            if (!differences.isEmpty()) for (int index : group)
                decisions[index] = new Decision(index, first.ref(), false, "ref-conflict", List.copyOf(differences));
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (decisions[i] != null) continue;
            Candidate c = candidates.get(i);
            String rejected = qualification(c, view);
            if (rejected != null) decisions[i] = new Decision(i, c == null ? "" : safe(c.ref()), false, rejected);
            else ordered.add(i);
        }
        ordered.sort(java.util.Comparator.<Integer, Boolean>comparing(i -> !candidates.get(i).conditionsKnown())
                .thenComparing(i -> !candidates.get(i).verified()));
        List<String> entries = new ArrayList<>();
        int used = HEAD.length() + TAIL.length();
        java.util.Map<String, Candidate> seen = new java.util.HashMap<>();
        for (int index : ordered) {
            Candidate c = candidates.get(index);
            Candidate prior = seen.putIfAbsent(c.ref(), c);
            if (prior != null) {
                decisions[index] = new Decision(index, c.ref(), false,
                        prior.conditionsKnown() != c.conditionsKnown() ? "duplicate-enriched" : "duplicate"); continue;
            }
            if (entries.size() >= MAX_ENTRIES) {
                decisions[index] = new Decision(index, c.ref(), false, "over-limit"); continue;
            }
            String item = "<entry ref=\"" + xml(c.ref()) + "\" status=\"" + xml(c.status()) + "\"" +
                    " conditions=\"UNKNOWN\" provenance=\"" + (c.verified() ? "VERIFIED" : "UNKNOWN") + "\"" +
                    (c.availability().isEmpty() ? "" : " availability=\"" + xml(c.availability()) + "\"") + ">" +
                    "<name>" + xml(c.name()) + "</name><purpose>" + xml(c.purpose()) + "</purpose>" +
                    "</entry>";
            if (item.length() > MAX_ENTRY_CHARS) {
                decisions[index] = new Decision(index, c.ref(), false, "over-limit"); continue;
            }
            if (budget < 0 || used + item.length() > budget) {
                decisions[index] = new Decision(index, c.ref(), false, "over-budget"); continue;
            }
            entries.add(item); used += item.length();
            decisions[index] = new Decision(index, c.ref(), true, c.conditionsKnown() ? "included" : "condition-unknown");
        }
        String fragment = entries.isEmpty() ? "" : HEAD + String.join("", entries) + TAIL;
        return new Result(fragment, List.copyOf(entries), List.of(decisions), entries.isEmpty() ? "no-qualified-entry" : "included");
    }
    private static List<String> differences(Candidate a, Candidate b) {
        List<String> diff = new ArrayList<>();
        if (!java.util.Objects.equals(a.name(), b.name())) diff.add("name");
        if (!java.util.Objects.equals(a.purpose(), b.purpose())) diff.add("purpose");
        if (!java.util.Objects.equals(a.audience(), b.audience())) diff.add("audience");
        if (!java.util.Objects.equals(a.status(), b.status())) diff.add("status");
        if (a.bodyReadable() != b.bodyReadable()) diff.add("bodyReadable");
        if (a.verified() != b.verified()) diff.add("verified");
        if (!java.util.Objects.equals(a.availability(), b.availability())) diff.add("availability");
        return diff;
    }
    private static String qualification(Candidate c, String view) {
        if (c == null || c.ref() == null || c.name() == null || c.name().isBlank()
                || c.purpose() == null || c.purpose().isBlank() || !validXml(c.name()) || !validXml(c.purpose())) return "not-verified";
        try { com.dwinovo.numen.api.LearnedSkillBodyResolver.Reference.parse(c.ref()); }
        catch (IllegalArgumentException invalid) { return "not-verified"; }
        if (!c.bodyReadable()) return "body-missing";
        if (!("TRIAL".equals(c.status()) || "PUBLISHED".equals(c.status()))) return "not-verified";
        if (c.availability() == null || !(c.availability().isEmpty() || c.availability().equals("available")
                || c.availability().equals("degraded"))) return "not-verified";
        if (view == null || view.isBlank()) return "audience-mismatch";
        if (c.audience() != null && !c.audience().isBlank()) {
            boolean match = false;
            for (String audience : c.audience().split(",")) if (audience.trim().equalsIgnoreCase(view.trim())) match = true;
            if (!match) return "audience-mismatch";
        }
        // An AC JSON script copied into purpose is not a verified plain-language purpose.
        String purpose = c.purpose().stripLeading();
        if (purpose.startsWith("{") || purpose.startsWith("[")) return "unsafe-purpose";
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
