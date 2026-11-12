package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
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
 * 2026-11-12: freeze delayed network-reply isolation at the real transport/dispatcher seam.
 * WHY: forget + a reused model call id lets an old wire result complete the new ToolCall.
 * USAGE: managed api:common test, exact ServerToolTransportReplyIsolationTest.
 * Whole production transport, dispatcher, ToolCall and invocation sources are compiled unchanged.
 * Only MC, payload records, registry and network IO are detached; no replacement pairing logic.
 * Unlike the local-callback sleep test, replies echo IDs captured from actual transport sends.
 * LIMIT: does not execute payload codecs/handlers, server admission, or a real client loop.
 * Test-only freeze: expected failing behavior is not a measured red until the managed run.
 *
 * ID impact audit on parent 70e515140 (no product change in this freeze):
 * - ToolCall.id and dispatcher result IDs belong to the model protocol; keep them verbatim.
 * - Transport currently keys IN_FLIGHT and ExecuteToolPayload by that same ID. An opaque
 *   per-send wire ID could isolate replies, with the original ToolCall retained as the value.
 * - ExecuteToolPayload/MAX_TOOL_CALL_ID_LENGTH and TaskResultPayload cap the wire ID at 128.
 *   Their server success/error paths echo it, including CompanionBrain sync outbox delivery.
 * - TaskRecord.getToolCallId, server tools journal/observer and learner SkillTaskFeedback
 *   would then see wire IDs. Client synthetic/local result records retain model IDs:
 *   evidence joins need an explicit relationship, not an assumption that both IDs are equal.
 * - TaskRecord.publicId() is "t" + its own internal sequence. CurrentTaskPayload mirror and
 *   sleep_interrupt use that public ID, NOT getToolCallId; those exact-match checks are separate.
 * - TaskRecord.isExternalCall uses the "mcp-" prefix; a future built-in wire ID must not
 *   accidentally select the external route. Actuator MCP IDs also pass through this transport;
 *   their existing mcp- namespace must be retained on the wire.
 * - Wire uniqueness does not revoke an ExecuteToolPayload already sent to the server.
 *   Server admission, owner/BLOCKED loop state, persistence and real network ordering are
 *   outside this test. The abort positive checks transport targeting, not the full owner loop.
 */
