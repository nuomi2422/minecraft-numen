package com.dwinovo.numen.plugins.worldstate.probe;

import com.dwinovo.numen.plugins.worldstate.probe.impl.DifficultyProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.DistancesProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.EntityCensusProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.GameRulesProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.LightProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.PlayersProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.SelfProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.TimeProbe;
import com.dwinovo.numen.plugins.worldstate.probe.impl.WeatherProbe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 探针目录与快照合成。
 *
 * <p>目录是<b>固定有名字</b>的：不知道「players」这个探针存在的人，永远不会想到去问它。
 * 所以 {@code action=list} 是本服务的一等公民，不是调试附加项。
 *
 * <p>快照只聚合<b>控制变量</b>（难度/规则/玩家/时间/天气/视距）；实体/光照/自身是
 * 按需探针，不进快照。单个控制探针失败不伪造默认值，写进 {@code errors} 并令
 * {@code complete=false}。
 *
 * <p><b>目录元数据与实现分离</b>：{@code action=list} 不需要加载任何 Minecraft 类，
 * 这样离线单测能在没有 MC classpath 的测试 JVM 里验证目录与对照键。探针实现只在
 * {@link #get} 真正执行时才被构造。
 */
public final class ProbeService {

    /** 快照固定的控制变量探针顺序。 */
    private static final List<String> SNAPSHOT_ORDER = List.of(
            "difficulty", "gamerules", "players", "time", "weather", "distances");

    /** control_key 里固定顺序的规则；与 GameRulesProbe 的默认列表保持一致。 */
    private static final List<String> CONTROL_RULES = List.of(
            "doMobSpawning", "doDaylightCycle", "keepInventory", "playersSleepingPercentage");

    /** 目录真源：id/说明/参数/返回字段。改这里等于改目录，必须同步探针实现与本文。 */
    private static final List<ProbeSpec> SPECS = List.of(
            new ProbeSpec("difficulty", "Server difficulty and hardcore",
                    "Difficulty, hardcore, difficulty lock and default game mode. "
                            + "Use before diagnosing 'no hostiles spawned'.",
                    List.of(), List.of("difficulty", "hardcore", "difficulty_locked", "default_game_mode")),
            new ProbeSpec("gamerules", "Selected game rules",
                    "Spawning/day-cycle/inventory/sleep rules; pass gamerules=a,b for specific keys.",
                    List.of("gamerules"), List.of("rules")),
            new ProbeSpec("players", "Online players and sleep state",
                    "Name, mode, spectator, sleeping, OP and dimension for every online player. "
                            + "Use before blaming sleep/day-skip code.",
                    List.of(), List.of("count", "players[name,mode,spectator,sleeping,op,dimension]")),
            new ProbeSpec("time", "World time and day phase",
                    "Day count, time of day, 24-bucket phase, is_day, moon phase, fixed-time flag.",
                    List.of(), List.of("day", "time_of_day", "time_bucket", "is_day", "moon_phase", "has_fixed_time")),
            new ProbeSpec("weather", "Weather",
                    "clear/rain/thunder, raw raining/thundering flags and rain level.",
                    List.of(), List.of("weather", "raining", "thundering", "rain_level")),
            new ProbeSpec("entities", "Nearby entity census",
                    "Count entities by hostile/passive/other around a point; reports loaded/skipped columns.",
                    List.of("radius", "category", "type", "x", "y", "z"),
                    List.of("center", "radius", "total", "hostile", "passive", "other",
                            "selected", "matching", "scanned_columns", "skipped_columns")),
            new ProbeSpec("light", "Light level at a position",
                    "Block light, sky light and can_see_sky at x/y/z; unloaded chunk fails explicitly.",
                    List.of("x", "y", "z"), List.of("x", "y", "z", "block_light", "sky_light", "can_see_sky")),
            new ProbeSpec("distances", "Simulation and view distance",
                    "Server simulation/view distance in chunks.",
                    List.of(), List.of("simulation_distance", "view_distance")),
            new ProbeSpec("self", "Companion self state",
                    "HP, food, saturation, armor, equipment, deaths, XP, position, mode, sleeping.",
                    List.of("companion"), List.of("name", "mode", "hp", "food", "saturation", "armor",
                            "equipment", "deaths", "xp_level", "x", "y", "z", "sleeping")));

    private static final Map<String, ProbeSpec> BY_ID = new LinkedHashMap<>();

    static {
        for (ProbeSpec s : SPECS) BY_ID.put(s.id(), s);
    }

    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProbeSpec s : SPECS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("title", s.title());
            row.put("description", s.description());
            row.put("params", s.params());
            row.put("returns", s.returns());
            out.add(row);
        }
        return out;
    }

    public Optional<WorldProbe> probe(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!BY_ID.containsKey(key)) return Optional.empty();
        return Optional.of(create(key));
    }

    /** 只在真正执行时构造实现——目录/对照键的离线测试不加载 Minecraft 类。 */
    private static WorldProbe create(String id) {
        return switch (id) {
            case "difficulty" -> new DifficultyProbe();
            case "gamerules" -> new GameRulesProbe();
            case "players" -> new PlayersProbe();
            case "time" -> new TimeProbe();
            case "weather" -> new WeatherProbe();
            case "entities" -> new EntityCensusProbe();
            case "light" -> new LightProbe();
            case "distances" -> new DistancesProbe();
            case "self" -> new SelfProbe();
            default -> throw new IllegalArgumentException("unknown probe: " + id);
        };
    }

    public ProbeResult get(String id, ProbeContext ctx) {
        Optional<WorldProbe> p = probe(id);
        if (p.isEmpty()) {
            return ProbeResult.failed("NOT_FOUND",
                    "unknown probe '" + id + "'; call world_probe action=list for the catalog");
        }
        try {
            ProbeResult r = p.get().collect(ctx);
            return r == null ? ProbeResult.failed("PROVIDER_ERROR", "probe returned null") : r;
        } catch (RuntimeException e) {
            return ProbeResult.failed("PROVIDER_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 控制变量快照。返回的 map 已含 {@code control} / {@code control_key} /
     * {@code stable_key} / {@code errors}，可直接进 TaskResult.data。
     */
    public Map<String, Object> snapshot(ProbeContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", 1);
        out.put("at", Instant.now().toString());
        out.put("world_id", ctx.worldId());
        out.put("dimension", ctx.dimension());

        Map<String, Object> control = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String id : SNAPSHOT_ORDER) {
            ProbeResult r = get(id, ctx);
            if (!r.ok()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("probe", id);
                err.put("reason", r.reason());
                err.put("message", r.message());
                errors.add(err);
                continue;
            }
            control.putAll(r.data());
        }

        // self 不是世界控制变量：失败只记 errors，不令 complete=false。
        ProbeResult self = get("self", ctx);
        if (self.ok()) {
            out.put("self", self.data());
        } else {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("probe", "self");
            err.put("reason", self.reason());
            err.put("message", self.message());
            errors.add(err);
        }

        out.put("control", control);
        out.put("control_key", controlKey(control));
        out.put("stable_key", stableKey(control));
        out.put("complete", errors.stream().noneMatch(e -> !"self".equals(e.get("probe"))));
        out.put("errors", errors);
        return out;
    }

    /**
     * 两轮实验的对照键（含一天内时刻分桶）。玩家按名字排序，规则按固定顺序，
     * 保证同一世界状态永远得到同一串。绝对天数/精确 tick 不进 key。
     */
    public static String controlKey(Map<String, Object> control) {
        return key(control, true);
    }

    /** 一轮内前后漂移检查：去掉一天内时段，只看难度/规则/天气/玩家/距离。 */
    public static String stableKey(Map<String, Object> control) {
        return key(control, false);
    }

    private static String key(Map<String, Object> control, boolean includeTime) {
        Map<String, Object> c = control == null ? Map.of() : control;
        StringBuilder sb = new StringBuilder(includeTime ? "v1" : "s1");
        sb.append("|difficulty=").append(str(c.get("difficulty")));
        sb.append("|hardcore=").append(str(c.get("hardcore")));
        sb.append("|default_mode=").append(str(c.get("default_game_mode")));
        sb.append("|rules=");
        Object rawRules = c.get("rules");
        if (rawRules instanceof Map<?, ?> rules) {
            List<String> parts = new ArrayList<>();
            for (String name : CONTROL_RULES) {
                parts.add(name + "=" + str(rules.get(name)));
            }
            sb.append(String.join(";", parts));
        } else {
            sb.append("?");
        }
        if (includeTime) {
            sb.append("|time_bucket=").append(str(c.get("time_bucket")));
        }
        sb.append("|weather=").append(str(c.get("weather")));
        sb.append("|players=");
        Object rawPlayers = c.get("players");
        if (rawPlayers instanceof List<?> players) {
            List<String> rows = new ArrayList<>();
            for (Object o : players) {
                if (o instanceof Map<?, ?> m) {
                    rows.add(str(m.get("name")) + ":" + str(m.get("mode")) + ":"
                            + str(m.get("sleeping")) + ":" + str(m.get("spectator")));
                }
            }
            rows.sort(Comparator.naturalOrder());
            sb.append(String.join(";", rows));
        } else {
            sb.append("?");
        }
        sb.append("|dist=").append(str(c.get("simulation_distance")))
                .append("/").append(str(c.get("view_distance")));
        return sb.toString();
    }

    private static String str(Object v) {
        if (v == null) return "?";
        if (v instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(v);
    }
}
