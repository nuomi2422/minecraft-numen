package com.dwinovo.numen.link.core;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Host-neutral contracts. Not network authentication and not a Minecraft entity adapter.
 * 2026-11-10 / 033 D004: keep native pairs; select services without swapping bodies or data.
 */
public final class LinkTypes {
    private LinkTypes() {}
    public enum Side { NUMEN, MAID }
    public enum Mode { NATIVE, READ, DELEGATE, OVERRIDE, AUGMENT }
    public enum Effect { READ, REFRESH, WRITE }
    public enum Capability { CHAT, MEMORY, EMOTION, VOICE, PERCEPTION, EXPERIENCE, ASSETS, EXECUTION }
    public enum Code { OK, STALE, BUSY, UNSUPPORTED, UNBOUND, UNAVAILABLE, DENIED, CAPACITY, UNKNOWN }
    public record Check(Code code, String reason) {
        public Check { Objects.requireNonNull(code); text(reason); }
        public boolean ok() { return code == Code.OK; }
        public static Check yes() { return new Check(Code.OK, "accepted-contract-only"); }
    }
    public record Body(Side side, String id) {
        public Body { Objects.requireNonNull(side); text(id); }
    }
    /** New instance per server session/owner/binding. Absent other side is legal for native-only. */
    public record Binding(String id, String owner, String world, String session, Map<Side, Body> bodies) {
        public Binding {
            text(id); text(owner); text(world); text(session);
            bodies = Map.copyOf(bodies);
            if (bodies.isEmpty()) throw new IllegalArgumentException("no bodies");
            bodies.forEach((side, body) -> {
                if (side != body.side()) throw new IllegalArgumentException("body side mismatch");
            });
            if (bodies.values().stream().map(Body::id).distinct().count() != bodies.size())
                throw new IllegalArgumentException("distinct bodies must not share identity");
        }
    }
    /** Host is the physical output/action body, NOT the provider's implementation side. */
    public record Slot(Side host, Capability capability) {
        public Slot { Objects.requireNonNull(host); Objects.requireNonNull(capability); }
    }
    /** Stored provider id pins an exact descriptor/version; replacement requires a policy switch. */
    public record Choice(boolean enabled, String provider, Mode mode, String profile) {
        public Choice { text(provider); Objects.requireNonNull(mode); text(profile); }
    }
    public record Support(Side target, Mode mode) {
        public Support { Objects.requireNonNull(target); Objects.requireNonNull(mode); }
    }
    public record Provider(String id, String version, Side origin, Capability capability,
                           Effect effect, Set<Support> supports, Set<Side> requiredBodies,
                           Set<String> dependencies) {
        public Provider {
            text(id); text(version); Objects.requireNonNull(origin); Objects.requireNonNull(capability);
            Objects.requireNonNull(effect); supports = Set.copyOf(supports);
            requiredBodies = Set.copyOf(requiredBodies); dependencies = Set.copyOf(dependencies);
            dependencies.forEach(LinkTypes::text);
            if (supports.isEmpty()) throw new IllegalArgumentException("no explicit support");
            if (supports.stream().anyMatch(s -> s.mode() == Mode.READ) && effect != Effect.READ)
                throw new IllegalArgumentException("refresh/write cannot advertise read-only");
            if (supports.stream().anyMatch(s -> s.mode() == Mode.NATIVE && s.target() != origin))
                throw new IllegalArgumentException("native support must keep original side");
        }
    }
    static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank identifier");
        return value;
    }
}
