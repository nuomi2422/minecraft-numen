package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 80 号契约 §4 / 83 号 §9 的**片段版聚合记账规则**（REQUEST_DELIVERED 的上游）：
 *
 * <ol>
 *   <li>片段整体接受 → 它的 refs 全部 delivered=false；</li>
 *   <li>片段被切断 → 跨切点/找不到边界的 refs 记 truncated=true（审计可见，不算回执）；
 *       闭合标签完整落在保留前缀内的条目仍记 false（2026-10-31 V-20261031-03 修正）；</li>
 *   <li>预算满后不再处理的片段 → 一条不记（先裁后记）；</li>
 *   <li>落账终点只收非 truncated 的 refs；终点抛异常不许冒泡。</li>
 * </ol>
 *
 * <p>纯函数级测试：不依赖 MC、不依赖 ExposureLedger（api 不依赖 experience-core）。
 * 「REQUEST_DELIVERED 算回执」在 experience-core 的 {@code ExposureLedgerTest} 里已有覆盖。
 */
class RequestDeliveredAggregationTest {

    private static final UUID C = UUID.randomUUID();

    private static NumenPlugins.KnowledgeContributor frag(String text, String... refs) {
        return (uuid, objective) -> {
            List<NumenPlugins.DeliveredRef> rs = new ArrayList<>();
            for (String r : refs) {
                rs.add(new NumenPlugins.DeliveredRef(r, "v1", false));
            }
            return new NumenPlugins.KnowledgeFragment(text, rs);
        };
    }

