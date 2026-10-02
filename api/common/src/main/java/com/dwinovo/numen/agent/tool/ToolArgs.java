package com.dwinovo.numen.agent.tool;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared argument readers for {@link NumenTool} implementations. Every tool hand-parses
 * the same handful of JSON-arg shapes — a required int, a nullable coordinate, a namespaced
 * item id — so the readers lived as private copies in a dozen tools. This collapses them
 * into one place with one set of error messages.
 *
 * <p>All throw {@link IllegalArgumentException} on malformed input; the payload handler and
 * agent loop turn that into a {@code success:false} tool result the model can read and correct.
 */
public final class ToolArgs {

    private ToolArgs() {}

    // ---- integers ----

    /** A required integer arg. */
    public static int requireInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer");
        }
    }

    /** A required integer, clamped into {@code [min, max]}. */
    public static int requireInt(JsonObject args, String key, int min, int max) {
        return Math.clamp(requireInt(args, key), min, max);
    }

    /** A nullable integer arg: {@code null} when absent or JSON null. */
    public static Integer optionalInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer or null");
        }
    }

    /** An optional integer that falls back to {@code fallback} when absent. */
    public static int optionalInt(JsonObject args, String key, int fallback) {
        Integer v = optionalInt(args, key);
        return v != null ? v : fallback;
    }

    // ---- doubles ----

    /** A required numeric arg. */
    public static double requireDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number");
        }
    }

    /** A required numeric arg, clamped into {@code [min, max]}. */
    public static double requireDouble(JsonObject args, String key, double min, double max) {
        return Math.clamp(requireDouble(args, key), min, max);
    }

    /** A nullable numeric arg: {@code null} when absent or JSON null. */
    public static Double optionalDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number or null");
        }
    }

    // ---- items ----

    /** A required namespaced item id under {@code key}. */
    public static Item requireItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return parseItem(args.get(key).getAsString());
    }

    /** An optional namespaced item id under {@code key}: {@code null} when absent. */
    public static Item optionalItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        return parseItem(args.get(key).getAsString());
    }

    /** Parse a raw namespaced item id (e.g. {@code minecraft:diamond}) into a real item. */
    public static Item parseItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            throw new IllegalArgumentException("not a valid item id: " + id);
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return item;
    }

    /**
     * A lenient set of items from a string array under {@code key}: unparseable or unknown
     * ids are skipped, and an absent / non-array arg yields an empty set — the "match
     * everything" filter the collect/scan tools rely on.
     */
    public static Set<Item> itemSet(JsonObject args, String key) {
        Set<Item> out = new LinkedHashSet<>();
        if (!args.has(key) || !args.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement el : args.getAsJsonArray(key)) {
            if (el == null || el.isJsonNull()) continue;
            ResourceLocation id = ResourceLocation.tryParse(el.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                out.add(BuiltInRegistries.ITEM.get(id));
            }
        }
        return out;
    }

    // ---- positions ----

    /**
     * An optional block coordinate from {@code x}/{@code y}/{@code z}: all three present →
     * that position; none present → {@code null} (the caller auto-picks); a partial set is
     * an error.
     */
    public static BlockPos optionalPos(JsonObject args) {
        boolean hasX = args.has("x") && !args.get("x").isJsonNull();
        boolean hasY = args.has("y") && !args.get("y").isJsonNull();
        boolean hasZ = args.has("z") && !args.get("z").isJsonNull();
        if (!hasX && !hasY && !hasZ) return null;
        if (!(hasX && hasY && hasZ)) {
            throw new IllegalArgumentException("give all three of x/y/z to target a position, or none");
        }
        return new BlockPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
    }

    // ---- typed args records ----

    private static final Gson GSON = new Gson();

    /** Pulls {@code $.some_key} out of a Gson type-mismatch message. */
    private static final Pattern GSON_PATH = Pattern.compile("path \\$((?:\\.[A-Za-z0-9_]+)+)");

    /**
     * Deserialize a tool's typed args record, tolerating the shape mistake models make most
     * often: sending an array as a JSON <em>string</em> — e.g.
     * {@code "block_ids":"[\"minecraft:stone\"]"} instead of {@code "block_ids":["minecraft:stone"]}.
     *
     * <p>Untreated, Gson throws {@code JsonSyntaxException} whose message is the wrapped
     * {@code "java.lang.IllegalStateException: Expected BEGIN_ARRAY but was STRING at path
     * $.block_ids"}. That reaches the model as an opaque {@code invalid arguments: ...} which
     * names neither the key it should fix nor the shape it should use, so it retries the same
     * shape. Here such a string is coerced back into the container it plainly means (a bare
     * scalar becomes a one-element array), and whatever is still unusable comes back as a plain
     * {@code IllegalArgumentException} naming the key — the contract every tool here documents.
     *
     * <p>Only record components declared as a collection or array are touched, so scalars keep
     * failing loudly exactly as before.
     *
     * @param args raw args; {@code null} or JSON null yields {@code null}, matching the
     *             {@code GSON.fromJson} behaviour the tools already branch on
     */
    public static <T> T fromJson(JsonObject args, Class<T> cls) {
        if (args == null || args.isJsonNull()) {
            return null;
        }
        JsonObject normalized = coerceNullSentinels(coerceScalarizedCollections(args, cls), cls);
        try {
            return GSON.fromJson(normalized, cls);
        } catch (JsonParseException | IllegalStateException | NumberFormatException ex) {
            throw new IllegalArgumentException(describeMismatch(normalized, cls, ex));
        }
    }

    /**
     * The same read for tools that hold their arguments as a raw JSON string
     * ({@code ToolCall.rawArgs()}) rather than a parsed object.
     *
     * <p>Blank input yields {@code null} and anything that is not a JSON object is rejected
     * loudly, so the callers that dereference the result keep failing where they used to instead
     * of quietly receiving an empty record.
     */
    public static <T> T fromJson(String rawJson, Class<T> cls) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(rawJson);
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("arguments are not valid JSON: " + ex.getMessage());
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object, got "
                    + gotTypeName(parsed));
        }
        return fromJson(parsed.getAsJsonObject(), cls);
    }

    /**
     * Copy {@code args} with every "I don't know" placeholder in a nullable numeric field
     * replaced by JSON {@code null}, so a field the tool treats as optional really is optional.
     *
     * <p>Models routinely spell "unspecified" as the <em>string</em> {@code "None"} (a Python
     * reflex) rather than omitting the key. Untreated, Gson hands that to the numeric adapter
     * and it dies with {@code NumberFormatException: For input string: "None"}, which reaches
     * the model as {@code invalid arguments: For input string: "None"} — no key, no hint. On
     * {@code goto} that is one call wasted on a field the schema already says to omit.
     *
     * <p>Only boxed-number components are touched, so a primitive {@code int} still fails loudly
     * rather than being silently zeroed, and a numeric string like {@code "5"} is left alone —
     * Gson already reads that correctly.
     */
    private static JsonObject coerceNullSentinels(JsonObject args, Class<?> cls) {
        RecordComponent[] components;
        try {
            components = cls.getRecordComponents();
        } catch (RuntimeException ex) {
            return args;
        }
        JsonObject out = null;
        for (RecordComponent component : components) {
            Class<?> type = component.getType();
            if (!Number.class.isAssignableFrom(type) || type.isPrimitive()) {
                continue;
            }
            JsonElement value = args.get(component.getName());
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            if (!isNullSentinel(value.getAsString())) {
                continue;
            }
            if (out == null) {
                out = args.deepCopy();
            }
            out.add(component.getName(), com.google.gson.JsonNull.INSTANCE);
        }
        return out == null ? args : out;
    }

    /** Placeholder spellings of "no value" a model emits instead of omitting the key. */
    private static boolean isNullSentinel(String raw) {
        String s = raw.trim();
        return s.isEmpty() || s.equalsIgnoreCase("none") || s.equalsIgnoreCase("null")
                || s.equalsIgnoreCase("nil") || s.equalsIgnoreCase("undefined");
    }

    /**
     * Copy {@code args} with every string-valued collection field replaced by the container it
     * encodes. Returns {@code args} itself when there is nothing to change.
     */
    private static JsonObject coerceScalarizedCollections(JsonObject args, Class<?> cls) {
        RecordComponent[] components;
        try {
            components = cls.getRecordComponents();
        } catch (RuntimeException ex) {
            return args;
        }
        JsonObject out = null;
        for (RecordComponent component : components) {
            if (!component.getType().isArray() && !Collection.class.isAssignableFrom(component.getType())) {
                continue;
            }
            JsonElement value = args.get(component.getName());
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            JsonElement container = containerFromScalarString(value.getAsString());
            if (container == null) {
                continue;
            }
            if (out == null) {
                out = args.deepCopy();
            }
            out.add(component.getName(), container);
        }
        return out == null ? args : out;
    }

    /**
     * A JSON string that plainly means a container: parse it when it starts like one (this also
     * rescues the single-quoted {@code ['a','b']} form), otherwise wrap it as a single element.
     * Returns {@code null} when it only looks structured and does not parse, so the original
     * value reaches Gson and produces the mismatch message.
     */
    private static JsonElement containerFromScalarString(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new JsonArray();
        }
        char first = trimmed.charAt(0);
        if (first != '[' && first != '{') {
            JsonArray single = new JsonArray();
            single.add(trimmed);
            return single;
        }
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            if (parsed.isJsonArray() || parsed.isJsonObject()) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Not JSON after all -- fall through and report the original mismatch.
        }
        return null;
    }

    /**
     * Turn whatever Gson threw into the plain-English shape error a model can act on.
     *
     * <p>Two shapes of failure need different keys named. A container mismatch comes out of Gson
     * as {@code Expected BEGIN_ARRAY but was STRING at path $.block_ids} — the path gives the key
     * away. A scalar mismatch comes out of the numeric adapter as a bare
     * {@code NumberFormatException: For input string: "abc"} with no path at all, so the key has
     * to be recovered by looking at which numeric field is actually holding unparseable text.
     */
    private static String describeMismatch(JsonObject args, Class<?> cls, RuntimeException ex) {
        String named = describeShapeMismatch(args, cls, ex);
        if (named != null) {
            return named;
        }
        String unparseable = describeUnparseableNumber(args, cls);
        return unparseable != null ? unparseable : String.valueOf(ex.getMessage());
    }

    /** The key-named message for a container mismatch, or {@code null} when there is no path. */
    private static String describeShapeMismatch(JsonObject args, Class<?> cls, RuntimeException ex) {
        String raw = String.valueOf(ex.getMessage());
        Matcher path = GSON_PATH.matcher(raw);
        if (!path.find()) {
            return null;
        }
        String key = path.group(1).replace(".", "");
        JsonElement got = args.get(key);
        String gotType = got == null ? "absent" : gotTypeName(got);
        Class<?> component = componentType(cls, key);
        if (component != null && Number.class.isAssignableFrom(component) && !component.isPrimitive()) {
            return "argument '" + key + "' was given as " + gotType
                    + " but must be a number or omitted -- send a bare number (12.5),"
                    + " or leave the key out entirely instead of sending a placeholder";
        }
        return "argument '" + key + "' was given as " + gotType
                + " but must be an array or object -- send a real JSON array ([\"a\",\"b\"]),"
                + " not a string containing one";
    }

    /**
     * Name the numeric field that is holding unparseable text, since the exception itself does
     * not. Returns {@code null} when every numeric field is absent or genuinely numeric.
     */
    private static String describeUnparseableNumber(JsonObject args, Class<?> cls) {
        RecordComponent[] components;
        try {
            components = cls.getRecordComponents();
        } catch (RuntimeException ex) {
            return null;
        }
        for (RecordComponent component : components) {
            Class<?> type = component.getType();
            if (!Number.class.isAssignableFrom(type) || type.isPrimitive()) {
                continue;
            }
            JsonElement value = args.get(component.getName());
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            String text = value.getAsString();
            try {
                Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return "argument '" + component.getName() + "' was given as the string \"" + text
                        + "\" but must be a number -- send a bare number (12.5), or leave the key"
                        + " out entirely instead of sending a placeholder";
            }
        }
        return null;
    }

    /** The declared type of one args-record component, or {@code null} if there is no such field. */
    private static Class<?> componentType(Class<?> cls, String key) {
        RecordComponent[] components;
        try {
            components = cls.getRecordComponents();
        } catch (RuntimeException ex) {
            return null;
        }
        for (RecordComponent component : components) {
            if (component.getName().equals(key)) {
                return component.getType();
            }
        }
        return null;
    }

    private static String gotTypeName(JsonElement el) {
        if (el.isJsonArray()) return "an array";
        if (el.isJsonObject()) return "an object";
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) return "a string";
        if (el.isJsonPrimitive()) return "a number";
        if (el.isJsonNull()) return "null";
        return "an unexpected value";
    }
}
