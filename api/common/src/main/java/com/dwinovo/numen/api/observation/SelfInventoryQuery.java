package com.dwinovo.numen.api.observation;

import java.math.BigDecimal;
import java.util.*;

/** Bounded main-slot derivation over the current detached SELF input, never a world reader.
 * Counts are item units, not hunger restoration or permission to eat. Main hand is an alias;
 * offhand/armor are outside main36. Missing FOOD evidence leaves food unknown, not zero.
 */
public final class SelfInventoryQuery {
    public static final String ALGORITHM = "self-main36-v1";
    public static final String SCOPE = "main36";
    private static final int CAPACITY = 128;
    private static final Map<Key, Result> CACHE = new LinkedHashMap<>(16, .75f, true);
    private SelfInventoryQuery() {}

    public record Result(Map<String, Long> counts, Long total, Long foodCount,
                         String reason, String foodReason, ObservationMetadata input) {
        public Result { counts = Map.copyOf(counts); }
        public boolean known() { return reason == null; }
        public boolean foodKnown() { return known() && foodReason == null; }
        public Map<String,Object> data() {
            var out = new LinkedHashMap<String,Object>();
            out.put("algorithm", ALGORITHM); out.put("scope", SCOPE); out.put("unit", "items");
            out.put("state", known() ? "KNOWN" : "UNKNOWN");
            out.put("foodState", foodKnown() ? "KNOWN" : "UNKNOWN");
            if (known()) { out.put("counts", counts); out.put("total", total); }
            else out.put("reason", reason);
            if (foodKnown()) out.put("foodCount", foodCount);
            else out.put("foodReason", foodReason == null ? reason : foodReason);
            if (input != null) {
                var provenance = new LinkedHashMap<String,Object>();
                provenance.put("worldId", input.worldId()); provenance.put("serverSession", input.serverSession());
                provenance.put("dimension", input.dimension()); provenance.put("subjectId", input.subjectId());
                provenance.put("source", input.source()); provenance.put("observationKey", input.observationKey());
                provenance.put("revision", input.revision()); provenance.put("observedAtMs", input.observedAtMs());
                provenance.put("validUntilMs", input.validUntilMs());
                out.put("input", Collections.unmodifiableMap(provenance));
            }
            return Collections.unmodifiableMap(out);
        }
    }
    private record Slot(int index, String id, long count, Boolean edible) {}
    private record Key(ObservationMetadata input, String algorithm, String scope, Set<String> ids, List<Slot> slots) {}
    private static Result unknown(String reason, ObservationMetadata input) {
        return new Result(Map.of(), null, null, reason, reason, input);
    }
    public static Result read(WorldObservationService.Response response, long now) {
        if (response == null || !"self".equals(response.topic()) || response.items().size() != 1
                || (response.status() != WorldObservationService.Status.OK && response.status() != WorldObservationService.Status.PARTIAL)
                || response.coverage() != WorldObservationService.Coverage.FULL)
            return unknown("self_unavailable", response == null ? null : response.metadata());
        return query(response.metadata(), response.items().getFirst(), now, Set.of());
    }
    /** Empty ids selects all main36 items; a nonempty immutable set is an exact-ID filter. */
    public static Result query(ObservationMetadata meta, WorldObservationService.Item item, long now, Set<String> ids) {
        if (meta == null || !meta.identified() || meta.subjectId().isEmpty() || item == null
                || meta.freshness() != ObservationMetadata.Freshness.CURRENT
                || meta.observedAtMs() > now || meta.validUntilMs() <= now)
            return unknown("self_not_current", meta);
        if (masked(item, "companionUUID") || masked(item, "dimension")
                || !meta.subjectId().equals(item.values().get("companionUUID"))
                || !meta.dimension().equals(item.values().get("dimension"))) return unknown("self_identity", meta);
        if (masked(item, "main")) return unknown("main_unknown", meta);
        for (int i=0; i<36; i++)
            for (String field : List.of("slot", "id", "count"))
                if (masked(item, "main["+i+"]."+field)) return unknown("main_unknown", meta);
        List<Slot> slots = slots(item.values().get("main"));
        if (slots == null) return unknown("invalid_slots", meta);
        var normalized = new ArrayList<Slot>(36);
        for (Slot slot : slots) normalized.add(new Slot(slot.index(), slot.id(), slot.count(),
                masked(item, "main["+slot.index()+"].edible") ? null : slot.edible()));
        Set<String> filter = ids == null ? Set.of() : Set.copyOf(ids);
        if (filter.size() > 36) return unknown("filter_budget", meta);
        Key key = new Key(meta, ALGORITHM, SCOPE, filter, List.copyOf(normalized));
        synchronized (CACHE) {
            CACHE.entrySet().removeIf(e -> e.getKey().input().validUntilMs() <= now);
            Result cached = CACHE.get(key);
            if (cached != null) return cached;
            Result result = aggregate(normalized, filter, meta);
            // No unknown numeric value is made reusable. Re-reading recovered input recomputes it.
            if (result.known() && result.foodKnown()) {
                CACHE.put(key, result);
                while (CACHE.size() > CAPACITY) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return result;
        }
    }
    /** Compatibility projection without provenance: deliberately not cached or labelled current. */
    public static Result aggregate(Object raw) {
        List<Slot> slots = slots(raw);
        return slots == null ? unknown("invalid_slots", null) : aggregate(slots, Set.of(), null);
    }
    private static Result aggregate(List<Slot> slots, Set<String> ids, ObservationMetadata input) {
        var counts = new LinkedHashMap<String,Long>(); long total=0, food=0; boolean foodKnown=true;
        try {
            for (Slot slot : slots) {
                if (slot.count()==0 || (!ids.isEmpty() && !ids.contains(slot.id()))) continue;
                counts.merge(slot.id(), slot.count(), Math::addExact); total=Math.addExact(total,slot.count());
                if (slot.edible()==null) foodKnown=false;
                else if (slot.edible()) food=Math.addExact(food,slot.count());
            }
        } catch (ArithmeticException overflow) { return unknown("overflow", input); }
        return new Result(counts,total,foodKnown?food:null,null,foodKnown?null:"food_component_unknown",input);
    }
    private static List<Slot> slots(Object raw) {
        if (!(raw instanceof List<?> slots) || slots.size()!=36) return null;
        var out=new ArrayList<Slot>(36);
        for (int i=0;i<36;i++) {
            if (!(slots.get(i) instanceof Map<?,?> slot)) return null;
            Long index=integer(slot.get("slot")), count=integer(slot.get("count"));
            if (index==null || index!=i || count==null || count<0 || !(slot.get("id") instanceof String id)
                    || (id.isEmpty() && count!=0)) return null;
            out.add(new Slot(i,id,count,slot.get("edible") instanceof Boolean b?b:null));
        }
        return List.copyOf(out);
    }
    private static boolean masked(WorldObservationService.Item item,String path) {
        return java.util.stream.Stream.concat(item.unknownFields().stream(),item.staleFields().stream())
                .anyMatch(p -> p.isEmpty() || path.equals(p) || path.startsWith(p+".") || path.startsWith(p+"["));
    }
    private static Long integer(Object raw) {
        if (!(raw instanceof Number n)) return null;
        try { return new BigDecimal(n.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }
}
