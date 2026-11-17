package com.dwinovo.numen.acx.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.core.AcxLoader;
import com.dwinovo.numen.acx.core.AcxRunner;
import com.dwinovo.numen.acx.core.PortToolAdapter;

/**
 * 临时"挖三填一"应急 AC 的红绿 + 反例（真 Loader + 真 Runner + 假世界）。
 *
 * <p><b>为什么不写假的执行器</b>：这条 AC 的全部价值都在"人在坑外绝不许封空腔"这条不变量上，
 * 而它是由 {@link AcxLoader}（strictFields 的字段校验）、{@link PortToolAdapter}
 * （真实 schema 校验）和 {@link AcxRunner}（guard 的 on_fail=fail / for / if 语义）
 * 三方共同保证的。拿手写执行器去测只会测到我自己的假设，所以这里只用真实的
 * 加载/执行链，假的只有世界。</p>
 *
 * <p><b>四个用例</b>（前三个是红/绿对照，第四个是旧草稿缺陷的专项反例）：</p>
 * <ol>
 *   <li>正常：落到底、封上盖、四壁本来就实心 → SUCCESS，且墙不会被无谓换成泥土。</li>
 *   <li>挖不动（清空被静默吞掉，身体留在原地）→ FAIL 在 guard_in_pit，
 *       <b>一块泥土都不许放下</b>（封空腔从未发生）。</li>
 *   <li>盖不住（放置被静默吞掉）→ FAIL 在 guard_cap，补缝一轮都不许跑。</li>
 *   <li>深度对但<b>不是同一列</b>（身体被挤到隔壁一格）→ 仍然 FAIL。
 *       这正是旧草稿"只比 Y 变小"会误判通过的那条路径。</li>
 * </ol>
 */
class Dig3Seal1EmergencyAcTest {

    /** 版本化源文件；Gradle 的 test 工作目录就是 plugins/acx。 */
    private static final Path SCRIPT = Path.of("scripts", "stable", "dig3_seal1_emergency.ac");

    // ── 假世界 ────────────────────────────────────────────────────────

    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String AIR = "minecraft:air";

    private static final class World {
        final Map<String, String> override = new LinkedHashMap<>();
        /** 脚所在列（她自己脚下那一格是 63；落到坑底后脚在 61）。 */
        int x0 = 8;
        int z0 = 8;
        double x = 8.5;
        double y = 64.0;
        double z = 8.5;
        /** 清空是否真的生效（false = 任务回"完成"但世界没变）。 */
        boolean digWorks = true;
        /** 放置是否真的生效（false = 封盖/补缝静默失败）。 */
        boolean placeWorks = true;
        /** 落地时被挤开多少格（模拟"深度对但不是同一列"）。 */
        double driftX = 0;
        boolean drifted = false;
        int buildCalls = 0;
        /** 带 in_place 锚点且身体确实在锚点上的 build 次数（闸门真的核过一遍）。 */
        int anchoredCalls = 0;
        /** 第一单 build 回"受理"而不是"完成"（模拟后台任务在 PAUSED 期间跑完）。 */
        boolean asyncDig = false;

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        String block(int x, int y, int z) {
            String v = override.get(key(x, y, z));
            if (v != null) {
                return v;
            }
            return y <= 63 ? STONE : AIR;
        }

        void set(int x, int y, int z, String b) {
            override.put(key(x, y, z), b);
        }

        int ix() {
            return (int) Math.floor(x);
        }

        int iy() {
            return (int) Math.floor(y);
        }

        int iz() {
            return (int) Math.floor(z);
        }

        /** 重力：脚下方是空气就往下掉（最多 8 格，防呆）。 */
        void settle() {
            int guard = 0;
            while (AIR.equals(block(ix(), iy() - 1, iz())) && guard++ < 8) {
                y -= 1.0;
            }
        }

        boolean hasDirt() {
            return override.containsValue(DIRT);
        }
    }

    // ── 三个真形状的端口替身 ──────────────────────────────────────────

    private static final class SelfStatus implements AcxToolPort {
        private final World w;

        SelfStatus(World w) {
            this.w = w;
        }

        @Override
        public String name() {
            return "get_self_status";
        }

        @Override
        public String description() {
            return "fake get_self_status";
        }

