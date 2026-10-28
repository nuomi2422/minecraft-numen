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
 *   <li>片段被切断 → 该片段 refs 全部 truncated=true（审计可见，不算回执）；</li>
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
