package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 `EntryBody` —— 实例化的正文（不可变、带 contentHash，能与
 * {@link EntryDescriptor#entryRef()} 或 {@code bodyRef} 对应）。
 *
 * <p>检索结果只读（IC10：展开/发布不等于自动执行）。
 */
public record EntryBody(
        String entryRef,
        String contentHash,
        String contentType,
        String content,
        boolean truncated,
        String source) { }
