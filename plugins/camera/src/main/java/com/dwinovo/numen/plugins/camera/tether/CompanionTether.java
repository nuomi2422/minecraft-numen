package com.dwinovo.numen.plugins.camera.tether;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 绑定 + 牵引：把「玩家」这个客户端概念跟「同伴」这个实体概念焊在一起。
 *
 * <h2>要做两件事</h2>
 * <ol>
 *   <li><b>绑定</b> —— {@link CameraRig#targetName()} 说盯谁就是谁，这里只负责「选谁」。</li>
 *   <li><b>牵引</b> —— 同伴走出客户端渲染距离（=「离开我的加载区域」）时，玩家也过去。
 *       这样「追踪」才闭环：不然镜头只能一直卡在最后机位上。</li>
 * </ol>
 *
 * <h2>为什么改成「服务端静默传送」（2026-10-31 用户反馈后重做）</h2>
 * 旧实现发 {@code tp @s ...}：每补一次位置就有一条命令回执进聊天栏，用户的原话是
 * 「用传送就会刷屏」；而且单人存档只要没开作弊就<b>没有权限</b>，牵引形同不存在。
 *
 * <p>现在走 {@code Minecraft.getSingleplayerServer()} 拿服务端实体，直接
 * {@code ServerPlayer.connection.teleport(...)}：服务端权威执行、只给这一个客户端发
 * 位置包、<b>不产生任何聊天输出</b>，也不需要作弊权限。和 {@code Possession} 绕开
 * {@code /spectate} 的权限是同一个套路 —— 绕开命令权限，不绕开游戏规则。
 *
 * <p>为什么不在客户端直接改坐标：{@code LocalPlayer} 改了也会被服务端的
 * {@code ServerboundMovePlayerPacket} 校正拉回去（实测：瞬移回去又弹回来）。
 *
 * <p>代价：这条路只对<b>单人存档</b>有效（联机服拿不到 integrated server）。那种情况
 * 退回老的 {@code /tp} 命令路径，并照旧在状态行里写清楚权限问题。
 *
 * <h2>为什么开牵引就自动切旁观者</h2>
 * 身体只是「区块加载锚点」，镜头由 {@code CameraRig} 另算。留在生存档的话它会掉下去、
 * 淹死、被怪打、传进方块里窒息 —— 每一条都会把机位拽走。旁观者档没有碰撞、没有伤害、
 * 不吃重力，正好只承担「让这片区块被加载」这一件事。进/退档都会落盘记一份，崩在旁观者
 * 档里下次进世界能自动恢复（见 {@link #recoverFromCrash}）。
 *
 * <h2>为什么要冷却 + 连续丢失判定</h2>
 * 刚传送过去那一瞬间，客户端实体表里可能还没出现新同伴（区块要重收），
 * {@code resolveTarget()} 会连续返回 null 几十个 tick。如果一见到 null 就传送，
 * 就会和「刚传过去→还没加载完→再传一次」打成死循环，一秒刷几十条 /tp 把日志淹掉。
 * 所以：<b>连续丢失 40 tick（2 秒）</b>才判为「真的走了」，且每次传送后 <b>冷却 100 tick</b>。
 *
 * <h2>权限</h2>
 * {@code /tp} 是原版命令，单人游戏里默认需要作弊。拿不到权限就只在状态输出里说清楚，
 * <b>不静默失败</b> —— 否则用户看到「追踪开着但人不动」，完全查不出原因。
 */
public final class CompanionTether {

    /** 连续多少 tick 解析不到目标才判定「真的走出加载区了」。40 tick = 2 秒。 */
    private static final int LOST_TICKS_BEFORE_TP = 40;

    /** 传送后的冷却 tick 数，防止「传过去→还没加载→再传」死循环。 */
    private static final int TP_COOLDOWN_TICKS = 100;

    /**
     * 两次「按距离补传」之间至少隔多少 tick。40 tick = 2 秒。
     *
     * <p>静默传送不刷聊天栏，但每传一次都要重收一片区块；按 tick 连续补传会把区块
     * 加载线程打满（症状是画面贴图一格一格地才出来）。所以要低频。
     */
    private static final int TP_MIN_GAP_TICKS = 40;

    /** 超过这个距离就主动传过去（只在目标可见时判）。默认 64 格 ≈ 一个区块多一点。 */
    private static volatile double maxDist = 64.0;

    /** 牵引总开关。 */
    private static volatile boolean follow;

    /** 牵引状态行（给 /cam status 看）。 */
    private static volatile String status = "未开启";

    private static int lostTicks;
    private static int cooldownTicks;
    private static boolean warnedNoPermission;

    /** 崩溃恢复每进一次世界只查一次，别每 tick 读盘。 */
    private static boolean crashChecked;

    /** 旁观者档是不是我们切的（身体机位和牵引共用这一份记录）。 */
    private static volatile boolean ownsMode;

    private CompanionTether() {
    }

    public static boolean follow() {
        return follow;
    }

    public static double maxDist() {
        return maxDist;
    }

    public static String status() {
        return status;
    }

    /** 开关牵引。绑定为空时直接拒绝 —— 没有目标就没有「跟着谁」。 */
    public static String setFollow(boolean on) {
        String t = CameraRig.targetName();
        if (t.isEmpty()) {
            follow = false;
            return "还没绑定同伴。先 /cam bind <名字>。";
        }
        if (on) {
            follow = true;
            lostTicks = 0;
            cooldownTicks = 0;
            status = "已开启：" + t + " 走出加载区就静默补传（不经过 /tp，不进聊天栏），最远 "
                    + (long) maxDist + " 格｜" + ensureSpectator();
            return status;
        }
        follow = false;
        status = "已关闭" + restoreMode();
        return status;
    }

    public static String setMaxDist(double d) {
        maxDist = Math.max(1.0, Math.min(256.0, d));
        return "牵引距离阈值 = " + (long) maxDist + " 格";
    }

    /** 能否执行 /tp。客户端命令的 source 永远 hasPermission==true，所以只能问服务端给不给。 */
    private static boolean canTeleport() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                && (mc.player.hasPermissions(2) || mc.player.isCreative());
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            crashChecked = false;   // 换世界后要重新查一次崩溃残留
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        // 附身期间服务端每 tick 都在把我搬到镜头实体身上，这里再补传就是两条路抢同一个位置
        // —— 表现是位置抖动 + 偶尔被服务端校正拉回。附身优先，牵引让位。
        if (com.dwinovo.numen.plugins.camera.possess.Possession.isActive()) {
            lostTicks = 0;
            return;
        }
        if (!follow) {
            recoverFromCrash(mc);
            lostTicks = 0;
            return;
        }
        if (CameraRig.mode() == CameraRig.Mode.BODY) {
            lostTicks = 0;
            return;   // 身体机位自己会搬人，牵引再传就是两条路抢位置
        }
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;   // 聊天界面/暂停菜单开着时别动玩家
        }
        Entity t = CameraRig.resolveTarget();
        if (t != null) {
            lostTicks = 0;
            if (t.distanceTo(mc.player) > maxDist && cooldownTicks == 0) {
                tpTo((AbstractClientPlayer) t, "超出 " + (long) maxDist + " 格");
            }
            return;
        }
        // 目标不在客户端实体表里。名字对得上才算「走了」，否则只是压根没这个同伴。
        lostTicks++;
        if (lostTicks < LOST_TICKS_BEFORE_TP) {
            return;
        }
        if (cooldownTicks > 0) {
            return;
        }
        AbstractClientPlayer candidate = findByName(CameraRig.targetName());
        if (candidate == null) {
            // 名字都没匹配上：可能是改过名，也可能是它真的被卸载到连客户端实体表都没有。
            // 这时也传 —— 因为「tp @s 名字」是服务端执行，服务端能找到它就会把我拉过去。
            tpByName(CameraRig.targetName(), "已离开客户端加载区");
            return;
        }
        tpTo(candidate, "已离开客户端加载区");
    }

    private static AbstractClientPlayer findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractClientPlayer p
                    && !mc.player.getUUID().equals(p.getUUID())
                    && name.equalsIgnoreCase(p.getName().getString())) {
                return p;
            }
        }
        return null;
    }

    /**
     * 传到目标旁边（偏移 1.5 格，别和它重叠）。
     *
     * <p>用 <b>坐标</b>而不是 <code>tp @s &lt;名字&gt;</code>：按名字传会落在它<b>脚下
     * 正中</b>，万一那个格子是实心（它站在方块顶上的边缘）就可能被挤进墙里。给坐标能
     * 挑一个确定的空位，也顺带让状态行里的数字跟实际落点一致。
     */
    private static void tpTo(AbstractClientPlayer target, String why) {
        Vec3 feet = target.position();
        teleport(feet.x + 1.5, feet.y, feet.z + 1.5, why, TP_MIN_GAP_TICKS);
    }

    /** 目标名匹配不上（已离开客户端实体表）时按名字传：服务端认识它，服务端找人。 */
    private static void tpByName(String name, String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || name == null || name.isEmpty()) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            if (!canTeleport()) {
                reportNoPermission();
                return;
            }
            mc.player.connection.sendCommand("tp @s " + name);
            afterTp(why + " → 已发 /tp @s " + name, TP_COOLDOWN_TICKS);
            return;
        }
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        server.execute(() -> {
            ServerPlayer self = server.getPlayerList().getPlayer(mc.player.getUUID());
            ServerPlayer t = server.getPlayerList().getPlayerByName(name);
            if (self == null || t == null) {
                return;
            }
            self.connection.teleport(t.getX() + 1.5, t.getY(), t.getZ() + 1.5, yaw, pitch);
        });
        afterTp(why + " → 静默补传（服务端直调，不走 /tp）", TP_COOLDOWN_TICKS);
    }

    /**
     * 单人存档：服务端直调传送。联机服：退回 {@code /tp} 命令。
     *
     * <p>坐标而不是 {@code tp @s <名字>}：按名字会落在目标脚下正中，万一是实心格会卡进墙
     * （旁观者档不卡，但换回生存档那一刻就卡了）。给坐标能挑一个确定空位。
     */
    private static void teleport(double x, double y, double z, String why, int cooldown) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            if (!canTeleport()) {
                reportNoPermission();
                return;
            }
            mc.player.connection.sendCommand(String.format("tp @s %.1f %.1f %.1f", x, y, z));
            afterTp(String.format("%s → /tp @s %.1f %.1f %.1f", why, x, y, z), cooldown);
            return;
        }
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        server.execute(() -> {
            ServerPlayer self = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self == null) {
                return;
            }
            self.connection.teleport(x, y, z, yaw, pitch);
        });
        afterTp(String.format("%s → 静默补传 (%.1f, %.1f, %.1f)", why, x, y, z), cooldown);
    }

    private static void reportNoPermission() {
        if (warnedNoPermission) {
            return;
        }
        warnedNoPermission = true;
        status = "牵引触发过但没传送权限（单人游戏要开作弊）。"
                + "解法：世界设置里开「作弊」，或 /gamemode creative。";
    }

    private static void afterTp(String what, int cooldown) {
        cooldownTicks = cooldown;
        lostTicks = 0;
        warnedNoPermission = false;
        status = what;
    }

    // ── 旁观者档：自动进入 / 退出 / 崩溃恢复 ─────────────────────────────────
    //
    // 为什么不用 /gamemode 命令：同上 —— 单人存档没开作弊就没有权限，而且会刷屏。
    // 走服务端 setGameMode，和 Possession 是同一对 API。

    /** 进旁观者之前的档位落盘。没有它，崩在旁观者档里就再也回不去了。 */
    private static Path recordPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen_api").resolve("cam_follow.mode");
    }

    private static void writeRecord(GameType prev) {
        try {
            Path p = recordPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, prev.getName(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 落盘失败不该拦住牵引：档位还在内存里，本次会话仍能恢复
        }
    }

    private static GameType readRecord() {
        try {
            Path p = recordPath();
            if (!Files.exists(p)) {
                ownsMode = false;
                return null;
            }
            ownsMode = true;
            return GameType.valueOf(Files.readString(p, StandardCharsets.UTF_8).trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static void clearRecord() {
        try {
            Files.deleteIfExists(recordPath());
        } catch (Exception ignored) {
        }
    }

    /** 身体机位和牵引都要问一句：现在这个旁观者档是不是我们切的。 */
    public static boolean ownsSpectator() {
        return ownsMode;
    }

    /** 开牵引（或进身体机位）时调用：切旁观者并记下原档位。返回给状态行的一半说明。 */
    public static String ensureSpectator() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "还没进世界";
        }
        GameType cur = mc.gameMode.getPlayerMode();
        if (cur == GameType.SPECTATOR) {
            return "已在旁观者档";
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return "（联机服不动档位）";
        }
        writeRecord(cur);
        ownsMode = true;
        server.execute(() -> {
            ServerPlayer self = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self != null) {
                self.setGameMode(GameType.SPECTATOR);
            }
        });
        return "已切旁观者档（关牵引时恢复成 " + cur.getName() + "）";
    }

    /** 关牵引 / 退出身体机位时调用：回到进旁观者之前的档位。没有记录就什么都不做。 */
    public static String restoreMode() {
        GameType prev = readRecord();
        if (prev == null) {
            return "";
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            clearRecord();
            return "";
        }
        server.execute(() -> {
            ServerPlayer self = mc.player == null ? null : server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self != null && self.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                self.setGameMode(prev);
            }
        });
        clearRecord();
        ownsMode = false;
        return "，档位恢复成 " + prev.getName();
    }

    /**
     * 崩溃恢复：上次强杀进程时人还卡在旁观者档。
     *
     * <p>只在「有记录 且 当前确实是旁观者 且 牵引没开」时动手 —— 三条都满足才说明这个
     * 旁观者档是我们自己切的。当前不是旁观者就说明早就恢复了，直接删记录。
     */
    private static void recoverFromCrash(Minecraft mc) {
        if (crashChecked) {
            return;
        }
        crashChecked = true;
        GameType prev = readRecord();
        if (prev == null) {
            return;
        }
        if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            clearRecord();
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer self = mc.player == null ? null : server.getPlayerList().getPlayer(mc.player.getUUID());
            if (self != null) {
                self.setGameMode(prev);
            }
        });
        clearRecord();
        status = "上次没正常收尾，人还卡在旁观者档 —— 已自动恢复成 " + prev.getName() + "。";
    }

    /** 给 /cam status 用的一行摘要。 */
    public static Component describe() {
        return Component.literal(status + " ｜ 目标=" + (CameraRig.targetName().isEmpty()
                ? "(无)" : CameraRig.targetName()));
    }

    /** 让 /cam bind 顺手开牵引：绑定成功但没开牵引的话，多数人的意图就是要跟着。 */
    public static void autoEnableIfFreshlyBound(String previousTarget) {
        if (follow) {
            return;
        }
        String now = CameraRig.targetName();
        if (now.isEmpty() || now.equalsIgnoreCase(previousTarget)) {
            return;
        }
        setFollow(true);
    }
}