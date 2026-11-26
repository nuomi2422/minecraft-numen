package com.dwinovo.numen.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure canonical tool-call fingerprints. Object key order and whitespace are not
 * semantic; array order, coordinates, targets and quantities are. Invalid or
 * non-object arguments are UNKNOWN and must not be counted as repeats.
 */
public final class ToolCallFingerprint {
    private ToolCallFingerprint() {}

    /** Stable JSON with recursively sorted object keys; no numeric normalization. */
    public static String canonicalArguments(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(argsJson);
            if (!parsed.isJsonObject()) return null;
            return canonical(parsed).toString();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** SHA-256 of exact visible text; blank/unknown text is not repeat evidence. */
    public static String hashText(String text) {
        if (text == null || text.isBlank()) return null;
        return hash(text);
    }

    /** SHA-256 of canonical arguments, or null when the arguments are UNKNOWN. */
    public static String hashArguments(String argsJson) {
        String canonical = canonicalArguments(argsJson);
        if (canonical == null) return null;
        return hash(canonical);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) hex.append(String.format("%02x", valueByte & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Tool name plus canonical argument hash. The raw arguments never enter the key. */
    public static String action(String tool, String argsJson) {
        if (tool == null || tool.isBlank()) return null;
        String args = hashArguments(argsJson);
        return args == null ? null : tool + "|" + args;
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject input = value.getAsJsonObject();
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
                sorted.put(entry.getKey(), canonical(entry.getValue()));
            }
            JsonObject output = new JsonObject();
            sorted.forEach(output::add);
            return output;
        }
        if (value.isJsonArray()) {
            JsonArray input = value.getAsJsonArray();
            List<JsonElement> items = new ArrayList<>(input.size());
            input.forEach(item -> items.add(canonical(item)));
            JsonArray output = new JsonArray();
            items.forEach(output::add);
            return output;
        }
        return value;
    }
}
