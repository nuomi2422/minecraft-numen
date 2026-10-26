package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.plugins.worldstate.WorldStateService;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeService;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ★ 世界源探针目录 —— 「诊断时按名字拉一次当前世界状态」。
 *
 * <pre>
 * world_probe action=list
 * world_probe action=get    probe=difficulty
 * world_probe action=get    probe=players
 * world_probe action=get    probe=entities radius=32 category=hostile
 * world_probe action=get    probe=light x=.. y=.. z=..
 * world_probe action=snapshot
 * </pre>
 *
 * <p><b>与 world_state 的分工</b>：{@code world_state} 管带时间/覆盖/新鲜度的观测账本；
 * 本工具管现场只读探针。两者都不改世界，但本工具<b>每次都现场读</b>，不返回旧缓存。
 *
 * <p><b>目录是一等公民</b>：{@code action=list} 必须能让第一次接触的人发现
 * 「玩家列表」「光照」这些探针存在 —— 盲点无法预推，但可以让人知道有哪些可问。
 */
public final class WorldProbeTool implements NumenTool {

    private final ProbeService probes;

    public WorldProbeTool(ProbeService probes) {
        this.probes = probes;
    }

    @Override
    public String name() {
        return "world_probe";
    }

    @Override
    public String description() {
        return "★ 世界源只读探针目录（每次现场读，不加载区块、不改世界）。\n"
                + "action=list —— 列出全部探针（id/说明/参数/返回字段）。先看这个再猜。\n"
                + "action=get probe=<id> —— 执行一个探针，如 difficulty / gamerules / players / time / weather / "
                + "entities / light / distances / self。\n"
                + "action=snapshot —— 合成实验控制变量快照（难度/规则/玩家/时间/天气/视距 + control_key）。\n"
                + "典型诊断：敌对计数=0 先查 difficulty 与 gamerules.doMobSpawning；睡了不跳夜先查 players "
                + "（有没有非旁观玩家没睡）与 gamerules.playersSleepingPercentage；远处看不到实体先查 distances。\n"
                + "本工具只回答世界侧事实，不判定「是不是代码 bug」。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "list=目录 / get=执行一个探针 / snapshot=控制变量快照", "list", "get", "snapshot")
                .optionalString("probe", "get 用：探针 id，见 action=list")
                .optionalString("dimension", "维度 id，默认同伴当前维度，如 minecraft:overworld")
                .optionalInteger("x", "light/entities 用：中心 x", -30000000, 30000000)
                .optionalInteger("y", "light/entities 用：中心 y", -64, 320)
                .optionalInteger("z", "light/entities 用：中心 z", -30000000, 30000000)
                .optionalInteger("radius", "entities 用：半径（默认 32，上限 64）", 1, 64)
                .optionalEnum("category", "entities 用：只看某一类", "hostile", "passive", "all")
                .optionalString("type", "entities 用：精确实体 id，如 minecraft:zombie")
                .optionalString("companion", "self 用：同伴名，默认调用者")
                .optionalString("gamerules", "gamerules 用：逗号分隔的规则名，默认返回 6 条常用规则")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null) {
                reply.accept(TaskResult.fail("world_probe: not on a server").toJson());
                return;
            }
            String action = args.has("action")
                    ? args.get("action").getAsString().toLowerCase(Locale.ROOT) : "list";
            ProbeContext ctx = buildContext(self, args);
            switch (action) {
                case "list" -> {
                    List<Map<String, Object>> catalog = probes.catalog();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", catalog.size());
                    data.put("probes", catalog);
                    reply.accept(TaskResult.ok("world_probe: " + catalog.size() + " probes", data).toJson());
                }
                case "get" -> handleGet(ctx, args, reply);
                case "snapshot" -> {
                    Map<String, Object> snapshot = probes.snapshot(ctx);
                    reply.accept(TaskResult.ok("world snapshot complete=" + snapshot.get("complete"), snapshot).toJson());
                }
                default -> reply.accept(TaskResult.fail(
                        "world_probe: action must be list|get|snapshot").toJson());
            }
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("world_probe error: " + ex.getMessage()).toJson());
        }
    }

    private void handleGet(ProbeContext ctx, JsonObject args, Consumer<String> reply) {
        String id = optString(args, "probe");
        if (id == null || id.isBlank()) {
            reply.accept(TaskResult.fail("world_probe: get requires probe=<id>; call action=list").toJson());
            return;
        }
        if ("snapshot".equalsIgnoreCase(id.trim())) {
            reply.accept(TaskResult.fail("world_probe: use action=snapshot for the composite snapshot").toJson());
            return;
        }
        ProbeResult result = probes.get(id, ctx);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("probe", id.trim().toLowerCase(Locale.ROOT));
        data.put("at", Instant.now().toString());
        data.put("world_id", ctx.worldId());
        data.put("dimension", ctx.dimension());
        data.putAll(result.envelope());
        if (result.ok()) {
            reply.accept(TaskResult.ok("world_probe " + id + " (" + result.coverage() + ")", data).toJson());
        } else {
            reply.accept(TaskResult.fail("world_probe " + id + " failed: " + result.reason()
                    + " (" + result.message() + ")", data).toJson());
        }
    }

    private static ProbeContext buildContext(NumenPlayer self, JsonObject args) {
        MinecraftServer server = self.getServer();
        String dimension = optString(args, "dimension");
        if (dimension == null || dimension.isBlank()) {
            dimension = self.level().dimension().location().toString();
        }
        ServerLevel level = McCollectionContext.levelOf(server, dimension);

        ServerPlayer target = self;
        String companion = optString(args, "companion");
        if (companion != null && !companion.isBlank()) {
            target = server.getPlayerList().getPlayerByName(companion.trim());
        }

        int x = optInt(args, "x", target == null ? 0 : Mth.floor(target.getX()));
        int y = optInt(args, "y", target == null ? 0 : Mth.floor(target.getY()));
        int z = optInt(args, "z", target == null ? 0 : Mth.floor(target.getZ()));
        int radius = optInt(args, "radius", 0);
        String category = optString(args, "category");
        String type = optString(args, "type");
        List<String> gamerules = splitCsv(optString(args, "gamerules"));

        return new ProbeContext(server, level, target, WorldStateService.worldId(server), dimension,
                x, y, z, radius, category, type, gamerules);
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean has(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }

    private static String optString(JsonObject args, String key) {
        return has(args, key) ? args.get(key).getAsString() : null;
    }

    private static int optInt(JsonObject args, String key, int fallback) {
        return has(args, key) ? args.get(key).getAsInt() : fallback;
    }
}