        @Override
        public AcxPortSchema schema() {
            return AcxPortSchema.builder().allowUnknown(false)
                    .output("position.x", "position.y", "position.z",
                            "on_ground", "in_water", "in_lava", "air")
                    .build();
        }

        @Override
        public Result invoke(Map<String, Object> params) {
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", w.x);
            pos.put("y", w.y);
            pos.put("z", w.z);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("position", pos);
            data.put("on_ground", true);
            data.put("in_water", false);
            data.put("in_lava", false);
            data.put("air", "300/300 ticks");
            return Result.completed(data, "ok");
        }
    }

    private static final class Inspect implements AcxToolPort {
        private final World w;

        Inspect(World w) {
            this.w = w;
        }

        @Override
        public String name() {
            return "inspect_block";
        }

        @Override
        public String description() {
            return "fake inspect_block";
        }

        @Override
        public AcxPortSchema schema() {
            return AcxPortSchema.builder().allowUnknown(false)
                    .param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER))
                    .param("y", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER))
                    .param("z", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER))
                    .output("block", "is_air", "is_solid", "is_liquid", "unbreakable")
                    .build();
        }

        @Override
        public Result invoke(Map<String, Object> params) {
            int x = num(params.get("x"));
            int y = num(params.get("y"));
            int z = num(params.get("z"));
            String b = w.block(x, y, z);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("x", x);
            data.put("y", y);
            data.put("z", z);
            data.put("block", b);
            data.put("is_air", AIR.equals(b));
            data.put("is_solid", !AIR.equals(b));
            data.put("is_liquid", false);
            data.put("unbreakable", false);
            return Result.completed(data, "ok");
        }
    }

    private static final class Build implements AcxToolPort {
        private final World w;

        Build(World w) {
            this.w = w;
        }

        @Override
        public String name() {
            return "build";
        }

        @Override
        public String description() {
            return "fake build";
        }

        @Override
        public AcxPortSchema schema() {
            return AcxPortSchema.builder().allowUnknown(false)
                    .param("ops", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING_ARRAY))
                    .param("replace_existing", AcxPortSchema.Param.opt(AcxPortSchema.Type.BOOLEAN))
                    .param("in_place", AcxPortSchema.Param.opt(AcxPortSchema.Type.OBJECT))
                    .build();
        }

        @Override
        public Result invoke(Map<String, Object> params) {
            w.buildCalls++;
            // 镜像真实 build 的 in_place 闸门：身体不在锚点就整单拒绝、一格都不放
            Object poseRaw = params.get("in_place");
            if (poseRaw instanceof Map<?, ?> pose) {
                int ax = num(pose.get("x"));
                int az = num(pose.get("z"));
                int feet = num(pose.get("feet_y"));
                boolean anchored = w.ix() == ax && w.iz() == az && Math.abs(w.y - feet) <= 0.2;
                if (!anchored) {
                    return Result.failed("in_place anchor broken（身体不在锚点坑里）");
                }
                w.anchoredCalls++;
            }
            boolean asyncNow = w.asyncDig && w.buildCalls == 1;
            Object raw = params.get("ops");
            if (!(raw instanceof List<?> ops)) {
                return Result.failed("ops 必须是数组");
            }
            for (Object o : ops) {
                if (!(o instanceof Map<?, ?> op)) {
                    continue;
                }
                String blockId = String.valueOf(op.get("block_id"));
                int x = num(op.get("x"));
                int y = num(op.get("y"));
                int z = num(op.get("z"));
                if (AIR.equals(blockId)) {
                    if (!w.digWorks) {
                        continue;   // 静默吞掉：回执说完成，世界没变
                    }
                    w.set(x, y, z, AIR);
                    if (y == w.iy() - 1) {
                        w.settle();
                    }
                } else {
                    if (!w.placeWorks) {
                        continue;   // 同上
                    }
                    w.set(x, y, z, blockId);
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("placed", ops.size());
            if (asyncNow) {
                // 受理 ≠ 完成：世界在 PAUSED 期间被后台任务改完了，但这一步的回执只是"已受理"
                return Result.accepted("t-dig", data, "accepted");
            }
            return Result.completed(data, "ok");
        }
    }

    private static final class Goto implements AcxToolPort {
        private final World w;

        Goto(World w) {
            this.w = w;
        }

        @Override
        public String name() {
            return "goto";
        }

        @Override
        public String description() {
            return "fake goto";
        }

        @Override
        public AcxPortSchema schema() {
            return AcxPortSchema.builder().allowUnknown(false)
                    .param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable())
                    .param("y", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable())
                    .param("z", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable())
                    .param("block", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING))
                    .param("may_alter_terrain", AcxPortSchema.Param.opt(AcxPortSchema.Type.BOOLEAN))
                    .build();
        }

        @Override
        public Result invoke(Map<String, Object> params) {
            double tx = dbl(params.get("x"));
            double ty = dbl(params.get("y"));
            double tz = dbl(params.get("z"));
            if (!AIR.equals(w.block((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz)))) {
                return Result.failed("no path（目标格不是空的，走不进去）");
            }
            w.x = tx;
            w.y = ty;
            w.z = tz;
            w.settle();
            if (!w.drifted && w.driftX != 0) {
                // 落到坑底之后被挤开一格：深度对、列不对 —— 旧草稿只看 Y 会判它通过
                w.x += w.driftX;
                w.drifted = true;
            }
            return Result.completed(new LinkedHashMap<>(), "ok");
        }
    }

    private static int num(Object v) {
        return (int) Math.round(dbl(v));
    }

    private static double dbl(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
    }

    // ── 用例 ──────────────────────────────────────────────────────────

    @Test
    void loadsThroughTheRealLoaderWithStrictFields() throws Exception {
        Fake.Registry reg = registry(new World());
        AcxLoader.LoadReport report = load(reg);
        assertTrue(report.errors().isEmpty(), "真 loader 不该报错: " + report.errors());
        assertTrue(report.warnings().isEmpty(),
                "strictFields 下 warning 也会变错误来源，这里必须零 warning: " + report.warnings());
        assertTrue(report.registered().containsKey("dig3_seal1_emergency"),
                "AC 没注册成功: " + report.summary());
    }

    @Test
    void happyPathLandsInPitCapsItAndLeavesWallsAlone() throws Exception {
        World w = new World();
        AcxRunRecord rec = run(w);

        assertEquals(AcxStatus.SUCCESS, rec.status(), "正常路径应当成功: " + rec.errorMessage());
        assertEquals(61.0, w.y, 0.001, "脚必须真的落到 y0-3");
        assertEquals(DIRT, w.block(8, 63, 8), "封盖格必须是泥土");
        assertEquals(AIR, w.block(8, 62, 8), "头所在格必须还是空的");
        assertEquals(AIR, w.block(8, 61, 8), "脚所在格必须还是空的");
        // 四壁本来就实心 → 不许被无谓换成泥土（补缝只在缺口处动手）
        assertEquals(STONE, w.block(7, 62, 8));
        assertEquals(STONE, w.block(9, 62, 8));
        assertEquals(STONE, w.block(8, 61, 7));
        assertEquals(STONE, w.block(8, 61, 9));
        assertEquals(STONE, w.block(7, 61, 8));
        assertEquals(STONE, w.block(8, 62, 9));
        // 三次 build（挖/补/封）都必须带 in_place 锚点，且此刻身体确实在锚点上
        assertEquals(w.buildCalls, w.anchoredCalls,
                "每一单 build 都必须就地锚定并通过身体位置闸门");
        assertTrue(w.anchoredCalls >= 2, "挖 + 封至少两次就地 build: " + w.anchoredCalls);
    }

    @Test
    void bodyNeverFallsInSoNothingIsEverCapped() throws Exception {
        World w = new World();
        w.digWorks = false;   // 清空回执说完成，世界没变 → 身体留在原地
        AcxRunRecord rec = run(w);

        assertEquals(AcxStatus.FAIL, rec.status(), "身体没进坑就必须失败");
        assertEquals("guard_in_pit", rec.failedStep(), "应该停在入坑判定上，而不是别处");
        assertEquals(64.0, w.y, 0.001);
        assertFalse(w.hasDirt(), "★ 人还在坑外，一块泥土都不许放下（绝不许封空腔）");
        assertEquals(STONE, w.block(8, 63, 8));
        assertEquals(1, w.buildCalls, "只该发生挖的那一次 build，封盖/补缝一次都不许发");
    }

    @Test
    void capNotActuallyPlacedFailsInsteadOfReportingSuccess() throws Exception {
        World w = new World();
        w.placeWorks = false;   // 放置被静默吞掉
        AcxRunRecord rec = run(w);

        assertEquals(AcxStatus.FAIL, rec.status(), "封盖没成立就不能报成功: " + rec.errorMessage());
        // AC 现在把封盖放在最后一笔、末段用 guard_final 一次性取证，所以停在这里
        assertEquals("guard_final", rec.failedStep(), "应该停在末段封盖后态取证上");
        assertEquals(AIR, w.block(8, 63, 8));
        assertFalse(w.hasDirt());
        assertEquals(2, w.buildCalls, "挖 + 封盖各一次，补缝一轮都不该跑");
    }

    @Test
    void sameDepthButWrongColumnStillFails() throws Exception {
        World w = new World();
        w.driftX = 1.0;   // 落地时被挤到隔壁一列：深度对，列不对
        AcxRunRecord rec = run(w);

        assertEquals(AcxStatus.FAIL, rec.status(),
                "★ 只看 Y 变小就会在这里误判通过，而身体其实不在自己挖的坑里");
        assertEquals("guard_in_pit", rec.failedStep());
        assertFalse(w.hasDirt(), "列不对就不许封");
    }

    // ── 装配 ──────────────────────────────────────────────────────────

    private static Fake.Registry registry(World w) {
        return registry(w, null);
    }

    private static Fake.Registry registry(World w, com.dwinovo.numen.acx.api.AcxCompletionProbe probe) {
        Fake.Registry reg = new Fake.Registry();
        reg.add(PortToolAdapter.builder(new SelfStatus(w)).build());
        reg.add(PortToolAdapter.builder(new Inspect(w)).build());
        reg.add(PortToolAdapter.builder(new Build(w)).probe(probe).build());
        reg.add(PortToolAdapter.builder(new Goto(w)).build());
        return reg;
    }

    /**
     * 真 PAUSED → 探针 → resume：异步受理停在挖那一步，续跑时要能靠 vars 把坐标接上，
     * 末段取证也不能依赖上一帧的 allOutputs（AcxRunner 每帧重建 allOutputs，只保 vars）。
     */
    @Test
    void asyncDigPausesThenResumesWithVarsIntact() throws Exception {
        World w = new World();
        w.asyncDig = true;
        FakePorts.Probe probe = new FakePorts.Probe().thenDone(new java.util.LinkedHashMap<>());
        Fake.Registry reg = registry(w, probe);
        AcxLoader.LoadReport report = load(reg);
        AcxDefinition def = report.registered().get("dig3_seal1_emergency");
        AcxRunner runner = AcxRunner.builder().tools(reg).catalog(report.catalog()).build();

        AcxRunRecord first = runner.run(def, Map.of());
        assertEquals(AcxStatus.PAUSED, first.status(),
                "异步受理必须停在 PAUSED 而不是报成功: " + first.errorMessage());

        AcxRunRecord second = runner.resume(def, first);
        assertEquals(AcxStatus.SUCCESS, second.status(),
                "续跑应当用 vars 里的坐标接上并走完: " + second.errorMessage());
        assertEquals(61.0, w.y, 0.001);
        assertEquals(DIRT, w.block(8, 63, 8));
        assertFalse(probe.asked.isEmpty(), "续跑必须真的问过完成探针");
    }

    private static AcxLoader.LoadReport load(Fake.Registry reg) throws Exception {
        Path dir = Files.createTempDirectory("dig3-ac");
        Path stable = dir.resolve("stable");
        Files.createDirectories(stable);
        Files.writeString(stable.resolve("dig3_seal1_emergency.ac"),
                Files.readString(SCRIPT, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return AcxLoader.loadAll(dir, reg, false, true);
    }

    private static AcxRunRecord run(World w) throws Exception {
        Fake.Registry reg = registry(w);
        AcxLoader.LoadReport report = load(reg);
        assertTrue(report.errors().isEmpty(), "加载有错: " + report.errors());
        AcxDefinition def = report.registered().get("dig3_seal1_emergency");
        assertTrue(def != null, "AC 未注册: " + report.summary());
        return AcxRunner.builder().tools(reg).catalog(report.catalog()).build().run(def, Map.of());
    }
}