class ServerToolTransportReplyIsolationTest {
    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("api/common/src"))) root = root.getParent();
        assertNotNull(root, "repository source root must be available");
        Map<String, String> sources = new LinkedHashMap<>();
        for (String relative : List.of(
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ServerToolTransport.java",
                "api/common/src/client/java/com/dwinovo/numen/client/agent/ToolDispatcher.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCall.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolInvocation.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java")) {
            String source = Files.readString(root.resolve(relative));
            String pkg = source.substring(source.indexOf("package ") + 8, source.indexOf(';')).trim();
            String name = Path.of(relative).getFileName().toString().replace(".java", "");
            sources.put(pkg + "." + name, source);
        }
        sources.put("com.dwinovo.numen.api.Internal",
                "package com.dwinovo.numen.api; public @interface Internal {}");
        sources.put("com.dwinovo.numen.Constants",
                "package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log(); public static class Log {public void info(String s,Object...a){} public void warn(String s,Object...a){}}}");
        sources.put("net.minecraft.client.player.AbstractClientPlayer",
                "package net.minecraft.client.player; public class AbstractClientPlayer {}");
        sources.put("com.google.gson.JsonObject",
                "package com.google.gson; public class JsonObject {}");
        sources.put("com.google.gson.JsonParser",
                "package com.google.gson; public class JsonParser {public static JsonParser parseString(String s){return new JsonParser();} public JsonObject getAsJsonObject(){return new JsonObject();}}");
        sources.put("com.dwinovo.numen.agent.tool.ClientToolContext",
                "package com.dwinovo.numen.agent.tool; public record ClientToolContext(net.minecraft.client.player.AbstractClientPlayer entity,java.util.UUID entityUuid) implements ToolAnchor {}");
        sources.put("com.dwinovo.numen.agent.tool.NumenTool",
                "package com.dwinovo.numen.agent.tool; public interface NumenTool {String name(); void invoke(ToolCall call);}");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry",
                "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool tool; public static NumenTool resolve(String name){return tool;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolAliases",
                "package com.dwinovo.numen.agent.tool; public class ToolAliases {public static String resolve(String s){return s;}}");
        sources.put("com.dwinovo.numen.agent.tool.LocalToolCallLog",
                "package com.dwinovo.numen.agent.tool; public class LocalToolCallLog {public static void publishTransportBinding(java.util.UUID companionId,String modelId,String wireId,String tool){} public static boolean runsLocally(NumenTool t){return false;} public static void publishSynthetic(Object...a){} public static void publishResult(Object...a){} public static void publishCall(Object...a){} public static void publishRejected(Object...a){}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolDisclosure",
                "package com.dwinovo.numen.agent.tool; public class ToolDisclosure {public static String notExpanded(String s){return s;}}");
        sources.put("com.dwinovo.numen.task.TaskResult",
                "package com.dwinovo.numen.task; public record TaskResult(String text) {public static TaskResult fail(String s){return new TaskResult(s);} public String toJson(){return text;}}");
        sources.put("com.dwinovo.numen.api.CompanionEvent",
                "package com.dwinovo.numen.api; public class CompanionEvent {public static final Object ABORT=new Object();}");
        sources.put("com.dwinovo.numen.entity.CompanionEvents",
                "package com.dwinovo.numen.entity; public class CompanionEvents {public static void fire(Object a,Object b){}}");
        sources.put("com.dwinovo.numen.network.payload.ExecuteToolPayload",
                "package com.dwinovo.numen.network.payload; public record ExecuteToolPayload(java.util.UUID entityUuid,String toolCallId,String toolName,String argumentsJson) {}");
        sources.put("com.dwinovo.numen.network.payload.CancelTasksPayload",
                "package com.dwinovo.numen.network.payload; public record CancelTasksPayload(java.util.UUID entityUuid) {}");
        sources.put("com.dwinovo.numen.platform.Services", """
                package com.dwinovo.numen.platform;
                public class Services {
                  public static final Network NETWORK = new Network();
                  public static class Network {
                    public final java.util.List<Object> packets = new java.util.ArrayList<>();
                    public void sendToServer(Object packet) { packets.add(packet); }
                  }
                }
                """);
        sources.put("com.dwinovo.numen.client.agent.TransportReplyFixture", """
                package com.dwinovo.numen.client.agent;
                import java.util.*;
                import com.dwinovo.numen.agent.tool.*;
                import com.dwinovo.numen.network.payload.*;
                import com.dwinovo.numen.platform.Services;

                public class TransportReplyFixture {
                  static final Set<UUID> actors = new HashSet<>();
                  final UUID entityUuid = UUID.randomUUID();
                  final List<String> resultIds = new ArrayList<>(), results = new ArrayList<>();
                  int settled;
                  final ToolDispatcher dispatcher = new ToolDispatcher(entityUuid, new ToolDispatcher.Sink() {
                    public void onResult(ToolInvocation inv, String json) { resultIds.add(inv.id()); results.add(json); }
                    public void onAllSettled() { settled++; }
                    public net.minecraft.client.player.AbstractClientPlayer entity() { return null; }
                  });
                  public TransportReplyFixture() { actors.add(entityUuid); }
                  static void setup() {
                    for (UUID actor : actors) ServerToolTransport.forget(actor);
                    actors.clear();
                    Services.NETWORK.packets.clear();
                    ToolRegistry.tool = new NumenTool() {
                      public String name() { return "mine"; }
                      public void invoke(ToolCall call) { ServerToolTransport.ship(call); }
                    };
                  }
                  void dispatch(String... ids) {
                    dispatcher.dispatch(Arrays.stream(ids).map(id -> new ToolInvocation(id, "mine", "{\\"probe\\":1}")).toList(), Set.of("mine"));
                  }
                  static List<ExecuteToolPayload> sends() {
                    return Services.NETWORK.packets.stream().filter(ExecuteToolPayload.class::isInstance)
                        .map(ExecuteToolPayload.class::cast).toList();
                  }
                  static long cancels() {
                    return Services.NETWORK.packets.stream().filter(CancelTasksPayload.class::isInstance).count();
                  }
                  // This is the real deliver entry used by TaskResultPayload.handle; no direct ToolCall.complete.
                  static void reply(ExecuteToolPayload request, String result) {
                    ServerToolTransport.deliver(request.toolCallId(), result);
                  }
                  public static Map<String,Object> afterForgetAndReuse() {
                    setup(); var f = new TransportReplyFixture();
                    f.dispatch("same", "old-queued");
                    ExecuteToolPayload old = sends().get(0);
                    List<String> cancelled = f.dispatcher.cancelAndDrain(false);
                    f.dispatch("same", "fresh-next");
                    ExecuteToolPayload fresh = sends().get(1);
                    reply(old, "stale");
                    int resultsAfterOld = f.results.size(), sentAfterOld = sends().size();
                    boolean busyAfterOld = f.dispatcher.busy();
                    reply(fresh, "fresh");
                    ExecuteToolPayload next = sends().get(2);
                    reply(old, "stale-duplicate");
                    reply(next, "next");
                    Map<String,Object> r = new LinkedHashMap<>();
                    r.put("cancelled", cancelled); r.put("resultsAfterOld", resultsAfterOld);
                    r.put("sentAfterOld", sentAfterOld); r.put("busyAfterOld", busyAfterOld);
                    r.put("ids", List.copyOf(f.resultIds)); r.put("results", List.copyOf(f.results));
                    r.put("settled", f.settled); r.put("busy", f.dispatcher.busy()); r.put("cancels", cancels());
                    return r;
                  }
                  public static Map<String,Object> duplicateAfterSettlement() {
                    setup(); var f = new TransportReplyFixture();
                    f.dispatch("same"); ExecuteToolPayload old = sends().get(0);
                    reply(old, "old-ok");
                    f.dispatch("same", "next"); ExecuteToolPayload fresh = sends().get(1);
                    reply(old, "duplicate-old");
                    int resultsAfterDuplicate = f.results.size(), sentAfterDuplicate = sends().size();
                    reply(fresh, "fresh-ok"); reply(sends().get(2), "next-ok");
                    return Map.of("resultsAfterDuplicate", resultsAfterDuplicate,
                        "sentAfterDuplicate", sentAfterDuplicate, "ids", List.copyOf(f.resultIds),
                        "results", List.copyOf(f.results), "settled", f.settled, "busy", f.dispatcher.busy());
                  }
                  public static Map<String,Object> twoCompanionsReuseId() {
                    setup(); var a = new TransportReplyFixture(); var b = new TransportReplyFixture();
                    a.dispatch("same"); ExecuteToolPayload pa = sends().get(0);
                    b.dispatch("same"); ExecuteToolPayload pb = sends().get(1);
                    reply(pa, "for-a");
                    int aAfterFirst = a.results.size(), bAfterFirst = b.results.size();
                    reply(pb, "for-b");
                    return Map.of("aAfterFirst", aAfterFirst, "bAfterFirst", bAfterFirst,
                        "aResults", List.copyOf(a.results), "bResults", List.copyOf(b.results),
                        "aBusy", a.dispatcher.busy(), "bBusy", b.dispatcher.busy());
                  }
                  public static Map<String,Object> normalReplies() {
                    setup(); var f = new TransportReplyFixture();
                    String providerId = "p".repeat(128);
                    f.dispatch(providerId, "next"); ExecuteToolPayload first = sends().get(0);
                    reply(first, "ok-one"); ExecuteToolPayload next = sends().get(1);
                    reply(first, "duplicate"); reply(next, "ok-two"); reply(next, "duplicate");
                    return Map.of("ids", List.copyOf(f.resultIds), "results", List.copyOf(f.results),
                        "sent", sends().size(), "settled", f.settled, "busy", f.dispatcher.busy(),
                        "entity", first.entityUuid().equals(f.entityUuid), "tool", first.toolName(),
                        "args", first.argumentsJson(), "wireLength", first.toolCallId().length(),
                        "cancels", cancels());
                  }
                  public static Map<String,Object> scopedForget() {
                    setup(); var a = new TransportReplyFixture(); var b = new TransportReplyFixture();
                    a.dispatch("a"); ExecuteToolPayload pa = sends().get(0);
                    b.dispatch("b"); ExecuteToolPayload pb = sends().get(1);
                    List<String> cancelled = a.dispatcher.cancelAndDrain(false);
                    reply(pa, "cancelled-old"); reply(pb, "for-b");
                    a.dispatch("fresh"); reply(sends().get(2), "fresh-ok");
                    return Map.of("cancelled", cancelled, "aResults", List.copyOf(a.results),
                        "aIds", List.copyOf(a.resultIds), "bResults", List.copyOf(b.results),
                        "cancels", cancels(), "aBusy", a.dispatcher.busy(), "bBusy", b.dispatcher.busy());
                  }
                  public static Map<String,Object> scopedAbort() {
                    setup(); var a = new TransportReplyFixture(); var b = new TransportReplyFixture();
                    a.dispatch("a"); ExecuteToolPayload pa = sends().get(0);
                    b.dispatch("b"); ExecuteToolPayload pb = sends().get(1);
                    ServerToolTransport.abort(a.entityUuid);
                    reply(pa, "cancelled-old"); reply(pb, "for-b");
                    CancelTasksPayload cancel = (CancelTasksPayload) Services.NETWORK.packets.stream()
                        .filter(CancelTasksPayload.class::isInstance).findFirst().orElseThrow();
                    return Map.of("aResults", List.copyOf(a.results), "bResults", List.copyOf(b.results),
                        "cancels", cancels(), "target", cancel.entityUuid().equals(a.entityUuid),
                        "bBusy", b.dispatcher.busy());
                  }
                }
                """);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-classpath", build.toString(), "-d", build.toString()));
        for (var entry : sources.entrySet()) {
            Path file = build.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler required for production consumers");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "fixture compilation must succeed before a behavioral red is counted");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.client.agent.TransportReplyFixture");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> run(String method) throws Exception {
        return (Map<String, Object>) fixture.getMethod(method).invoke(null);
    }

    @Test void delayedReplyCannotSettleReusedIdAfterForgetOrDrainNext() throws Exception {
        var r = run("afterForgetAndReuse");
        assertEquals(List.of("same", "old-queued"), r.get("cancelled"));
        assertEquals(0, r.get("resultsAfterOld"), "old wire reply must not complete the fresh invocation");
        assertEquals(2, r.get("sentAfterOld"), "old result must not dispatch fresh-next");
        assertEquals(true, r.get("busyAfterOld"));
        assertEquals(List.of("same", "fresh-next"), r.get("ids"));
        assertEquals(List.of("fresh", "next"), r.get("results"));
        assertEquals(1, r.get("settled"));
        assertEquals(false, r.get("busy"));
        assertEquals(0L, r.get("cancels"));
    }

    @Test void duplicateReplyCannotSettleReusedIdAfterNormalCompletion() throws Exception {
        var r = run("duplicateAfterSettlement");
        assertEquals(1, r.get("resultsAfterDuplicate"), "old success must not complete another generation");
        assertEquals(2, r.get("sentAfterDuplicate"));
        assertEquals(List.of("same", "same", "next"), r.get("ids"));
        assertEquals(List.of("old-ok", "fresh-ok", "next-ok"), r.get("results"));
        assertEquals(2, r.get("settled"));
        assertEquals(false, r.get("busy"));
    }

    @Test void identicalModelIdsOnTwoCompanionsKeepTheirOwnReplies() throws Exception {
        var r = run("twoCompanionsReuseId");
        assertEquals(1, r.get("aAfterFirst"), "reply to a must complete a");
        assertEquals(0, r.get("bAfterFirst"), "reply to a must not steal b's callback");
        assertEquals(List.of("for-a"), r.get("aResults"));
        assertEquals(List.of("for-b"), r.get("bResults"));
        assertEquals(false, r.get("aBusy"));
        assertEquals(false, r.get("bBusy"));
    }

    @Test void normalRepliesPreserveProviderIdsAndCompleteExactlyOnce() throws Exception {
        var r = run("normalReplies");
        assertEquals(List.of("p".repeat(128), "next"), r.get("ids"));
        assertEquals(List.of("ok-one", "ok-two"), r.get("results"));
        assertEquals(2, r.get("sent")); assertEquals(1, r.get("settled"));
        assertEquals(false, r.get("busy")); assertEquals(true, r.get("entity"));
        assertEquals("mine", r.get("tool")); assertEquals("{\"probe\":1}", r.get("args"));
        assertTrue((int) r.get("wireLength") > 0 && (int) r.get("wireLength") <= 128);
        assertEquals(0L, r.get("cancels"));
    }

    @Test void forgetKeepsOtherCompanionAndFreshCallsAliveWithoutBodyCancel() throws Exception {
        var r = run("scopedForget");
        assertEquals(List.of("a"), r.get("cancelled"));
        assertEquals(List.of("fresh-ok"), r.get("aResults"));
        assertEquals(List.of("fresh"), r.get("aIds"));
        assertEquals(List.of("for-b"), r.get("bResults"));
        assertEquals(0L, r.get("cancels"));
        assertEquals(false, r.get("aBusy")); assertEquals(false, r.get("bBusy"));
    }

    @Test void abortStillForgetsOnlyTargetAndSendsItsCancel() throws Exception {
        var r = run("scopedAbort");
        assertEquals(List.of(), r.get("aResults")); assertEquals(List.of("for-b"), r.get("bResults"));
        assertEquals(1L, r.get("cancels")); assertEquals(true, r.get("target"));
        assertEquals(false, r.get("bBusy"));
    }
}
