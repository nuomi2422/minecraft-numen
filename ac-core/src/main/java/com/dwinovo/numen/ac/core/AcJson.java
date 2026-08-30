package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.*;
import com.google.gson.*;
import java.io.Reader;
import java.util.*;

public final class AcJson {
    private AcJson() {}
    public static AcDefinition load(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        if (!root.has("name") || !root.has("version") || !root.has("steps")) throw new IllegalArgumentException("AC requires name, version and steps");
        List<AcDefinition.AcStep> steps = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("steps")) {
            JsonObject s = e.getAsJsonObject();
            Map<String,Object> params = s.has("parameters") ? new Gson().fromJson(s.get("parameters"), Map.class) : Map.of();
            steps.add(new AcDefinition.AcStep(s.get("id").getAsString(), s.get("tool").getAsString(), params));
        }
        return new AcDefinition(root.get("name").getAsString(), root.get("version").getAsString(), steps);
    }
}
