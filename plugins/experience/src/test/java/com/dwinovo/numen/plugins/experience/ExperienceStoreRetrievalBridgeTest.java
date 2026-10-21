package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.retrieval.EntryKind;
import com.dwinovo.numen.agent.retrieval.ExperienceEntryRead;
import com.dwinovo.numen.agent.retrieval.Maturity;
import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5d 判据 S1（桥形状）的离线证据：真 {@link ExperienceMemory} → 统一检索
 * {@link ExperienceEntryRead} 的形状转换，以及渲染进上下文的文本。
 *
 * <p>全部用临时经验库文件，不起游戏、不碰真实同伴数据。
 */
class ExperienceStoreRetrievalBridgeTest {

    @TempDir
    Path dir;

    private ExperienceMemory emptyMemory() {
        return ExperienceMemory.at(dir.resolve("exp-empty.jsonl"), new LexicalExperienceRetriever());
    }

    private ExperienceMemory memoryWith(ExperienceEntry... entries) {
        ExperienceMemory memory = ExperienceMemory.at(dir.resolve("exp.jsonl"), new LexicalExperienceRetriever());
        for (ExperienceEntry e : entries) {
            memory.learn(e);
        }
        return memory;
    }

    private static ExperienceEntry.Builder base(String id, String title, ExperienceMaturity maturity) {
        return ExperienceEntry.builder()
                .id(id)
                .type(ExperienceType.EXECUTION)
                .title(title)
                .description("desc-" + id)
                .maturity(maturity)
                .verifiedCount(3)
                .triggerStrings(List.of("挖矿"))
                .failureConditions("下雨天")
                .mechanism("先插火把再挖")
                .recommendedResponse("沿矿脉走");
    }

    // ---------- S1：ref 前缀 / kind / maturity / 直传字段 ----------

    @Test
    void 转换_ref有exp前缀且kind是EXPERIENCE() {
        ExperienceEntryRead read = ExperienceStoreRetrievalBridge.convert(
                base("e1", "矿洞安全", ExperienceMaturity.VERIFIED).build());
        assertNotNull(read);
        assertTrue(read.entryRef().startsWith(ExperienceStoreRetrievalBridge.REF_PREFIX),
                "entryRef 必须带 exp: 前缀，否则 MinimalRetrievalService 的 expand 路由认不到");
        assertEquals("exp:e1", read.entryRef());
        assertEquals(EntryKind.EXPERIENCE, read.kind());
        assertEquals("experience", read.source());
        assertEquals("矿洞安全", read.title());
        assertEquals("desc-e1", read.summary());
    }

    @Test
    void 转换_成熟度与验证数直传() {
        ExperienceEntryRead read = ExperienceStoreRetrievalBridge.convert(
                base("e2", "矿洞安全", ExperienceMaturity.GENERALIZED).build());
        assertEquals(Maturity.GENERALIZED, read.maturity());
        assertEquals(3, read.verifiedCount());
        assertFalse(read.retracted());
    }

    @Test
    void 转换_撤回状态直传() {
        ExperienceEntryRead read = ExperienceStoreRetrievalBridge.convert(
                base("e3", "矿洞安全", ExperienceMaturity.VERIFIED).retracted(true).build());
        assertNotNull(read);
        assertTrue(read.retracted());
    }

    @Test
    void 转换_触发词进适用条件_失效条件进排除条件() {
        ExperienceEntryRead read = ExperienceStoreRetrievalBridge.convert(
                base("e4", "矿洞安全", ExperienceMaturity.ATTEMPTED).build());
        assertEquals(List.of("挖矿"), read.applicableConditions());
        assertEquals(List.of("下雨天"), read.excludedConditions(),
                "七字段的 failureConditions 是单段文字，进契约要变成单元素列表");
        assertEquals(List.of("e4"), read.evidenceRefs());
    }

