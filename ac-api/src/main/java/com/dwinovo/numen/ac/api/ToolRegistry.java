package com.dwinovo.numen.ac.api;

import java.util.Optional;

public interface ToolRegistry {
    void register(String name, AcTool tool);
    Optional<AcTool> find(String name);
}
