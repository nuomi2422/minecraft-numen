package com.dwinovo.numen.api;

/** Typed gap from knowledge contributors; thrown instead of returning null when a body is missing/unreadable. */
public final class KnowledgeGapException extends RuntimeException {
    public enum Reason { BODY_MISSING, READ_FAILED }
    private final Reason reason;
    public KnowledgeGapException(Reason reason, String detail) { super(detail == null ? "" : detail); this.reason = reason; }
    public Reason reason() { return reason; }
}
