package com.dwinovo.numen.worldstate.core.changes;

import java.util.Set;

/**
 * 变化日志（041）：记录"被接受的写入"，按游标增量读取。
 *
 * <p>实现必须保证：**只追加、不修改**；游标单调；断档显式返回 gap。
 */
public interface ChangeLog {

    /** 追加一条变化（由存储写入路径调用；调用方保证只在写入被接受后调用）。 */
    void append(Change change);

    /**
     * 读取 {@code after} 之后的变化。
     *
     * @param after  消费者已看到的最大 revision（0 = 从头）
     * @param topics 主题过滤（null/空 = 不过滤）
     * @param limit  最多返回多少条
     */
    ChangesResult after(long after, Set<String> topics, int limit);

    /** 当前保留的最小 revision（没有记录返回 0）。 */
    long oldestRetainedRevision();

    /** 当前保留的条数。 */
    int size();
}
