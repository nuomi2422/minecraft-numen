package com.dwinovo.numen.api;

/** Pure checks for authenticated external commands, not the in-game tool whitelist. */
public final class ExternalCommandPolicy {
    private ExternalCommandPolicy() {}
    public static void requireBound(String expectedWorld, String expectedSession,
                                    String world, String session, boolean owned) {
        if (!owned || world == null || session == null || world.isBlank() || session.isBlank()
                || !world.equals(expectedWorld) || !session.equals(expectedSession))
            throw new IllegalArgumentException("owner/world/server-session mismatch or unknown identity");
    }
    public static String command(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("one nonblank command, maximum 4096 characters");
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
