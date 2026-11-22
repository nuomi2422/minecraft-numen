package com.dwinovo.numen.acx.api;

/** Immutable session owner. The host token is opaque to the pure JVM engine. */
public record AcxRunBinding(String ownerId, Object bodyToken, Object yieldBinding) {
    public AcxRunBinding(String ownerId, Object bodyToken) { this(ownerId, bodyToken, null); }
    public AcxRunBinding {
        ownerId = ownerId == null ? "" : ownerId;
    }

    public static AcxRunBinding named(String ownerId) {
        return new AcxRunBinding(ownerId, null);
    }

    /** Same UUID with a replacement body is a different host incarnation. */
    public boolean matches(AcxRunBinding caller) {
        return caller != null && ownerId.equals(caller.ownerId) && bodyToken == caller.bodyToken;
    }
}
