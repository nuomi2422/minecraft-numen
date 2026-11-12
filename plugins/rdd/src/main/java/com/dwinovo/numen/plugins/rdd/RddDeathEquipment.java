package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort.*;
import com.dwinovo.numen.rdd.side.death.DeathPreparation;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * 2026-11-12: current death-preparation evidence from the existing SELF provider.
 * WHY: recovered inventory and historical armor completion do not prove worn slots.
 * Full current subject/world/session sample only; unknown refreshes, never falls back to a second scan.
 * Data belongs to the provider; this adapter only checks explicit same-chain conditions.
 */
final class RddDeathEquipment {
    private RddDeathEquipment() {}
    record Sample(Map<String, Integer> held, List<String> armor, String mainhand, String offhand, String dimension) {}

    static Sample read(WorldObservationService service, UUID id, String dimension, Map<String, String> stamp, long now) {
        if (service == null) return null;
        try {
        var request = new WorldObservationService.Request("rdd-death-prepare", dimension, "self",
                Map.of("target", "player:" + id), 3000, 1, 50);
        var response = service.read(request);
        Sample sample = sample(response, id, dimension, stamp, now);
        if (sample == null) sample = sample(service.refresh(request), id, dimension, stamp, System.currentTimeMillis());
        return sample;
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    static Sample sample(WorldObservationService.Response response, UUID id, String dimension,
                         Map<String, String> stamp, long now) {
        if (response == null || response.status() != WorldObservationService.Status.OK
                || response.coverage() != WorldObservationService.Coverage.FULL
                || !"self".equals(response.topic()) || response.items().size() != 1 || stamp == null) return null;
        var meta = response.metadata();
        if (!meta.identified() || !id.toString().equals(meta.subjectId()) || !dimension.equals(meta.dimension())
                || !meta.worldId().equals(stamp.get("world_id")) || !meta.serverSession().equals(stamp.get("server_session"))
                || meta.freshness() != ObservationMetadata.Freshness.CURRENT || meta.observedAtMs() > now
                || now - meta.observedAtMs() > 3000 || meta.validUntilMs() <= now) return null;
        var item = response.items().get(0);
        if (masked(item, "companionUUID") || masked(item, "dimension")
                || !id.toString().equals(item.values().get("companionUUID"))
                || !dimension.equals(item.values().get("dimension"))) return null;
        var values = item.values();
        if (!(values.get("main") instanceof List<?> main) || main.size() != 36
                || !(values.get("armor") instanceof List<?> armor) || armor.size() != 4) return null;
        Map<String, Integer> held = new LinkedHashMap<>();
        List<String> armorIds = new ArrayList<>();
        for (int index = 0; index < 36; index++) if (add(main.get(index), index, item, "main[" + index + "]", held) == null) return null;
        for (int index = 0; index < 4; index++) {
            String key = add(armor.get(index), 36 + index, item, "armor[" + index + "]", held);
            if (key == null) return null;
            armorIds.add(key);
        }
        String off = add(values.get("offhand"), 40, item, "offhand", held);
        if (off == null || masked(item, "selected")) return null;
        Integer selected = number(values.get("selected"));
        if (selected == null || selected < 0 || selected > 8) return null;
        Object rawMain = main.get(selected);
        if (!(rawMain instanceof Map<?, ?> hand) || !(hand.get("id") instanceof String mainId)) return null;
        return new Sample(Map.copyOf(held), List.copyOf(armorIds), mainId, off, dimension);
    }

    private static String add(Object raw, int index, WorldObservationService.Item item, String path, Map<String, Integer> held) {
        if (masked(item, path) || masked(item, path + ".slot") || masked(item, path + ".id")
                || masked(item, path + ".count") || !(raw instanceof Map<?, ?> slot)) return null;
        Integer observedIndex = number(slot.get("slot")), count = number(slot.get("count"));
        if (observedIndex == null || observedIndex != index || count == null || count < 0
                || !(slot.get("id") instanceof String key) || key.isEmpty() && count != 0
                || !key.isEmpty() && (count == 0 || !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) return null;
        if (!key.isEmpty()) {
            try { held.merge(key, count, Math::addExact); }
            catch (ArithmeticException ex) { return null; }
        }
        return key;
    }

    private static Integer number(Object raw) {
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue()) return null;
        return n.intValue();
    }
    private static boolean masked(WorldObservationService.Item item, String path) {
        return java.util.stream.Stream.concat(item.unknownFields().stream(), item.staleFields().stream())
                .anyMatch(p -> p.isEmpty() || path.equals(p) || path.startsWith(p + ".") || path.startsWith(p + "["));
    }

    static Boolean condition(Sample sample, Map<String, Object> c, BiPredicate<String, String> match) {
        if (sample == null) return null;
        Object dimension = c.get("dimension");
        if (dimension != null && !dimension.equals(sample.dimension())) return null;
        if ("equipment".equals(c.get("type"))) {
            if (!WorldFactConditions.valid(c)) return null;
            String wanted = String.valueOf(c.get("item"));
            boolean[] hits = new boolean[6];
            for (int i = 0; i < 4; i++) hits[i] = hit(wanted, sample.armor().get(i), match);
            hits[4] = hit(wanted, sample.mainhand(), match);
            hits[5] = hit(wanted, sample.offhand(), match);
            return WorldFactConditions.equipmentSlotsSatisfied(c, hits[0], hits[1], hits[2], hits[3], hits[4], hits[5]);
        }
        Object raw = c.containsKey("asset_key") ? c.get("asset_key") : c.get("item");
        Integer minimum = number(c.getOrDefault("minimum", 1));
        if (!(raw instanceof String key) || minimum == null || minimum <= 0) return null;
        return sample.held().getOrDefault(key, 0) >= minimum;
    }
    private static boolean hit(String wanted, String key, BiPredicate<String, String> match) {
        return !key.isEmpty() && (wanted.startsWith("#") ? match != null && match.test(wanted, key) : wanted.equals(key));
    }

    static PreparationFacts facts(Sample sample, SideTask task, Map<String, Integer> backup, BiPredicate<String, String> match) {
        List<Map<String, Object>> targets = DeathPreparation.conditions(task, DeathPreparation.TARGETS);
        if (targets.isEmpty()) return PreparationFacts.unknown("no_applicable_equipment_declaration");
        if (sample == null) return PreparationFacts.unknown("self_equipment_unverified_after_refresh");
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> c : targets) {
            Boolean value = condition(sample, c, match);
            if (value == null) return PreparationFacts.unknown("equipment_condition_not_verifiable: " + c);
            if (!value) missing.add(c.toString());
        }
        return new PreparationFacts(missing.isEmpty() ? PreparationState.READY : PreparationState.MISSING,
                missing.isEmpty() ? "current_equipment_and_declared_stock_verified" : "missing_current_equipment: " + missing,
                sample.held(), backup);
    }
}
