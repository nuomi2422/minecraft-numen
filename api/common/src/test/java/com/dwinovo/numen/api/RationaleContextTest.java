package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RationaleContextTest {
    @Test
    void staleGenerationCannotOverwriteNewer() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("新理由足够长", "", "new", ""), 10);
        RationaleContext.set(id, new RationaleContext.Entry("旧理由也够长", "", "old", ""), 9);
        String xml = RationaleContext.render(id);
        assertTrue(xml.contains("新理由足够长"));
        assertFalse(xml.contains("旧理由也够长"));
        assertEquals(10L, RationaleContext.generation(id));
    }

    @Test
    void sameRevisionOnAnotherChainIsNotCurrent() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("钻石镐可直接挖黑曜石", "", "src", "cond"), 5L, "chain-1");
        assertTrue(RationaleContext.applicable(id, 5L, "chain-1"));
        assertFalse(RationaleContext.applicable(id, 5L, "chain-2"),
                "same plan revision on a different chain instance must not reuse the why");
        assertFalse(RationaleContext.applicable(id, 4L, "chain-1"));
        assertFalse(RationaleContext.applicable(id, 5L, null), "unknown chain is not evidence");
        assertFalse(RationaleContext.applicable(id, 5L, " "));
    }

    @Test
    void checkAndRenderUseOneSnapshot() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("链一的原因足够长", "", "src", ""), 5L, "chain-1");
        assertTrue(RationaleContext.renderApplicable(id, 5L, "chain-1").contains("链一的原因足够长"));
        // A newer write for another chain must not be rendered under the old chain's identity.
        RationaleContext.set(id, new RationaleContext.Entry("链二的原因足够长", "", "src", ""), 5L, "chain-2");
        assertEquals("", RationaleContext.renderApplicable(id, 5L, "chain-1"));
        assertTrue(RationaleContext.renderApplicable(id, 5L, "chain-2").contains("链二的原因足够长"));
    }

    @Test
    void lateCallbackCannotRelabelScope() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("新的已绑定原因", "", "src", ""), 6L, "chain-new");
        RationaleContext.set(id, new RationaleContext.Entry("迟到的旧回调原因", "", "src", ""), 5L, "chain-old");
        assertTrue(RationaleContext.renderApplicable(id, 6L, "chain-new").contains("新的已绑定原因"));
        assertEquals("", RationaleContext.renderApplicable(id, 5L, "chain-old"),
                "a stale callback must not resurrect its own chain scope");
    }

    @Test
    void unscopedLegacyEntryIsNeverFresh() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("旧写法没有链绑定", "", "src", ""), 5L);
        assertNotNull(RationaleContext.get(id));
        assertNull(RationaleContext.chain(id));
        assertFalse(RationaleContext.applicable(id, 5L, "chain-1"),
                "legacy/unscoped entry must not be presented as current");
    }

    @Test
    void clearDropsChainScopeToo() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("有链绑定", "", "src", ""), 3L, "chain-9");
        assertTrue(RationaleContext.applicable(id, 3L, "chain-9"));
        RationaleContext.clear(id);
        assertNull(RationaleContext.chain(id));
        assertFalse(RationaleContext.applicable(id, 3L, "chain-9"));
    }

    @Test
    void acceptsShortCausalAndRejectsMultilingualGeneric() {
        assertTrue(RationaleContext.substantiveWhy("怕僵尸"));
        assertTrue(RationaleContext.substantiveWhy("因为害怕僵尸所以取盾"));
        assertFalse(RationaleContext.substantiveWhy("comme prévu"));
        assertFalse(RationaleContext.substantiveWhy(""));
        assertEquals("ABSENT", RationaleContext.whyStatus(null));
        assertEquals("GENERIC", RationaleContext.whyStatus(new RationaleContext.Entry("按计划", "", "s", "")));
        assertEquals("PRESENT", RationaleContext.whyStatus(new RationaleContext.Entry("怕僵尸", "", "s", "")));
    }

    @Test
    void genericWhyIsOmittedAndCompanionsIsolated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RationaleContext.set(a, new RationaleContext.Entry("按计划继续", "", "src", "c"));
        RationaleContext.set(b, new RationaleContext.Entry("需要钻石镐才能挖黑曜石", "", "src", "c"));
        assertFalse(RationaleContext.render(a).contains("<why>"));
        assertFalse(RationaleContext.render(a).contains("按计划继续"));
        assertTrue(RationaleContext.render(b).contains("钻石镐"));
        assertFalse(RationaleContext.render(a).contains("钻石镐"));
    }

    @Test
    void escapesXmlAndCapsLength() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("<script>" + "x".repeat(600), "", "src", "c"));
        String xml = RationaleContext.render(id);
        assertFalse(xml.contains("<script>"));
        assertTrue(xml.contains("&lt;script&gt;"));
        assertTrue(xml.length() < 700);
    }

    @Test
    void rendersOnlyPresentFieldsAndClears() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("because diamond is needed", "", "src", "cond"));
        String xml = RationaleContext.render(id);
        assertTrue(xml.contains("<why>because diamond is needed</why>"));
        assertTrue(xml.contains("<source>src</source>"));
        assertFalse(xml.contains("<risks>"));
        RationaleContext.clear(id);
        assertEquals("", RationaleContext.render(id));
    }
}
