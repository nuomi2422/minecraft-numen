package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.WorldIdentity;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-05：离线编译两份完整生产宿主源码，测试真实会话/线程/身份接线；仅 MC 和日志/采集边界替身。
 * 案例：同进程重开同名存档，或非服务端线程刷新，不能把旧缓存当当前或偷读世界。
 * 不用复制生产方法或只搜源码字符串：那会漏掉构造、context、read、refresh 间的真实调用关系。
 * 运行 :plugins:worldstate:test --tests '*WorldStateSessionWiringTest'；需要 JDK 与源码树，不启动游戏。
 * 这不是 Minecraft 实机或真实调度测试；isSameThread 签名另已对 1.21.1 实物 javap 核查。
 */
class WorldStateSessionWiringTest {
    private static final String PKG = "com.dwinovo.numen.plugins.worldstate.";
    private static final ObservationRequest REQUEST = ObservationRequest.environment("minecraft:overworld");
    @TempDir static Path compiled;
    @TempDir Path config;
    private static URLClassLoader loader;
    private static Class<?> serviceType;
    private static Class<?> serverType;
    private static Class<?> contextType;
    private static Class<?> providerType;

    @BeforeAll
    static void compileProductionSourcesAgainstBoundaryStubs() throws Exception {
        Path plugin = root().resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate");
        List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none",
                "-classpath", location(Observation.class) + File.pathSeparator + location(Gson.class)
                        + File.pathSeparator + location(com.dwinovo.numen.api.observation.WorldObservationService.class),
                "-d", compiled.toString()));
        for (var source : stubs().entrySet()) {
            Path target = compiled.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, source.getValue(), StandardCharsets.UTF_8);
            arguments.add(target.toString());
        }
        for (String name : List.of("WorldStateService", "McCollectionContext", "SelfSampleBridge",
                "EnvironmentRefresher", "SelfSampleReader", "SelfObservationProjection",
                "ObservationResponses", "ObservationMap")) {
            arguments.add(plugin.resolve(name + ".java").toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required for isolated production-source wiring test");
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
        // 2026-11-05：父加载器可能有插件/MC 类；用本次完整源码编译物，不能误测父类路径旧产物。
        loader = new URLClassLoader(new URL[]{compiled.toUri().toURL()}, WorldStateSessionWiringTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(PKG) || name.startsWith("net.minecraft.") || name.startsWith("com.dwinovo.numen.entity.") || name.startsWith("org.slf4j.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> found = findLoadedClass(name);
                        if (found == null) found = findClass(name);
                        if (resolve) resolveClass(found);
                        return found;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        serviceType = loader.loadClass(PKG + "WorldStateService");
        serverType = loader.loadClass("net.minecraft.server.MinecraftServer");
        contextType = loader.loadClass(PKG + "McCollectionContext");
        providerType = loader.loadClass(PKG + "provider.EnvironmentWorldProvider");
        for (Class<?> type : List.of(serviceType, serverType, contextType)) {
            assertEquals(compiled.toUri().toURL(), type.getProtectionDomain().getCodeSource().getLocation());
        }
    }

    @AfterAll
    static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    @BeforeEach
    void resetProviderCounter() throws Exception {
        providerType.getField("calls").setInt(null, 0);
    }

    private Object host(Path directory) throws Exception {
        return serviceType.getConstructor(Path.class).newInstance(directory);
    }

    private Object server(Path root, boolean sameThread, boolean failPath) throws Exception {
        return serverType.getConstructor(Path.class, boolean.class, boolean.class).newInstance(root, sameThread, failPath);
    }

    private static Object invoke(Object host, String method, Class<?>[] types, Object... arguments) throws Exception {
        try { return serviceType.getMethod(method, types).invoke(host, arguments); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }

    private static String session(Object host, Object server) throws Exception {
        return (String) invoke(host, "sessionId", new Class<?>[]{serverType}, server);
    }

    private static String world(Object server) throws Exception {
        return (String) invoke(null, "worldId", new Class<?>[]{serverType}, server);
    }

    private static RefreshResult refresh(Object host, Object server) throws Exception {
        return (RefreshResult) invoke(host, "refresh", new Class<?>[]{serverType, ObservationRequest.class}, server, REQUEST);
    }

    private static ReadResult read(Object host, Object server) throws Exception {
        return (ReadResult) invoke(host, "read", new Class<?>[]{serverType, ObservationRequest.class}, server, REQUEST);
    }

    private static int calls() throws Exception {
        return providerType.getField("calls").getInt(null);
    }

    @Test
    void sessionsFollowObjectIdentityNotEqualsOrServiceInstance() throws Exception {
        Object host = host(config);
        Object otherHost = host(config.resolve("other-service"));
        Object first = server(config.resolve("live"), true, false);
        Object second = server(config.resolve("live"), true, false);
        assertEquals(first, second, "fixture intentionally makes different servers equals-equal");
        String session = session(host, first);
        assertEquals(session, UUID.fromString(session).toString());
        assertEquals(session, session(host, first));
        assertEquals(session, session(otherHost, first));
        assertNotEquals(session, session(host, second));
    }

    @Test
    void nullServerHasNoKnownSessionAndRefreshFailsWithoutCollecting() throws Exception {
        Object host = host(config);
        assertEquals("", session(host, null));
        assertEquals("unknown", world(null));
        RefreshResult result = refresh(host, null);
        assertFalse(result.success());
        assertEquals(FailReason.INVALID_REQUEST, result.reason());
        assertTrue(result.message().contains("no server"));
        assertEquals(0, calls());
        assertFalse(Files.exists(config.resolve("worldstate/observations.json")));
    }

    @Test
    void contextPreservesNewSessionAndKeepsOldConstructorUnknown() throws Exception {
        CollectionContext legacy = (CollectionContext) contextType
                .getConstructor(serverType, String.class, long.class, long.class)
                .newInstance(null, "live", 42L, 1000L);
        assertEquals("", legacy.serverSession());
        for (String input : new String[]{"session-a", "", null, " "}) {
            CollectionContext current = (CollectionContext) contextType
                    .getConstructor(serverType, String.class, long.class, long.class, String.class)
                    .newInstance(null, "live", 42L, 1000L, input);
            assertEquals("session-a".equals(input) ? input : "", current.serverSession());
            assertEquals(42L, current.gameTick());
        }
    }

    @Test
    void reopenedServerCannotUsePreviousSessionAsCurrentUntilRefresh() throws Exception {
        Object host = host(config);
        Object first = server(config.resolve("live"), true, false);
        Object reopened = server(config.resolve("live"), true, false);
        RefreshResult original = refresh(host, first);
        assertTrue(original.success());
        assertEquals(session(host, first), original.observation().serverSession());
        assertEquals(Freshness.CURRENT, read(host, first).freshness());
        assertEquals(Freshness.HISTORICAL, read(host, reopened).freshness());
        assertEquals(1, calls());
        RefreshResult next = refresh(host, reopened);
        assertTrue(next.success());
        assertEquals(original.observation().revision() + 1L, next.observation().revision());
        assertEquals(Freshness.CURRENT, read(host, reopened).freshness());
        assertEquals(Freshness.HISTORICAL, read(host, first).freshness());
        assertEquals(2, calls());
    }

    @Test
    void offThreadRefreshFailsBeforeContextCollectionOrDiskAndRetainsCache() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        assertTrue(refresh(host, server).success());
        Path file = config.resolve("worldstate/observations.json");
        byte[] before = Files.readAllBytes(file);
        int pathReads = serverType.getField("pathReads").getInt(server);
        int tickReads = serverType.getField("tickReads").getInt(server);
        serverType.getField("sameThread").setBoolean(server, false);
        RefreshResult result = refresh(host, server);
        assertFalse(result.success());
        assertEquals(FailReason.INVALID_REQUEST, result.reason());
        assertTrue(result.message().contains("server thread"));
        assertEquals(1, calls());
        assertEquals(pathReads, serverType.getField("pathReads").getInt(server));
        assertEquals(tickReads, serverType.getField("tickReads").getInt(server));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(Freshness.CURRENT, read(host, server).freshness(), "cache reads need not be on server thread");
    }

    @Test
    void sameBasenameAtDifferentRootsDoesNotShareObservations() throws Exception {
        Object host = host(config);
        Path firstRoot = config.resolve("a/live");
        Object first = server(firstRoot, true, false);
        Object other = server(config.resolve("b/live"), true, false);
        assertEquals(WorldIdentity.forPath(firstRoot), world(first));
        assertNotEquals(world(first), world(other));
        assertTrue(refresh(host, first).success());
        assertFalse(read(host, other).present());
        assertEquals(1, calls());
    }

    @Test
    void unavailablePathsAreExplicitUnknownAndIsolatedByServer() throws Exception {
        Object host = host(config);
        Object missing = server(null, true, false);
        Object throwing = server(config.resolve("live"), true, true);
        assertEquals("unknown-" + session(host, missing), world(missing));
        assertEquals("unknown-" + session(host, throwing), world(throwing));
        assertNotEquals(world(missing), world(throwing));
        assertTrue(refresh(host, missing).success());
        assertFalse(read(host, throwing).present());
    }

    @Test
    void diskReloadRemainsHistoricalEvenForSameServerAndContinuesRevision() throws Exception {
        Object firstHost = host(config);
        Object server = server(config.resolve("live"), true, false);
        RefreshResult first = refresh(firstHost, server);
        String json = Files.readString(config.resolve("worldstate/observations.json"));
        assertEquals(session(firstHost, server), ObservationJson.fromJson(json).getFirst().serverSession());
        Object reloadedHost = host(config);
        assertEquals(session(firstHost, server), session(reloadedHost, server));
        assertEquals(Freshness.HISTORICAL, read(reloadedHost, server).freshness());
        RefreshResult next = refresh(reloadedHost, server);
        assertEquals(first.observation().revision() + 1L, next.observation().revision());
        assertEquals(Freshness.CURRENT, read(reloadedHost, server).freshness());
    }

    @Test void selfRefreshStaysInSameMemoryStoreAndNeverCreatesDiskFile() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        ObservationRequest self = ObservationRequest.self("minecraft:overworld", UUID.randomUUID());
        RefreshResult result = (RefreshResult) invoke(host, "refresh", new Class<?>[]{serverType, ObservationRequest.class}, server, self);
        assertTrue(result.success());
        assertTrue(((ReadResult) invoke(host, "read", new Class<?>[]{serverType, ObservationRequest.class}, server, self)).present());
        assertFalse(Files.exists(config.resolve("worldstate/observations.json")));
        assertTrue(refresh(host, server).success());
        List<Observation> persisted = ObservationJson.fromJson(Files.readString(config.resolve("worldstate/observations.json")));
        assertEquals(1, persisted.size());
        assertEquals(com.dwinovo.numen.worldstate.core.model.Topic.ENVIRONMENT, persisted.getFirst().key().topic());
    }

    @Test void loadIgnoresLegacySelfWhileKeepingOtherHistoricalTopics() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        assertTrue(refresh(host, server).success());
        Path file = config.resolve("worldstate/observations.json");
        List<Observation> rows = new ArrayList<>(ObservationJson.fromJson(Files.readString(file)));
        UUID uuid = UUID.randomUUID();
        var request = ObservationRequest.self("minecraft:overworld", uuid);
        var payload = new com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload(
                "minecraft:overworld", uuid, null, null, null, null, null, null, null, null, null, null, null);
        rows.add(new Observation(request.keyFor(world(server)), payload, "old", com.dwinovo.numen.worldstate.core.model.Origin.DIRECT,
                42, System.currentTimeMillis(), com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE, Freshness.CURRENT, 2, 1, session(host, server)));
        Files.writeString(file, ObservationJson.toJson(rows));
        Object reloaded = host(config);
        assertFalse(((ReadResult) invoke(reloaded, "read", new Class<?>[]{serverType, ObservationRequest.class}, server, request)).present());
        assertEquals(Freshness.HISTORICAL, read(reloaded, server).freshness());
    }

    @Test void selfRefreshPreservesPreviouslyCapturedEpochAndTick() throws Exception {
        Object host=host(config), server=server(config.resolve("live"),true,false);
        Class<?> body=loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
        Object player=body.getConstructor().newInstance();
        Object list=serverType.getMethod("getPlayerList").invoke(server);
        list.getClass().getField("player").set(list,player);
        Class<?> sampler=loader.loadClass("com.dwinovo.numen.entity.CompanionBodySample");
        var request=ObservationRequest.self("minecraft:overworld",UUID.randomUUID());
        for(long tick:new long[]{42,43}) {
            sampler.getField("tick").setLong(null,tick);
            sampler.getField("epoch").setLong(null,1000+tick);
            RefreshResult result=(RefreshResult)invoke(host,"refresh",new Class<?>[]{serverType,ObservationRequest.class},server,request);
            assertEquals(tick,result.observation().gameTick());
            assertEquals(1000+tick,result.observation().epochMs());
        }
        assertFalse(Files.exists(config.resolve("worldstate/observations.json")));
    }

    // ==== 2026-11-06 自动 SELF 采样 + 周期环境刷新 ====
    // 这一组盯的是"READY → refresh / DEATH·REMOVE → invalidateSelf / 到点才刷 environment"
    // 这三条真实接线；采样节拍本身在 api 的 CompanionBodySampleTest 里测。

    /** 造一具带服务端句柄与身份的身体，并挂进 player list（SELF refresh 会按 UUID 取它）。 */
    private static Object companion(Object server, UUID id) throws Exception {
        Class<?> body = loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
        Object player = body.getConstructor().newInstance();
        body.getField("server").set(player, server);
        body.getField("uuid").set(player, id);
        Object list = serverType.getMethod("getPlayerList").invoke(server);
        list.getClass().getField("player").set(list, player);
        return player;
    }

    /** 直接调生产映射层（WorldStatePlugin 的订阅只是把它接到事件总线上）。 */
    private static void bridge(String method, Object host, Object player) throws Exception {
        Class<?> body = loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
        // 被测的是隔离加载器里按桩编译出来的那份生产代码。它与测试类同名包、但类加载器不同，
        // 属于不同的运行时包，所以包级方法必须 setAccessible 才能反射调用（不改产品可见性）。
        var mapper = loader.loadClass(PKG + "SelfSampleBridge")
                .getDeclaredMethod(method, serviceType, body);
        mapper.setAccessible(true);
        mapper.invoke(null, host, player);
    }

    private static ReadResult selfRead(Object host, Object server, ObservationRequest request) throws Exception {
        return (ReadResult) invoke(host, "read", new Class<?>[]{serverType, ObservationRequest.class}, server, request);
    }

    private static int size(Object host) throws Exception {
        return (Integer) invoke(host, "size", new Class<?>[]{});
    }

    @Test void selfSampleReadyRefreshesAtTheSampledTickAndNeverTouchesDisk() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        UUID id = UUID.randomUUID();
        Object player = companion(server, id);
        // Watch 已经采好这一帧；refresh 必须原样沿用它的 tick/epoch，不能自己再取一次。
        Class<?> sampler = loader.loadClass("com.dwinovo.numen.entity.CompanionBodySample");
        sampler.getField("tick").setLong(null, 42L);
        sampler.getField("epoch").setLong(null, 1_042L);

        bridge("ready", host, player);

        ObservationRequest request = ObservationRequest.self("minecraft:overworld", id);
        ReadResult read = selfRead(host, server, request);
        assertTrue(read.present());
        assertEquals(42L, read.observation().gameTick(), "沿用采样帧的 tick");
        assertEquals(1_042L, read.observation().epochMs(), "沿用采样帧的 epoch");
        assertEquals(id, read.observation().payload().getClass()
                .getMethod("companionUUID").invoke(read.observation().payload()), "主体就是发事件的那具身体");
        assertFalse(Files.exists(config.resolve("worldstate/observations.json")), "SELF 刷新不落盘");
    }

    @Test void readyIntoNewDimensionInvalidatesTheOldDimensionKeyWithoutGrowingTheStore() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        UUID id = UUID.randomUUID();
        Object player = companion(server, id);
        ObservationRequest overworld = ObservationRequest.self("minecraft:overworld", id);

        bridge("ready", host, player);
        assertTrue(selfRead(host, server, overworld).present());
        int before = size(host);

        loader.loadClass("com.dwinovo.numen.entity.NumenPlayer").getField("dimensionId")
                .set(player, "minecraft:the_end");
        bridge("ready", host, player);

        assertFalse(selfRead(host, server, overworld).present(), "换维度后旧维度的键必须失效");
        assertTrue(selfRead(host, server, ObservationRequest.self("minecraft:the_end", id)).present());
        assertEquals(before, size(host), "旧键删、新键写：不许留下两代同主体");
    }

    @Test void deathAndServerStopInvalidateOnlySelfWhileOtherTopicsSurvive() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        UUID id = UUID.randomUUID();
        Object player = companion(server, id);
        ObservationRequest self = ObservationRequest.self("minecraft:overworld", id);

        assertTrue(refresh(host, server).success());
        bridge("ready", host, player);
        assertTrue(selfRead(host, server, self).present());
        assertEquals(2, size(host));

        bridge("gone", host, player);
        assertFalse(selfRead(host, server, self).present(), "死亡/移除后回到 PENDING（存储层等价事实：没有观测）");
        assertEquals(1, size(host), "不许为了 PENDING 造一条空观测");
        assertEquals(Freshness.CURRENT, read(host, server).freshness(), "其它主题不丢");

        bridge("ready", host, player);
        assertTrue(selfRead(host, server, self).present());
        assertEquals(2, size(host));

        int removed = (Integer) invoke(host, "invalidateAllSelf", new Class<?>[]{});
        assertEquals(1, removed, "停服只清 SELF");
        assertFalse(selfRead(host, server, self).present());
        assertEquals(1, size(host));
        assertEquals(Freshness.CURRENT, read(host, server).freshness(), "停服清 SELF 不许动其它主题");

        assertTrue(refresh(host, server).success());
        List<Observation> persisted = ObservationJson.fromJson(
                Files.readString(config.resolve("worldstate/observations.json")));
        assertEquals(1, persisted.size());
        assertEquals(com.dwinovo.numen.worldstate.core.model.Topic.ENVIRONMENT,
                persisted.getFirst().key().topic(), "落盘的只有非 SELF 主题");
    }

    @Test void environmentRefreshesOnIntervalOnlyAndFailureKeepsTheOldObservation() throws Exception {
        Object host = host(config);
        Object server = server(config.resolve("live"), true, false);
        Class<?> refresherType = loader.loadClass(PKG + "EnvironmentRefresher");
        var constructor = refresherType.getDeclaredConstructor(serviceType);
        constructor.setAccessible(true);
        Object refresher = constructor.newInstance(host);
        var tick = refresherType.getDeclaredMethod("tick", serverType, long.class);
        tick.setAccessible(true);

        assertEquals(1, (int) (Integer) tick.invoke(refresher, server, 0L), "首次到点：刷一次");
        assertEquals(1, calls());
        assertEquals(0, (int) (Integer) tick.invoke(refresher, server, 19_999L), "没到间隔：不许刷");
        assertEquals(1, calls(), "未到点连采集器都不许进");
        assertEquals(1, (int) (Integer) tick.invoke(refresher, server, 20_000L), "到点：再刷一次");
        assertEquals(2, calls());

        // 失败：采集器抛错 → 旧观测原样留着（修订号/时间戳不变），年龄继续走，自然过期。
        ReadResult before = read(host, server);
        assertTrue(before.present());
        providerType.getField("failNext").setBoolean(null, true);
        assertEquals(0, (int) (Integer) tick.invoke(refresher, server, 40_000L));
        assertEquals(3, calls(), "失败也要确实尝试过");
        ReadResult after = read(host, server);
        assertTrue(after.present(), "失败不许把旧值删掉，也不许补一条假事实");
        assertEquals(before.observation().revision(), after.observation().revision());
        assertEquals(before.observation().epochMs(), after.observation().epochMs());
        providerType.getField("failNext").setBoolean(null, false);
        assertEquals(0, (int) (Integer) tick.invoke(refresher, server, 40_001L), "失败后按节拍等下一轮，不每 tick 重试");
        assertEquals(3, calls());
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static Path root() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/WorldStateService.java"))) return path;
        }
        throw new IllegalStateException("repository root not found");
    }

    private static Map<String, String> stubs() {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("net.minecraft.server.MinecraftServer", """
                package net.minecraft.server;
                import java.nio.file.Path;
                import net.minecraft.resources.ResourceKey;
                import net.minecraft.server.level.ServerLevel;
                import net.minecraft.world.level.Level;
                import net.minecraft.world.level.storage.LevelResource;
                /** 2026-11-05：MC 边界替身；模拟 equals 相等但实例不同，不用简单 map 键测试掩盖串会话。 */
                public class MinecraftServer {
                    private final Path root;
                    private final boolean failPath;
                    public boolean sameThread;
                    public int pathReads, tickReads;
                    public MinecraftServer(Path root, boolean sameThread, boolean failPath) {
                        this.root = root; this.sameThread = sameThread; this.failPath = failPath;
                    }
                    public final net.minecraft.server.players.PlayerList players=new net.minecraft.server.players.PlayerList();
                    public net.minecraft.server.players.PlayerList getPlayerList(){return players;}
                    /** 2026-11-06：只给 EnvironmentRefresher 的维度枚举用；一个维度够验"到点才刷"。 */
                    public java.util.Collection<ServerLevel> levels=java.util.List.of(new ServerLevel());
                    public java.util.Collection<ServerLevel> getAllLevels(){return levels;}
                    public boolean isSameThread() { return sameThread; }
                    public Path getWorldPath(LevelResource ignored) {
                        pathReads++;
                        if (failPath) throw new IllegalStateException("path unavailable");
                        return root;
                    }
                    public int getTickCount() { tickReads++; return 42; }
                    public ServerLevel getLevel(ResourceKey<Level> key) { return null; }
                    @Override public boolean equals(Object other) { return other instanceof MinecraftServer; }
                    @Override public int hashCode() { return 1; }
                }
                """);
        sources.put("com.dwinovo.numen.entity.NumenPlayer", """
                package com.dwinovo.numen.entity;
                /** 2026-11-06：SELF 采样接线用的身体边界；只给"服务端句柄 + 身份 + 维度"，不含任何背包。 */
                public class NumenPlayer {
                    public String dimensionId="minecraft:overworld";
                    public java.util.UUID uuid=java.util.UUID.randomUUID();
                    public net.minecraft.server.MinecraftServer server;
                    public NumenPlayer serverLevel(){return this;} public NumenPlayer dimension(){return this;}
                    public String location(){return dimensionId;}
                    public java.util.UUID getUUID(){return uuid;}
                    public net.minecraft.server.MinecraftServer getServer(){return server;}
                }
                """);
        sources.put("com.dwinovo.numen.entity.CompanionBodySample", """
                package com.dwinovo.numen.entity;
                public class CompanionBodySample {
                    public static long epoch=1000,tick=42; public static int calls;
                    public record Frame(long tick,long epochMs){}
                    public static Frame capture(NumenPlayer body){calls++; return new Frame(tick,epoch);}
                }
                """);
        sources.put("net.minecraft.server.players.PlayerList", """
                package net.minecraft.server.players;
                public class PlayerList { public com.dwinovo.numen.entity.NumenPlayer player;
                    public com.dwinovo.numen.entity.NumenPlayer getPlayer(java.util.UUID id){return player;} }
                """);
        sources.put("net.minecraft.world.level.storage.LevelResource",
                "package net.minecraft.world.level.storage; public class LevelResource { public static final LevelResource ROOT = new LevelResource(); }");
        sources.put("net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level {}");
        sources.put("net.minecraft.server.level.ServerLevel", """
                package net.minecraft.server.level;
                public class ServerLevel {
                    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension(){
                        return net.minecraft.resources.ResourceKey.of("minecraft:overworld");
                    }
                }
                """);
        sources.put("net.minecraft.resources.ResourceLocation", """
                package net.minecraft.resources;
                public record ResourceLocation(String id) {
                    public static ResourceLocation parse(String id) { return new ResourceLocation(id); }
                    @Override public String toString() { return id; }   // 生产里 dimension().location().toString() 就是维度 id
                }
                """);
        sources.put("net.minecraft.resources.ResourceKey", """
                package net.minecraft.resources;
                public class ResourceKey<T> {
                    private String id = "minecraft:overworld";
                    public static <T> ResourceKey<T> create(Object registry, ResourceLocation location) {
                        ResourceKey<T> key = new ResourceKey<>(); key.id = location.id(); return key;
                    }
                    public static <T> ResourceKey<T> of(String id) {
                        ResourceKey<T> key = new ResourceKey<>(); key.id = id; return key;
                    }
                    public ResourceLocation location() { return new ResourceLocation(id); }
                }
                """);
        sources.put("net.minecraft.core.registries.Registries",
                "package net.minecraft.core.registries; public class Registries { public static final Object DIMENSION = new Object(); }");
        sources.put("org.slf4j.Logger", "package org.slf4j; public interface Logger { default void warn(String text, Object value) {} default void info(String text, Object... values) {} default void debug(String text, Object... values) {} }");
        sources.put("org.slf4j.LoggerFactory", """
                package org.slf4j;
                public class LoggerFactory { public static Logger getLogger(String name) { return new Logger() {}; } }
                """);
        sources.put(PKG + "WorldStateMonitor", "package " + PKG.substring(0, PKG.length() - 1)
                + "; public class WorldStateMonitor { public static void publish(String event, java.util.Map<String, Object> values) {} }");
        for (var entry : Map.of("Environment", "ENVIRONMENT", "EntityCount", "ENTITIES",
                "BlockCount", "BLOCKS", "Container", "CONTAINERS", "Self", "SELF").entrySet()) {
            String name = entry.getKey() + "WorldProvider";
            sources.put(PKG + "provider." + name, """
                    package com.dwinovo.numen.plugins.worldstate.provider;
                    import com.dwinovo.numen.worldstate.core.model.*;
                    import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
                    import com.dwinovo.numen.worldstate.core.provider.*;
                    import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
                    /** 2026-11-05：只替换采集边界并计数；不复制核心刷新逻辑，否则漏掉真实接线错误。 */
                    public class %s implements WorldProvider {
                        public static int calls;
                        public Topic topic() { return Topic.%s; }
                        public String id() { return "fixture-%s"; }
                        public int dataVersion() { return 1; }
                        /** 2026-11-06：周期环境刷新的失败分支用；false 时行为不变。 */
                        public static boolean failNext;
                        public ProviderResult collect(CollectionContext context, ObservationRequest request) {
                            calls++;
                            if (failNext && topic() == Topic.ENVIRONMENT) {
                                throw new ProviderException(FailReason.PROVIDER_ERROR, "fixture failure");
                            }
                            if (topic() == Topic.SELF) return ProviderResult.of(
                                new com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload(request.dimension(),
                                    java.util.UUID.fromString(request.target().substring(7)), null, null, null, null,
                                    null, null, null, null, null, null, null), Coverage.COMPLETE);
                            return ProviderResult.of(new EnvironmentPayload(request.dimension(), 6000L, true, false), Coverage.COMPLETE);
                        }
                    }
                    """.formatted(name, entry.getValue(), name));
        }
        // 2026-11-05：其余声明仅补类型边界，不模拟 MC 行为；不以游戏运行时掩盖离线依赖。
        sources.replaceAll((name, source) -> "/** 2026-11-05：离线类型边界；如线程失败用例只需签名，不以游戏启动替代纯接线测试。 */\n" + source);
        return sources;
    }
}
