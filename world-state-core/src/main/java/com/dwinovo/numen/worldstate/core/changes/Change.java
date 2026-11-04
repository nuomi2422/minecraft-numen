package com.dwinovo.numen.worldstate.core.changes;

/**
 * 一条变化记录（041 §2）：**只有被存储接受的写入**才会产生它。
 *
 * <p>{@code revision} 与观测自己的修订号同一个数——这样"看到哪一条变化"和"观测的哪一版"
 * 是同一个坐标系，不需要换算。
 *
 * @param kind CREATED=该键此前没有观测；UPDATED=已有观测且本次写入被接受；GONE=观测被显式清除
 */
public record Change(long revision, String topic, String key, Kind kind, long epochMs) {

    public enum Kind {
        CREATED,
        UPDATED,
        GONE
    }

    public Change {
        topic = topic == null ? "" : topic;
        key = key == null ? "" : key;
        kind = kind == null ? Kind.UPDATED : kind;
    }
}
