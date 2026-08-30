package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultToolRegistry implements ToolRegistry {
    private final Map<String, AcTool> tools = new ConcurrentHashMap<>();
    public void register(String name, AcTool tool) { if (name == null || name.isBlank() || tool == null) throw new IllegalArgumentException("tool name and implementation required"); if (tools.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("duplicate tool: " + name); }
    public Optional<AcTool> find(String name) { return Optional.ofNullable(tools.get(name)); }
}
