package com.dwinovo.numen.plugins.rdd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stateless flag IO/interpretation only. RddPlugin owns state, fallback and publication. */
final class RddControlFlagReads {
    private RddControlFlagReads() {}

    static boolean bodyDispatchEnabled(Path flag) throws IOException {
        String content = content(flag);
        return content.equalsIgnoreCase("on") || content.equalsIgnoreCase("run") || content.equals("1");
    }

    static boolean supervisionPaused(Path flag) throws IOException {
        String content = content(flag);
        return !content.isEmpty() && !content.equalsIgnoreCase("run");
    }

    static boolean pauseDisabled(Path flag) {
        return Files.exists(flag);
    }

    private static String content(Path flag) throws IOException {
        return Files.exists(flag) ? Files.readString(flag, StandardCharsets.UTF_8).trim() : "";
    }
}
