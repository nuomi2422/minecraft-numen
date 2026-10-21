package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * G5d 判据 S2（模式开关）的离线证据：两条路互斥，默认走旧路。
 *
 * <p>用假 memory / 计数器，不起游戏、不需要宿主 API 类路径。
 */
class ExperiencePlanningRouterTest {

    @TempDir
    Path dir;

    private ExperienceMemory memory() {
        ExperienceMemory memory = ExperienceMemory.at(dir.resolve("exp.jsonl"),
                new LexicalExperienceRetriever());
        memory.learn(ExperienceEntry.builder()
                .id("p1")
                .type(ExperienceType.EXECUTION)
                .title("矿洞安全")
                .description("desc")
                .maturity(ExperienceMaturity.VERIFIED)
                .verifiedCount(2)
                .triggerStrings(List.of("挖矿"))
                .mechanism("先插火把")
                .recommendedResponse("沿矿脉走")
                .build());
        return memory;
    }

    @Test
    void legacy默认_新检索零调用且不发事件() {
        AtomicInteger memoryCalls = new AtomicInteger();
        String text = ExperiencePlanningRouter.knowledgeFor(
                UUID.randomUUID(), "去挖矿", "stage-a", List.of(), false,
                id -> {
                    memoryCalls.incrementAndGet();
                    return memory();
                },
                rendered -> fail("legacy 模式不该发 retrieval_snapshot"));

        assertEquals(0, memoryCalls.get(), "legacy 模式不许碰统一检索的 memory 接缝");
        assertEquals("", text, "插件未 setup 时旧路读不到经验库 → 空文本，但规划照常");
    }

    @Test
    void unified_走新检索并产出可观测事件() {
        List<ExperienceStoreRetrievalBridge.Rendered> events = new ArrayList<>();
        String text = ExperiencePlanningRouter.knowledgeFor(
                UUID.randomUUID(), "去挖矿", "stage-a", List.of(), true,
                id -> memory(),
                events::add);

        assertTrue(text.contains("<retrieved_knowledge"), text);
        assertTrue(text.contains("ref=\"exp:"), text);   // 落盘 id 是 stableKey，不钉临时 id
        assertTrue(text.contains("矿洞安全"), text);
        assertEquals(1, events.size(), "unified 每轮要发一条 retrieval_snapshot");
        assertEquals(1, events.get(0).entries());
        assertEquals("experience", events.get(0).asEvent().get("providerId"));
    }

    @Test
    void unified_经验库取不到时降级为空文本不发事件也不炸() {
        List<ExperienceStoreRetrievalBridge.Rendered> events = new ArrayList<>();
        String text = ExperiencePlanningRouter.knowledgeFor(
                UUID.randomUUID(), "去挖矿", "stage-a", List.of(), true,
                id -> {
                    throw new IllegalStateException("库读不出来");
                },
                events::add);

        assertEquals("", text);
        assertEquals(1, events.size(), "降级也要留一条事件（entries=0 是证据，不是没有发生）");
        assertEquals(0, events.get(0).entries());
    }
}
