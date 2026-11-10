package com.dwinovo.numen.link.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/** Finite per-session request ledger, NOT a second TaskChain. No silent eviction/replay on restart.
 * The production host must atomically call accept with its live GoalVersion and bind that reservation
 * in its own task serial domain. Never check here then asynchronously invoke a replacement /goal.
 * Keys/versions are supplied by an authenticated adapter, not trusted from a model/network payload.
 */
public final class DelegationLedger {
    public record Key(String binding, String request) {
        public Key { LinkTypes.text(binding); LinkTypes.text(request); }
    }
    public record GoalVersion(String world, String session, String body, String goal, long revision, long attempt) {
        public GoalVersion {
            LinkTypes.text(world); LinkTypes.text(session); LinkTypes.text(body); LinkTypes.text(goal);
            if (revision < 0 || attempt < 0) throw new IllegalArgumentException("negative version");
        }
    }
    public enum State { ACCEPTED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
    public static final class Handle {
        private final Key key;
        private final GoalVersion goal;
        private Handle(Key key, GoalVersion goal) { this.key = key; this.goal = goal; }
        public Key key() { return key; }
        public GoalVersion goal() { return goal; }
    }
    public record Receipt(Check check, Handle handle, State state) {}
    private static final class Entry {
        final String payload;
        final Handle handle;
        State state = State.ACCEPTED;
        String evidence;
        Entry(String payload, Handle handle) { this.payload = payload; this.handle = handle; }
    }
    private final Map<Key, Entry> entries = new HashMap<>();
    private final int capacity;
    private boolean open = true;
    public DelegationLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    private static boolean terminal(State s) { return s == State.SUCCEEDED || s == State.FAILED || s == State.CANCELLED; }
    public synchronized Receipt accept(Key key, String canonicalPayload, GoalVersion expected,
                                       GoalVersion actual, boolean hostBusy) {
        Objects.requireNonNull(key); LinkTypes.text(canonicalPayload); Objects.requireNonNull(expected);
        if (!open) return new Receipt(new Check(Code.STALE, "ledger-closed"), null, null);
        Entry old = entries.get(key);
        if (old != null) {
            if (!old.payload.equals(canonicalPayload) || !old.handle.goal.equals(expected))
                return new Receipt(new Check(Code.DENIED, "request-key-reused"), null, null);
            return new Receipt(Check.yes(), old.handle, old.state); // replay receipt only, never re-execute
        }
        if (!expected.equals(actual)) return new Receipt(new Check(Code.STALE, "goal-changed"), null, null);
        if (hostBusy || entries.values().stream().anyMatch(e -> !terminal(e.state)
                && e.handle.goal.body.equals(actual.body) && e.handle.goal.world.equals(actual.world)))
            return new Receipt(new Check(Code.BUSY, "non-preemptive"), null, null);
        if (entries.size() >= capacity) return new Receipt(new Check(Code.CAPACITY, "receipt-limit-no-eviction"), null, null);
        Handle h = new Handle(key, actual);
        entries.put(key, new Entry(canonicalPayload, h));
        return new Receipt(Check.yes(), h, State.ACCEPTED);
    }
    private Entry entry(Handle handle) {
        if (handle == null) return null;
        Entry e = entries.get(handle.key);
        return e != null && e.handle == handle ? e : null;
    }
    public synchronized Check start(Handle h, GoalVersion actual) {
        Entry e = entry(h);
        if (!open || e == null || !h.goal.equals(actual)) return new Check(Code.STALE, "request-or-goal");
        if (e.state != State.ACCEPTED) return new Check(Code.DENIED, "not-accepted");
        e.state = State.RUNNING; return Check.yes();
    }
    public synchronized Check cancel(Handle h, GoalVersion actual) {
        Entry e = entry(h);
        if (!open || e == null || !h.goal.equals(actual)) return new Check(Code.STALE, "cancel-must-match-original-goal");
        if (terminal(e.state)) return new Check(Code.STALE, "already-terminal");
        e.state = State.CANCEL_REQUESTED; return Check.yes();
    }
    public synchronized Check uncertain(Handle h) {
        Entry e = entry(h);
        if (e == null || terminal(e.state)) return new Check(Code.STALE, "no-live-request");
        e.state = State.UNKNOWN; return Check.yes();
    }
    /** Record matching ORIGINAL task evidence even after cancellation/close, never update a new goal.
     * SUCCEEDED requires host business verification, CANCELLED requires actual stop evidence.
     */
    public synchronized Check settle(Handle h, GoalVersion evidenceGoal, State result, String evidenceRef) {
        Entry e = entry(h);
        if (e == null || !h.goal.equals(evidenceGoal)) return new Check(Code.STALE, "evidence-identity");
        if (!terminal(result)) return new Check(Code.DENIED, "not-terminal-evidence");
        LinkTypes.text(evidenceRef);
        if (terminal(e.state)) return new Check(Code.STALE, "already-settled");
        e.state = result; e.evidence = evidenceRef; return Check.yes();
    }
    public synchronized State state(Handle h) { Entry e = entry(h); return e == null ? null : e.state; }
    public synchronized String evidence(Handle h) { Entry e = entry(h); return e == null ? null : e.evidence; }
    public synchronized void close() {
        open = false;
        entries.values().stream().filter(e -> !terminal(e.state)).forEach(e -> e.state = State.UNKNOWN);
    }
}
