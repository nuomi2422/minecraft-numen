package com.dwinovo.numen.plugins.rdd;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded projection of 36 main slots; an unknown result carries no numeric total. */
final class RddMainInventory {
    record Result(Map<String, Long> counts, Long total, String reason) { boolean known() { return reason == null; } }
    private static Result unknown(String reason) { return new Result(Map.of(), null, reason); }
    private RddMainInventory() {}

    static Result aggregate(Object raw) {
        if (!(raw instanceof List<?> slots) || slots.size() != 36) return unknown("invalid_slots");
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < 36; i++) {
            if (!(slots.get(i) instanceof Map<?, ?> slot)) return unknown("invalid_slots");
            Long index = integer(slot.get("slot")), count = integer(slot.get("count"));
            if (index == null || index != i || count == null || count < 0
                    || !(slot.get("id") instanceof String id)) return unknown("invalid_slots");
            if (id.isEmpty()) { if (count != 0) return unknown("invalid_slots"); continue; }
            if (count > 0) {
                try { counts.merge(id, count, Math::addExact); total = Math.addExact(total, count); }
                catch (ArithmeticException overflow) { return unknown("overflow"); }
            }
        }
        return new Result(Map.copyOf(counts), total, null);
    }

    private static Long integer(Object raw) {
        if (!(raw instanceof Number n)) return null;
        try { return new BigDecimal(n.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }
}
