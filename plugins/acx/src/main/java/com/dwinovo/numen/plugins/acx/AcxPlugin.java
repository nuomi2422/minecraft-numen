package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxDefinition;
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

    /** jar 内脚本目录（前缀，与 resources/acx-lib/stable 同步）。 */
    private static final String RES_PREFIX = "/acx-lib/stable/";
    private static final String RES_DIR = "acx-lib/stable/";

    /**
     * jar 内置脚本清单，<b>从 jar 现场枚举</b>，不再硬编码。
     *
     * <p>为什么改：硬编码名单在真机踩过——往 jar 里加了
     * {@code mine_nearest_ore.ac}、jar 也确实打进去了，但名单没同步，
     * 脚本既没铺到 config 也没进版本库，表现为「按名执行说库里没有」。
     * 加脚本必须只改 resources 目录、不改 Java。</p>
     */
    /** jar 内脚本清单的缓存：枚举一次就够，别每步重算（原来日志被同样的 warn 刷了几十行）。 */
    private static volatile java.util.List<String> BUNDLED_CACHE;

    private static List<String> bundledScripts() {
        java.util.List<String> cached = BUNDLED_CACHE;
        if (cached != null) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        // ① classloader 资源枚举。不能按协议分派：NeoForge 的 union 类加载器给的是
        //    union:/E:/…/mods/xxx.jar（没有 !/ 段），普通 jar 类加载器给 jar:file:/…!/acx-lib/stable，
        //    dev 环境给 file:…/acx-lib/stable。统一走 jarFromUrl() 剥协议再判断。
        try {
            java.util.Enumeration<java.net.URL> urls =
                    AcxPlugin.class.getClassLoader().getResources(RES_DIR);
            while (urls.hasMoreElements()) {
                java.net.URL u = urls.nextElement();
                try {
                    if ("file".equals(u.getProtocol())) {
                        Path dir = Path.of(u.toURI());
                        if (Files.isDirectory(dir)) {
                            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                                s.map(f -> f.getFileName().toString())
                                        .filter(n -> n.endsWith(".ac"))
                                        .sorted()
                                        .forEach(names::add);
                            }
                        } else {
                            names.addAll(acNamesInJar(dir));
                        }
                    } else {
                        Path jar = jarFromUrl(u);
                        if (jar != null) {
                            names.addAll(acNamesInJar(jar));
                        } else {
                            LOG.warn("[acx] 看不懂的资源 URL（协议 {}，剥完不像 jar）: {}", u.getProtocol(), u);
                        }
                    }
                } catch (Exception inner) {
                    LOG.warn("[acx] 资源 {} 枚举失败: {}", u, inner.toString());
                }
            }
        } catch (Exception e) {
            LOG.warn("[acx] classloader 资源枚举失败: {}", e.toString());
        }
        // ② 兜底：code source。NeoForge 下是 union:/…，所以同样走 jarFromUrl，并把 URL 打进日志便于诊断
        if (names.isEmpty()) {
            try {
                java.net.URL loc = AcxPlugin.class.getProtectionDomain().getCodeSource().getLocation();
                LOG.info("[acx] code source = {}", loc);
                Path jar = jarFromUrl(loc);
                if (jar != null) {
                    names.addAll(acNamesInJar(jar));
                } else {
                    LOG.warn("[acx] code source 剥协议后不是常规 jar: {}", loc);
                }
            } catch (Exception e) {
                LOG.warn("[acx] code source 兜底失败: {}", e.toString());
            }
        }
        // ③ 再兜底：classloader 容器根（union:/…/x.jar 或 jar:file:/…!/ 或 file:…/）
        if (names.isEmpty()) {
            try {
                java.net.URL root = AcxPlugin.class.getResource("/");
                LOG.info("[acx] classloader root = {}", root);
                if (root != null) {
                    Path jar = jarFromUrl(root);
                    if (jar != null) {
                        names.addAll(acNamesInJar(jar));
                    }
                }
            } catch (Exception e) {
                LOG.warn("[acx] classloader root 兜底失败: {}", e.toString());
            }
        }
        if (names.isEmpty()) {
            LOG.warn("[acx] ★ 枚举不出 jar 内置脚本（内置脚本不会自动上线）："
                    + "往 resources/acx-lib/stable/ 放 .ac 后请检查这行日志");
        }
        java.util.List<String> deduped = names.stream().distinct().sorted().collect(java.util.stream.Collectors.toList());
        BUNDLED_CACHE = deduped;
        return deduped;
    }

    /**
     * 把任意协议的资源 URL 还原成本地 jar 路径；不是常规 .jar 文件就返回 {@code null}。
     *
     * <p>为什么要这么写（真机踩过两轮）：jar 里是 {@code jar:file:/E:/…/x.jar!/acx-lib/stable}，
     * 而 NeoForge 的 union 类加载器给的是 {@code union:/E:/…/mods/x.jar}（没有 {@code !/} 段、
     * 空格被 URL 编码成 {@code %20}）。所以顺序固定是：剥协议头 → URL 解码 → 砍 {@code #} 补丁标记 → 剥 {@code !/} 之后
     * → 剥前导斜杠 → 必须以 .jar 结尾且 isRegularFile。</p>
     */
    private static Path jarFromUrl(java.net.URL u) {
        String spec = u.toString();
        int colon = spec.indexOf(':');
        if (colon > 0) {
            spec = spec.substring(colon + 1);
        }
        try {
            spec = java.net.URLDecoder.decode(spec, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 解码失败就按原样试
        }
        // NeoForge 的 union 类加载器会在 jar 名后面插 %23<数字>!（解出来形如 "xxx.jar#240"），
        // 必须在判 .jar 之前砍掉这个补丁标记，否则永远认不出 jar ——真机日志：
        // union:/E:/.../mods/numen-plugin-acx-...jar%23240!/acx-lib/stable
        int hash = spec.indexOf('#');
        if (hash >= 0) {
            spec = spec.substring(0, hash);
        }
        int bang = spec.indexOf("!/");
        if (bang >= 0) {
            spec = spec.substring(0, bang);
        }
        while (spec.startsWith("/")) {
            spec = spec.substring(1);
        }
        if (!spec.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return null;
        }
        try {
            Path path = Path.of(spec);
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出 jar 里 {@code acx-lib/stable/*.ac} 的文件名。 */
    private static List<String> acNamesInJar(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            jf.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(n -> n.startsWith(RES_DIR) && n.endsWith(".ac"))
                    .map(n -> n.substring(RES_DIR.length()))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

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
            autoApproveBundled(library, load);

            runner = AcxRunner.builder()
                    .tools(registry)
                    // ★ 子 AC 委托靠它：没有 catalog，step.block 写成别的 AC 名就会「找不到积木或 AC」。
                    //   真机实测踩过（subac_nesting FAIL），loader 那时明明已经加载成功了。
                    .catalog(load.catalog())
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

    /**
     * jar 自带脚本自动进版本库并上线（AC-B6）。
     *
     * <p>策略：<b>只对「版本库里从没见过的 jar 自带脚本」</b>做 publish + approve。
     * 理由：随 jar 发布的脚本等价于随插件版本发布的代码，天然已审；自动批准省掉每次发版
     * 都要人点一次。但玩家/学习者在游戏里改过的脚本<b>不</b>自动批准 —— 它已经进过库，
     * 走 GENERATED/PENDING，必须 {@code acx_approve} 人工放行，人工批准那道闸才不形同虚设。
     * 用户自己放进 config 的 .ac 不在 jar 内置脚本名单 名单里，同样不自动批准。</p>
     */
    private void autoApproveBundled(FileAcxLibrary library, AcxLoader.LoadReport load) {
        List<String> approved = new ArrayList<>();
        for (AcxDefinition def : load.registered().values()) {
            if (!isBundled(def.name())) {
                continue;
            }
            // 判据是「库里的生效版本是不是还等于 jar 里这份」，不是「库里有这个名字没有」。
            // 只判名字的话，插件升版后内置脚本改了内容、库里旧定义还在，就会一直跑旧版本
            // （真机踩过：timeout_demo 换了循环体，跑起来还是旧的 set_timer 版本）。
            if (library.hasAnyVersion(def.name())) {
                AcxDefinition online = null;
                try {
                    online = library.active(def.name());
                } catch (RuntimeException ignored) {
                    // 没上线 / active 状态异常 → 视为需要更新
                }
                if (online != null
                        && com.dwinovo.numen.acx.core.AcxFingerprint.of(online)
                                .equals(com.dwinovo.numen.acx.core.AcxFingerprint.of(def))) {
                    continue;
                }
            }
            try {
                // 子 AC 委托要过静态校验，得把同批已加载的 AC 名交给版本库
                String version = library.publish(def, "jar 内置脚本，随插件版本发布",
                        new java.util.HashSet<>(load.registered().keySet()));
                library.approve(def.name(), version, "acx-builtin",
                        "随 jar 发布视为已审；此后任何修改都需人工 acx_approve");
                approved.add(def.name() + "@" + version);
            } catch (RuntimeException e) {
                LOG.warn("[acx] 内置脚本未能上线（不影响其余）: {} -> {}", def.name(), e.getMessage());
            }
        }
        if (!approved.isEmpty()) {
            library.persist();
            LOG.info("[acx] 内置脚本已上线（发布即已审）: {}", approved);
        }
    }

    private static boolean isBundled(String acName) {
        for (String file : bundledScripts()) {
            if (file.equals(acName + ".ac")) {
                return true;
            }
        }
        return false;
    }

    /** jar 内 stable 脚本铺到 config；已存在不覆盖（游戏内修订优先）。 */
    private void materializeStable(Path libRoot) throws IOException {
        Path stable = libRoot.resolve("stable");
        Files.createDirectories(stable);
        List<String> bundledNames = bundledScripts();
        LOG.info("[acx] jar 内置脚本 {} 个: {}", bundledNames.size(), bundledNames);
        for (String name : bundledNames) {
            try (InputStream in = AcxPlugin.class.getResourceAsStream(RES_PREFIX + name)) {
                if (in == null) {
                    LOG.warn("[acx] jar 内脚本缺失: /acx-lib/stable/{}", name);
                    continue;
                }
                byte[] bundled = in.readAllBytes();
                Path target = stable.resolve(name);
                if (Files.exists(target)) {
                    byte[] onDisk = Files.readAllBytes(target);
                    if (java.util.Arrays.equals(bundled, onDisk)) {
                        continue;
                    }
                    // jar 拥有的脚本：内容变了说明插件升版了。lib/stable 是 jar 的物料区，
                    // 玩家要改自己的 AC 应该走 acx_publish 进版本库，所以这里以 jar 为准，
                    // 旧内容留一份 .stale-<ts> 存档而不是静默覆盖。
                    Path stale = stable.resolve(name + ".stale-" + System.currentTimeMillis());
                    Files.move(target, stale, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("[acx] 内置脚本随插件升版已更新（旧内容存档 {}）: {}", stale.getFileName(), name);
                }
                Files.write(target, bundled);
            }
        }
        // 不在 jar 名单里的 .ac 一律当玩家脚本处理（只提醒，不动它）
        try (java.util.stream.Stream<Path> files = Files.list(stable)) {
            files.filter(p -> p.toString().endsWith(".ac"))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !isBundledFile(n))
                    .forEach(n -> LOG.warn("[acx] lib/stable/{} 不在 jar 内置名单，按玩家脚本处理", n));
        }
    }

    private static boolean isBundledFile(String fileName) {
        return bundledScripts().contains(fileName);
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
