package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RationaleContextTest {
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
        RationaleContext.set(id, new RationaleContext.Entry("why", "", "src", "cond"));
        String xml = RationaleContext.render(id);
        assertTrue(xml.contains("<why>why</why>"));
        assertTrue(xml.contains("<source>src</source>"));
        assertFalse(xml.contains("<risks>"));
        RationaleContext.clear(id);
        assertEquals("", RationaleContext.render(id));
    }
}