    @Test
    void 转换_正文含机制与推荐处理_超长截断() {
        ExperienceEntryRead shortBody = ExperienceStoreRetrievalBridge.convert(
                base("e5", "矿洞安全", ExperienceMaturity.VERIFIED).build());
        assertTrue(shortBody.bodyContent().contains("先插火把再挖"));
        assertTrue(shortBody.bodyContent().contains("推荐处理"));
        assertTrue(shortBody.bodyContent().length() <= ExperienceStoreRetrievalBridge.MAX_BODY_CHARS);

        String longMechanism = "挖".repeat(ExperienceStoreRetrievalBridge.MAX_BODY_CHARS * 3);
        ExperienceEntryRead longBody = ExperienceStoreRetrievalBridge.convert(
                base("e6", "矿洞安全", ExperienceMaturity.VERIFIED).mechanism(longMechanism).build());
        assertNotNull(longBody);
        assertEquals(ExperienceStoreRetrievalBridge.MAX_BODY_CHARS + 1, longBody.bodyContent().length(),
                "截断后要带一个省略号，长度 = 上限 + 1");
        assertTrue(longBody.bodyContent().endsWith("…"));
    }

    @Test
    void 转换_脏数据跳过不抛() {
        assertNull(ExperienceStoreRetrievalBridge.convert(null));
        // 空白 id 在 Builder.build() 里会被换成 stableKey（不会产生脏条目）——
        // 桥仍然自己再兜一次，这里只钉「null 不抛、不占位」。
    }

    // ---------- 快照：真 memory 读得出来 ----------

    @Test
    void 快照_空库返回空列表不抛() {
        assertTrue(ExperienceStoreRetrievalBridge.snapshot(emptyMemory()).isEmpty());
        assertTrue(ExperienceStoreRetrievalBridge.snapshot(null).isEmpty());
    }

    @Test
    void 快照_真memory全量转换() {
        ExperienceMemory memory = memoryWith(
                base("a1", "矿洞安全", ExperienceMaturity.VERIFIED).build(),
                base("a2", "夜晚赶路", ExperienceMaturity.ATTEMPTED).build());
        List<ExperienceEntryRead> reads = ExperienceStoreRetrievalBridge.snapshot(memory);
        assertEquals(2, reads.size());
        assertTrue(reads.stream().allMatch(r -> r.entryRef().startsWith("exp:")));
    }

    // ---------- 渲染：文本真的能进规划知识 ----------

    @Test
    void 渲染_进上下文有抬头有ref有成熟度() {
        ExperienceMemory memory = memoryWith(
                base("r1", "矿洞安全", ExperienceMaturity.VERIFIED).build());
        ExperienceStoreRetrievalBridge.Rendered rendered =
                ExperienceStoreRetrievalBridge.render(memory, "去挖矿", 8, 2400);

        assertEquals(1, rendered.entries());
        assertTrue(rendered.text().contains("<retrieved_knowledge"), rendered.text());
        // 落盘后 id 是 stableKey(type|title)，所以只钉前缀 + 标题，不钉临时 id
        assertTrue(rendered.text().contains("ref=\"exp:"), rendered.text());
        assertTrue(rendered.text().contains("maturity=\"VERIFIED\""), rendered.text());
        assertTrue(rendered.text().contains("矿洞安全"), rendered.text());
        assertEquals("OK", rendered.status());
    }

    @Test
    void 渲染_排除条件命中的条目不给规划器() {
        ExperienceMemory memory = memoryWith(
                base("f1", "雨天不要下矿", ExperienceMaturity.VERIFIED).build());
        ExperienceStoreRetrievalBridge.Rendered rendered =
                ExperienceStoreRetrievalBridge.render(memory, "下雨天下矿", 8, 2400);

        assertFalse(rendered.text().contains("ref=\"exp:f1\""),
                "applicability=FAIL 的条目不该出现在规划知识里：" + rendered.text());
    }

    @Test
    void 渲染_空库返回空文本而不是抛() {
        ExperienceStoreRetrievalBridge.Rendered rendered =
                ExperienceStoreRetrievalBridge.render(emptyMemory(), "随便走走", 8, 2400);
        assertEquals("", rendered.text());
        assertEquals(0, rendered.entries());
    }
}
