package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.api.AssetStatus;
import java.util.*;
import java.util.function.Consumer;

/** 2026-11-11 B2: bounded negative evidence from exact loaded points, never scan-list absence.
 * World identity comes from the concrete server. Legacy world-less records are not inferred.
 */
final class MachinePointRecheck {
    record Point(int x, int y, int z) {}
    interface Reader {
        boolean loaded(Point p);
        String block(Point p);
    }
    private MachinePointRecheck() {}
    static int check(AssetRegistry registry, String world, String session, String dimension,
                     Point origin, int budget, Reader reader, Consumer<Map<String,Object>> event) {
        if (registry == null || world == null || world.isBlank() || origin == null || budget <= 0) return 0;
        int checked = 0, invalidated = 0;
        for (var entry : registry.snapshot()) {
            if (!"world_machine".equals(entry.observation().type()) || entry.status() == AssetStatus.INVALID) continue;
            var value = entry.observation().value();
            if (!world.equals(value.get("world_id")) || dimension == null || !dimension.equals(value.get("dimension"))) continue;
            Object block = value.get("block");
            if (!(block instanceof String expected) || !expected.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || expected.contains("portal")) continue;
            Point p = point(value);
            if (p == null || Math.abs((long)p.x-origin.x)>8 || Math.abs((long)p.y-origin.y)>4 || Math.abs((long)p.z-origin.z)>8) continue;
            String expectedId = "machine|"+dimension+"|"+expected+"|"+packed(p);
            if (!expectedId.equals(entry.assetId())) continue;
            if (checked >= budget) break;
            checked++;
            try {
                if (!reader.loaded(p)) continue;
                String actual = reader.block(p);
                if (actual == null || !actual.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || actual.equals(expected)) continue;
                registry.invalidate(entry.assetId()); invalidated++;
                Map<String,Object> evidence = new LinkedHashMap<>();
                evidence.put("asset_id",entry.assetId());evidence.put("reason","loaded_block_changed");
                evidence.put("expected_block",expected);evidence.put("actual_block",actual);
                evidence.put("world_id",world);evidence.put("server_session",session==null||session.isBlank()?"UNKNOWN":session);
                evidence.put("dimension",dimension);evidence.put("x",p.x);evidence.put("y",p.y);evidence.put("z",p.z);
                event.accept(evidence);
            } catch (RuntimeException unreadable) { /* no loaded/exact read proof: do not infer absence */ }
        }
        return invalidated;
    }
    private static Point point(Map<String,Object> v) {
        try {
            return new Point(coordinate(v.get("x"),-33554432,33554431),coordinate(v.get("y"),-2048,2047),coordinate(v.get("z"),-33554432,33554431));
        } catch (IllegalArgumentException e) { return null; }
    }
    private static int coordinate(Object o,int min,int max) {
        if (!(o instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=Math.rint(n.doubleValue())
                || n.doubleValue()<min || n.doubleValue()>max) throw new IllegalArgumentException();
        return n.intValue();
    }
    static long packed(Point p) { return ((long)p.x & 0x3FFFFFFL)<<38 | ((long)p.z & 0x3FFFFFFL)<<12 | ((long)p.y & 0xFFFL); }
}
