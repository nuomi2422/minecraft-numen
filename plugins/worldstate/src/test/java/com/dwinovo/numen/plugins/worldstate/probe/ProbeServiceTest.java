package com.dwinovo.numen.plugins.worldstate.probe;

import com.dwinovo.numen.plugins.worldstate.probe.impl.GameRulesProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目录与对照键的纯逻辑回归；不碰真实世界。 */
class ProbeServiceTest {

    @Test
    void catalogHasTheNineStableIds() {
        ProbeService service = new ProbeService();
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : service.catalog()) {
            ids.add(String.valueOf(row.get("id")));
        }
        assertEquals(List.of("difficulty", "gamerules", "players", "time", "weather",
                "entities", "light", "distances", "self"), ids);
    }

    @Test
    void unknownProbeFailsWithNotFound() {
        ProbeService service = new ProbeService();
        ProbeResult r = service.get("nope", null);
        assertFalse(r.ok());
        assertEquals("NOT_FOUND", r.reason());
        assertTrue(r.message().contains("action=list"));
    }

    @Test
    void controlKeyIsOrderIndependentAndIncludesPlayersSorted() {
        Map<String, Object> control = control();
        String a = ProbeService.controlKey(control);

        // 打乱玩家顺序，key 必须不变（玩家按名字排序）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) control.get("players");
        List<Map<String, Object>> reversed = new ArrayList<>(players);
        java.util.Collections.reverse(reversed);
        control.put("players", reversed);

        assertEquals(a, ProbeService.controlKey(control));
        assertTrue(a.startsWith("v1|difficulty=peaceful"));
        assertTrue(a.contains("|time_bucket=6|"));
        assertTrue(a.contains("|players=A:creative:false:false;B:survival:false:false|"));
    }

    @Test
    void stableKeyIgnoresTimeBucketButControlKeyDoesNot() {
        Map<String, Object> control = control();
        String stable = ProbeService.stableKey(control);
        String full = ProbeService.controlKey(control);
        control.put("time_bucket", 20);
        assertEquals(stable, ProbeService.stableKey(control), "stable_key 不应吃时段");
        assertFalse(full.equals(ProbeService.controlKey(control)), "control_key 必须吃时段");
    }

    @Test
    void gamerulesDefaultsAreTheDocumentedSix() {
        assertEquals(List.of("doMobSpawning", "doDaylightCycle", "keepInventory",
                "playersSleepingPercentage", "naturalRegeneration", "doInsomnia"),
                GameRulesProbe.DEFAULT_KEYS);
        assertNotNull(GameRulesProbe.DEFAULT_KEYS);
    }

    private static Map<String, Object> control() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("doMobSpawning", false);
        rules.put("doDaylightCycle", true);
        rules.put("keepInventory", true);
        rules.put("playersSleepingPercentage", 100);

        List<Map<String, Object>> players = new ArrayList<>();
        players.add(player("A", "creative", false, false));
        players.add(player("B", "survival", false, false));

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("difficulty", "peaceful");
        control.put("hardcore", false);
        control.put("default_game_mode", "survival");
        control.put("rules", rules);
        control.put("time_bucket", 6);
        control.put("weather", "clear");
        control.put("players", players);
        control.put("simulation_distance", 10);
        control.put("view_distance", 12);
        return control;
    }

    private static Map<String, Object> player(String name, String mode, boolean sleeping, boolean spectator) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("mode", mode);
        p.put("sleeping", sleeping);
        p.put("spectator", spectator);
        return p;
    }
}
