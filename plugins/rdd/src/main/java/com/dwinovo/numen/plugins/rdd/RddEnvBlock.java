package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import java.util.*;

/**
 * B2b, 2026-11-06: environment context must not read a live server entity on the client thread.
 * Reuses the existing SELF observation store; no scanning, refresh or second fact cache.
 * Coordinates/support/cliff are deliberately UNKNOWN until their provider contract exists.
 * Complete inventory details already have a client renderer; this block only summarizes counts.
 */
final class RddEnvBlock {
    private RddEnvBlock() {}
    static String build(UUID subject) {
        try {
            return build(subject, NumenPlugins.capability(WorldObservationService.class), System::currentTimeMillis);
        } catch (RuntimeException | LinkageError unavailable) {
            return unknown();
        }
    }
    static String build(UUID subject, WorldObservationService service, long nowMs) {
        return build(subject, service, () -> nowMs);
    }
    static String build(UUID subject, WorldObservationService service, java.util.function.LongSupplier clock) {
        if (subject == null || service == null) return unknown();
        try {
            var self = service.readSelf("rdd-env", subject, 3_000L);
            WorldObservationService.Response environment = null;
            if (self != null && current(subject, self.metadata(), clock.getAsLong())) {
                try {
                    environment = service.read(new WorldObservationService.Request("rdd-env", self.metadata().dimension(),
                            "environment", Map.of(), 30_000, 1, 50));
                } catch (RuntimeException | LinkageError optionalUnavailable) {
                    // Optional weather failure must not erase independently known SELF facts.
                }
            }
            // Read the clock AFTER all provider calls; a slow cached read can cross expiry.
            long now = clock.getAsLong();
            String env = render(subject, self, now);
            if (self == null || !current(subject, self.metadata(), now) || env.equals(unknown())) return env;
            try { env = appendFact(env, weather(self.metadata(), environment, now)); }
            catch (RuntimeException | LinkageError optionalUnavailable) { env = appendFact(env, "时间/天气 未观测"); }
            try { env = appendFact(env, rememberedHome(subject, self.metadata().dimension())); }
            catch (RuntimeException | LinkageError optionalUnavailable) { /* Keep SELF; memory is optional. */ }
            return env;
        } catch (RuntimeException | LinkageError unavailable) {
            return unknown();
        }
    }
    private static String appendFact(String env, String line) {
        if (line == null || line.isEmpty() || env.length()+line.length()+1 > 400) return env;
        return env.replace("</env>", line + "\n</env>");
    }
    private static String rememberedHome(UUID subject, String dim) {
        int[] anchor = RddWaypoints.anchorPos(subject, dim);
        if (anchor != null) return "回家记忆 " + shortText(dim,48) + " (" + anchor[0]+","+anchor[1]+","+anchor[2]+")（存档归属未核）";
        var base = RddBaseAnchor.get(subject);
        return base == null ? "" : "基地记忆 ("+base.getX()+","+base.getY()+","+base.getZ()+")（维度/存档归属未核）";
    }
    static String weather(ObservationMetadata self, WorldObservationService.Response response, long now) {
        if (response == null || !"environment".equals(response.topic()) || response.items().size()!=1
                || response.status()==WorldObservationService.Status.ERROR || response.status()==WorldObservationService.Status.PENDING)
            return "时间/天气 未观测";
        var meta=response.metadata();
        if (!meta.identified() || !self.worldId().equals(meta.worldId())
                || !self.serverSession().equals(meta.serverSession()) || !self.dimension().equals(meta.dimension())
                || meta.freshness()!=ObservationMetadata.Freshness.CURRENT || meta.observedAtMs()>now
                || meta.validUntilMs()<=now) return "时间/天气 未观测";
        var item=response.items().get(0);
        Long day=masked(item,"day_time") ? null : integer(item.values().get("day_time"));
        String time="未知";
        if(day!=null) { long part=Math.floorMod(day,24000L); time=part<12000?"白天":part<13000?"傍晚":part<23000?"夜晚":"黎明"; }
        Object w=masked(item,"weather") ? null : item.values().get("weather");
        return "时间 "+time+" · 天气 "+(w instanceof String s ? shortText(s,24) : "未知");
    }
    static String render(UUID subject, WorldObservationService.Response response, long nowMs) {
        if (response == null || !"self".equals(response.topic()) || response.items().size() != 1
                || (response.status() != WorldObservationService.Status.OK && response.status() != WorldObservationService.Status.PARTIAL)
                || response.coverage() != WorldObservationService.Coverage.FULL) return unknown();
        var meta = response.metadata();
        if (!current(subject, meta, nowMs)) return unknown();
        var item = response.items().get(0);
        if (masked(item,"companionUUID") || masked(item,"dimension")) return unknown();
        var values = item.values();
        if (!subject.toString().equals(values.get("companionUUID"))
                || !meta.dimension().equals(values.get("dimension"))) return unknown();
        String main = stackMasked(item,"mainHand") ? "未知" : slot(values.get("mainHand"));
        String off = stackMasked(item,"offhand") ? "未知" : slot(values.get("offhand"));
        boolean inventoryUnknown=masked(item,"main");
        for(int i=0;i<36&&!inventoryUnknown;i++) inventoryUnknown=stackMasked(item,"main["+i+"]");
        String inventory = inventoryUnknown ? "未知" : inventory(values.get("main"));
        String dimension = meta.dimension().replace("minecraft:", "");
        StringBuilder out = new StringBuilder("<env>\n");
        out.append("维度 ").append(shortText(dimension, 64)).append("（采样）\n");
        out.append("手上 ").append(main).append(" · 副手 ").append(off).append('\n');
        out.append("背包 ").append(inventory).append('\n');
        out.append("身体 血量").append(number(masked(item,"hp") ? null : values.get("hp")))
                .append(" · 饥饿").append(number(masked(item,"food") ? null : values.get("food"))).append('\n');
        // A low HP value alone is NOT evidence of ongoing damage. No unsafe geometry fallback.
        out.append("位置/脚下/周边/持续掉血 未观测\n");
        out.append("</env>");
        return out.toString();
    }
    private static boolean stackMasked(WorldObservationService.Item item,String path) {
        return masked(item,path) || masked(item,path+".id") || masked(item,path+".count") || masked(item,path+".slot");
    }
    private static boolean masked(WorldObservationService.Item item,String path) {
        return java.util.stream.Stream.concat(item.unknownFields().stream(),item.staleFields().stream())
                .anyMatch(p -> p.isEmpty() || path.equals(p) || path.startsWith(p+".") || path.startsWith(p+"["));
    }
    private static boolean current(UUID subject, ObservationMetadata meta, long now) {
        return subject != null && meta != null && meta.identified()
                && subject.toString().equals(meta.subjectId())
                && meta.freshness() == ObservationMetadata.Freshness.CURRENT
                && meta.observedAtMs() <= now && now - meta.observedAtMs() <= 3_000L
                && meta.validUntilMs() > now;
    }
    private static String inventory(Object raw) {
        if (!(raw instanceof List<?> slots) || slots.size() != 36) return "未知";
        Map<String, Long> totals = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            if (!(slots.get(i) instanceof Map<?,?> slot)) return "未知";
            Long index = integer(slot.get("slot")), count = integer(slot.get("count"));
            if (index == null || index != i || count == null || count < 0
                    || !(slot.get("id") instanceof String id)) return "未知";
            if (id.isEmpty()) { if (count != 0) return "未知"; continue; }
            if (count > 0) {
                try { totals.merge(id, count, Math::addExact); }
                catch (ArithmeticException overflow) { return "未知"; }
            }
        }
        long total = 0;
        try { for(long n:totals.values()) total=Math.addExact(total,n); }
        catch(ArithmeticException overflow) { return "未知"; }
        return totals.size() + "种 " + total + "件（主包36槽，手持不另加）";
    }
    private static String slot(Object raw) {
        if (!(raw instanceof Map<?,?> value) || !(value.get("id") instanceof String id)) return "未知";
        Long count = integer(value.get("count"));
        if (count == null || count < 0 || (id.isEmpty() && count != 0)) return "未知";
        if (id.isEmpty() || count == 0) return "空";
        return shortText(id, 64) + "×" + count;
    }
    private static String number(Object raw) {
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0) return "未知";
        String text = new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return text.length() <= 20 ? text : "未知";
    }
    private static Long integer(Object raw) {
        if (!(raw instanceof Number n)) return null;
        try { return new java.math.BigDecimal(n.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }
    private static String shortText(String text, int max) {
        String clean = text.replace('\n', ' ').replace('\r', ' ').replace('<', '_').replace('>', '_');
        return clean.length() <= max ? clean : clean.substring(0,max-1) + "…";
    }
    private static String unknown() {
        return "<env>\n自身观测不可用（缺失/过期/身份不明）；背包、血量、位置与周边未知\n</env>";
    }
}
