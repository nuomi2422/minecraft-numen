package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.DeliveredContextDispatch;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 2026-11-13 C1 真实注册路径：{@code DeliveredContextDispatch.deliveryDispatched → context sink → UsageLedger}。
 * 不是直接调中性 helper：这里注册的是 setup 同一段 {@link DeliveryContextSink}，
 * 由父 API 的真实派发纯函数（llm_request/dispatched + requestId CAS）触发。
 */
class DeliveryDispatchIntegrationTest {
    private static final UUID C = UUID.fromString("a43d753e-790d-472b-8d5b-dc18ac0afbc0");

    @TempDir Path dir;

    private static NumenPlugins.DeliveredContext ctx(String requestId) {
        var token = new WorldContextStamp.Token(Map.of("world_id", "live-x", "server_session", "s-1"), 1L);
        return new NumenPlugins.DeliveredContext(C, token, requestId, "execution", "EXECUTOR", "", "",
                List.of(new NumenPlugins.DeliveredRef("skill:sk-int", "v1", false)));
    }

    private static void dispatch(AtomicReference<NumenPlugins.DeliveredContext> pending, String requestId) {
        DeliveredContextDispatch.deliveryDispatched(pending, "llm_request",
                Map.of("status", "dispatched", "requestId", requestId));
    }

