package com.dwinovo.numen.ac.api;

import java.util.Map;

/** Describes an injectable tool without exposing its host implementation. */
public record ToolSchema(String name, String version, String description, Map<String, String> parameters) {
    public ToolSchema {
        if (name == null || name.isBlank() || version == null || version.isBlank()) throw new IllegalArgumentException("tool name/version required");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
