package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏规则。默认只返回会直接影响实验/刷怪/过夜/死亡惩罚的 6 条，不把 60 条规则倒给模型。
 *
 * <p>未知 key 明确报错并列出已知 key —— 「拼错规则名」和「规则不存在」不能长得一样。
 */
public final class GameRulesProbe implements WorldProbe {

    /** 默认返回的规则顺序。 */
    public static final List<String> DEFAULT_KEYS = List.of(
            "doMobSpawning", "doDaylightCycle", "keepInventory", "playersSleepingPercentage",
            "naturalRegeneration", "doInsomnia");

    // 刻意不在 static 块里碰 GameRules：那会让「只读目录」的离线测试也拖进 MC 类链。
    // 规则表在 collect() 里建（14 条，成本可忽略），类加载时不解析 MC。
    private static Map<String, GameRules.Key<GameRules.BooleanValue>> bools() {
        Map<String, GameRules.Key<GameRules.BooleanValue>> m = new LinkedHashMap<>();
        m.put("doMobSpawning", GameRules.RULE_DOMOBSPAWNING);
        m.put("doDaylightCycle", GameRules.RULE_DAYLIGHT);
        m.put("keepInventory", GameRules.RULE_KEEPINVENTORY);
        m.put("naturalRegeneration", GameRules.RULE_NATURAL_REGENERATION);
        m.put("doInsomnia", GameRules.RULE_DOINSOMNIA);
        m.put("doFireTick", GameRules.RULE_DOFIRETICK);
        m.put("mobGriefing", GameRules.RULE_MOBGRIEFING);
        m.put("doWeatherCycle", GameRules.RULE_WEATHER_CYCLE);
        m.put("doImmediateRespawn", GameRules.RULE_DO_IMMEDIATE_RESPAWN);
        return m;
    }

    private static Map<String, GameRules.Key<GameRules.IntegerValue>> ints() {
        Map<String, GameRules.Key<GameRules.IntegerValue>> m = new LinkedHashMap<>();
        m.put("playersSleepingPercentage", GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
        m.put("randomTickSpeed", GameRules.RULE_RANDOMTICKING);
        m.put("spawnRadius", GameRules.RULE_SPAWN_RADIUS);
        m.put("maxEntityCramming", GameRules.RULE_MAX_ENTITY_CRAMMING);
        return m;
    }

    @Override public String id() { return "gamerules"; }

    @Override public String title() { return "Selected game rules"; }

    @Override public String description() {
        return "Read the gamerules that change spawning, day cycle, inventory loss, sleeping, "
                + "regeneration and insomnia. Default returns a curated set; pass gamerules=name1,name2 "
                + "to ask for specific keys. Unknown keys fail with the known list.";
    }

    @Override public List<String> params() { return List.of("gamerules"); }

    @Override public List<String> returns() { return List.of("rules"); }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) {
            return ProbeResult.failed("NOT_FOUND", "not attached to a server");
        }
        List<String> requested = ctx.gameruleKeys() == null || ctx.gameruleKeys().isEmpty()
                ? DEFAULT_KEYS : ctx.gameruleKeys();

        Map<String, GameRules.Key<GameRules.BooleanValue>> bools = bools();
        Map<String, GameRules.Key<GameRules.IntegerValue>> ints = ints();
        List<String> unknown = new ArrayList<>();
        for (String key : requested) {
            if (!bools.containsKey(key) && !ints.containsKey(key)) unknown.add(key);
        }
        if (!unknown.isEmpty()) {
            List<String> known = new ArrayList<>(bools.keySet());
            known.addAll(ints.keySet());
            return ProbeResult.failed("INVALID_REQUEST",
                    "unknown gamerule(s): " + unknown + "; known: " + known);
        }

        GameRules rules = server.getGameRules();
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : requested) {
            if (bools.containsKey(key)) {
                values.put(key, rules.getBoolean(bools.get(key)));
            } else {
                values.put(key, rules.getInt(ints.get(key)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", values);
        out.put("requested", requested);
        return ProbeResult.complete(out);
    }
}
