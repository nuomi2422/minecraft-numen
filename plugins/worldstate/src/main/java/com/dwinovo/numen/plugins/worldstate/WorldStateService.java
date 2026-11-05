package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.plugins.worldstate.provider.BlockCountWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.ContainerWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.EntityCountWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.EnvironmentWorldProvider;
import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.model.WorldIdentity;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.query.QueryService;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * 世界观测的宿主侧真源：装配存储、采集器、查询服务，并把观测持久化到
 * {@code config/numen/worldstate/observations.json}（按世界存档命名空间隔离）。
 *
 * <p><b>同步采集</b>：{@code read}/{@code refresh} 由工具在服务端线程调用，采集器直接读真实世界，
 * 线程安全。写盘用 tmp + 原子替换；重启后载入的观测一律标历史（见 {@link ObservationJson}）。
 *
 * <p>共享方式沿用 settlement 的先例：{@link WorldStateMod#service()} 给别的模块一个只读入口，
 * 不必 import 整份实现。
 */
public final class WorldStateService {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    private final Path file;
    private final com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog changeLog =
            new com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog();
    private final InMemoryObservationStore store;
    /**
     * v2 变化流（041 §19 / R3 接线第 1 步）：**本版只构造、不接任何调用点**。
     *
     * <p>为什么先放着：v2 的语义（streamSeq/epoch/RESET）要等能力面版本化才敢切；
     * 先把组件就位，接线时只改调用点，不改构造。
     */
    /**
     * v2 变化流的 epoch：**每个进程必须不同**。
     *
     * <p>之前写死成 `"worldstate-v2"`，后果是：进程重启后 epoch 不变，任何"持久化游标"都会被当成
     * 仍然有效——而新进程的 streamSeq 是从 1 重新发的，等于把新流接到旧游标上。
     * 现在带上进程启动毫秒，重启即换流，旧游标一律 RESET。
     */
    private final com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed changeFeedV2 =
            new com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed(
                    "ws-" + System.currentTimeMillis());
    private final ProviderRegistry providers = new ProviderRegistry();
    private final TopicFreshnessPolicy freshness = TopicFreshnessPolicy.defaults();
    private final RefreshService refreshService;
    private final QueryService queryService;

    public WorldStateService(Path configDir) {
        this.file = configDir.resolve("worldstate").resolve("observations.json");
        // 041 U4b：给存储挂上变化日志——被接受的写入才产生变化
        // R3 过渡期双写：v1 日志 + v2 流（041 §24.4）；两边的键/主题/修订号/类型必须逐条一致
        this.store = new InMemoryObservationStore(512, changeLog, changeFeedV2);
        this.providers.register(new EnvironmentWorldProvider());
        this.providers.register(new EntityCountWorldProvider());
        this.providers.register(new BlockCountWorldProvider());
        this.providers.register(new ContainerWorldProvider());
        // 先载入磁盘观测**再**建 RefreshService：后者要按存储里的最大修订号续号，
        // 否则重启后新进程序列从 0 起，所有刷新都会被 put() 判成"更旧"而静默丢弃
        //（2026-11-05 实机事故，见 RefreshRevisionContinuityTest）。
        load();
        this.refreshService = new RefreshService(providers, store, Runnable::run);
        this.queryService = new QueryService(store, freshness, refreshService, System::currentTimeMillis);
    }

    public Path file() {
        return file;
    }

    /** v2 变化流（本版未接线，只供后续 R3 接线使用）。 */
    public com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed changeFeedV2() {
        return changeFeedV2;
    }

    /** 变化日志（041）：能力面与工具面都从这里读增量。 */
    public com.dwinovo.numen.worldstate.core.changes.ChangeLog changeLog() {
        return changeLog;
    }

    public ProviderRegistry providers() {
        return providers;
    }

    /** 已接入采集器的主题。未列入的主题查询会明确返回 NO_PROVIDER。 */
    public Set<com.dwinovo.numen.worldstate.core.model.Topic> topics() {
        return providers.topics();
    }

    public int size() {
        return store.size();
    }

    /** 只读已有观测（不扫描世界）。 */
    public ReadResult read(MinecraftServer server, ObservationRequest request) {
        return queryService.read(worldId(server), request);
    }

    /** 主动采集并写入观测；成功才落盘。 */
    public RefreshResult refresh(MinecraftServer server, ObservationRequest request) {
        RefreshResult result = queryService.refresh(context(server), request);
        if (result.success()) {
            persist();
        }
        return result;
    }

    private McCollectionContext context(MinecraftServer server) {
        return new McCollectionContext(server, worldId(server),
                server == null ? 0L : server.getTickCount(), System.currentTimeMillis());
    }

    /**
     * 世界存档标识——观测命名空间的一部分，两个存档不能互相串数据。
     *
     * <p>优先存档目录名：exp 副本目录是 `exp-xxx`，但 level.dat 的 LevelName 常仍是
     * `live`；只按显示名分会把副本混进同一命名空间。目录名取不到才回退显示名。
     */
    public static String worldId(MinecraftServer server) {
        if (server == null) return "unknown";
        String saveDir = null;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            if (root != null && root.getFileName() != null) {
                saveDir = root.getFileName().toString();
            }
        } catch (RuntimeException ignored) {
            // 目录名取不到就回退显示名，不崩。
        }
        String levelName = null;
        try {
            levelName = server.getWorldData().getLevelName();
        } catch (RuntimeException ignored) {
            // 显示名也取不到时由 WorldIdentity 回退 unknown。
        }
        return WorldIdentity.of(saveDir, levelName);
    }

    private void load() {
        try {
            if (Files.isRegularFile(file)) {
                for (Observation o : ObservationJson.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8))) {
                    store.put(o);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 坏掉的观测缓存不拖垮插件启动（与 SettlementService / RddAssetStore 同纪律）。
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, ObservationJson.toJson(store.all()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败只记事件与日志——本次内存观测仍有效，不把成功的刷新变成崩溃。
            LOG.warn("[worldstate] persist failed: {}", e.toString());
            WorldStateMonitor.publish("persist_failed", java.util.Map.of("error", String.valueOf(e)));
        }
    }
}
