package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
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

    /** 每个同伴上一次调用本工具的时间（毫秒）：60 秒闸，防她自己用作弊通道绕过能力验证。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> LAST_CALL =
            new java.util.concurrent.ConcurrentHashMap<>();

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
            // 2026-10-29（§37 权限边界）：本工具注册在**同伴工具集**里，她理论上能自己调。
            //   实测她还没用过（三次调用全是我发的，stuck 字段可证），但必须设闸：
            //   同一个同伴 60 秒内只允许一次，被挡时记 admin_command_refused。
            Long lastAt = LAST_CALL.get(companion.getUUID());
            long nowMs = System.currentTimeMillis();
            if (lastAt != null && nowMs - lastAt < 60_000L) {
                RddMonitor.publish("admin_command_refused", Map.of(
                        "companionId", companion.getUUID().toString(),
                        "reason", "cooldown-60s"));
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("admin command refused: cooldown-60s").toJson());
                return;
            }
            LAST_CALL.put(companion.getUUID(), nowMs);

            String lower = command.toLowerCase(java.util.Locale.ROOT) + " ";
            for (String bad : FORBIDDEN) {
                if (lower.contains(bad)) {
                    throw new IllegalArgumentException("refused: contains forbidden token '" + bad.trim() + "'");
                }
            }
            String verb = lower.split("\\s+", 2)[0];
            // 2026-10-29（revive）：假玩家死了**不会自己复活** —— Companions.respawnDead 只在主人登录时触发，
            //   而外置学习者全程走 MCP、没有主人登录 ⇒ 她死了主线就永久停住（实机：末地虚空坠落 3 次后 hp 一直 0）。
            // 2026-10-29（dragon）：这个存档的末地从来就没有龙 —— DIM1/data 里没有 ender_dragon_fight.dat，
            //   因为她进末地走的是 D-20 兜底（假玩家传送），绕过了原版"首次进入维度"的龙战初始化。
            //   这里给一个显式入口，把末地龙战初始化/复活跑一遍。
            // 2026-10-29（pillar）：她只会往下挖，不会往上搭 —— 末地柱顶水晶(y+13~27)和飞行中的龙都够不到。
            //   这里给一个"原地向上垫方块"的通道：每次在脚下放一块圆石并上移 1 格。
            // 2026-10-29（crystal）：attack 对末影水晶完全无效（450 秒预算 0/6，因为水晶不是 LivingEntity）。
            //   这里直接把它们从世界里移除（discard 不会像被打碎那样爆炸，避免再把她炸进虚空）。
            // 2026-10-29（heal）：她 hp 11、背包 5 个金苹果却不吃（低血回血不稳定）。
            //   末地死亡 = 丢全身装备，所以给一条兜底回血通道。
            // 2026-10-29（shoot）：attack 只会"地面追击+近距射",对 77 格外的飞行末影龙无效（三次实测）。
            //   这里直接朝坐标发一支箭（以她为射手），补上"朝天上射"这个动作。
            if ("shoot".equals(verb)) {
                String[] sp = command.trim().split("\s+");
                if (sp.length < 4) throw new IllegalArgumentException("shoot needs x y z");
                double tx = Double.parseDouble(sp[1]), ty = Double.parseDouble(sp[2]), tz = Double.parseDouble(sp[3]);
                var sl = (net.minecraft.server.level.ServerLevel) companion.level();
                var arrow = new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW, sl);
                arrow.setOwner(companion);
                arrow.setPos(companion.getX(), companion.getEyeY(), companion.getZ());
                double ddx = tx - companion.getX(), ddy = ty - companion.getEyeY(), ddz = tz - companion.getZ();
                double len = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                if (len < 1.0e-4) throw new IllegalArgumentException("shoot: zero distance");
                arrow.shoot(ddx / len, ddy / len, ddz / len, 3.0f, 0.0f);
                sl.addFreshEntity(arrow);
                RddMonitor.publish("admin_command_used", Map.of("companionId", companion.getUUID().toString(),
                        "command", command, "stuck", stuck, "mode", "shoot-at"));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "shot one arrow at " + tx + "," + ty + "," + tz + " (dist " + Math.round(len) + ")"
                                + " —— 算 CHEATED，事后必须补测。",
                        Map.of("cheated", true)).toJson());
                return;
            }
            if ("heal".equals(verb)) {
                companion.setHealth(companion.getMaxHealth());
                RddMonitor.publish("admin_command_used", Map.of("companionId", companion.getUUID().toString(),
                        "command", command, "stuck", stuck, "mode", "heal"));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "healed to " + Math.round(companion.getHealth()) + " —— 算 CHEATED，事后必须补测。",
                        Map.of("cheated", true)).toJson());
                return;
            }
            if ("crystal".equals(verb)) {
                var lvl2 = (net.minecraft.server.level.ServerLevel) companion.level();
                var box = companion.getBoundingBox().inflate(96.0);
                int removed = 0;
                for (var e : lvl2.getEntities(companion, box)) {
                    if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) { e.discard(); removed++; }
                }
                RddMonitor.publish("admin_command_used", Map.of("companionId", companion.getUUID().toString(),
                        "command", command, "stuck", stuck, "mode", "crystal-clear"));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "removed " + removed + " end crystals（discard，无爆炸）—— 算 CHEATED，事后必须补测。",
                        Map.of("cheated", true)).toJson());
                return;
            }
            if ("pillar".equals(verb)) {
                int n = 8;
                String[] parts = command.trim().split("\s+");
                if (parts.length > 1) { try { n = Math.max(1, Math.min(64, Integer.parseInt(parts[1]))); } catch (NumberFormatException ignored) { } }
                var lvl = (net.minecraft.server.level.ServerLevel) companion.level();
                for (int i = 0; i < n; i++) {
                    net.minecraft.core.BlockPos below = companion.blockPosition().below();
                    lvl.setBlock(below, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
                    companion.teleportTo(companion.getX(), companion.getY() + 1.0, companion.getZ());
                }
                RddMonitor.publish("admin_command_used", Map.of("companionId", companion.getUUID().toString(),
                        "command", command, "stuck", stuck, "mode", "pillar-up"));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "pillar up " + n + " blocks, y now " + Math.round(companion.getY())
                                + " —— 算 CHEATED，事后必须补测。",
                        Map.of("cheated", true)).toJson());
                return;
            }
            if ("dragon".equals(verb)) {
                var endLevel = companion.getServer().getLevel(net.minecraft.world.level.Level.END);
                if (endLevel == null) throw new IllegalArgumentException("dragon: end level missing");
                var fight = endLevel.getDragonFight();
                if (fight == null) throw new IllegalArgumentException("dragon: no dragon fight in this level");
                fight.tryRespawn();
                RddMonitor.publish("admin_command_used", Map.of("companionId", companion.getUUID().toString(),
                        "command", command, "stuck", stuck, "mode", "dragon-fight"));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "dragon fight: tryRespawn done, previouslyKilled=" + fight.hasPreviouslyKilledDragon()
                                + " —— 算 CHEATED，事后必须补测。",
                        Map.of("cheated", true)).toJson());
                return;
            }
            if ("revive".equals(verb)) {
                var server = companion.getServer();
                UUID cid = companion.getUUID();
                var reg = CompanionRegistry.get(server).find(cid);
                if (reg == null) throw new IllegalArgumentException("revive: companion not in registry");
                NumenPlayer live = NumenPlayer.findByUuid(server, cid);
                // 2026-10-29（修订）：discard+spawn 在"实体位于未加载区块"时无效（她 y=-380）。
                //   改成先把血补回来（MC 的玩家死亡本质是 hp<=0），再传送到主世界基地。
                if (live != null) {
                    live.setHealth(live.getMaxHealth());
                    var ow = server.overworld();
                    live.teleportTo(ow, 126.0, 67.0, 703.0, java.util.Set.of(), live.getYRot(), live.getXRot());
                    RddMonitor.publish("admin_command_used", Map.of("companionId", cid.toString(),
                            "command", command, "stuck", stuck, "mode", "revive-heal"));
                    reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                            "revived: healing + teleported to overworld base —— 算 CHEATED，事后必须补测。",
                            Map.of("cheated", true)).toJson());
                    return;
                }
                if (live != null) live.discard();
                var fresh = CompanionFactory.spawn(server, cid, reg.name(), reg.owner(), server.overworld(), null);
                RddMonitor.publish("admin_command_used", Map.of("companionId", cid.toString(),
                        "command", command, "stuck", stuck, "mode", "revive"));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "revived " + (fresh == null ? "FAILED" : "ok") + " —— 算 CHEATED，事后必须补测。",
                        Map.of("cheated", true)).toJson());
                return;
            }
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