    @Test
    void acceptedFragmentMarksAllRefsDelivered() {
        NumenPlugins.KnowledgeFragment out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(frag("<entry ref=/>", "exp:a", "exp:b")), C, "goal");
        assertEquals("<entry ref=/>", out.text());
        assertEquals(2, out.refs().size());
        assertTrue(out.refs().stream().noneMatch(NumenPlugins.DeliveredRef::truncated));
        assertTrue(out.refs().stream().anyMatch(r -> r.entryRef().equals("exp:a")));
        assertTrue(out.refs().stream().anyMatch(r -> r.entryRef().equals("exp:b")));
    }

    @Test
    void truncatedFragmentMarksItsRefsTruncated() {
        String big = "x".repeat(NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS + 50);
        NumenPlugins.KnowledgeFragment out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(frag(big, "exp:cut")), C, "");
        assertEquals(NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS, out.text().length());
        assertEquals(1, out.refs().size());
        assertTrue(out.refs().get(0).truncated(), "被切断的片段：refs 记 truncated=true（审计可见，不算回执）");
    }

    @Test
    void truncatedFragmentKeepsFullyIncludedEntryAsDelivered() {
        // 2026-10-31 V-20261031-03：片段超 4000 被切断，但 exp:a 的 </entry> 在切点之前 ——
        // 它其实完整进了正文，不能因为「片段被切」就把它记成 truncated（否则 request_delivered 归零）。
        String a = "<entry ref=\"exp:a\">" + "A".repeat(3000) + "</entry>";
        String b = "<entry ref=\"exp:b\">" + "B".repeat(2000) + "</entry>";
        NumenPlugins.KnowledgeFragment out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(frag(a + b, "exp:a", "exp:b")), C, "");
        assertEquals(NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS, out.text().length());
        assertEquals(2, out.refs().size());
        java.util.Map<String, Boolean> cut = new java.util.HashMap<>();
        for (NumenPlugins.DeliveredRef r : out.refs()) cut.put(r.entryRef(), r.truncated());
        assertFalse(cut.get("exp:a"), "exp:a 完整落在保留前缀内，应记 false");
        assertTrue(cut.get("exp:b"), "exp:b 跨切点，应记 truncated=true");
    }

    @Test
    void fragmentsAfterBudgetAreNotRecorded() {
        String big = "x".repeat(NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS);
        NumenPlugins.KnowledgeFragment out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(frag(big, "exp:first"), frag("y", "exp:never")), C, "");
        assertTrue(out.text().contains("x"));
        assertEquals(1, out.refs().size(), "预算满后的片段一条不记（先裁后记）");
        assertEquals("exp:first", out.refs().get(0).entryRef());
    }

    @Test
    void blankFragmentYieldsEmptyTextAndNoRefs() {
        NumenPlugins.KnowledgeFragment out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(frag("   ", "exp:blank")), C, "");
        assertEquals("", out.text());
        assertEquals(0, out.refs().size());
    }

    @Test
    void throwingContributorIsIsolated() {
        NumenPlugins.KnowledgeFragment out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of(
                        (uuid, obj) -> { throw new IllegalStateException("boom"); },
                        frag("ok", "exp:after")), C, "");
        assertEquals("ok", out.text());
        assertEquals(1, out.refs().size());
    }

    @Test
    void taskAttributionMustMatchRequestBodyNotLaterSameWorldOwner() {
        var original = new WorldContextStamp.Token(java.util.Map.of("world_id", "w", "server_session", "s"), 1L);
        var otherEpoch = new WorldContextStamp.Token(original.identity(), 2L);
        assertTrue(NumenPlugins.deliveryTaskMatches("tA", "tA", original, original));
        assertFalse(NumenPlugins.deliveryTaskMatches("tA", "tB", original, original),
                "Same world is insufficient: do not attach later B to a body about A");
        assertFalse(NumenPlugins.deliveryTaskMatches("tA", "", original, null), "Ended task is not idle episode");
        assertFalse(NumenPlugins.deliveryTaskMatches("", "tB", original, original), "Task-free body must not acquire later B");
        assertFalse(NumenPlugins.deliveryTaskMatches("tA", "tA", original, otherEpoch));
        assertFalse(NumenPlugins.deliveryTaskMatches("tA", "tA", null, original));
        assertTrue(NumenPlugins.deliveryTaskMatches("", "", original, null), "Truly task-free dispatch stays delivery-only");
    }

    @Test
    void onlyOriginalActualDispatchConsumesDeliveryAndTransportRetryIsIdempotent() {
        UUID companion = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        NumenPlugins.sinkRequestDeliveredWithContext(context -> {
            if (companion.equals(context.companion())) calls.incrementAndGet();
        });
        var original = new NumenPlugins.DeliveredContext(companion,
                new WorldContextStamp.Token(java.util.Map.of("world_id", "w", "server_session", "s"), 1L),
                "original-dispatch", "execution", "EXECUTOR", "t9", "call9",
                List.of(new NumenPlugins.DeliveredRef("skill:kept", "v1", false)));
        var pending = new AtomicReference<>(original);
        var dispatched = java.util.Map.<String, Object>of("requestId", original.requestId(), "status", "dispatched");
        NumenPlugins.deliveryDispatched(pending, "runtime_state", dispatched);
        NumenPlugins.deliveryDispatched(pending, "llm_response", dispatched);
        NumenPlugins.deliveryDispatched(pending, "llm_request", java.util.Map.of("requestId", "other", "status", "dispatched"));
        NumenPlugins.deliveryDispatched(pending, "llm_request", java.util.Map.of("requestId", original.requestId(), "status", "prepared"));
        assertSame(original, pending.get(), "Unsent or mismatched events must not consume a candidate");
        assertEquals(0, calls.get());
        NumenPlugins.deliveryDispatched(pending, "llm_request", dispatched);
        assertNull(pending.get());
        assertEquals(1, calls.get());
        NumenPlugins.deliveryDispatched(pending, "llm_request", dispatched);
        assertEquals(1, calls.get(), "Transport retry is still the original dispatch evidence");
    }

    @Test
    void contextualDeliveryPreservesOriginalIdentityAndFiltersBeforeBothConsumers() {
        UUID companion = UUID.randomUUID();
        AtomicReference<NumenPlugins.DeliveredContext> got = new AtomicReference<>();
        AtomicReference<List<NumenPlugins.DeliveredRef>> legacy = new AtomicReference<>();
        NumenPlugins.sinkRequestDeliveredWithContext(context -> {
            if (companion.equals(context.companion())) throw new IllegalStateException("broken earlier sink");
        });
        NumenPlugins.sinkRequestDeliveredWithContext(context -> {
            if (companion.equals(context.companion())) got.set(context);
        });
        NumenPlugins.sinkRequestDelivered((who, refs) -> {
            if (companion.equals(who)) legacy.set(refs);
        });
        var identity = new java.util.HashMap<>(java.util.Map.of("world_id", "world-original", "server_session", "server-original"));
        var token = new WorldContextStamp.Token(identity, 1L);
        var refs = new ArrayList<>(List.of(new NumenPlugins.DeliveredRef("skill:keep", "v1", false),
                new NumenPlugins.DeliveredRef("skill:cut", "v2", true)));
        var original = new NumenPlugins.DeliveredContext(companion, token, "request-original", "execution_retry",
                "EXECUTOR", "t9", "call-original", refs);
        identity.put("world_id", "later-world");
        refs.clear();
        assertDoesNotThrow(() -> NumenPlugins.requestDelivered(original));
        assertNotNull(got.get(), "One failing consumer must not suppress the later consumer");
        assertEquals("world-original", got.get().worldToken().identity().get("world_id"));
        assertEquals(1L, got.get().worldToken().epoch());
        assertEquals("request-original", got.get().requestId());
        assertEquals("execution_retry", got.get().phase());
        assertEquals("t9", got.get().taskId());
        assertEquals("call-original", got.get().taskToolCallId());
        assertEquals(List.of("skill:keep"), got.get().refs().stream().map(NumenPlugins.DeliveredRef::entryRef).toList());
        assertEquals(got.get().refs(), legacy.get());
        assertThrows(UnsupportedOperationException.class, () -> got.get().refs().clear());
    }

    @Test
    void legacyDeliveryCannotManufactureRequestOrWorldIdentityAndAllTruncatedIsNotDelivered() {
        UUID companion = UUID.randomUUID();
        AtomicReference<NumenPlugins.DeliveredContext> got = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        NumenPlugins.sinkRequestDeliveredWithContext(context -> {
            if (companion.equals(context.companion())) { got.set(context); calls.incrementAndGet(); }
        });
        NumenPlugins.requestDelivered(companion, List.of(new NumenPlugins.DeliveredRef("skill:keep", "v1", false)));
        assertEquals("", got.get().requestId());
        assertNull(got.get().worldToken());
        assertEquals("", got.get().taskId());
        NumenPlugins.requestDelivered(new NumenPlugins.DeliveredContext(companion,
                new WorldContextStamp.Token(java.util.Map.of("world_id", "w", "server_session", "s"), 1L),
                "not-delivered", "execution", "EXECUTOR", "", "",
                List.of(new NumenPlugins.DeliveredRef("skill:cut", "v1", true))));
        assertEquals(1, calls.get(), "All-truncated fragments must not reach either contextual or legacy consumers");
    }

    @Test
    void requestDeliveredForwardsOnlyNonTruncatedAndSwallowsSinkErrors() {
        AtomicReference<List<NumenPlugins.DeliveredRef>> got = new AtomicReference<>(List.of());
        AtomicInteger calls = new AtomicInteger();
        NumenPlugins.sinkRequestDelivered((uuid, refs) -> {
            calls.incrementAndGet();
            if (refs.stream().anyMatch(r -> r.entryRef().equals("exp:boom"))) {
                throw new IllegalStateException("sink broken");
            }
            got.set(refs);
        });
        try {
            // truncated 的不转发；转发过的要让坏 sink 不冒泡
            NumenPlugins.requestDelivered(C, List.of(
                    new NumenPlugins.DeliveredRef("exp:keep", "v1", false),
                    new NumenPlugins.DeliveredRef("exp:cut", "v1", true)));
            assertEquals(List.of("exp:keep"), got.get().stream().map(NumenPlugins.DeliveredRef::entryRef).toList());
            NumenPlugins.requestDelivered(C, List.of(new NumenPlugins.DeliveredRef("exp:boom", "v1", false)));
            assertTrue(calls.get() >= 2, "坏 sink 被吞，不影响其它终点/后续调用");
        } finally {
            // 测试隔离：通过重置静态表无法直接做（sink 列表无清空口），这里依赖顺序执行即可。
            // 若未来加 resetForTest，这里应补清理。
        }
    }
}
