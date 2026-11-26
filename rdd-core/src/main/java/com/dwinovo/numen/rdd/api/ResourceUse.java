package com.dwinovo.numen.rdd.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** A bounded, local declaration of purpose. Never evidence of consumption, transfer or loss. */
public record ResourceUse(String assetKey, int quantity, Kind kind) {
    public enum Kind { CONSUME, RETAIN, TRANSFER }
    public static final int MAX_USES = 8;

    public ResourceUse {
        if (assetKey == null || assetKey.length() > 256
                || !assetKey.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("resource use requires an exact asset key");
        if (quantity <= 0 || kind == null)
            throw new IllegalArgumentException("resource use requires positive quantity and known kind");
    }

    /** null is a legacy unspecified declaration, not permission to infer resource use. */
    public static List<ResourceUse> validate(List<ResourceUse> uses, Map<String, Object> condition) {
        if (uses == null) return List.of();
        if (uses.size() > MAX_USES) throw new IllegalArgumentException("too many resource uses");
        var seen = new HashSet<String>();
        for (ResourceUse use : uses) {
            if (use == null || !seen.add(use.assetKey() + "/" + use.kind()))
                throw new IllegalArgumentException("null or duplicate resource use");
            if (use.kind() == Kind.TRANSFER && !exactTransfer(use, condition))
                throw new IllegalArgumentException("transfer requires the matching exact container result");
        }
        return List.copyOf(uses);
    }

    private static boolean exactTransfer(ResourceUse use, Map<String, Object> c) {
        if (c == null || !"container_at".equals(c.get("type")) || !use.assetKey().equals(c.get("item"))
                || !(c.get("dimension") instanceof String dimension)
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return false;
        for (String axis : List.of("x", "y", "z")) {
            if (!(c.get(axis) instanceof Number number)) return false;
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value != Math.rint(value)
                    || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return false;
        }
        return c.get("minimum") instanceof Number number
                && number.doubleValue() == use.quantity();
    }

    /** Wire-oriented, bounded declaration view; it deliberately carries no observed-state claim. */
    public static List<Map<String, Object>> declarations(List<ResourceUse> uses) {
        if (uses == null) return List.of();
        return uses.stream().map(use -> Map.<String, Object>of(
                "asset_key", use.assetKey(), "quantity", use.quantity(), "kind", use.kind().name())).toList();
    }

    /** Only RETAIN is an additional completion obligation; unknown counts never prove it. */
    public static boolean retainedSatisfied(List<ResourceUse> uses, Map<String, Integer> counts) {
        if (uses == null) return true;
        for (ResourceUse use : uses) {
            if (use.kind() == Kind.RETAIN) {
                Integer have = counts == null ? null : counts.get(use.assetKey());
                if (have == null || have < use.quantity()) return false;
            }
        }
        return true;
    }
}
