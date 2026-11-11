package com.dwinovo.numen.link.core;

import java.util.*;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/**
 * 052/P2, 2026-11-11: UI-neutral rows and revision-checked patch receipts.
 * Snapshot lacks draining diagnostics: absent trusted host status stays UNKNOWN.
 * No timer, auto-drain/resume, direct body access or inferred successful switch.
 * Current consumer is the offline fixture; a graphical UI/production adapter is not installed.
 */
public final class LinkStatusView {
    private LinkStatusView() {}
    public record Row(Slot slot, Choice choice, String providerVersion, Check status) {}
    public record View(long revision, List<Row> rows) {
        public View { rows = List.copyOf(rows); }
    }
    public record Update(Check check, LinkPolicy.Snapshot snapshot) {}

    /** Document revision is a compare-and-set precondition, not a restored live epoch.
     * Missing slots remain unchanged: callers disable explicitly when changing a full preset.
     */
    public static Update submitDocument(LinkPolicy policy, LinkConfiguration.Document document,
                                        Binding binding, CapabilityCatalog catalog, Set<String> available) {
        synchronized (policy) {
            if (policy.snapshot().revision() != document.revision())
                return new Update(new Check(Code.STALE, "policy-session-or-revision"), policy.snapshot());
            Check check = LinkConfiguration.validate(document, binding, catalog, available);
            if (check.ok()) check = policy.apply(document.revision(), document.choices(), available);
            return new Update(check, policy.snapshot());
        }
    }

    public static View render(LinkPolicy.Snapshot snapshot, CapabilityCatalog catalog,
                              Map<Slot, Check> trustedDiagnostics) {
        if (!snapshot.choices().keySet().containsAll(trustedDiagnostics.keySet()))
            throw new IllegalArgumentException("diagnostic-for-unknown-slot");
        var rows = new ArrayList<Row>();
        snapshot.choices().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Slot, Choice> e) -> e.getKey().host().name())
                        .thenComparing(e -> e.getKey().capability().name()))
                .forEach(entry -> {
                    Choice choice = entry.getValue();
                    Provider provider = catalog.provider(choice.provider());
                    Check status = !choice.enabled() ? new Check(Code.DENIED, "disabled")
                            : trustedDiagnostics.getOrDefault(entry.getKey(),
                                    new Check(Code.UNKNOWN, "host-status-not-provided"));
                    rows.add(new Row(entry.getKey(), choice, provider == null ? "UNKNOWN" : provider.version(), status));
                });
        return new View(snapshot.revision(), rows);
    }
    /** Trusted host serial domain only. A BUSY/STALE result is displayed, never bypassed. */
    public static Update submit(LinkPolicy policy, long expectedRevision, Map<Slot, Choice> patch,
                                Set<String> available) {
        synchronized (policy) {
            Check check = policy.apply(expectedRevision, patch, available);
            return new Update(check, policy.snapshot());
        }
    }
}
