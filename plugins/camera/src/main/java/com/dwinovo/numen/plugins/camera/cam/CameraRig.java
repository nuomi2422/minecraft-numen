package com.dwinovo.numen.plugins.camera.cam;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 相机机位状态机：决定「镜头该站在哪、看向哪」，算法本体在 {@code MixinCamera}。
 *
 * <h2>为什么自己算而不是用原版第三人称</h2>
 * 原版只有一种行为（背后固定 4 格），而跟拍要的是能换的追踪。原版那套的算法已经整个
 * 抄进 {@code MixinCamera}（含 partial-tick 插值与 8 角点碰撞拉近），这里只管策略层 ——
 * 换模式就是换几行常数。
 *
 * <h2>模式</h2>
 * <ul>
 *   <li>{@link Mode#SHOULDER} 越肩：贴在目标背后，back/up/shoulder 三轴可调</li>
 *   <li>{@link Mode#ORBIT} 轨道：世界方位角固定，目标转身镜头不跟（适合看它干活）</li>
 *   <li>{@link Mode#LOCK} 固定机位：镜头钉在世界某点只转头（适合长镜）</li>
 *   <li>{@link Mode#CINEMATIC} 电影：越肩 + 俯角偏置 + 跨帧阻尼（不抖）</li>
 * </ul>
 *
 * <h2>为什么配置从文件读</h2>
 * 相机是纯客户端的，MCP 工具跑在服务端侧。配置文件每 20 tick（1s）重读一次，外部写文件
 * 即可改机位 —— 调试迭代不用重启游戏。游戏内指令（{@code /cam}）改的是同一份内存状态，
 * <b>并且会写回同一个文件</b>，所以两条路不会互相打架。
 *
 * <h2>目标不在客户端实体表里就退回原版</h2>
 * 客户端只持有渲染距离内的实体。目标走出范围时 {@link #resolveTarget()} 返回 null，
 * 此时 mixin 不取消，原版相机照常工作。<b>绝不能拿 null 硬算</b> —— 那会让整帧渲染崩。
 *
 * <h2>为什么 config 目录名还是 numen_api</h2>
 * 2026-10-04 从 {@code api/common} 的 client 源码集搬进本模块，但配置文件路径保持
 * {@code config/numen_api/cam.json} 不变 —— 换路径会让用户已有的机位配置当场失效，
 * 而换模块本来不该弄丢任何一份现场配置。
 */
public final class CameraRig {

    /** 本模块的日志：文件扳机没有「敲指令的人」，出结果只能靠日志和聊天栏两处留痕。 */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 本模块的 modId。 */
    public static final String MOD_ID = "numencam";

    /**
     * 配置目录名。<b>故意不是 {@link #MOD_ID}</b>：见类注释「为什么 config 目录名还是
     * numen_api」。
     */
    private static final String CONFIG_DIR = "numen_api";

    /** 追踪模式。{@link #OFF} 表示完全交还原版。 */
    public enum Mode {
        OFF, SHOULDER, ORBIT, LOCK, CINEMATIC,
        /**
         * 身体机位：<b>相机就是玩家自己</b>（原版第一人称），AI 去搬玩家这个身体。
         *
         * <p>和上面四种的本质区别：那些是「相机离开身体、单独算一个机位」，
         * 画面不是玩家眼睛看到的；BODY 不动相机，只动身体 —— 拍出来的和用户自己
         * 站在那里看到的一模一样，所以要「用我的身体直接拍」就用它。
         */
        BODY
    }

    private static final Gson GSON = new Gson();
    private static final int RELOAD_TICKS = 20;

    // ── 策略参数（全部可被配置文件或 /cam 指令覆写）────────────────────────────
    private static volatile Mode mode = Mode.OFF;
    private static volatile String targetName = "";
    /** SHOULDER/CINEMATIC：镜头在目标背后多少格。 */
    private static volatile float distance = 4.0f;
    /** 镜头比目标眼睛高多少格（负数=俯视）。 */
    private static volatile float height = 0.0f;
    /** 肩部横移：正值把镜头推到目标右手边（经典越肩构图）。 */
    private static volatile float shoulder = 0.6f;
    /** ORBIT：绕目标的方位角（度），相对世界坐标，不跟目标转身。 */
    private static volatile float bearing = 35.0f;
    /** CINEMATIC：额外俯角偏置（度）。 */
    private static volatile float pitchBias = -8.0f;
    /** LOCK：镜头钉住的世界坐标。 */
    private static volatile double lockX, lockY, lockZ;
    /** 目标不可见时是否保持上一帧机位（不跟随）；false = 交还原版。 */
    private static volatile boolean holdWhenLost = true;

    /**
     * 指令改过内存状态之后，有多少 tick 内<b>不</b>再从文件重读。
     *
     * <p>为什么需要：{@code /cam mode ORBIT} 刚把内存设成 ORBIT，1 秒后 {@link #tick()}
     * 重读 cam.json 又把它覆写回去 —— 用户看着「指令没生效」。指令同时会
     * {@link #saveQuietly()} 写回文件，所以这段时间内跳过重读不会丢配置，只是避免
     * 两份真相打架。
     */
    private static volatile int holdFileTicks;

    // ── 跨帧状态 ──────────────────────────────────────────────────────────
    private static Entity target;
    private static Vec3 dampedPos;
    private static float dampedYaw = Float.NaN;
    private static float dampedPitch = Float.NaN;
    private static int tickCounter;
    private static long lastSeenMs;

    /**
     * 客户端 tick 计数：<b>每 tick 必 +1</b>，与渲染帧数无关。
     *
     * <p>为什么非要单独一个计数器（2026-11-07）：机位算法跑在 {@code Camera#setup} 里，
     * <b>一渲染帧一次</b>（60~144 次/秒）；而本类里所有「缓变 / 滞留」常数都是按 tick 写的
     * （20 次/秒）。两者混用时 {@code OCC_HOLD_TICKS = 200} 本意是停 10 秒，在 60fps 下
     * 只停 3.3 秒、144fps 下只停 1.4 秒 —— 用来防闪的滞回等于没有，过树丛就反复切。
     * 所以缓变与滞留一律以本计数器为准，帧内只做插值。
     */
    private static volatile long clientTicks;

    /** 镜头距离每 tick 朝目标收敛的比例：0.35 ≈ 8 tick 走到 95%。见 {@link #smoothZoom}。 */
    private static final float ZOOM_LAMBDA_PER_TICK = 0.35f;

    /**
     * CINEMATIC 的阻尼强度（<b>每 tick</b>）。
     *
     * <p>0.35 ≈ 0.14 秒时间常数：目标以 4 格/秒 走时镜头落后约 0.6 格，肉眼看是「有重量」
     * 而不是「追不上」。旧版写 0.15 且按帧乘，60fps 下等于 0.06 秒——几乎等于没阻尼，
     * 所以 CINEMATIC 名存实亡。
     */
    public static final float CINEMATIC_LAMBDA = 0.35f;

    /** {@link #smoothZoom} 的「上一 tick / 本 tick」两个端点。 */
    private static float zoomPrev = Float.NaN;
    private static float zoomTarget = Float.NaN;
    private static long zoomTick = Long.MIN_VALUE;

    /** {@link #damp} 的「上一 tick / 本 tick」两个端点（位移与朝向各一套）。 */
    private static Vec3 dampPrevPos;
    private static Vec3 dampTargetPos;
    private static float dampPrevYaw = Float.NaN;
    private static float dampTargetYaw = Float.NaN;
    private static float dampPrevPitch = Float.NaN;
    private static float dampTargetPitch = Float.NaN;
    private static long dampTick = Long.MIN_VALUE;

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

    /** 上一帧镜头位置，供 CINEMATIC 阻尼与调试取用。 */
    public static Vec3 dampedPosition() {
        return dampedPos;
    }

    public static boolean isActive() {
        return mode != Mode.OFF;
    }

    /**
     * 解析当前要跟拍的实体。
     *
     * <p><b>为什么不缓存</b>：实体表通常几十个，每帧线性扫一次成本可忽略，换来
     * 「实体卸载/重生立刻反映」，不会指向野实体。
     *
     * @return 目标实体；名字为空、找不到、或本地玩家自己时返回 null
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

    /** 目标最后一次可见的时间戳（epoch ms）；从未见过返回 0。 */
    public static long lastSeenMs() {
        return lastSeenMs;
    }

    /** 每 tick 调一次：按节奏重读配置文件（指令刚改过的那一小段时间内跳过）。 */
    public static void tick() {
        // ★ 必须在两个提前 return 之前：clientTicks 是「缓变/滞留按 tick 推进」的唯一依据，
        //   漏掉任何一个 tick 都会让 10 秒滞留缩水。
        clientTicks++;
        if (holdFileTicks > 0) {
            holdFileTicks--;
            return;
        }
        if (++tickCounter < RELOAD_TICKS) return;
        tickCounter = 0;
        reload();
    }

    /**
     * 跨帧阻尼：朝目标机位收敛，避免逐帧硬切导致的跳变。
     *
     * <p><b>lambda 是「每 tick」的比例，不是每帧</b>（2026-11-07 改）。旧版每个渲染帧乘一次，
     * 于是同一个 0.15 在 60fps 下等于 0.06 秒收敛、30fps 下 0.22 秒 ——「重」的程度跟着
     * 帧率变。现在每 tick 推进一步，帧内用 {@code pt} 在上下两个 tick 端点之间插值，
     * 所以画面既平滑又与帧率无关。
     *
     * <p>{@code lambda >= 1} 表示<b>不做阻尼</b>：原样透传、一帧都不许延迟。越肩/轨道
     * 要贴着目标，多插一 tick 就是 50ms 拖影。
     */
    public static void damp(Vec3 desired, float desiredYaw, float desiredPitch, float lambda, float pt) {
        if (lambda >= 1f) {
            dampedPos = desired;
            dampedYaw = desiredYaw;
            dampedPitch = desiredPitch;
            return;
        }
        long now = clientTicks;
        if (dampTargetPos == null || now != dampTick) {
            dampTick = now;
            if (dampTargetPos == null || Float.isNaN(dampPrevYaw)) {
                dampPrevPos = desired;
                dampPrevYaw = desiredYaw;
                dampPrevPitch = desiredPitch;
                dampTargetPos = desired;
                dampTargetYaw = desiredYaw;
                dampTargetPitch = desiredPitch;
            } else {
                dampPrevPos = dampTargetPos;
                dampPrevYaw = dampTargetYaw;
                dampPrevPitch = dampTargetPitch;
                dampTargetPos = dampTargetPos.lerp(desired, lambda);
                dampTargetYaw = lerpAngle(dampTargetYaw, desiredYaw, lambda);
                dampTargetPitch = dampTargetPitch + (desiredPitch - dampTargetPitch) * lambda;
            }
        }
        dampedPos = dampPrevPos.lerp(dampTargetPos, pt);
        dampedYaw = lerpAngle(dampPrevYaw, dampTargetYaw, pt);
        dampedPitch = dampPrevPitch + (dampTargetPitch - dampPrevPitch) * pt;
    }

    /** 阻尼后的朝向。CINEMATIC 必须把它设回相机，否则「转身阻尼」只是算了个寂寞。 */
    public static float dampedYaw() {
        return dampedYaw;
    }

    public static float dampedPitch() {
        return dampedPitch;
    }

    /**
     * 镜头离目标眼睛多近就隐藏目标模型。
     *
     * <p><b>为什么需要</b>（2026-11-08 主人反馈"第一人称脸贴脸、镜头合进头里"）：遮挡切
     * 第一人称时 {@code applyOcclusion} 把机位压到眼睛位置，但<b>同伴的模型照样在渲染</b> ——
     * 于是镜头等于嵌在头骨里，看到的是头颅内壁和头发。MC 只对本地玩家自动隐藏模型，对
     * 别的实体不管，所以必须自己在渲染层跳过。
     *
     * <p><b>0.8 格</b>：比眼高还近就藏、退开立刻恢复。绝不用隐身药水 —— 那会改世界状态。
     */
    public static final float HIDE_MODEL_DISTANCE = 0.8f;

    /** 这一帧该不该跳过该实体的模型渲染（只针对当前跟拍目标，别误伤别人）。 */
    public static boolean shouldHideModel(Entity e) {
        if (e == null || !isActive() || e != target) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) {
            return false;
        }
        return mc.gameRenderer.getMainCamera().getPosition()
                .distanceTo(e.getEyePosition(1.0f)) < HIDE_MODEL_DISTANCE;
    }

    /** 供相机侧做「第三人称朝向 → 目标视线」混合用，跨 ±180 也走最短路。 */
    public static float lerpYaw(float from, float to, float t) {
        return lerpAngle(from, to, t);
    }

    /** 最短路径角度插值（yaw 跨 ±180 时不能直接线性插，否则会绕远路甩镜头）。 */
    private static float lerpAngle(float from, float to, float t) {
        if (Float.isNaN(from)) return to;
        float diff = ((to - from + 540.0f) % 360.0f) - 180.0f;
        return from + diff * t;
    }

    /** 把当前实际机位记成阻尼起点（切模式/换目标时调，避免从旧机位飞过来）。 */
    public static void resetDamping() {
        dampedPos = null;
        dampedYaw = Float.NaN;
        dampedPitch = Float.NaN;
        dampPrevPos = null;
        dampTargetPos = null;
        dampPrevYaw = Float.NaN;
        dampTargetYaw = Float.NaN;
        dampPrevPitch = Float.NaN;
        dampTargetPitch = Float.NaN;
        dampTick = Long.MIN_VALUE;
        resetZoom();
    }

    /**
     * 把「这一帧想要的镜头距离」缓变（约 8 tick 收敛），再按 partialTick 插值输出。
     *
     * <p>为什么必须有它（2026-11-07）：穿墙拉近（{@code numencam$maxZoom} 的 8 角点射线）
     * 和视线裁剪（{@code numencam$keepLineOfSight}）都是<b>逐帧重算的世界查询</b>。目标在
     * 树丛/墙角附近走动时，命中距离会一帧一个值、一帧跳好几格，镜头就被硬拽过去再硬弹
     * 回来 —— 看着像"抖过去抖过来"，其实是<b>距离在跳</b>，不是相机在晃。
     *
     * <p>040 施工单 §3.3 要求「与上一帧做 8 tick 线性过渡」，2026-11-07 这轮才真正落地。
     * 按 tick 而不是按帧推进：按帧的话 144fps 下过渡只剩 0.055 秒，等于没过渡。
     */
    public static float smoothZoom(float desired, float pt) {
        if (desired < 0.3f) {
            desired = 0.3f;   // 别把镜头塞进目标眼睛
        }
        long now = clientTicks;
        if (Float.isNaN(zoomTarget)) {
            zoomPrev = desired;
            zoomTarget = desired;
            zoomTick = now;
        } else if (now != zoomTick) {
            zoomTick = now;
            zoomPrev = zoomTarget;
            zoomTarget = zoomTarget + (desired - zoomTarget) * ZOOM_LAMBDA_PER_TICK;
        }
        return Mth.lerp(pt, zoomPrev, zoomTarget);
    }

    /** 切模式/换目标时丢掉距离缓变，免得从上一场的位置飘过来。 */
    public static void resetZoom() {
        zoomPrev = Float.NaN;
        zoomTarget = Float.NaN;
        zoomTick = Long.MIN_VALUE;
    }

    /** 配置文件路径。 */
    public static Path configPath() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath().resolve("config").resolve(CONFIG_DIR).resolve("cam.json");
    }

    /**
     * 配置文件里的「附身请求」。语义是<b>扳机</b>而不是状态：
     * <b>值一变就执行一次</b>（非空 → 附身，空 → 退出），之后不再重复执行。
     *
     * <h2>为什么要有这条路（2026-10-19 实测逼出来的）</h2>
     * 本来附身只能敲 {@code /cam possess}。但在这台机器上**键盘注入完全进不了游戏**：
     * {@code SendKeys} / {@code keybd_event} / 扫描码级 {@code SendInput} 三种都试过，
     * {@code GetForegroundWindow()} 明明返回 MC 窗口，按键就是不生效（推测是远程会话/GLFW 独占输入）。
     * 于是「让 AI 自己附身」这条路是断的。
     *
     * <p>改成文件扳机之后：<b>改 cam.json 就等于下指令</b>，不需要任何键鼠注入 ——
     * 这也正好符合用户的原话「指令都不用，直接附身」。20 tick 热载，1 秒内生效。
     */
    private static volatile String possessRequest = "";

    /** 首次读文件时只记录、不执行 —— 否则每次进世界都会自动附身（很吓人）。 */
    private static volatile boolean possessArmed;

    public static String possessRequest() {
        return possessRequest;
    }

    /**
     * 重读配置文件。
     *
     * <p>解析失败<b>不抛</b> —— 配置文件是外部手写的，一次手滑不该让游戏崩。
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
            if (o.has("possess")) {
                String want = o.get("possess").getAsString().trim();
                boolean changed = !want.equals(possessRequest);
                possessRequest = want;
                if (possessArmed && changed) {
                    // 值变了才动手：文件扳机不能变成「每 20 tick 重试一次」的刷屏源。
                    String msg = want.isEmpty()
                            ? com.dwinovo.numen.plugins.camera.possess.Possession.release()
                            : com.dwinovo.numen.plugins.camera.possess.Possession.possess(want);
                    LOGGER.info("[numencam] cam.json possess={} -> {}", want.isEmpty() ? "(空)" : want, msg);
                    // 也打到聊天栏：文件扳机是「没人敲指令」的路径，回执必须自己找地方说。
                    com.dwinovo.numen.plugins.camera.possess.Possession.announce("cam.json possess=" + (want.isEmpty() ? "(空)" : want));
                }
            }
            possessArmed = true;
        } catch (RuntimeException e) {
            // 字段类型不对（比如 distance 写成字符串）——同样保留旧配置，别崩。
        }
    }

    /** 写一份当前配置回去。 */
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
        // 把扳机原样写回：否则随便一条 /cam 指令落盘就会把它抹掉，外面就再也「改文件即附身」不了。
        if (possessRequest != null && !possessRequest.isEmpty()) {
            o.addProperty("possess", possessRequest);
        }
        JsonObject l = new JsonObject();
        l.addProperty("x", lockX);
        l.addProperty("y", lockY);
        l.addProperty("z", lockZ);
        o.add("lock", l);
        Path p = configPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(o), StandardCharsets.UTF_8);
    }

    /** 该实体是不是当前本地玩家（用于避免把玩家自己当跟拍目标）。 */
    public static boolean isLocalPlayer(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return e instanceof Player && mc.player != null
                && mc.player.getUUID().equals(e.getUUID());
    }

    // ── /cam 指令用的写入口 ───────────────────────────────────────────────────
    //
    // 每个 setter 都做三件事：改内存 → 打断文件重读若干 tick → 写回 cam.json。
    // 「写回」是必须的：否则下次游戏启动时又读回旧值，用户会以为指令只是临时生效。

    private static void poke() {
        holdFileTicks = 2 * RELOAD_TICKS;
        tickCounter = 0;
    }

    /** 改完内存后落盘；落盘失败只记进返回值，不抛 —— 指令不该因为写不了文件就崩。 */
    private static String persist(String what) {
        try {
            save();
            return what;
        } catch (IOException | RuntimeException e) {
            return what + "（⚠ 写不回 cam.json：" + e.getClass().getSimpleName()
                    + " — 重启后会丢）";
        }
    }

    public static String setMode(Mode m) {
        if (m != mode) resetDamping();
        mode = m;
        return persist("机位模式 = " + m.name());
    }

    public static String setTarget(String name) {
        String n = name == null ? "" : name.trim();
        if (n.equalsIgnoreCase(targetName) && !n.isEmpty()) {
            return "已经盯着 " + n + " 了。";
        }
        targetName = n;
        resetDamping();
        // 顺带从 ORBIT 切起来：绑定了目标却停在 OFF 的话，看上去像「绑定没生效」。
        return persist(n.isEmpty() ? "解除了绑定。" : "绑定目标 = " + n
                + (mode == Mode.OFF ? "（顺手把模式从 OFF 切成 ORBIT，否则看不出效果）" : ""));
    }

    /** 绑定后如果模式还是 OFF，切成 ORBIT。 */
    public static void ensureActiveMode() {
        if (mode == Mode.OFF) {
            mode = Mode.ORBIT;
            resetDamping();
            poke();
        }
    }

    public static String setDistance(float v) {
        distance = clamp(v, 0.5f, 32.0f);
        return persist(String.format("距离 = %.2f", distance));
    }

    public static String setHeight(float v) {
        height = clamp(v, -16.0f, 32.0f);
        return persist(String.format("高度 = %.2f", height));
    }

    public static String setShoulder(float v) {
        shoulder = clamp(v, -8.0f, 8.0f);
        return persist(String.format("肩移 = %.2f", shoulder));
    }

    public static String setBearing(float v) {
        bearing = v;
        return persist(String.format("方位角 = %.1f°", bearing));
    }

    public static String setPitchBias(float v) {
        pitchBias = clamp(v, -90.0f, 90.0f);
        return persist(String.format("俯角偏置 = %.1f°", pitchBias));
    }

    public static String setHoldWhenLost(boolean v) {
        holdWhenLost = v;
        return persist("目标走丢时保持机位 = " + v);
    }

    /** 钉住机位。不传坐标就钉在本地玩家当前站的地方。 */
    /** 2026-11-08: follow tracking re-anchor; no file write, no mode flip. */
    public static void reanchorLock(double x, double y, double z) {
        lockX = x;
        lockY = y;
        lockZ = z;
    }

    public static String setLock(double x, double y, double z) {
        lockX = x;
        lockY = y;
        lockZ = z;
        if (mode != Mode.LOCK) {
            mode = Mode.LOCK;
            resetDamping();
        }
        return persist(String.format("机位已钉在 %.1f, %.1f, %.1f（模式 = LOCK）", x, y, z));
    }

    /** 立刻重读文件（外部脚本改完 cam.json 想马上生效时用）。 */
    public static String forceReload() {
        poke();
        reload();
        return "已重读 " + configPath() + "（mode=" + mode.name() + " target=" + targetName + "）";
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── 遮挡 → 第一人称（2026-11-05）────────────────────────────────────────
    //
    // 用户反馈：被墙挡住时镜头只能「收近」，墙一让开又「弹回去」，看着像抖动。
    // 现在改成有滞回的状态机：被挡就压到眼睛（第一人称），至少停 10 秒；
    // 视线恢复后 2 秒内缓缓拉回第三人称。
    //
    // 为什么需要 10 秒滞留：树丛/墙角里射线会一两帧就 MISS，没有滞留就会来回闪。

    /**
     * 遮挡混合系数：0 = 第三人称，1 = 完全第一人称。
     *
     * <p>三个值分工（2026-11-07）：{@link #occBlend} 是<b>上一 tick</b> 的值、
     * {@link #occBlendTarget} 是<b>本 tick</b> 该到的值，两者之间按 partialTick 插值得到
     * {@link #occBlendNow} 输出给相机。<b>状态只在 tick 上推进</b>，帧内只插值 ——
     * 否则 60fps 下「停 10 秒」会缩成 3.3 秒。
     */
    private static volatile float occBlend;
    private static volatile float occBlendTarget;
    private static volatile float occBlendNow;
    /** 状态机上一次推进发生在哪个 tick。 */
    private static long occTick = Long.MIN_VALUE;
    /** 还要保持第一人称多少 tick（用户要求至少 10 秒 = 200 tick）。 */
    private static int occHoldTicks;

    private static final int OCC_HOLD_TICKS = 200;
    /** 切进去用 5 tick（0.25s）——要快，否则「被挡住」的那一刻画面还是糊的。 */
    private static final int OCC_IN_TICKS = 5;
    /** 缓出来用 40 tick（2s）——要慢，这是用户要的「缓慢变回第三人称」。 */
    private static final int OCC_OUT_TICKS = 40;

    /** 本帧的混合系数（已按 partialTick 插值）。 */
    public static float occBlend() {
        return occBlendNow;
    }

    /** 关掉遮挡切换时把状态归零，别把上一次的混合系数留在下一次机位里。 */
    public static void resetOcclusion() {
        occBlend = 0f;
        occBlendTarget = 0f;
        occBlendNow = 0f;
        occHoldTicks = 0;
        occTick = Long.MIN_VALUE;
    }

    /**
     * 把「第三人称机位」按遮挡情况压向眼睛。
     *
     * @param level  当前世界（mixin 里 shadow 的 BlockGetter）
     * @param target 被拍的目标
     * @param third  本该站的位置（模式算完的结果）
     * @param eye    目标眼睛位置
     * @return 这一帧实际该站的位置
     */
    public static Vec3 applyOcclusion(BlockGetter level, Entity target, Vec3 third, Vec3 eye, float pt) {
        if (level == null || target == null || third == null || eye == null || !isActive()) {
            resetOcclusion();
            return third;
        }
        Vec3 dir = third.subtract(eye);
        double len = dir.length();
        boolean blocked = false;
        if (len > 0.6) {
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, third, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, target));
            // 命中点比目标位置近 0.6 格以上才算「真被挡」——贴墙时的擦边命中不算，
            // 否则站在墙边会一直切第一人称。
            blocked = hit.getType() != HitResult.Type.MISS
                    && hit.getLocation().distanceTo(eye) < len - 0.6;
        }
        // ★ 状态机每 tick 只推进一步（2026-11-07）。本方法是「每渲染帧」调一次的，
        //   旧版直接在这里递减 occHoldTicks，于是 200 tick 的「10 秒滞留」在 60fps 下
        //   只剩 3.3 秒、144fps 下只剩 1.4 秒 —— 防闪的滞回失效，过树丛就来回切。
        long now = clientTicks;
        if (now != occTick) {
            occTick = now;
            occBlend = occBlendTarget;          // 上一 tick 的目标成为这一 tick 的起点
            if (blocked) {
                occHoldTicks = OCC_HOLD_TICKS;
                occBlendTarget = Math.min(1f, occBlendTarget + 1f / OCC_IN_TICKS);
            } else if (occHoldTicks > 0) {
                occHoldTicks--;
            } else if (occBlendTarget > 0f) {
                occBlendTarget = Math.max(0f, occBlendTarget - 1f / OCC_OUT_TICKS);
            }
        }
        occBlendNow = Mth.lerp(pt, occBlend, occBlendTarget);
        if (occBlendNow <= 0f) {
            return third;
        }
        return third.lerp(eye, occBlendNow);
    }
}