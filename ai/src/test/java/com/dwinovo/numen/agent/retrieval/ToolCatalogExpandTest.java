package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5g 判据 Y3：`tool:` 条目能展开出正文（G5f 时这里还是 {@code NO_OWNER}/{@code UNSUPPORTED}）。
 *
 * <p>只读：expand 只从 source 的条目里取 body，不解析参数、不派任务。
 */
class ToolCatalogExpandTest {

    private static ExperienceEntryRead read(String ref, String body) {
        return new ExperienceEntryRead(ref, EntryKind.PROCEDURE, "标题 " + ref, "摘要",
                List.<String>of(), List.<String>of(), List.<String>of(),
                Maturity.VERIFIED, 0, false, body, "h-" + ref, "fake", true, true);
    }

    private static ToolCatalogRetrievalProvider provider() {
        return new ToolCatalogRetrievalProvider(() -> List.of(
                read("tool:goto", "goto：走向目标坐标。参数 x,y,z / block。"),
                read("tool:sleep", "sleep：就在身边的床上躺下。参数 x,y,z 可省。")));
    }

    private static EntryBody okBody(Result<EntryBody> r) {
        assertTrue(r instanceof Result.Ok, "期望 Ok，实际 Err: "
                + (r instanceof Result.Err ? ((Result.Err<?>) r).code() : "?"));
        return (EntryBody) ((Result.Ok<?>) r).value();
    }

    private static String errCode(Result<EntryBody> r) {
        assertTrue(r instanceof Result.Err, "期望 Err，实际 Ok");
        return ((Result.Err<?>) r).code();
    }

    @Test
    void expandReturnsBody() {
        Result<EntryBody> r = provider().expand(
                new EntryRef("tool:goto"),
                new ExpansionRequest("q-1", RetrievalRole.EXECUTOR, 0, ""));
        EntryBody body = okBody(r);
        assertEquals("tool:goto", body.entryRef());
        assertTrue(body.content().contains("走向目标坐标"), "正文要真的是那条工具的说明");
        assertFalse(body.truncated());
        assertEquals("tool-catalog", body.source(), "来源要如实标 tool-catalog");
    }

    @Test
    void expandTruncatesExplicitly() {
        Result<EntryBody> r = provider().expand(
                new EntryRef("tool:goto"),
                new ExpansionRequest("q-1", RetrievalRole.EXECUTOR, 8, ""));
        EntryBody body = okBody(r);
        assertTrue(body.truncated(), "超长要显式标 truncated，不许把半句话当完整正文");
        assertTrue(body.content().endsWith("…"), "截断要有省略号");
        assertEquals(9, body.content().length(), "8 字符 + 省略号");
    }

    @Test
    void expandUnknownRefIsNotFound() {
        Result<EntryBody> r = provider().expand(
                new EntryRef("tool:no_such_tool"),
                new ExpansionRequest("q-1", RetrievalRole.EXECUTOR, 0, ""));
        assertEquals("NOT_FOUND", errCode(r), "没有这条 ≠ 没有正文，错误码要分得开");
    }

    @Test
    void expandNullRefIsBadRef() {
        Result<EntryBody> r = provider().expand(null, new ExpansionRequest("q-1", RetrievalRole.EXECUTOR, 0, ""));
        assertEquals("BAD_REF", errCode(r));
    }

    @Test
    void serviceRoutesToolRefToCatalogOwner() {
        RetrievalRegistry registry = new RetrievalRegistry();
        assertTrue(registry.register(provider()).ok());
        MinimalRetrievalService service = new MinimalRetrievalService(registry);
        Result<EntryBody> r = service.expand(new EntryRef("tool:sleep"),
                new ExpansionRequest("q-1", RetrievalRole.EXECUTOR, 0, ""));
        assertEquals("tool:sleep", okBody(r).entryRef(), "统一入口要按 tool: 前缀找到 tool-catalog owner");
    }
}
