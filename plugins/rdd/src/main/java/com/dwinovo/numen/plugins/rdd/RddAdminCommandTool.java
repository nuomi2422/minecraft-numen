package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 外置学习者专用：下达一条**限定白名单**的服务器指令（2026-10-29，主人 goal 第 8 条授权）。
 *
 * <p><b>为什么需要它</b>：卡点（门熄着 + 没有点火物 + 下界做不出打火石）会让整条主线停住，
 * 而主人明确说过"允许作弊跳过、不因单个能力问题阻断主线"。此前 MCP 工具集里**没有任何**
 * 下达指令/给物品的道具（find_tools 实测：无 give / 无 command），所以我只能看着她空转。
 *
 * <p><b>安全边界</b>（必须守）：
 * <ul>
 *   <li>只允许白名单前缀：{@code give / setblock / fill / summon / gamemode / time / weather / tp}；</li>
 *   <li>拒绝一切带 {@code @a @e @r} 的选择器（避免影响其它实体/玩家）；</li>
 *   <li>拒绝 {@code op / deop / ban / kick / stop / kill / difficulty / gamerule}；</li>
 *   <li>必须带 {@code confirm:true}；</li>
 *   <li>每次调用写 monitor 事件 {@code admin_command_used}（含卡点说明），便于事后审计。</li>
 * </ul>
 *
 * <p><b>审计口径</b>：凡是通过本工具达成的进展，**都不能算 AI 学会了**——它只用于保持主线连续，
 * 事后必须补测（记进总账"作弊/跳过"一节）。
 */
final class RddAdminCommandTool implements NumenTool {

    /** 允许的指令前缀（第一条空格前的词）。 */
    private static final List<String> ALLOWED = List.of(
            "give", "setblock", "fill", "summon", "gamemode", "time", "weather", "tp");

    /** 明令禁止的词（出现在任何位置即拒绝）。 */
    private static final List<String> FORBIDDEN = List.of(
            "op ", "deop", "ban", "kick", "stop", "kill", "difficulty", "gamerule", "@a", "@e", "@r");

    @Override public String name() { return "rdd_admin_command"; }

