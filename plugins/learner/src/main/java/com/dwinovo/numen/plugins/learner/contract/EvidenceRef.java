package com.dwinovo.numen.plugins.learner.contract;

import java.util.List;
import java.util.Objects;

/**
 * 字段级证据引用（158 §5）。
 *
 * <h2>为什么要有类型前缀，而不是一个裸字符串</h2>
 * 一条断言可能引用三种不同的东西：环境块（`block:`）、原始事件（`event:`）、
 * 决策上下文（`context:` / 块 id）。它们的**可核验程度完全不同**：
 * 块能当场查到，事件要回原始日志，决策上下文要证明"执行者当时真的收到了"。
 * 用裸字符串就分不开这三种，于是"有引用"和"引用是有效的"被混成一件事。
 *
 * <h2>解析失败绝不默认成"有效"</h2>
 * 认不出的前缀一律是 {@link Kind#OTHER}，不是 {@link Kind#BLOCK} ——
 * 默认成 BLOCK 会让一条写错的引用看起来能查到。
 *
 * <p>纯 JVM，可离线单测。
 */
public record EvidenceRef(Kind kind, String id) {

    /** 引用类别。 */
    public enum Kind {
        /** 环境块：`block:dc-turn-3`。 */
        BLOCK,
        /** 原始事件：`event:dead-123`。 */
        EVENT,
        /** 决策上下文：`context:dc-turn-3`。 */
        CONTEXT,
        /** 认不出的前缀（解析成功但要标出来）。 */
        OTHER
    }

    public EvidenceRef {
        Objects.requireNonNull(kind, "kind");
        id = id == null ? "" : id.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("证据引用不能没有 id");
        }
    }

    /**
     * 解析 `prefix:id`。没有冒号的一律当 {@link Kind#OTHER}（可解析但不可核验）。
     */
    public static EvidenceRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("证据引用为空");
        }
        String s = raw.trim();
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return new EvidenceRef(Kind.OTHER, s);
        }
        String prefix = s.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
        String id = s.substring(colon + 1).trim();
        Kind k = switch (prefix) {
            case "block" -> Kind.BLOCK;
            case "event" -> Kind.EVENT;
            case "context" -> Kind.CONTEXT;
            default -> Kind.OTHER;
        };
        return new EvidenceRef(k, id);
    }

    /** 是否能当场核验（块与决策上下文能，事件要回原始日志，OTHER 不能）。 */
    public boolean verifiableOffline() {
        return kind == Kind.BLOCK || kind == Kind.CONTEXT;
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + id;
    }

    /** 去重键。 */
    public String key() {
        return toString();
    }

    public static List<String> keys(List<EvidenceRef> refs) {
        return refs == null ? List.of() : refs.stream().map(EvidenceRef::key).toList();
    }
}
