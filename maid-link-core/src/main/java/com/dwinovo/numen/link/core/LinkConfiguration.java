package com.dwinovo.numen.link.core;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/**
 * 052/P1, 2026-11-11: strict, versioned configuration for the offline core.
 * Use decode/validate before applying a patch; save validates before touching disk.
 * Java Properties avoids a second JSON parser/dependency; duplicate/unknown keys fail.
 * These documents select services, not live binding credentials or recovered work.
 * Current consumers are contract fixtures; there is no production config registration.
 */
public final class LinkConfiguration {
    public static final int SCHEMA = 1;
    private static final int MAX_CHARS = 65_536;
    private LinkConfiguration() {}

    public record Selection(Choice choice, String providerVersion) {
        public Selection { Objects.requireNonNull(choice); text(providerVersion); }
    }
    public record Document(int schema, long revision, Map<Slot, Selection> selections) {
        public Document {
            if (schema != SCHEMA || revision < 0) throw new IllegalArgumentException("schema-or-revision");
            selections = Map.copyOf(selections);
            if (selections.size() > Side.values().length * Capability.values().length)
                throw new IllegalArgumentException("too-many-slots");
        }
        public Map<Slot, Choice> choices() {
            var result = new HashMap<Slot, Choice>();
            selections.forEach((slot, selection) -> result.put(slot, selection.choice()));
            return Map.copyOf(result);
        }
    }
    private static final class StrictProperties extends Properties {
        @Override public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) throw new IllegalArgumentException("duplicate-key: " + key);
            return super.put(key, value);
        }
    }
    private static String take(Properties p, String key) {
        Object value = p.remove(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("missing-key: " + key);
        return text(s);
    }
    public static Document decode(String input) {
        Objects.requireNonNull(input);
        if (input.length() > MAX_CHARS) throw new IllegalArgumentException("configuration-too-large");
        var p = new StrictProperties();
        try { p.load(new StringReader(input)); }
        catch (IOException e) { throw new IllegalArgumentException("configuration-read", e); }
        int schema = Integer.parseInt(take(p, "schema"));
        long revision = Long.parseLong(take(p, "revision"));
        var slots = new HashSet<String>();
        for (String key : p.stringPropertyNames()) {
            String[] parts = key.split("\\.", -1);
            if (parts.length != 4 || !parts[0].equals("slot"))
                throw new IllegalArgumentException("unknown-key: " + key);
            Side.valueOf(parts[1]); Capability.valueOf(parts[2]);
            slots.add(parts[1] + "." + parts[2]);
        }
        var selections = new HashMap<Slot, Selection>();
        for (String name : slots) {
            String prefix = "slot." + name + ".";
            String enabled = take(p, prefix + "enabled");
            if (!enabled.equals("true") && !enabled.equals("false"))
                throw new IllegalArgumentException("invalid-boolean");
            String provider = take(p, prefix + "provider");
            Mode mode = Mode.valueOf(take(p, prefix + "mode"));
            String profile = take(p, prefix + "profile");
            String version = take(p, prefix + "version");
            String[] pair = name.split("\\.");
            selections.put(new Slot(Side.valueOf(pair[0]), Capability.valueOf(pair[1])),
                    new Selection(new Choice(Boolean.parseBoolean(enabled), provider, mode, profile), version));
        }
        if (!p.isEmpty()) throw new IllegalArgumentException("unknown-key: " + p.keySet());
        return new Document(schema, revision, selections);
    }
    public static String encode(Document document) {
        var p = new Properties();
        p.setProperty("schema", Integer.toString(document.schema()));
        p.setProperty("revision", Long.toString(document.revision()));
        document.selections().forEach((slot, selection) -> {
            String prefix = "slot." + slot.host() + "." + slot.capability() + ".";
            Choice c = selection.choice();
            p.setProperty(prefix + "enabled", Boolean.toString(c.enabled()));
            p.setProperty(prefix + "provider", c.provider());
            p.setProperty(prefix + "mode", c.mode().name());
            p.setProperty(prefix + "profile", c.profile());
            p.setProperty(prefix + "version", selection.providerVersion());
        });
        var out = new StringWriter();
        try { p.store(out, "maid-link schema 1"); }
        catch (IOException e) { throw new IllegalStateException(e); }
        String encoded = out.toString();
        if (encoded.length() > MAX_CHARS) throw new IllegalArgumentException("configuration-too-large");
        return encoded;
    }
    public static Check validate(Document document, Binding binding, CapabilityCatalog catalog,
                                 Set<String> available) {
        for (var entry : document.selections().entrySet()) {
            Selection s = entry.getValue();
            Check check = catalog.validate(binding, entry.getKey(), s.choice(), available);
            if (!check.ok()) return check;
            if (s.choice().enabled()) {
                Provider p = catalog.provider(s.choice().provider());
                if (!s.providerVersion().equals(p.version()))
                    return new Check(Code.STALE, "provider-version-changed");
            }
        }
        return Check.yes();
    }
    /** Host-selected file only. No discovery of game paths; validation failure leaves it untouched.
     * Atomic-move unavailable is an IO failure, never a non-atomic replacement fallback.
     * Saving a document does not apply/recover runtime policy or authorize any action.
     */
    public static void save(Path file, Document document, Binding binding, CapabilityCatalog catalog,
                            Set<String> available) throws IOException {
        Check check = validate(document, binding, catalog, available);
        if (!check.ok()) throw new IllegalArgumentException(check.reason());
        String encoded = encode(document);
        Path target = file.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".maid-link-", ".tmp");
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
