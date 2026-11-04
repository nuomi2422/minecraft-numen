package com.dwinovo.numen.worldstate.core.changes;

import java.util.List;

/**
 * 一次 changes 查询的结果（041 §4）。
 *
 * <p><b>gap 为 true 表示"历史断档，你得重新同步"</b>——不是"没有变化"。
 * 这条与 039 §4 的"读不到 ≠ 空"是同一套语义。
 *
 * @param changes                按 revision 升序的变化
 * @param nextCursor             下次请求应使用的游标（不小于请求里的 after）
 * @param gap                    历史是否已断档
 * @param oldestRetainedRevision 当前还保留着的最小 revision（没有记录时为 0）
 */
public record ChangesResult(List<Change> changes, long nextCursor, boolean gap, long oldestRetainedRevision) {

    public ChangesResult {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public static ChangesResult empty(long cursor) {
        return new ChangesResult(List.of(), cursor, false, 0L);
    }
}
