package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.core.AcxFacade;
import com.dwinovo.numen.acx.core.AcxHostBridge;
import com.dwinovo.numen.acx.core.AcxLoader;
import com.dwinovo.numen.acx.core.AcxRunner;
import com.dwinovo.numen.acx.core.AcxSessionManager;
import com.dwinovo.numen.acx.core.FileAcxLibrary;
import com.dwinovo.numen.acx.core.JsonlRecordStore;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.entity.NumenPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ACX 插件的 NUMEN 宿主适配层。
 *
 * <p>职责边界：本类只做「装配」——把 Numen 工具桥接成 ACX 端口、把 jar 里的
 * stable 脚本铺到 config、建执行器/会话/版本库/门面，然后注册 8 个 {@code acx_*}
 * 门面工具。执行语义全在 {@code acx-core}（纯 JVM、离线 153 项测试）。</p>
 *
 * <p><b>惰性初始化</b>：插件 setup 早于 {@code NumenCore.registerTools()} 全量注册，
 * 不能在构造期桥接。首次任一 {@code acx_*} 调用时 {@link #ensureReady()} 一次成型。</p>
 *
 * <p>落盘：{@code config/numen/acx/{records.jsonl, library.json, lib/stable/*.ac}}，
 * 与旧 AC 的 {@code config/numen/ac-library.json} 刻意分开，防互相清库。</p>
 */
public final class AcxPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(AcxPlugin.class);

    /** jar 内 stable 脚本（与 resources/acx-lib/stable 同步；缺文件只告警）。 */
    private static final String[] STABLE_SCRIPTS = {
            "numen_smoke_read_move_read.ac",
            "ore_survey.ac",
            "survey_iron.ac",
            "survey_diamond.ac",
            "guarded_ore_survey.ac",
            "defend_self_v2.ac",
            "iron_armor_surface_v2.ac",
            "mine_smelt_craft_iron_pickaxe.ac"
    };

    private NumenApi api;
    private AcxHostGate gate;
    private AcxRegistry registry;
    private AcxRunner runner;
    private AcxSessionManager sessions;
    private AcxFacade facade;
    private JsonlRecordStore store;
    private ExecutorService executor;
    private volatile NumenPlayer companion;
    private volatile boolean ready;
    private String readySummary = "尚未初始化";

    @Override
    public void setup(NumenApi numen) {
        this.api = numen;
        this.gate = new AcxHostGate();
        numen.registerTool(new AcxFacadeTools.Execute(this));
        numen.registerTool(new AcxFacadeTools.Status(this));
        numen.registerTool(new AcxFacadeTools.Resume(this));
        numen.registerTool(new AcxFacadeTools.Cancel(this));
        numen.registerTool(new AcxFacadeTools.Publish(this));
        numen.registerTool(new AcxFacadeTools.Approve(this));
        numen.registerTool(new AcxFacadeTools.Rollback(this));
        numen.registerTool(new AcxFacadeTools.Library(this));
        LOG.info("[acx] 8 个门面已注册（惰性初始化：首次调用时桥接工具并加载脚本）");
    }

    /** 幂等惰性初始化；失败会抛出，由门面壳转成失败回执。 */
    public synchronized void ensureReady() {
        if (ready) {
            return;
        }
        Path base = api.configDir().resolve("acx");
        try {
            Files.createDirectories(base);
            Path libRoot = base.resolve("lib");
            materializeStable(libRoot);

            registry = new AcxRegistry();

            // 惰性桥接：此刻 NumenCore 全量工具已注册（首次 acx_* 调用必然晚于引擎 init）
            List<AcxToolPort> ports = new ArrayList<>();
            for (NumenTool tool : ToolRegistry.all()) {
                String name = tool.name();
                if (name == null || name.isBlank() || name.startsWith("acx_")) {
                    continue;
                }
                ports.add(new NumenToolBridgeX(tool, this));
            }
            AcxHostBridge.Report bridge = AcxHostBridge.registerAll(
                    registry, ports, NumenToolBridgeX.probeFor(this::currentCompanion));

            AcxLoader.LoadReport load = AcxLoader.loadAll(libRoot, registry, false, true);
            for (String err : load.errors()) {
                LOG.warn("[acx] 脚本加载错误（已隔离）: {}", err);
            }
            for (String warn : load.warnings()) {
                LOG.warn("[acx] 脚本加载告警: {}", warn);
            }

            store = new JsonlRecordStore(base.resolve("records.jsonl"));
            FileAcxLibrary library = new FileAcxLibrary(base.resolve("library.json"), registry);
            library.load();

            runner = AcxRunner.builder()
                    .tools(registry)
                    .events(AcxMonitor::publish)
                    .store(store)
                    .build();
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "numen-acx-runner");
                t.setDaemon(true);
                return t;
            });
            sessions = new AcxSessionManager(runner, executor);
            facade = new AcxFacade(sessions, library::active, library::version, registry, library);

            ready = true;
            readySummary = "桥接工具 " + bridge.registered().size()
                    + " 别名 " + bridge.aliasNames().size()
                    + " 跳过 " + bridge.skipped().size()
                    + "；脚本注册 " + load.registered().size()
                    + "（beta " + load.betaOnly().size() + "）"
                    + " 错误 " + load.errors().size()
                    + " 警告 " + load.warnings().size();
            LOG.info("[acx] ready: {}", readySummary);
        } catch (IOException e) {
            throw new IllegalStateException("ACX 初始化 IO 失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("ACX 初始化失败: " + e.getMessage(), e);
        }
    }

    /** jar 内 stable 脚本铺到 config；已存在不覆盖（游戏内修订优先）。 */
    private void materializeStable(Path libRoot) throws IOException {
        Path stable = libRoot.resolve("stable");
        Files.createDirectories(stable);
        for (String name : STABLE_SCRIPTS) {
            Path target = stable.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = AcxPlugin.class.getResourceAsStream("/acx-lib/stable/" + name)) {
                if (in == null) {
                    LOG.warn("[acx] jar 内脚本缺失: /acx-lib/stable/{}", name);
                    continue;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ── 门面壳用的访问口 ────────────────────────────────────────────────

    /** 每个 acx_* 调用入口先绑一次当前同伴（桥接派发要用）。 */
    public void bindCompanion(NumenPlayer player) {
        if (player != null) {
            this.companion = player;
        }
    }

    public NumenPlayer currentCompanion() {
        return companion;
    }

    public AcxHostGate gate() {
        return gate;
    }

    public AcxFacade facade() {
        ensureReady();
        return facade;
    }

    /** 供调试/观测的初始化摘要。 */
    public String readySummary() {
        return readySummary;
    }
}
