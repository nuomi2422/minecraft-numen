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
        return build(subject, true);
    }

    /** Execution view: backpack detail lives in runtime_state.inventory; env keeps only counts. */
    static String buildForExecution(UUID subject) {
        return build(subject, false);
    }

    private static String build(UUID subject, boolean includeBackpackCount) {
        try {
            return build(subject, NumenPlugins.capability(WorldObservationService.class), System::currentTimeMillis, includeBackpackCount);
        } catch (RuntimeException | LinkageError unavailable) {
            return unknown();
        }
    }
    static String build(UUID subject, WorldObservationService service, long nowMs) {
        return build(subject, service, () -> nowMs, true);
    }
    static String build(UUID subject, WorldObservationService service, java.util.function.LongSupplier clock) {
        return build(subject, service, clock, true);
    }
    static String build(UUID subject, WorldObservationService service, java.util.function.LongSupplier clock, boolean includeBackpackCount) {
        if (subject == null || service == null) return unknown();
        try {
            var self = service.readSelf("rdd-env", subject, 3_000L);
            WorldObservationService.Response environment = null, beds = null;
            String bedBox = null;
            var selfItem = currentSelf(subject, self, clock.getAsLong());
            if (selfItem != null) {
                try {
                    environment = service.read(new WorldObservationService.Request("rdd-env", self.metadata().dimension(),
                            "environment", Map.of(), 6_000, 1, 50));
                } catch (RuntimeException | LinkageError optionalUnavailable) {
                    // Optional weather failure must not erase independently known SELF facts.
                }
                try {
                    var rest = restSample(selfItem);
                    if (rest != null) {
                        Integer x = coordinate(selfItem, rest, "x"), y = coordinate(selfItem, rest, "y");
                        Integer z = coordinate(selfItem, rest, "z");
                        if (x != null && y != null && z != null) {
                            var request = RddObservation.nearbyBeds(self.metadata().dimension(), x, y, z);
                            bedBox = request.selector().get("box");
                            beds = service.read(request);
                        }
                    }
                } catch (RuntimeException | LinkageError optionalUnavailable) {
                    // An unavailable bed observation is independent of weather and SELF.
                }
            }
            // Read the clock AFTER all provider calls; a slow cached read can cross expiry.
            long now = clock.getAsLong();
            selfItem = currentSelf(subject, self, now);
            if (selfItem == null) return unknown();
            String weather;
            try { weather = weather(self.metadata(), environment, now); }
            catch (RuntimeException | LinkageError optionalUnavailable) { weather = "时间/天气 未观测"; }
            var bedsFact = bedAvailability(self.metadata(), beds, bedBox, now);
            var rest = restSample(selfItem);
            String candidate = rest != null && !masked(selfItem, "rest.bedWorks")
                    && Boolean.TRUE.equals(rest.get("bedWorks")) ? bedsFact.candidate() : "";
            String env = render(self.metadata(), selfItem, includeBackpackCount,
                    weather + "\n" + overnight(selfItem, bedsFact.summary()), candidate, now);
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
        if(day!=null) { long part=com.dwinovo.numen.api.observation.WorldTime.inDay(day); time=part<12000?"白天":part<13000?"傍晚":part<23000?"夜晚":"黎明"; }
        Object w=masked(item,"weather") ? null : item.values().get("weather");
        return "时间 "+time+" · 天气 "+(w instanceof String s ? shortText(s,24) : "未知");
    }
    static String render(UUID subject, WorldObservationService.Response response, long nowMs) {
        return render(subject, response, nowMs, true);
    }
    static String render(UUID subject, WorldObservationService.Response response, long nowMs, boolean includeBackpackCount) {
        var item = currentSelf(subject, response, nowMs);
        return item == null ? unknown() : render(response.metadata(), item, includeBackpackCount, "", "", nowMs);
    }
    private static WorldObservationService.Item currentSelf(UUID subject, WorldObservationService.Response response, long nowMs) {
        if (response == null || !"self".equals(response.topic()) || response.items().size() != 1
                || (response.status() != WorldObservationService.Status.OK && response.status() != WorldObservationService.Status.PARTIAL)
                || response.coverage() != WorldObservationService.Coverage.FULL) return null;
        var meta = response.metadata();
        if (!current(subject, meta, nowMs)) return null;
        var item = response.items().get(0);
        if (masked(item,"companionUUID") || masked(item,"dimension")) return null;
        return subject.toString().equals(item.values().get("companionUUID"))
                && meta.dimension().equals(item.values().get("dimension")) ? item : null;
    }
    private static String render(ObservationMetadata meta, WorldObservationService.Item item,
                                 boolean includeBackpackCount, String overnight, String bedCandidate, long now) {
        var values = item.values();
        String main = stackMasked(item,"mainHand") ? "未知" : slot(values.get("mainHand"));
        String off = stackMasked(item,"offhand") ? "未知" : slot(values.get("offhand"));
        boolean inventoryUnknown=masked(item,"main");
        for(int i=0;i<36&&!inventoryUnknown;i++) inventoryUnknown=stackMasked(item,"main["+i+"]");
        var derived = com.dwinovo.numen.api.observation.SelfInventoryQuery.query(meta, item, now, Set.of());
        RddMainInventory.Result mainInventory = inventoryUnknown ? null
                : new RddMainInventory.Result(derived.counts(), derived.total(), derived.reason());
        String inventory = mainInventory == null || !mainInventory.known() ? "未知"
                : mainInventory.counts().size() + "种 " + mainInventory.total() + "件（主包36槽，手持不另加）";
        String dimension = meta.dimension().replace("minecraft:", "");
        StringBuilder out = new StringBuilder("<env>\n");
        out.append("维度 ").append(shortText(dimension, 64)).append("（采样）\n");
        out.append("手上 ").append(main).append(" · 副手 ").append(off).append('\n');
        if (includeBackpackCount) out.append("背包 ").append(inventory).append('\n');
        out.append("可食储备 ").append(derived.foodKnown() ? derived.foodCount() + "件" : "未知")
                .append("〔主包36槽/FOOD组件，非恢复值〕\n");
        out.append("身体 血量").append(number(masked(item,"hp") ? null : values.get("hp")))
                .append(" · 饥饿").append(number(masked(item,"food") ? null : values.get("food"))).append('\n');
        // A low HP value alone is NOT evidence of ongoing damage. No unsafe geometry fallback.
        out.append("位置/脚下/周边/持续掉血 未观测\n");
        if (!overnight.isEmpty()) out.append(overnight).append('\n');
        if (!bedCandidate.isEmpty()) {
            String line = "床头候选 (" + bedCandidate + ")（采样，可达/可睡未核）\n";
            if (out.length() + line.length() + "</env>".length() <= 400) out.append(line);
        }
        if (mainInventory != null && mainInventory.known()) {
            int remaining = 390 - out.length() - "\n</env>".length();
            String key = RddKeyItems.line(mainInventory, Math.max(0, remaining));
            if (key != null && remaining > 0 && key.length() <= remaining) out.append(key).append('\n');
        }
        out.append("</env>");
        return out.toString();
    }
    private static Map<?,?> restSample(WorldObservationService.Item item) {
        return !masked(item, "rest") && item.values().get("rest") instanceof Map<?,?> rest ? rest : null;
    }
    private static Integer coordinate(WorldObservationService.Item item, Map<?,?> rest, String axis) {
        Long value = masked(item, "rest." + axis) ? null : integer(rest.get(axis));
        return value == null || value < -33_554_432L || value > 33_554_431L ? null : value.intValue();
    }
    private static String overnight(WorldObservationService.Item item, String bedSummary) {
        var rest = restSample(item);
        Object sleeping = rest == null || masked(item, "rest.sleeping") ? null : rest.get("sleeping");
        Object bedWorks = rest == null || masked(item, "rest.bedWorks") ? null : rest.get("bedWorks");
        String usable = Boolean.FALSE.equals(bedWorks) ? "本维度床不可睡 · 可达未知" : "可达/可睡未知";
        return "身边床(±4/±2格) " + bedSummary + " · " + usable
                + " · 已睡 " + (sleeping instanceof Boolean b ? (b ? "是" : "否") : "未知");
    }
    private record BedObservation(String summary, String candidate) {
        private static final BedObservation UNKNOWN = new BedObservation("未知", "");
    }
    private static BedObservation bedAvailability(ObservationMetadata self, WorldObservationService.Response beds,
                                                  String box, long now) {
        if (beds == null || !"blocks".equals(beds.topic()) || beds.status() != WorldObservationService.Status.OK
                || beds.coverage() != WorldObservationService.Coverage.FULL || beds.items().size() != 2)
            return BedObservation.UNKNOWN;
        var meta = beds.metadata();
        // The WorldState read already matches the exact requested box and filter.
        if (!meta.identified() || !self.worldId().equals(meta.worldId())
                || !self.serverSession().equals(meta.serverSession()) || !self.dimension().equals(meta.dimension())
                || meta.freshness() != ObservationMetadata.Freshness.CURRENT
                || meta.observedAtMs() > now || now - meta.observedAtMs() >= RddObservation.BED_MAX_AGE_MS
                || meta.validUntilMs() <= now) return BedObservation.UNKNOWN;
        Long total = null, unoccupied = null;
        String candidate = "";
        for (var row : beds.items()) {
            if (masked(row, "block") || masked(row, "count")) return BedObservation.UNKNOWN;
            Object selector = row.values().get("block");
            Long count = integer(row.values().get("count"));
            if (count == null || count < 0) return BedObservation.UNKNOWN;
            if (RddObservation.BEDS.equals(selector)) total = count;
            if (RddObservation.UNOCCUPIED_BED_HEADS.equals(selector)) {
                unoccupied = count;
                if (count > 0) candidate = bedHeadSample(row, box);
            }
        }
        if (total == null || unoccupied == null || unoccupied > total) return BedObservation.UNKNOWN;
        if (total == 0) return new BedObservation("无", "");
        String summary = unoccupied == 0 ? "有，未见未占床头"
                : candidate.isEmpty() ? "有，床头坐标未知" : "有，见未占床头";
        return new BedObservation(summary, candidate);
    }

    private static String bedHeadSample(WorldObservationService.Item row, String box) {
        if (box == null || masked(row, "samples[0]") || !(row.values().get("samples") instanceof List<?> samples)
                || samples.size() != 1 || !(samples.get(0) instanceof String raw)) return "";
        // Check the existing WorldState xyz sample against the same requested box.
        String[] xyz = raw.split(",", -1);
        if (xyz.length != 3) return "";
        String[] bounds = box.split(",", -1);
        try {
            for (int i = 0; i < 3; i++) {
                int coord = Integer.parseInt(xyz[i]);
                if (coord < Integer.parseInt(bounds[i]) || coord > Integer.parseInt(bounds[i + 3])) return "";
                xyz[i] = Integer.toString(coord);
            }
            return String.join(",", xyz);
        } catch (NumberFormatException badSample) {
            return "";
        }
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
        return "<env>\n自身观测不可用（缺失/过期/身份不明）；背包、血量、位置与周边未知\n昼夜、附近床位、可达/可睡、已睡 均未知\n</env>";
    }
}
