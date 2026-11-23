package com.dwinovo.numen.entity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 055 第二片：复活解冻(handleRespawn -> onRespawned -> 同步开轮)的<b>真实发送顺序</b>消费者测试。
 *
 * <p>从生产 {@code Companions#sendRespawnThaw} <b>逐字抽取</b>方法体执行；边界只有：
 * {@code Services.NETWORK}(录制 sink)、{@code RequestStatePayload}/{@code NumenRespawnPayload}
 * 载荷桩、以及一个"客户端缓存"模型(收到 state = 缓存换成该背包;收到 thaw = 记下此刻缓存)。
 * 判据看的是<b>执行后的包序列和 thaw 时的缓存</b>,不是源码 indexOf；同样判据必须把反序判红。
 * 不覆盖 MC 调度、真实 ItemStack 序列化、网络丢失；这些由实机验收补。
 */
class CompanionsRespawnThawOrderTest {

    @TempDir static Path temp;
    private static Class<?> harness;
    private static URLClassLoader loader;
    private static Path root;

    @BeforeAll static void compileHarness() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/Companions.java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "SETUP: repository root missing");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        String companions = Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/Companions.java"));

        Map<String, String> units = new LinkedHashMap<>();
        units.put("com/dwinovo/numen/platform/Services", """
                package com.dwinovo.numen.platform;
                public class Services {
                  public static final Network NETWORK = new Network();
                  public static class Network {
                    public static java.util.function.BiConsumer<Object,Object> sink = (p, payload) -> {};
                    public void sendToPlayer(Object player, Object payload) { sink.accept(player, payload); }
                  }
                }
                """);
        units.put("com/dwinovo/numen/network/payload/RequestStatePayload", """
                package com.dwinovo.numen.network.payload;
                public class RequestStatePayload {
                  public record StateFrame(String backpack) {}
                  public static StateFrame snapshot(com.dwinovo.numen.entity.NumenPlayer body) {
                    return new StateFrame(body.backpack);
                  }
                }
                """);
        units.put("com/dwinovo/numen/network/payload/NumenRespawnPayload", """
                package com.dwinovo.numen.network.payload;
                public record NumenRespawnPayload(java.util.UUID entityUuid, String cause) {}
                """);
        units.put("com/dwinovo/numen/entity/NumenPlayer", """
                package com.dwinovo.numen.entity;
                public class NumenPlayer {
                  public final String backpack;
                  public NumenPlayer(String backpack) { this.backpack = backpack; }
                }
                """);
        units.put("net/minecraft/server/level/ServerPlayer",
                "package net.minecraft.server.level; public class ServerPlayer {}");
        units.put("com/dwinovo/numen/entity/RespawnThawHarness", """
                package com.dwinovo.numen.entity;
                import java.util.*;
                import com.dwinovo.numen.platform.Services;
                import com.dwinovo.numen.network.payload.RequestStatePayload;
                import com.dwinovo.numen.network.payload.NumenRespawnPayload;
                import net.minecraft.server.level.ServerPlayer;
                public class RespawnThawHarness {
                """ + method(companions, "    static void sendRespawnThaw(") + """

                  static final List<String> EVENTS = new ArrayList<>();
                  static String clientCache = null;
                  static String cacheAtThaw = null;

                  static void reset() { EVENTS.clear(); clientCache = null; cacheAtThaw = null; }

                  static void install() {
                    Services.NETWORK.sink = (player, payload) -> {
                      if (payload instanceof RequestStatePayload.StateFrame frame) {
                        EVENTS.add("state");
                        clientCache = frame.backpack();   // 客户端收到 state:缓存换成新身体
                      } else if (payload instanceof NumenRespawnPayload thaw) {
                        EVENTS.add("respawn");
                        cacheAtThaw = clientCache;        // thaw 抵达这一刻缓存里是什么
                      }
                    };
                  }

                  public static List<Object> run(String backpack, boolean thawFirst) {
                    reset(); install();
                    NumenPlayer body = new NumenPlayer(backpack);
                    ServerPlayer owner = new ServerPlayer();
                    UUID uuid = UUID.randomUUID();
                    if (thawFirst) reversedThawFirst(owner, body, uuid, "测试死因");
                    else sendRespawnThaw(owner, body, uuid, "测试死因");
                    return Arrays.asList(EVENTS, cacheAtThaw, clientCache);
                  }

                  /** 反序对照:只证明同一个判据分得出顺序,不是生产代码。 */
                  static void reversedThawFirst(ServerPlayer owner, NumenPlayer body, UUID uuid, String cause) {
                    Services.NETWORK.sendToPlayer(owner, new NumenRespawnPayload(uuid, cause));
                    Services.NETWORK.sendToPlayer(owner, RequestStatePayload.snapshot(body));
                  }
                }
                """);

        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", classes.toString()));
        for (var entry : units.entrySet()) {
            Path file = temp.resolve(entry.getKey() + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        var diagnostics = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, rc, "SETUP: respawn-thaw harness compile failed:\n" + diagnostics);
        loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        harness = loader.loadClass("com.dwinovo.numen.entity.RespawnThawHarness");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void thawArrivesOnlyAfterTheClientAlreadyHasTheNewBodyState() throws Exception {
        List<?> ok = run("PICKAXE");
        assertEquals(List.of("state", "respawn"), ok.get(0), "真实顺序必须是 state->thaw");
        assertEquals("PICKAXE", ok.get(1), "thaw 抵达时客户端缓存必须已是新身体背包");
        assertEquals("PICKAXE", ok.get(2));
    }

    @Test void emptyBackpackStillSendsAFrame() throws Exception {
        List<?> ok = run("");
        assertEquals(List.of("state", "respawn"), ok.get(0));
        assertEquals("", ok.get(1), "空背包也要有一帧(不是不发),否则解冻首轮会读回旧内容");
    }

    @Test void theSameOracleRejectsThawFirst() throws Exception {
        List<?> bad = runReversed("PICKAXE");
        assertNotEquals(List.of("state", "respawn"), bad.get(0), "反序必须被同一判据判红");
        assertFalse(cacheIsNewBody(bad), "thaw 先到就不可能看到新背包");
    }

    @Test void productionHasExactlyOneThawConstructionAndItIsInsideTheSeam() throws Exception {
        String companions = Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/Companions.java"));
        assertEquals(1, countOf(companions, "new NumenRespawnPayload("),
                "thaw 只许从 sendRespawnThaw 发,不能有绕过 prime 的第二处");
        String seam = method(companions, "    static void sendRespawnThaw(");
        assertTrue(seam.contains("RequestStatePayload.snapshot(body)"), seam);
        assertTrue(seam.indexOf("RequestStatePayload.snapshot(body)") < seam.indexOf("new NumenRespawnPayload("),
                "缝内先 state 后 thaw(执行测试已覆盖顺序,这里只防退化)");
    }

    private static List<?> run(String backpack) throws Exception { return run0(backpack, false); }
    private static List<?> runReversed(String backpack) throws Exception { return run0(backpack, true); }
    private static List<?> run0(String backpack, boolean thawFirst) throws Exception {
        return (List<?>) harness.getMethod("run", String.class, boolean.class).invoke(null, backpack, thawFirst);
    }
    private static boolean cacheIsNewBody(List<?> result) {
        return List.of("state", "respawn").equals(result.get(0)) && result.get(1) != null;
    }
    private static int countOf(String text, String needle) {
        int count = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
        return count;
    }
    /** 从源码里按大括号平衡取出一个方法(含签名行)。 */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "SETUP: method missing: " + signature);
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("SETUP: unbalanced method: " + signature);
    }
}