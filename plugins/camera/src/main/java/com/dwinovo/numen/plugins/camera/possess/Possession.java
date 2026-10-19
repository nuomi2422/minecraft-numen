package com.dwinovo.numen.plugins.camera.possess;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;
import com.dwinovo.numen.plugins.camera.tether.CompanionTether;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * 附身（观察者模式 + 视角绑定）：{@code /cam possess <名字>} 一次完成「变观察者 + 把镜头焊在同伴身上」。
 *
 * <h2>为什么不是「一直 /cam tp」</h2>
 * 旧路子（{@link CompanionTether}）是「同伴走出加载区 → 发一条 {@code /tp @s x y z}」。
 * 它的两个硬伤：
 * <ol>
 *   <li><b>刷屏</b>：同伴每跑远一次就一条 {@code /tp} 回执，跟拍十分钟能刷出上百行；</li>
 *   <li><b>视角是断的</b>：传过去只是「我也在那个位置」，镜头仍在我自己的第一人称上，
 *       想看它得自己转视角，它一转身我就跟丢。</li>
 * </ol>
 *
 * <h2>附身走的是原版「旁观者」那套机制</h2>
 * 原版 {@code /spectate} 做的事就两步（见 {@code SpectateCommand}）：
 * <pre>
 *   ① 玩家 gameMode = SPECTATOR
 *   ② serverPlayer.setCamera(目标)
 * </pre>
 * 第 ② 步才是关键 —— {@code ServerPlayer.tick()} 里有：
 * <pre>
 *   Entity cam = getCamera();
 *   if (cam != this) { absMoveTo(cam.getX(), cam.getY(), cam.getZ(), cam.getYRot(), cam.getXRot()); }
 * </pre>
 * 也就是说<b>服务端每 tick 自动把我的身体搬到镜头实体身上</b>，并给客户端发
 * {@code ClientboundSetCameraPacket} 让画面从它眼睛里出。所以：
 * <ul>
 *   <li>视角<b>物理绑定</b>，不是「传一次然后各看各的」；</li>
 *   <li>区块加载跟着我走（因为我的身体真的在它旁边），不会出现「镜头到了、区块没到」；</li>
 *   <li><b>一条指令都不用发第二次</b>，聊天栏干净。</li>
 * </ul>
 *
 * <h2>为什么不直接发 {@code /spectate} 指令</h2>
 * 原版 {@code /spectate} 要求 <b>OP 等级 2</b>，而 live 存档 {@code allowCommands=0}（没开作弊）、
 * 玩家是 survival 权限 0 —— 发出去只会红字失败。本模块在<b>单人存档</b>里直接走集成服务端
 * （{@code Minecraft.getSingleplayerServer()}）调用同一对 API，<b>绕开命令权限但不绕开游戏规则</b>：
 * 变的仍然是原版 SPECTATOR 档，仍然是服务端权威。
 *
 * <h2>联机服怎么办</h2>
 * 专用服务端拿不到 {@code IntegratedServer}，只能退回发 {@code /gamemode spectator} +
 * {@code /spectate <名字>}，需要 OP。权限不够时<b>明确报错</b>，不静默失败
 * （项目时间锁 TL-2026-10-03-01 的教训）。
 *
 * <h2>断线/崩溃保护</h2>
 * 观察者档会被写进 playerdata，游戏崩在附身状态里再进来人就是旁观者 —— 一个很脏的现场。
 * 所以附身前把「之前是什么档」落到 {@code config/numen_api/possess.json}，正常退出就删；
 * 下次进世界如果发现自己在 SPECTATOR 且这份记录还在，自动恢复成原来的档并说明原因。
 */
public final class Possession {

    /** 附身是否生效。{@link com.dwinovo.numen.plugins.camera.mixin.MixinCamera} 与牵引都读它。 */
    private static volatile boolean active;

    /** 当前附身目标名（空 = 没附身）。 */
    private static volatile String targetName = "";

    /** 请求发出后的宽限 tick：这段时间内不判「镜头被收回」，否则刚发完就被自己取消。 */
    private static volatile int graceTicks;

    /** 本进程只做一次「上次是否停在观察者」的恢复。 */
    private static volatile boolean recovered;

    /** 待打到聊天栏的回执（服务端线程写、客户端线程读）。 */
    private static final Deque<String> MESSAGES = new ArrayDeque<>();

    /** 记录文件里的「附身前是什么档」。 */
    private static volatile GameType previousMode;

    private Possession() {
    }

    public static boolean isActive() {
        return active;
    }

    public static String targetName() {
        return targetName;
    }

