package com.dwinovo.numen.client.cam;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 相机机位状态机:决定"镜头该站在哪、看向哪",算法本体在 {@code MixinCamera}。
 *
 * <p><b>为什么自己算而不是用原版第三人称</b>:原版只有一种行为(背后固定 4 格),
 * 而跟拍要的是能换的追踪。原版那套的算法已经整个抄进 {@code MixinCamera}
 * (含 partial-tick 插值与 8角点碰撞拉近),这里只管策略层 —— 换模式就是换几行常数。
 *
 * <p><b>模式</b>:
 * <ul>
 *   <li>{@link Mode#SHOULDER} 越肩:贴在目标背后,back/up/shoulder 三轴可调</li>
 *   <li>{@link Mode#ORBIT}  轨道:世界方位角固定,目标转身镜头不跟(适合看它干活)</li>
 *   <li>{@link Mode#LOCK}   固定机位:镜头钉在世界某点只转头(适合长镜)</li>
 *   <li>{@link Mode#CINEMATIC} 电影:越肩 + 俯角偏置 + 跨帧阻尼(不抖)</li>
 * </ul>
 *
 * <p><b>为什么配置从文件读而不是按键/MCP</b>:相机是纯客户端的,而 MCP 工具跑在服务端侧;
 * 按键要走 RegisterKeyMappingsEvent 且会和 NumenKeys 现有的 G/R/Y/V 抢位。配置文件
 * 每 20 tick(1s)重读一次,外部写文件即可改机位 —— 调试迭代不用重启游戏。
 *
 * <p><b>目标不在客户端实体表里就退回原版</b>:客户端只持有渲染距离内的实体。
 * 目标走出范围时{@link #resolveTarget()} 返回 null,此时 mixin 不取消,
 * 原版相机照常工作。**绝不能拿 null 硬算** —— 那会让整帧渲染崩。
 */
public final class CameraRig {

    /** 追踪模式。{@link #OFF} 表示完全交还原版。 */
    public enum Mode {
        OFF, SHOULDER, ORBIT, LOCK, CINEMATIC
    }

    private static final Gson GSON = new Gson();
    private static final int RELOAD_TICKS = 20;

    // ── 策略参数（全部可被配置文件覆写）────────────────────────────────
    private static volatile Mode mode = Mode.OFF;
    private static volatile String targetName = "";
    /** SHOULDER/CINEMATIC:镜头在目标背后多少格。 */
    private static volatile float distance = 4.0f;
    /** 镜头比目标眼睛高多少格(负数=俯视)。 */
    private static volatile float height = 0.0f;
    /** 肩部横移:正值把镜头推到目标右手边(经典越肩构图)。 */
    private static volatile float shoulder = 0.6f;
    /** ORBIT:绕目标的方位角(度),相对世界坐标,不跟目标转身。 */
    private static volatile float bearing = 35.0f;
    /** CINEMATIC:额外俯角偏置(度)。 */
    private static volatile float pitchBias = -8.0f;
    /** LOCK:镜头钉住的世界坐标。 */
    private static volatile double lockX, lockY, lockZ;
    /** 目标不可见时是否保持上一帧机位(不跟随);false = 交还原版。 */
    private static volatile boolean holdWhenLost = true;

    // ── 跨帧状态 ────────────────────────────────────────────────────
    private static Entity target;
    private static Vec3 dampedPos;
    private static float dampedYaw = Float.NaN;
    private static float dampedPitch = Float.NaN;
    private static int tickCounter;
    private static long lastSeenMs;

    private CameraRig() {
    }

    public static Mode mode() {
        return mode;
    }

    public static float distance() {
        return distance;
    }

    public static float height() {
        return height;
    }

    public static float shoulder() {
        return shoulder;
    }

    public static float bearing() {
        return bearing;
    }

    public static float pitchBias() {
        return pitchBias;
    }

    public static double lockX() {
        return lockX;
    }

    public static double lockY() {
        return lockY;
    }

    public static double lockZ() {
        return lockZ;
    }

    public static boolean holdWhenLost() {
        return holdWhenLost;
    }

    public static String targetName() {
        return targetName;
    }

    /** 上一帧镜头位置,供 CINEMATIC 阻尼与调试取用。 */
    public static Vec3 dampedPosition() {
        return dampedPos;
    }

    public static boolean isActive() {
        return mode != Mode.OFF;
    }

    /**
     * 解析当前要跟拍的实体。
     *
     * <p><b>为什么不缓存</b>:{@code ClientNumenLookup.resolve} 的类注释写明是
     * 线性扫描、per-prompt 用,per-tick 会拖垮。这里每帧扫一次实体列表 ——
     * 实体表通常几十个,成本可忽略,换来"实体卸载/重生立刻反映",不会指向野实体。
     *
     * @return 目标实体;名字为空、找不到、或本地玩家自己时返回 null
     */
    public static Entity resolveTarget() {
        if (mode == Mode.OFF || targetName.isEmpty()) {
            target = null;
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            target = null;
            return null;
        }
        Entity found = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractClientPlayer p)) continue;
            if (mc.player.getUUID().equals(p.getUUID())) continue;   // 不能跟自己
            if (p.getGameProfile() != null
                    && targetName.equalsIgnoreCase(p.getGameProfile().getName())) {
                found = p;
                break;
            }
            if (targetName.equalsIgnoreCase(p.getName().getString())) {
                found = p;
                break;
            }
        }
        target = found;
        if (found != null) {
            lastSeenMs = System.currentTimeMillis();
        }
        return found;
    }

    public static Entity currentTarget() {
        return target;
    }

    /** 目标最后一次可见的时间戳(epoch ms);从未见过返回 0。 */
    public static long lastSeenMs() {
        return lastSeenMs;
    }

    /** 每 tick 调一次:按节奏重读配置文件。 */
    public static void tick() {
        if (++tickCounter < RELOAD_TICKS) return;
        tickCounter = 0;
        reload();
    }

    /** CINEMATIC 跨帧阻尼:朝目标机位收敛,避免逐帧硬切导致的跳变。 */
    public static void damp(Vec3 desired, float desiredYaw, float desiredPitch, float lambda) {
        if (dampedPos == null) {
            dampedPos = desired;
            dampedYaw = desiredYaw;
            dampedPitch = desiredPitch;
            return;
        }
        dampedPos = dampedPos.lerp(desired, lambda);
        dampedYaw = lerpAngle(dampedYaw, desiredYaw, lambda);
        dampedPitch = dampedPitch + (desiredPitch - dampedPitch) * lambda;
    }

    /** 最短路径角度插值(yaw 跨 ±180 时不能直接线性插,否则会绕远路甩镜头)。 */
    private static float lerpAngle(float from, float to, float t) {
        if (Float.isNaN(from)) return to;
        float diff = ((to - from + 540.0f) % 360.0f) - 180.0f;
        return from + diff * t;
    }

    /** 把当前实际机位记成阻尼起点(切模式/换目标时调,避免从旧机位飞过来)。 */
    public static void resetDamping() {
        dampedPos = null;
        dampedYaw = Float.NaN;
        dampedPitch = Float.NaN;
    }

    /** 配置���件路径。 */
    public static Path configPath() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath().resolve("config").resolve(Constants.MOD_ID).resolve("cam.json");
    }

    /**
     * 重读配置文件。
     *
     * <p>解析失败**不抛** —— 配置文件是外部手写的,一次手滑不该让游戏崩。
     * 保留上一份有效配置继续跑。
     */
    public static void reload() {
        Path p = configPath();
        if (!Files.isRegularFile(p)) return;
        JsonObject o;
        try {
            o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return;
        }
        try {
            if (o.has("mode")) {
                Mode m = Mode.valueOf(o.get("mode").getAsString().trim().toUpperCase());
                if (m != mode) {
                    resetDamping();
                }
                mode = m;
            }
            if (o.has("target")) targetName = o.get("target").getAsString().trim();
            if (o.has("distance")) distance = o.get("distance").getAsFloat();
            if (o.has("height")) height = o.get("height").getAsFloat();
            if (o.has("shoulder")) shoulder = o.get("shoulder").getAsFloat();
            if (o.has("bearing")) bearing = o.get("bearing").getAsFloat();
            if (o.has("pitchBias")) pitchBias = o.get("pitchBias").getAsFloat();
            if (o.has("holdWhenLost")) holdWhenLost = o.get("holdWhenLost").getAsBoolean();
            if (o.has("lock")) {
                JsonObject l = o.getAsJsonObject("lock");
                lockX = l.get("x").getAsDouble();
                lockY = l.get("y").getAsDouble();
                lockZ = l.get("z").getAsDouble();
            }
        } catch (RuntimeException e) {
            // 字段类型不对(比如 distance 写成字符串)——同样保留旧配置,别崩。
        }
    }

    /** 写一份当前配置回去(供外部工具改前备份)。 */
    public static void save() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("target", targetName);
        o.addProperty("distance", distance);
        o.addProperty("height", height);
        o.addProperty("shoulder", shoulder);
        o.addProperty("bearing", bearing);
        o.addProperty("pitchBias", pitchBias);
        o.addProperty("holdWhenLost", holdWhenLost);
        JsonObject l = new JsonObject();
        l.addProperty("x", lockX);
        l.addProperty("y", lockY);
        l.addProperty("z", lockZ);
        o.add("lock", l);
        Path p = configPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(o), StandardCharsets.UTF_8);
    }

    /** 该实体是不是当前本地玩家(用于避免把玩家自己当跟拍目标)。 */
    public static boolean isLocalPlayer(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return e instanceof Player && mc.player != null
                && mc.player.getUUID().equals(e.getUUID());
    }
}