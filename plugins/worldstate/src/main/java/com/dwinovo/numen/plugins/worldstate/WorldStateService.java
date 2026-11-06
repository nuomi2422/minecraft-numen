package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.plugins.worldstate.provider.BlockCountWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.ContainerWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.SelfWorldProvider;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.plugins.worldstate.provider.EntityCountWorldProvider;
import com.dwinovo.numen.plugins.worldstate.provider.EnvironmentWorldProvider;
import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.model.FailReason;
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
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 世界观测的宿主侧真源：装配存储、采集器、查询服务，并把观测持久化到
 * {@code config/numen/worldstate/observations.json}（按世界存档命名空间隔离）。
 *
 * <p><b>同步采集</b>：{@code refresh} 必须在服务端线程调用，入口先检查再读真实世界；
 * {@code read} 只读缓存。写盘先写临时文件再替换（不承诺断电原子性）；载入的观测一律标历史。
 * 2026-11-05：同一 JVM 重开存档会换 MinecraftServer 对象，故按对象绑定 UUID 会话；
 * 不能仅用进程启动时间或存档名，否则上一轮缓存仍会在有效期内冒充当前。
 *
 * <p>共享方式沿用 settlement 的先例：{@link WorldStateMod#service()} 给别的模块一个只读入口，
 * 不必 import 整份实现。
 */
public final class WorldStateService {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    // 2026-11-05：弱引用不留住已关闭的服务器；显式用 ==，不用按 equals 合并对象的 WeakHashMap。
    // 案例：同名存档的新服务器实例必须换会话；静态表也让同一服务器的多个服务实例身份一致。
    private static final Map<WeakReference<MinecraftServer>, String> SERVER_SESSIONS = new HashMap<>();

    private final Path file;
    private final com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog changeLog =
            new com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog();
    private final InMemoryObservationStore store;
    /**
     * v2 变化流（041 §19 / §24）：**已接调用点**——写入走双写（见构造里的 store），
     * 读取走能力面 `changes(ChangesQuery)`。
     *
     * <p><b>epoch 每个进程必须不同</b>：写死常量时，进程重启后 epoch 不变，
     * 一旦游标持久化，旧游标就会被当成仍有效，接上新进程从 1 重发的 streamSeq（规格违背；
     * 当前游标在内存，所以今天没有实际故障，但持久化后必炸）。
     * 现在带进程启动毫秒，重启即换流，旧游标一律 RESET。
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
        this.providers.register(new SelfWorldProvider());
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

    /** v2 变化流：已由双写存储接入，能力面读取增量和快照水位。 */
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

    /** The actual host policy used by QueryService, not a second set of default TTLs. */
    public long topicTtlMs(com.dwinovo.numen.worldstate.core.model.Topic topic) {
        return freshness.ttlOf(topic);
    }

    public int size() {
        return store.size();
    }

    /** 只读已有观测（不扫描世界）。 */
    public ReadResult read(MinecraftServer server, ObservationRequest request) {
        return queryService.read(worldId(server), sessionId(server), request);
    }

    /** 主动采集并写入观测；成功才落盘。 */
    public RefreshResult refresh(MinecraftServer server, ObservationRequest request) {
        if (server == null) {
            return RefreshResult.failed(FailReason.INVALID_REQUEST, "no server; refresh requires a server thread", 0L);
        }
        // 2026-11-05：核过 MinecraftServer 继承的 BlockableEventLoop.isSameThread()；
        // 不能仅靠调用方承诺，否则异步能力调用会直接扫描真实世界。拒绝，不偷偷调度或刷新。
        if (!server.isSameThread()) {
            return RefreshResult.failed(FailReason.INVALID_REQUEST, "refresh requires the Minecraft server thread", 0L);
        }
        RefreshResult result = queryService.refresh(context(server), request);
        if (result.success() && request.topic() != Topic.SELF) {
            persist();
        }
        return result;
    }

    private McCollectionContext context(MinecraftServer server) {
        return new McCollectionContext(server, worldId(server),
                server == null ? 0L : server.getTickCount(), System.currentTimeMillis(), sessionId(server));
    }

    /** 当前服务器实例的会话；无服务器为空串（unknown），不制造可匹配的虚假会话。 */
    public String sessionId(MinecraftServer server) {
        return sessionFor(server);
    }

    private static synchronized String sessionFor(MinecraftServer server) {
        if (server == null) return "";
        var entries = SERVER_SESSIONS.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            MinecraftServer bound = entry.getKey().get();
            if (bound == null) entries.remove();
            else if (bound == server) return entry.getValue();
        }
        String session = UUID.randomUUID().toString();
        SERVER_SESSIONS.put(new WeakReference<>(server), session);
        return session;
    }

    /**
     * 2026-11-05：真实 save root 的规范化绝对路径区分同名存档，如 a/live 与 b/live。
     * 取路径失败时以 unknown + 服务器会话隔离，不能退回共享的 LevelName 或裸 unknown。
     */
    public static String worldId(MinecraftServer server) {
        if (server == null) return "unknown";
        try {
            return WorldIdentity.forPath(server.getWorldPath(LevelResource.ROOT));
        } catch (RuntimeException unavailable) {
            return "unknown-" + sessionFor(server);
        }
    }

    private void load() {
        try {
            if (Files.isRegularFile(file)) {
                for (Observation o : ObservationJson.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8))) {
                    // 2026-11-06: private full inventory is session-only, including old cache files.
                    if (o.key().topic() != Topic.SELF) store.put(o);
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
            Files.writeString(tmp, ObservationJson.toJson(store.all().stream().filter(o -> o.key().topic() != Topic.SELF).toList()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 落盘失败只记事件与日志——本次内存观测仍有效，不把成功的刷新变成崩溃。
            LOG.warn("[worldstate] persist failed: {}", e.toString());
            WorldStateMonitor.publish("persist_failed", java.util.Map.of("error", String.valueOf(e)));
        }
    }
}