    /** 给 {@code /cam status} 用的一行。 */
    public static String status() {
        if (!active) {
            return "未附身（跟拍/牵引仍可用）";
        }
        String mode = previousMode == null ? "?" : previousMode.getName();
        return "已附身 " + targetName + "（观察者模式，视角服务端每 tick 跟随；退出前是 " + mode + "）";
    }

    // ── 对外入口（全部在客户端线程被 /cam 调用）──────────────────────────────

    /**
     * 附身：观察者模式 + 镜头绑定，一步到位。
     *
     * @return 立刻能回给玩家的那句（真正的结果稍后由 {@link #tick()} 打到聊天栏）
     */
    public static String possess(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return "还没进世界。";
        }
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            return "要给同伴名字：/cam possess <名字>（/cam list 看有哪些）";
        }
        if (active) {
            return "已经附在 " + targetName + " 身上了。先 /cam release 再换人。";
        }

        // 附身期间别的「会动玩家」的开关必须让路，否则 /tp 会和服务端每 tick 的跟随抢位置。
        CompanionTether.setFollow(false);
        CameraRig.setTarget(n);
        // ⚠ 刻意不 setMode(OFF)：那会把用户存的机位模式落盘成 OFF，退出附身后就丢了。
        // 机位算法的让位交给 MixinCamera 的 Possession.isActive() 早退，内存态不改、退出即复原。

        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return possessRemote(n, mc);
        }

        UUID targetUuid = clientSideUuid(n, mc);
        previousMode = mc.gameMode.getPlayerMode();
        active = true;
        targetName = n;
        graceTicks = 40;

        server.execute(() -> {
            ServerPlayer self = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self == null) {
                fail("服务端找不到你的玩家实体（世界可能正在退出）");
                return;
            }
            ServerPlayer target = targetUuid != null
                    ? server.getPlayerList().getPlayer(targetUuid)
                    : server.getPlayerList().getPlayerByName(n);
            if (target == null) {
                fail("服务端玩家列表里没有叫 " + n + " 的同伴（可能已休眠，或不在这个存档里）");
                return;
            }
            if (target == self) {
                fail("目标是你自己");
                return;
            }
            GameType prev = self.gameMode.getGameModeForPlayer();
            previousMode = prev;
            writeRecord(n, prev);
            if (prev != GameType.SPECTATOR && !self.setGameMode(GameType.SPECTATOR)) {
                clearRecord();
                fail("服务端拒绝了切观察者模式（有 mod 拦了 onChangeGameType）");
                return;
            }
            self.setCamera(target);
            push("已附身 " + n + "：观察者模式 + 视角已绑定（服务端每 tick 跟随，不再发 /tp）。"
                    + "/cam release 退出，退出后回到 " + prev.getName() + "。");
        });
        return "正在附身 " + n + " …（结果打在聊天栏）";
    }

    /** 退出附身：镜头收回自己身上，并恢复附身前的游戏模式。 */
    public static String release() {
        Minecraft mc = Minecraft.getInstance();
        if (!active) {
            return "现在没附身。";
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return releaseRemote(mc);
        }
        server.execute(() -> {
            ServerPlayer self = mc.player == null ? null : server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self == null) {
                deactivate();
                clearRecord();
                return;
            }
            self.setCamera(self);
            GameType prev = readRecordMode();
            if (prev == null) {
                prev = previousMode;
            }
            if (prev != null && prev != GameType.SPECTATOR) {
                self.setGameMode(prev);
            }
            deactivate();
            clearRecord();
            push("已退出附身，视角回到自己身上"
                    + (prev == null ? "。" : "（游戏模式恢复成 " + prev.getName() + "）。"));
        });
        return "正在退出附身 …（结果打在聊天栏）";
    }

    // ── 每 tick ───────────────────────────────────────────────────────────────

    /** 由 {@code CameraMod} 每 tick 调：冲刷回执、开机恢复、看守附身是否被服务端收回。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        flush(mc);
        if (mc.player == null || mc.level == null) {
            return;
        }
        recoverIfNeeded(mc);
        if (!active) {
            return;
        }
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (cam == mc.player) {
            // 服务端把镜头收回去了：目标死亡/卸载，或者别的插件接管。观察者档不能就这么留着。
            deactivate();
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer self = server.getPlayerList().getPlayer(mc.player.getUUID());
                    GameType prev = readRecordMode();
                    if (self != null && prev != null && prev != GameType.SPECTATOR) {
                        self.setGameMode(prev);
                    }
                    clearRecord();
                });
            }
            push("附身已结束（目标死亡/卸载，或服务端收回镜头），游戏模式已恢复。");
        }
    }

    // ── 联机服（无集成服务端）────────────────────────────────────────────────

    private static String possessRemote(String n, Minecraft mc) {
        if (!mc.player.hasPermissions(2)) {
            return "联机服附身需要 OP（等级 2）：先 /op 自己或让服主给权限。"
                    + "单人存档不需要（本模块直接走集成服务端）。";
        }
        previousMode = mc.gameMode.getPlayerMode();
        active = true;
        targetName = n;
        graceTicks = 100;
        mc.player.connection.sendCommand("gamemode spectator");
        mc.player.connection.sendCommand("spectate " + n);
        return "已发 /gamemode spectator + /spectate " + n + "（联机服路径）。镜头没切过去就 /cam release。";
    }

    private static String releaseRemote(Minecraft mc) {
        if (mc.player == null) {
            return "还没进世界。";
        }
        mc.setCameraEntity(mc.player);
        GameType prev = previousMode;
        if (mc.player.hasPermissions(2)) {
            mc.player.connection.sendCommand("spectate");
            if (prev != null && prev != GameType.SPECTATOR) {
                mc.player.connection.sendCommand("gamemode " + prev.getName());
            }
        }
        deactivate();
        return "已退出附身。";
    }

    // ── 开机恢复 ─────────────────────────────────────────────────────────────

    private static void recoverIfNeeded(Minecraft mc) {
        if (recovered) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        recovered = true;
        if (!Files.isRegularFile(recordPath())) {
            return;
        }
        String who = readRecordTarget();
        GameType prev = readRecordMode();
        server.execute(() -> {
            ServerPlayer self = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self != null && self.gameMode.getGameModeForPlayer() == GameType.SPECTATOR
                    && prev != null && prev != GameType.SPECTATOR) {
                self.setCamera(self);
                self.setGameMode(prev);
                push("上次退出时停在观察者模式（附身 " + (who == null ? "?" : who)
                        + " 没正常退出），已自动恢复成 " + prev.getName() + "。");
            }
            clearRecord();
        });
    }

    // ── 状态与回执 ───────────────────────────────────────────────────────────

    private static void deactivate() {
        active = false;
        targetName = "";
        graceTicks = 0;
    }

    private static void fail(String why) {
        deactivate();
        clearRecord();
        push("附身失败：" + why + "。");
    }

    private static void push(String msg) {
        synchronized (MESSAGES) {
            MESSAGES.add(msg);
        }
    }

    /**
     * 给外部（例如 {@code cam.json} 的文件扳机）追一条聊天回执。
     *
     * <p>为什么需要：文件扳机那条路**没有敲指令的人** —— 结果只写日志则在场的人看不见，
     * 只写聊天栏则事后 grep 不到。两处都要有。
     */
    public static void announce(String why) {
        push("（由 " + why + " 触发）");
    }

    private static void flush(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        while (true) {
            String msg;
            synchronized (MESSAGES) {
                msg = MESSAGES.poll();
            }
            if (msg == null) {
                return;
            }
            mc.player.displayClientMessage(Component.literal("[numencam] " + msg), false);
        }
    }

    // ── 记录文件（防止崩在观察者档里）────────────────────────────────────────

    private static Path recordPath() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath().resolve("config").resolve("numen_api").resolve("possess.json");
    }

    private static void writeRecord(String target, GameType prev) {
        JsonObject o = new JsonObject();
        o.addProperty("target", target);
        o.addProperty("previousMode", prev == null ? "SURVIVAL" : prev.name());
        o.addProperty("ts", System.currentTimeMillis());
        try {
            Path p = recordPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // 写不了记录不该阻止附身；代价只是崩溃后要手动 /gamemode。
        }
    }

    private static GameType readRecordMode() {
        String raw = readRecordField("previousMode");
        if (raw == null) {
            return null;
        }
        try {
            return GameType.valueOf(raw.trim().toUpperCase());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readRecordTarget() {
        return readRecordField("target");
    }

    private static String readRecordField(String key) {
        Path p = recordPath();
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            return o.has(key) ? o.get(key).getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void clearRecord() {
        try {
            Files.deleteIfExists(recordPath());
        } catch (IOException | RuntimeException ignored) {
            // 删不掉下次开机多恢复一次，无害。
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    /**
     * 客户端实体表里按名字找同伴的 UUID。
     *
     * <p>为什么不直接用 {@code PlayerList.getPlayerByName}：同名的同伴可能不止一只
     * （本机存档里就有三只都叫 RDD），服务端那个「第一个同名」不一定是我在看的这只。
     * 客户端实体表里能看见的才是镜头要附的那只。
     */
    private static UUID clientSideUuid(String name, Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractClientPlayer p
                    && !mc.player.getUUID().equals(p.getUUID())
                    && name.equalsIgnoreCase(p.getName().getString())) {
                return p.getUUID();
            }
        }
        return null;
    }
}
