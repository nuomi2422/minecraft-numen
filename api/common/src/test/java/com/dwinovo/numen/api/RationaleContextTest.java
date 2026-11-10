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
        String xml = RationaleContext.peek(id);
        assertTrue(xml.contains("新理由足够长"));
        assertFalse(xml.contains("旧理由也够长"));
        assertEquals(10L, RationaleContext.generation(id));
    }

    @Test
    void sameRevisionOnAnotherChainIsNotCurrent() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-1");
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
        RationaleContext.noteCurrentChain(id, "chain-1");
        RationaleContext.set(id, new RationaleContext.Entry("链一的原因足够长", "", "src", ""), 5L, "chain-1");
        assertTrue(RationaleContext.renderApplicable(id, 5L, "chain-1").contains("链一的原因足够长"));
        // A write for another chain must not be rendered under the old chain's identity; the host
        // declares the switch first, which is what makes the new chain authoritative.
        RationaleContext.noteCurrentChain(id, "chain-2");
        RationaleContext.set(id, new RationaleContext.Entry("链二的原因足够长", "", "src", ""), 5L, "chain-2");
        assertEquals("", RationaleContext.renderApplicable(id, 5L, "chain-1"));
        assertTrue(RationaleContext.renderApplicable(id, 5L, "chain-2").contains("链二的原因足够长"));
    }

    @Test
    void newChainWithLowerRevisionIsAccepted() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-old");
        RationaleContext.set(id, new RationaleContext.Entry("旧链最后一条原因", "", "src", ""), 100L, "chain-old");
        // Chain restarts: revisions begin again at 1, and numeric ordering must not block it.
        RationaleContext.noteCurrentChain(id, "chain-new");
        RationaleContext.set(id, new RationaleContext.Entry("新链第一条原因", "", "src", ""), 1L, "chain-new");
        assertTrue(RationaleContext.renderApplicable(id, 1L, "chain-new").contains("新链第一条原因"));
    }

    @Test
    void lateWriteFromFinishedChainIsRejected() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-new");
        RationaleContext.set(id, new RationaleContext.Entry("当前链合法原因", "", "src", ""), 1L, "chain-new");
        // Same revision number, but a chain the host is no longer running.
        RationaleContext.set(id, new RationaleContext.Entry("旧链迟到原因", "", "src", ""), 1L, "chain-old");
        assertTrue(RationaleContext.renderApplicable(id, 1L, "chain-new").contains("当前链合法原因"),
                "a finished chain must not overwrite the current context even at the same revision");
        assertEquals("", RationaleContext.renderApplicable(id, 1L, "chain-old"));
    }

    @Test
    void sameChainLowerRevisionIsStale() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-1");
        RationaleContext.set(id, new RationaleContext.Entry("较新原因文本", "", "src", ""), 4L, "chain-1");
        RationaleContext.set(id, new RationaleContext.Entry("同链较旧原因", "", "src", ""), 3L, "chain-1");
        assertTrue(RationaleContext.renderApplicable(id, 4L, "chain-1").contains("较新原因文本"));
        assertEquals("", RationaleContext.renderApplicable(id, 3L, "chain-1"));
    }

    @Test
    void lateCallbackCannotRelabelScope() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-new");
        RationaleContext.set(id, new RationaleContext.Entry("新的已绑定原因", "", "src", ""), 6L, "chain-new");
        RationaleContext.set(id, new RationaleContext.Entry("迟到的旧回调原因", "", "src", ""), 5L, "chain-old");
        assertTrue(RationaleContext.renderApplicable(id, 6L, "chain-new").contains("新的已绑定原因"));
        assertEquals("", RationaleContext.renderApplicable(id, 5L, "chain-old"),
                "a stale callback must not resurrect its own chain scope");
    }

    @Test
    void afterClearALateCallbackCannotResurrectAScopedEntry() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-1");
        RationaleContext.set(id, new RationaleContext.Entry("已存在的当前原因", "", "src", ""), 2L, "chain-1");
        RationaleContext.clear(id);
        // clear() withdraws the declaration too; a late writer has no standing any more.
        RationaleContext.set(id, new RationaleContext.Entry("清空后迟到的旧回调", "", "src", ""), 2L, "chain-1");
        assertNull(RationaleContext.get(id), "a cleared context must stay cleared");
        assertEquals("", RationaleContext.renderApplicable(id, 2L, "chain-1"));
    }

    @Test
    void writerWithoutDeclarationIsRefused() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("未声明链时的写入", "", "src", ""), 1L, "chain-1");
        assertNull(RationaleContext.get(id), "no declared current chain means no authorised writer");
    }

    @Test
    void twoInterleavedRequestsCannotBothClaimCurrent() {
        UUID id = UUID.randomUUID();
        // Request A declares chain-A and writes; request B then declares chain-B and writes.
        RationaleContext.noteCurrentChain(id, "chain-A");
        RationaleContext.set(id, new RationaleContext.Entry("请求A的原因文本", "", "src", ""), 3L, "chain-A");
        RationaleContext.noteCurrentChain(id, "chain-B");
        RationaleContext.set(id, new RationaleContext.Entry("请求B的原因文本", "", "src", ""), 1L, "chain-B");
        // A late write from A (older request, no longer declared) must fail.
        RationaleContext.set(id, new RationaleContext.Entry("请求A迟到回写", "", "src", ""), 4L, "chain-A");
        assertTrue(RationaleContext.renderApplicable(id, 1L, "chain-B").contains("请求B的原因文本"));
        assertEquals("", RationaleContext.renderApplicable(id, 4L, "chain-A"));
    }

    @Test
    void withdrawingDeclarationRemovesAuthority() {
        UUID id = UUID.randomUUID();
        RationaleContext.noteCurrentChain(id, "chain-1");
        RationaleContext.set(id, new RationaleContext.Entry("声明有效时写入", "", "src", ""), 1L, "chain-1");
        assertNotNull(RationaleContext.get(id));
        RationaleContext.noteCurrentChain(id, null);
        RationaleContext.set(id, new RationaleContext.Entry("撤声明后写入", "", "src", ""), 2L, "chain-1");
        // Production fresh read must send nothing once the host withdrew the declaration;
        // only the diagnostic peek (render/get) may still show the stored history.
        assertEquals("", RationaleContext.renderApplicable(id, 1L, "chain-1"),
                "withdrawn declaration must not keep feeding the old reason into prompts");
        assertTrue(RationaleContext.peek(id).contains("声明有效时写入"),
                "diagnostic peek keeps history, and is not a fresh-render path");
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
        RationaleContext.noteCurrentChain(id, "chain-9");
        RationaleContext.set(id, new RationaleContext.Entry("有链绑定", "", "src", ""), 3L, "chain-9");
        assertTrue(RationaleContext.applicable(id, 3L, "chain-9"));
        RationaleContext.clear(id);
        assertNull(RationaleContext.chain(id));
        assertFalse(RationaleContext.applicable(id, 3L, "chain-9"));
        assertEquals("", RationaleContext.renderApplicable(id, 3L, "chain-9"),
                "after clear the fresh-render path must be empty");
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
        assertFalse(RationaleContext.peek(a).contains("<why>"));
        assertFalse(RationaleContext.peek(a).contains("按计划继续"));
        assertTrue(RationaleContext.peek(b).contains("钻石镐"));
        assertFalse(RationaleContext.peek(a).contains("钻石镐"));
    }

    @Test
    void escapesXmlAndCapsLength() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("<script>" + "x".repeat(600), "", "src", "c"));
        String xml = RationaleContext.peek(id);
        assertFalse(xml.contains("<script>"));
        assertTrue(xml.contains("&lt;script&gt;"));
        assertTrue(xml.length() < 700);
    }

    @Test
    void rendersOnlyPresentFieldsAndClears() {
        UUID id = UUID.randomUUID();
        RationaleContext.set(id, new RationaleContext.Entry("because diamond is needed", "", "src", "cond"));
        String xml = RationaleContext.peek(id);
        assertTrue(xml.contains("<why>because diamond is needed</why>"));
        assertTrue(xml.contains("<source>src</source>"));
        assertFalse(xml.contains("<risks>"));
        RationaleContext.clear(id);
        assertEquals("", RationaleContext.peek(id));
    }
}
