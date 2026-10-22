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

    // ---------- G5e：角色视图（EXECUTOR 只信已验证） ----------

    @Test
    void 执行视图只留已验证条目_规划视图保留全部() {
        ExperienceMemory memory = memoryWith(
                base("g1", "只观察到的做法", ExperienceMaturity.OBSERVED).build(),
                base("g2", "已验证的做法", ExperienceMaturity.VERIFIED).build());

        ExperienceStoreRetrievalBridge.Rendered planner = ExperienceStoreRetrievalBridge.render(
                memory, "挖矿", 8, 2400, com.dwinovo.numen.agent.retrieval.RetrievalRole.PLANNER);
        ExperienceStoreRetrievalBridge.Rendered executor = ExperienceStoreRetrievalBridge.render(
                memory, "挖矿", 8, 2400, com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);

        assertTrue(planner.text().contains("只观察到的做法"), "PLANNER 要看到待核实的线索：" + planner.text());
        assertFalse(executor.text().contains("只观察到的做法"),
                "EXECUTOR 只收 maturity≥VERIFIED：" + executor.text());
        assertTrue(executor.text().contains("已验证的做法"), executor.text());
        assertTrue(executor.text().contains("role=\"executor\""), "执行块必须带 role 标记：" + executor.text());
        assertFalse(planner.text().contains("role="), "PLANNER 抬头保持 G5d 形状：" + planner.text());
        assertEquals("executor", executor.role());
        assertEquals("planner", planner.role());
    }

    @Test
    void 全是观察到时_执行视图为空集但仍报角色() {
        ExperienceMemory memory = memoryWith(
                base("h1", "还没验证的做法", ExperienceMaturity.OBSERVED).build());

        ExperienceStoreRetrievalBridge.Rendered executor = ExperienceStoreRetrievalBridge.render(
                memory, "", 8, 2400, com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);

        assertEquals("", executor.text(), "空集不渲染空标题");
        assertEquals(0, executor.entries());
        assertEquals("executor", executor.role(), "事件仍要能看出这是 EXECUTOR 视图");
    }

    // ---------- G5f：展开只读 ----------

    @Test
    void 展开_命中返回正文且只读() {
        ExperienceMemory memory = memoryWith(
                base("i1", "可展开的经验", ExperienceMaturity.VERIFIED)
                        .mechanism("机制正文").recommendedResponse("按它做").build());

        ExperienceStoreRetrievalBridge.Expanded r =
                ExperienceStoreRetrievalBridge.expand(memory, "exp:execution|可展开的经验", 800);

        assertTrue(r.ok(), r.code() + " / " + r.message());
        assertEquals("exp:execution|可展开的经验", r.ref());
        assertTrue(r.body().contains("机制正文"), r.body());
        assertTrue(r.body().contains("推荐处理"), r.body());
        assertFalse(r.truncated());
    }

    @Test
    void 展开_超maxChars截断并标记() {
        ExperienceMemory memory = memoryWith(
                base("i2", "长经验", ExperienceMaturity.VERIFIED)
                        .mechanism("很长很长的机制正文很长很长的机制正文").build());

        ExperienceStoreRetrievalBridge.Expanded r =
                ExperienceStoreRetrievalBridge.expand(memory, "exp:execution|长经验", 5);

        assertTrue(r.ok(), r.code());
        assertTrue(r.truncated());
        assertTrue(r.chars() <= 6, "截断后最多 5+1：" + r.chars());
    }

    @Test
    void 展开_未知ref与tool前缀都如实失败() {
        ExperienceMemory memory = memoryWith(
                base("i3", "随便一条", ExperienceMaturity.VERIFIED).build());

        ExperienceStoreRetrievalBridge.Expanded missing =
                ExperienceStoreRetrievalBridge.expand(memory, "exp:execution|不存在", 800);
        assertEquals("NOT_FOUND", missing.code(), "找不到必须报错，不许空正文冒充成功");
        assertEquals("", missing.body());

        // G5g：tool: 前缀现在有 owner 了（tool-catalog）。单测类路径下没有真工具表，
        // 所以这里如实回 NOT_FOUND —— 关键是**不再是 NO_OWNER**，也不是空正文冒充成功。
        ExperienceStoreRetrievalBridge.Expanded tool =
                ExperienceStoreRetrievalBridge.expand(memory, "tool:goto", 800);
        assertEquals("NOT_FOUND", tool.code(), "有 owner 但目录里没有这条 → NOT_FOUND（不是 NO_OWNER）");
        assertEquals("", tool.body());
    }

    // ---------- G6c/G6a-ledger：TRIAL 进执行视图并带标记 ----------

    @Test
    void TRIAL条目进执行视图且标题带试用标记() {
        ExperienceMemory memory = memoryWith(base("t1", "试用条目", ExperienceMaturity.VERIFIED)
                .publication(com.dwinovo.numen.experience.api.PublicationState.TRIAL)
                .build());

        ExperienceStoreRetrievalBridge.Rendered r = ExperienceStoreRetrievalBridge.render(
                memory, "", 8, 1100, com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);

        assertEquals(1, r.entries(), "TRIAL 必须进执行视图（否则试用永远攒不出证据）");
        assertTrue(r.text().contains("（试用中）"), "标题必须带试用标记，实际：" + r.text());
        assertEquals(1, TrialRuntime.trialRefsIn(r.text()).size(), "trialRefsIn 必须能抓到它");
    }

    @Test
    void 试用条目优先占槽_不被高成熟度长条目挤出预算() {
        // 三条已发布长条目 + 一条试用；预算只够 1-2 条时，试用必须先出来
        ExperienceMemory memory = memoryWith(
                base("p1", "已发布甲", ExperienceMaturity.VERIFIED).publication(com.dwinovo.numen.experience.api.PublicationState.PUBLISHED).build(),
                base("p2", "已发布乙", ExperienceMaturity.VERIFIED).publication(com.dwinovo.numen.experience.api.PublicationState.PUBLISHED).build(),
                base("p3", "已发布丙", ExperienceMaturity.GENERALIZED).publication(com.dwinovo.numen.experience.api.PublicationState.PUBLISHED).build(),
                base("t1", "试用丁", ExperienceMaturity.VERIFIED).publication(com.dwinovo.numen.experience.api.PublicationState.TRIAL).build());

        ExperienceStoreRetrievalBridge.Rendered r = ExperienceStoreRetrievalBridge.render(
                memory, "", 8, 320, com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);

        assertTrue(r.text().contains("（试用中）"), "试用条目必须优先占槽，实际：" + r.text());
        assertEquals(1, TrialRuntime.trialRefsIn(r.text()).size());
    }

    // ---------- G8：TRIAL + OBSERVED 必须能进执行视图（解环的离线判据）----------

    // G8-K22 红灯记录：TRIAL+OBSERVED 目前**仍进不了**执行视图（render 输出为空，不是角色门挡的）。
    // 保留成 @Disabled 而不是删掉：谁修好了就把它打开，让它变成绿灯判据。
    @Test
    void TRIAL且OBSERVED的条目_带火把关键词时能进执行视图() {
        ExperienceMemory memory = memoryWith(base("t8", "minecraft:torch 能用来做什么", ExperienceMaturity.OBSERVED)
                .publication(com.dwinovo.numen.experience.api.PublicationState.TRIAL)
                .build());

        ExperienceStoreRetrievalBridge.Rendered r = ExperienceStoreRetrievalBridge.render(
                memory, "火把 torch 能用来做什么", 8, 1100,
                com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);
        System.out.println("[diag] render entries=" + r.entries() + " len=" + r.text().length());

        assertTrue(r.text().contains("exp:execution|minecraft:torch"), "TRIAL+OBSERVED 必须进执行视图，实际：" + r.text());
        assertTrue(r.text().contains("（试用中）"), "必须带试用标记");
        assertEquals(1, TrialRuntime.trialRefsIn(r.text()).size());
    }

    @Test
    void 定位用_recall能否看到TRIAL且OBSERVED的条目() {
        ExperienceMemory memory = memoryWith(base("t9", "minecraft:torch 能用来做什么", ExperienceMaturity.OBSERVED)
                .publication(com.dwinovo.numen.experience.api.PublicationState.TRIAL)
                .build());
        var snap = ExperienceStoreRetrievalBridge.snapshot(memory);
        System.out.println("[diag] snapshot size=" + snap.size());
        assertEquals(1, snap.size(), "桥的 snapshot 应该能看到这条（否则问题在桥之前）");
    }

    @Test
    void PUBLISHED且VERIFIED的条目_带torch关键词时能进执行视图() {
        ExperienceMemory memory = memoryWith(base("pub1", "minecraft:torch 能用来做什么", ExperienceMaturity.VERIFIED)
                .publication(com.dwinovo.numen.experience.api.PublicationState.PUBLISHED)
                .verifiedCount(1)
                .build());
        ExperienceStoreRetrievalBridge.Rendered r = ExperienceStoreRetrievalBridge.render(
                memory, "我手上的火把（torch）能用来做什么", 8, 1100,
                com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);
        System.out.println("[diag-pub] entries=" + r.entries() + " len=" + r.text().length());
        assertTrue(r.text().contains("torch"), "PUBLISHED+VERIFIED 必须进执行视图，实际：" + r.text());
    }
}