package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.DeliveredContextDispatch;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
}
