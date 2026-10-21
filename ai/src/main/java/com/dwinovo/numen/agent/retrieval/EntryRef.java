package com.dwinovo.numen.agent.retrieval;

import java.util.Objects;

/**
 * 75 号 §15.5 `EntryRef` —— 条目的唯一定位（G5b 时抽出为 record，不再裸 String）。
 *
 * <p>与 {@link EntryDescriptor#entryRef()} 的字段对齐（同 ref 不同 revision 不混成一版）。
 */
public record EntryRef(String value) {

    public EntryRef {
        value = value == null ? "" : value;
        if (value.isBlank()) throw new IllegalArgumentException("EntryRef.value 不能为空");
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof EntryRef other && value.equals(other.value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
