package com.dwinovo.numen.plugins.camera.api;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;
import com.dwinovo.numen.plugins.camera.lens.Lens;
import com.dwinovo.numen.plugins.camera.rec.VideoRecorder;
import com.dwinovo.numen.plugins.camera.tether.CompanionTether;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 给外部 AI 的<b>相机控制口</b>：本机 127.0.0.1:8767 上的一小块 HTTP。
 *
 * <h2>为什么不是复用 MCP（8765）</h2>
 * MCP 那条链是「AI 跟游戏下任务」用的，走 Numen 客户端那一层，而且 camera 这个 jar
 * 从设计上<b>不依赖 Numen 任何类型</b>（要在专用服务端也能加载，见模块文档 §2）。
 * 直接挂进 MCP 就得反向依赖，代价比收益大。
 *
 * <p>所以这里只开一个回环端口，接口按 MCP 工具的语义设计（{@code cam_state} / {@code cam_shot}
 * / {@code cam_lens} / {@code cam_follow} / {@code cam_rec} / {@code cam_off}），
 * 并在 {@code /mcp} 上提供了一个够 MCP 客户端做 {@code initialize → tools/list → tools/call}
 * 的最小 JSON-RPC 壳。以后要真正并进 8765，只需在 MCP 那边加一层转发，工具名不用改。
 *
 * <h2>线程模型（这是本类唯一真正危险的地方）</h2>
 * {@code HttpServer} 的 handler 跑在<b>自己的线程池</b>上，<b>绝对不能</b>直接读
 * {@code Minecraft.getInstance()} 的字段或调 {@code CameraRig} —— 那是客户端线程的东西，
 * 跨线程读会拿到撕裂的状态，写会直接把渲染搞崩。所以：
 * <ul>
 *   <li>读（{@code /state}）：投一个任务进 {@link #PENDING}，等客户端 tick 执行完把结果填回来；</li>
 *   <li>写（{@code /shot} 等）：同样投递，<b>由客户端 tick 执行</b>，HTTP 线程只负责收和回。</li>
 * </ul>
 * 游戏暂停/未进世界时 tick 仍在跑，所以这条不会死锁；真等不到就返回 503，而不是挂住。
 *
 * <h2>安全</h2>
 * 只绑回环地址，没有 token —— 能连 127.0.0.1:8767 的本机进程都能开镜头。这台机器是单人
 * 实验环境，接受这个前提；如果哪天要放到共享机器上，必须加 token（参照 mcp_server.json）。
 */
public final class DirectorServer {

    public static final int DEFAULT_PORT = 8767;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final BlockingQueue<Runnable> PENDING = new LinkedBlockingQueue<>();
    private static final long CLIENT_TIMEOUT_MS = 3000;

    private static HttpServer server;
    private static ExecutorService pool;
    private static volatile int port = -1;

    private DirectorServer() {
    }

    public static String status() {
        return server == null ? "API 未启动（端口被占？看日志）" : "API 在 http://127.0.0.1:" + port;
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        int p = Integer.getInteger("numencam.api.port", DEFAULT_PORT);
        try {
            // 显式写 127.0.0.1：getLoopbackAddress() 在某些 JVM 参数下会返回 ::1，
            // 那样 curl 127.0.0.1 连不上，排查起来很像「服务没起」。
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 16);
            s.createContext("/state", ex -> handle(ex, "state"));
            s.createContext("/shot", ex -> handle(ex, "shot"));
            s.createContext("/lens", ex -> handle(ex, "lens"));
            s.createContext("/follow", ex -> handle(ex, "follow"));
            s.createContext("/rec", ex -> handle(ex, "rec"));
            s.createContext("/off", ex -> handle(ex, "off"));
            s.createContext("/mcp", ex -> handle(ex, "mcp"));
            pool = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "numencam-api");
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(pool);
            s.start();
            server = s;
            port = p;
            LOGGER.info("[numencam] 相机控制口已开：http://127.0.0.1:{}（GET /state, POST /shot|/lens|/follow|/rec|/off, /mcp）", p);
        } catch (Exception e) {
            LOGGER.error("[numencam] 相机控制口起不来（端口 {} 被占？）：{}", p, e.toString());
        }
    }

    public static synchronized void stopIfRunning() {
        if (server != null) {
            server.stop(0);
            server = null;
            port = -1;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        PENDING.clear();
    }

    // ── 每 tick（客户端线程）：把 HTTP 线程投进来的活干完 ─────────────────────

    public static void tick() {
        for (int i = 0; i < 64; i++) {
            Runnable r = PENDING.poll();
            if (r == null) {
                return;
            }
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.warn("[numencam] API 投递的任务失败：{}", t.toString());
            }
        }
    }

    private static <T> T onClient(Supplier<T> sup) {
        CompletableFuture<T> f = new CompletableFuture<>();
        PENDING.add(() -> {
            try {
                f.complete(sup.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(CLIENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("客户端线程没响应（未进世界或卡住了）", e);
        }
    }

    // ── HTTP 分发 ────────────────────────────────────────────────────────────

    private static void handle(HttpExchange ex, String kind) {
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out;
            switch (kind) {
                case "state" -> out = onClient(DirectorServer::stateJson).toString();
                case "shot" -> out = op(body, DirectorServer::applyShot);
                case "lens" -> out = op(body, DirectorServer::applyLens);
                case "follow" -> out = op(body, DirectorServer::applyFollow);
                case "rec" -> out = op(body, DirectorServer::applyRec);
                case "off" -> out = onClient(() -> ok(AllOff.run()));
                case "mcp" -> out = mcp(body);
                default -> out = err("未知端点");
            }
            respond(ex, 200, out);
        } catch (Exception e) {
            try {
                respond(ex, 503, err(e.getMessage() == null ? e.toString() : e.getMessage()));
            } catch (Exception ignored) {
                // 连接已经断了，没法回
            }
        } finally {
            ex.close();
        }
    }

    /** 解析 JSON 体 → 投递到客户端线程执行 → 包成统一回执。 */
    private static String op(String body, java.util.function.Function<JsonObject, String> fn) {
        JsonObject a = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        String msg = onClient(() -> fn.apply(a));
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("msg", msg);
        return o.toString();
    }

    // ── 具体操作（全部在客户端线程执行）────────────────────────────────────────

    private static String applyShot(JsonObject a) {
        StringBuilder sb = new StringBuilder();
        Minecraft mc = Minecraft.getInstance();
        if (a.has("target")) {
            sb.append(CameraRig.setTarget(a.get("target").getAsString())).append(' ');
        }
        if (a.has("mode")) {
            String m = a.get("mode").getAsString().toUpperCase(Locale.ROOT);
            try {
                sb.append(CameraRig.setMode(CameraRig.Mode.valueOf(m))).append(' ');
            } catch (IllegalArgumentException e) {
                return "未知机位 " + m + "（可选 OFF/SHOULDER/ORBIT/LOCK/CINEMATIC）";
            }
        }
        if (a.has("distance")) {
            sb.append(CameraRig.setDistance(a.get("distance").getAsFloat())).append(' ');
        }
        if (a.has("height")) {
            sb.append(CameraRig.setHeight(a.get("height").getAsFloat())).append(' ');
        }
        if (a.has("shoulder")) {
            sb.append(CameraRig.setShoulder(a.get("shoulder").getAsFloat())).append(' ');
        }
        if (a.has("bearing")) {
            sb.append(CameraRig.setBearing(a.get("bearing").getAsFloat())).append(' ');
        }
        if (a.has("pitch")) {
            sb.append(CameraRig.setPitchBias(a.get("pitch").getAsFloat())).append(' ');
        }
        if (a.has("hold")) {
            sb.append(CameraRig.setHoldWhenLost(a.get("hold").getAsBoolean())).append(' ');
        }
        if (a.has("fov") && mc.options != null) {
            int f = Math.max(30, Math.min(110, a.get("fov").getAsInt()));
            mc.options.fov().set(f);
            sb.append("FOV=").append(f).append(' ');
        }
        if (a.has("lock")) {
            JsonArray v = a.getAsJsonArray("lock");
            if (v.size() == 3) {
                sb.append(CameraRig.setLock(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble())).append(' ');
            }
        }
        return sb.length() == 0 ? "没有可应用的字段" : sb.toString().trim();
    }

    private static String applyLens(JsonObject a) {
        if (a.has("restore") && a.get("restore").getAsBoolean()) {
            Lens.restore();
            return "亮度/夜视已还原";
        }
        StringBuilder sb = new StringBuilder();
        if (a.has("gamma")) {
            sb.append(Lens.setGamma(a.get("gamma").getAsDouble())).append(' ');
        }
        if (a.has("nightVision")) {
            sb.append(Lens.setNightVision(a.get("nightVision").getAsBoolean())).append(' ');
        }
        return sb.length() == 0 ? Lens.status() : sb.toString().trim();
    }

    private static String applyFollow(JsonObject a) {
        StringBuilder sb = new StringBuilder();
        if (a.has("dist")) {
            sb.append(CompanionTether.setMaxDist(a.get("dist").getAsDouble())).append(' ');
        }
        if (a.has("on")) {
            sb.append(CompanionTether.setFollow(a.get("on").getAsBoolean())).append(' ');
        }
        return sb.length() == 0 ? CompanionTether.status() : sb.toString().trim();
    }

    private static String applyRec(JsonObject a) {
        if (a.has("status") && a.get("status").getAsBoolean()) {
            return VideoRecorder.status();
        }
        if (a.has("on")) {
            if (a.get("on").getAsBoolean()) {
                int fps = a.has("fps") ? Math.max(1, Math.min(30, a.get("fps").getAsInt())) : 20;
                return VideoRecorder.start(fps);
            }
            return VideoRecorder.stop();
        }
        return VideoRecorder.status();
    }

    // ── 状态回读 ──────────────────────────────────────────────────────────────

    private static JsonObject stateJson() {
        JsonObject o = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        o.addProperty("ok", true);
        o.addProperty("api", "127.0.0.1:" + port);
        boolean inWorld = mc.level != null && mc.player != null;
        o.addProperty("inWorld", inWorld);
        o.addProperty("mode", CameraRig.mode().name());
        o.addProperty("target", CameraRig.targetName());
        Entity t = CameraRig.currentTarget();
        o.addProperty("targetVisible", t != null);

        JsonObject cam = new JsonObject();
        Camera c = mc.gameRenderer.getMainCamera();
        Vec3 cp = c.getPosition();
        cam.addProperty("x", round(cp.x));
        cam.addProperty("y", round(cp.y));
        cam.addProperty("z", round(cp.z));
        cam.addProperty("yaw", round(c.getYRot()));
        cam.addProperty("pitch", round(c.getXRot()));
        o.add("camera", cam);

        if (t != null) {
            JsonObject tg = new JsonObject();
            tg.addProperty("name", t.getName().getString());
            tg.addProperty("x", round(t.getX()));
            tg.addProperty("y", round(t.getY()));
            tg.addProperty("z", round(t.getZ()));
            o.add("targetEntity", tg);
        }

        if (inWorld) {
            JsonObject body = new JsonObject();
            body.addProperty("x", round(mc.player.getX()));
            body.addProperty("y", round(mc.player.getY()));
            body.addProperty("z", round(mc.player.getZ()));
            body.addProperty("gamemode", mc.gameMode.getPlayerMode().getName());
            o.add("body", body);
            o.addProperty("gameTime", mc.level.getGameTime());
        }

        JsonObject lens = new JsonObject();
        lens.addProperty("gamma", round(Lens.gamma()));
        lens.addProperty("nightVision", Lens.nightVision());
        lens.addProperty("status", Lens.status());
        o.add("lens", lens);

        JsonObject rig = new JsonObject();
        rig.addProperty("distance", CameraRig.distance());
        rig.addProperty("height", CameraRig.height());
        rig.addProperty("shoulder", CameraRig.shoulder());
        rig.addProperty("bearing", CameraRig.bearing());
        rig.addProperty("pitchBias", CameraRig.pitchBias());
        o.add("rig", rig);

        JsonObject follow = new JsonObject();
        follow.addProperty("on", CompanionTether.follow());
        follow.addProperty("maxDist", CompanionTether.maxDist());
        follow.addProperty("status", CompanionTether.status());
        o.add("follow", follow);

        JsonObject rec = new JsonObject();
        rec.addProperty("recording", VideoRecorder.recording());
        rec.addProperty("status", VideoRecorder.status());
        o.add("rec", rec);

        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── MCP 兼容壳 ────────────────────────────────────────────────────────────

    private static final String TOOLS_JSON = """
            [
              {"name":"cam_state","description":"读当前镜头状态（机位/目标/相机位姿/机体档位/亮度/夜视/录像）","inputSchema":{"type":"object","properties":{}}},
              {"name":"cam_shot","description":"设置镜头：target/mode/distance/height/shoulder/bearing/pitch/hold/fov","inputSchema":{"type":"object","properties":{"target":{"type":"string"},"mode":{"type":"string"},"distance":{"type":"number"},"height":{"type":"number"},"shoulder":{"type":"number"},"bearing":{"type":"number"},"pitch":{"type":"number"},"hold":{"type":"boolean"},"fov":{"type":"integer"}}}},
              {"name":"cam_lens","description":"亮度(gamma 0..1)与夜视；restore=true 一键还原","inputSchema":{"type":"object","properties":{"gamma":{"type":"number"},"nightVision":{"type":"boolean"},"restore":{"type":"boolean"}}}},
              {"name":"cam_follow","description":"牵引开关与距离阈值（走出加载区自动静默补传）","inputSchema":{"type":"object","properties":{"on":{"type":"boolean"},"dist":{"type":"number"}}}},
              {"name":"cam_rec","description":"录像：on=true 开始（fps 1..30），on=false 停止，status=true 查状态","inputSchema":{"type":"object","properties":{"on":{"type":"boolean"},"fps":{"type":"integer"},"status":{"type":"boolean"}}}},
              {"name":"cam_off","description":"一键全关：机位/牵引/附身/飞行/亮度/夜视/录像全部收回","inputSchema":{"type":"object","properties":{}}}
            ]""";

    private static String mcp(String body) {
        JsonObject req = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonElement id = req.get("id");

        if ("notifications/initialized".equals(method) || method.startsWith("notifications/")) {
            return "";
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null && !id.isJsonNull()) {
            resp.add("id", id);
        }

        switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2024-11-05");
                JsonObject caps = new JsonObject();
                caps.add("tools", new JsonObject());
                result.add("capabilities", caps);
                JsonObject info = new JsonObject();
                info.addProperty("name", "numencam");
                info.addProperty("version", "0.1.3");
                result.add("serverInfo", info);
                resp.add("result", result);
            }
            case "tools/list" -> {
                JsonObject result = new JsonObject();
                result.add("tools", JsonParser.parseString(TOOLS_JSON));
                resp.add("result", result);
            }
            case "tools/call" -> {
                JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
                String name = params.has("name") ? params.get("name").getAsString() : "";
                JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
                String text;
                try {
                    text = callTool(name, args);
                } catch (Exception e) {
                    text = "调用失败：" + e.getMessage();
                }
                JsonObject result = new JsonObject();
                JsonArray content = new JsonArray();
                JsonObject item = new JsonObject();
                item.addProperty("type", "text");
                item.addProperty("text", text);
                content.add(item);
                result.add("content", content);
                result.addProperty("isError", false);
                resp.add("result", result);
            }
            default -> {
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "不支持的方法 " + method);
                resp.add("error", error);
            }
        }
        return resp.toString();
    }

    private static String callTool(String name, JsonObject args) {
        return switch (name) {
            case "cam_state" -> onClient(DirectorServer::stateJson).toString();
            case "cam_shot" -> onClient(() -> applyShot(args));
            case "cam_lens" -> onClient(() -> applyLens(args));
            case "cam_follow" -> onClient(() -> applyFollow(args));
            case "cam_rec" -> onClient(() -> applyRec(args));
            case "cam_off" -> onClient(() -> ok(AllOff.run()));
            default -> "未知工具 " + name;
        };
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private static String ok(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("msg", msg);
        return o.toString();
    }

    private static String err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("msg", msg);
        return o.toString();
    }

    private static void respond(HttpExchange ex, int code, String body) {
        try {
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (Exception e) {
            LOGGER.warn("[numencam] 回包失败：{}", e.toString());
        }
    }
}