    @Override public String description() {
        return "ADMIN/DEBUG: run ONE whitelisted server command to unblock a stuck main line "
                + "(give/setblock/fill/summon/gamemode/time/weather/tp). Requires confirm=true and a "
                + "'stuck' reason. Refuses selectors (@a/@e/@r) and destructive verbs. Any progress made "
                + "this way must be recorded as CHEATED, not as the AI having learned it.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("command", "One command without leading slash, e.g. give @s minecraft:flint_and_steel 1")
                .string("stuck", "What is blocked and why (becomes the audit record).")
                .bool("confirm", "Must be true.")
                .build();
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                                       Consumer<String> reply) {
        try {
            String command = args.get("command").getAsString().trim();
            String stuck = args.has("stuck") ? args.get("stuck").getAsString().trim() : "";
            boolean confirm = args.has("confirm") && args.get("confirm").getAsBoolean();

            if (command.startsWith("/")) command = command.substring(1).trim();
            if (command.isEmpty()) throw new IllegalArgumentException("command required");
            if (!confirm) throw new IllegalArgumentException("confirm=true required");
            if (stuck.isEmpty()) throw new IllegalArgumentException("'stuck' reason required (audit trail)");

            String lower = command.toLowerCase(java.util.Locale.ROOT) + " ";
            for (String bad : FORBIDDEN) {
                if (lower.contains(bad)) {
                    throw new IllegalArgumentException("refused: contains forbidden token '" + bad.trim() + "'");
                }
            }
            String verb = lower.split("\\s+", 2)[0];
            if (!ALLOWED.contains(verb)) {
                throw new IllegalArgumentException("refused: '" + verb + "' is not whitelisted ("
                        + String.join("/", ALLOWED) + ")");
            }

            // 2026-10-29：`tp @s x y z [dim]` 特判 —— 假玩家走原版 tp 不生效（与 D-20 同源：
            //   维度/位置切换要"给客户端"的握手，空壳连接等不到）。这里直接调 teleportTo。
            // 2026-10-29（give-special）：`give @s <item> <n>` 也走原版命令不生效（假玩家收不到），
            //   这里直接把 ItemStack 塞进她背包。只支持 @s 与物品 id + 数量。
            if ("give".equals(verb)) {
                String[] g = command.trim().split("\\s+");
                if (g.length >= 4 && "@s".equals(g[1])) {
                    String itemId = g[2].contains(":") ? g[2] : "minecraft:" + g[2];
                    int n = (int) Math.max(1, Math.min(64, Double.parseDouble(g[3])));
                    var rl = net.minecraft.resources.ResourceLocation.parse(itemId);
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                    if (item == null) throw new IllegalArgumentException("unknown item " + itemId);
                    var stack = new net.minecraft.world.item.ItemStack(item, n);
                    boolean ok = companion.getInventory().add(stack);
                    if (!ok) companion.drop(stack, false);
                    RddMonitor.publish("admin_command_used", Map.of(
                            "companionId", companion.getUUID().toString(),
                            "command", command, "stuck", stuck, "result", ok ? 1 : 0, "mode", "direct-give"));
                    reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                            "gave " + n + " " + itemId + (ok ? " (into inventory)" : " (dropped at feet)")
                                    + " —— 这次进展算 CHEATED，不算 AI 学会；事后必须补测。",
                            Map.of("cheated", true)).toJson());
                    return;
                }
            }
            if ("tp".equals(verb)) {
                String[] t = command.trim().split("\\s+");
                if (t.length >= 5 && "@s".equals(t[1])) {
                    double x = Double.parseDouble(t[2]), y = Double.parseDouble(t[3]), z = Double.parseDouble(t[4]);
                    var server0 = companion.getServer();
                    net.minecraft.server.level.ServerLevel dest = companion.serverLevel();
                    if (t.length >= 6) {
                        String dimId = t[5].contains(":") ? t[5] : "minecraft:" + t[5];
                        var key = net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION,
                                net.minecraft.resources.ResourceLocation.parse(dimId));
                        var lv = server0.getLevel(key);
                        if (lv != null) dest = lv;
                    }
                    companion.teleportTo(dest, x, y, z, companion.getYRot(), companion.getXRot());
                    RddMonitor.publish("admin_command_used", Map.of(
                            "companionId", companion.getUUID().toString(),
                            "command", command, "stuck", stuck, "result", 1,
                            "mode", "direct-teleport"));
                    reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                            "teleported (direct) to " + x + "," + y + "," + z + " in " + dest.dimension().location()
                                    + " —— 这次进展算 CHEATED，不算 AI 学会；事后必须补测。",
                            Map.of("cheated", true)).toJson());
                    return;
                }
            }
            var server = companion.getServer();
            if (server == null) throw new IllegalStateException("no server");
            // 以命令方块同等权限执行；源头记成同伴，便于事后从日志回溯是谁触发的
            // 2026-10-29：命令源必须是**她本人**，否则 `@s` / `give @s` / `tp @s` 全部无效（她不是源）。
            var source = companion.createCommandSourceStack().withSuppressedOutput();
            // 这一版 MC 的 performPrefixedCommand 返回 void，拿不到 syncResultCode —— 记 0 表示"已执行未取返回码"
            server.getCommands().performPrefixedCommand(source, command);
            int ok = 0;

            String msg = "admin command executed (result=" + ok + "): /" + command;
            RddMonitor.publish("admin_command_used", Map.of(
                    "companionId", companion.getUUID().toString(),
                    "command", command,
                    "stuck", stuck,
                    "result", ok));
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    msg + " —— 注意：这次进展算 CHEATED，不算 AI 学会；事后必须补测。",
                    Map.of("cheated", true)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("admin command refused: " + ex.getMessage()).toJson());
        }
    }
}
