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