    /** Source wiring only: avoid initializing the MC facade while pinning its real delegates. */
    private static void assertProductionFacadeDelegates() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/api/NumenPlugins.java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "source tree must be available for the production wiring check");
        String facade = Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/api/NumenPlugins.java"));
        assertTrue(facade.contains("DeliveredContextDispatch.sinkRequestDeliveredWithContext(sink);"));
        assertTrue(facade.contains("DeliveredContextDispatch.sinkRequestDelivered(sink);"));
        assertTrue(facade.contains("DeliveredContextDispatch.deliveryDispatched(pending, type, event);"));
        assertTrue(facade.contains("DeliveredContextDispatch.requestDelivered(context);"));
        assertFalse(facade.contains("CONTEXT_DELIVERED_SINKS"), "facade must not retain a parallel sink table");
        assertFalse(facade.contains("DELIVERED_SINKS"), "facade must not retain a parallel legacy table");
        String setup = Files.readString(root.resolve(
                "plugins/learner/src/main/java/com/dwinovo/numen/plugins/learner/LearnerPlugin.java"));
        assertTrue(setup.contains("NumenPlugins.sinkRequestDeliveredWithContext("));
        assertTrue(setup.contains("DeliveryContextSink.forLedger("), "setup must register the same ledger mapping");
    }

    @Test
    void realDispatchWritesOneRowAndReplayAddsNothing() throws IOException {
        assertProductionFacadeDelegates();
        UsageLedger ledger = new UsageLedger(dir);
        DeliveredContextDispatch.sinkRequestDeliveredWithContext(DeliveryContextSink.forLedger(ledger));

        var pending = new AtomicReference<>(ctx("req-int-1"));
        dispatch(pending, "req-int-1");
        assertEquals(1, ledger.all().size(), "真实派发 → 注册 sink → 恰一行 PRESENTED/UNKNOWN");
        assertEquals(UsageLedger.Phase.PRESENTED, ledger.all().get(0).phase());
        assertEquals(UsageLedger.Outcome.UNKNOWN, ledger.all().get(0).outcome());
        assertNull(pending.get(), "同一原请求一次性消费（CAS）");

        var replay = new AtomicReference<>(ctx("req-int-1"));
        dispatch(replay, "req-int-1");
        assertEquals(1, ledger.all().size(), "重放零增");
    }

    @Test
    void missingOrMismatchedRequestIdNeverReachesTheSink() {
        UsageLedger ledger = new UsageLedger(dir);
        DeliveredContextDispatch.sinkRequestDeliveredWithContext(DeliveryContextSink.forLedger(ledger));

        var blank = new AtomicReference<>(ctx(""));
        dispatch(blank, "");
        assertEquals(0, ledger.all().size(), "无原 requestId 不入候选");

        var mismatch = new AtomicReference<>(ctx("req-a"));
        dispatch(mismatch, "req-b");
        assertEquals(0, ledger.all().size(), "派发事件 requestId 不匹配不消费");

        var notDispatched = new AtomicReference<>(ctx("req-a"));
        DeliveredContextDispatch.deliveryDispatched(notDispatched, "llm_request",
                Map.of("status", "composed", "requestId", "req-a"));
        assertEquals(0, ledger.all().size(), "组装/未派发不记");
    }

    private record Warning(String template, List<Object> fields) {}

    /** Capture the actual SLF4J warn call; the dispatch, sink and filesystem remain real. */
    private static Logger captureWarnings(List<Warning> warnings) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    if ("warn".equals(method.getName())) {
                        warnings.add(new Warning((String) args[0], List.of((Object[]) args[1])));
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test
    void blockedConfigReportsIoFailureAndExplicitRedeliveryRepairsOnce() throws IOException {
        Path blockedConfig = dir.resolve("blocked-config");
        Files.writeString(blockedConfig, "occupied by a regular file");
        assertIoFailureAndExplicitRepair(new UsageLedger(blockedConfig), blockedConfig, "req-io-config");
    }

    @Test
    void directoryAtLedgerReportsIoFailureAndExplicitRedeliveryRepairsOnce() throws IOException {
        UsageLedger ledger = new UsageLedger(dir.resolve("directory-ledger"));
        Files.createDirectories(ledger.file());
        assertIoFailureAndExplicitRepair(ledger, ledger.file(), "req-io-ledger");
    }

    private static void assertIoFailureAndExplicitRepair(UsageLedger ledger, Path obstruction,
                                                        String requestId) throws IOException {
        List<Warning> warnings = new ArrayList<>();
        DeliveredContextDispatch.sinkRequestDeliveredWithContext(
                DeliveryContextSink.forLedger(ledger, captureWarnings(warnings)));
        AtomicInteger followingSink = new AtomicInteger();
        DeliveredContextDispatch.sinkRequestDeliveredWithContext(delivered -> {
            if (requestId.equals(delivered.requestId())) followingSink.incrementAndGet();
        });

        var pending = new AtomicReference<>(ctx(requestId));
        assertDoesNotThrow(() -> dispatch(pending, requestId), "IO failure must not block the request");
        assertNull(pending.get(), "failed delivery still consumes the original dispatch CAS");
        assertEquals(1, followingSink.get(), "the following registered sink must still run");
        assertTrue(ledger.all().isEmpty(), "IO failure must not fabricate a PRESENTED row");
        assertTrue(Files.exists(obstruction), "the real filesystem obstruction remains intact");
        assertEquals(1, warnings.size(), "the returned ioFailed flag must reach the diagnostic exit");
        Warning warning = warnings.get(0);
        assertTrue(warning.template().contains("ioFailed=true"));
        assertEquals(List.of(requestId, C, ledger.file(), 0, 0), warning.fields(),
                "diagnostic identifies the failed request and reports written=0");

        Files.delete(obstruction);
        dispatch(pending, requestId);
        assertTrue(ledger.all().isEmpty(), "repair alone must not replay an already consumed CAS");
        assertEquals(1, followingSink.get());
        assertEquals(1, warnings.size());

        assertDoesNotThrow(() -> dispatch(new AtomicReference<>(ctx(requestId)), requestId));
        assertEquals(1, ledger.all().size(), "explicit redelivery repairs the same ledger once");
        assertEquals(UsageLedger.Phase.PRESENTED, ledger.all().get(0).phase());
        assertEquals(UsageLedger.Outcome.UNKNOWN, ledger.all().get(0).outcome());
        assertEquals(2, followingSink.get());
        assertEquals(1, warnings.size(), "successful repair must not emit another IO failure");

        dispatch(new AtomicReference<>(ctx(requestId)), requestId);
        assertEquals(1, ledger.all().size(), "repeated explicit delivery must not append a duplicate");
        assertEquals(3, followingSink.get(), "ledger dedup must not suppress other dispatch sinks");
        assertEquals(1, warnings.size());
    }
}
