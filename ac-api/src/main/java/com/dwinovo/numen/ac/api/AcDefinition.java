package com.dwinovo.numen.ac.api;

import java.util.List;
import java.util.Map;

public record AcDefinition(String name, List<AcStep> steps) {
    public AcDefinition { if (name == null || name.isBlank()) throw new IllegalArgumentException("AC name required"); if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("AC steps required"); steps = List.copyOf(steps); }
    public record AcStep(String id, String tool, Map<String,Object> parameters) {
        public AcStep { if (id == null || id.isBlank() || tool == null || tool.isBlank()) throw new IllegalArgumentException("step id/tool required"); parameters = parameters == null ? Map.of() : Map.copyOf(parameters); }
    }
}
