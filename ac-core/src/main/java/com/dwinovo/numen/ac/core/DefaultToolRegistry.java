package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultToolRegistry implements ToolRegistry {
    private final Map<String, AcTool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolSchema> schemas = new ConcurrentHashMap<>();
    public void register(String name, AcTool tool) { register(name, tool, new ToolSchema(name, "1", "", Map.of())); }
    public void register(String name, AcTool tool, ToolSchema schema) { if (name == null || name.isBlank() || tool == null || schema == null || !name.equals(schema.name())) throw new IllegalArgumentException("tool name, implementation and matching schema required"); if (tools.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("duplicate tool: " + name); schemas.put(name, schema); }
    public Optional<AcTool> find(String name) { return Optional.ofNullable(tools.get(name)); }
    public Optional<ToolSchema> schema(String name) { return Optional.ofNullable(schemas.get(name)); }
}
