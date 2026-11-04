package com.dwinovo.numen.worldstate.core.observation.payload;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界方块计数（039 U2b 新增，第一个 {@code blocks} 载荷）。
 *
 * <p>用途：把"身边有没有箱子/岩浆/传送门"从各模块的临时扫描改成可复用观测。
 * <b>计数是下限不是总数</b>，除非外层 {@code Coverage=COMPLETE}——"没看到"不等于"没有"，
 * 这个判断留给消费者，不在这里下。
 *
 * @param dimension      维度 id
 * @param box            查询范围
 * @param blockIds       请求里点名的方块 id（有序，便于渲染）
 * @param counts         本次观察到的计数（按 {@code blockIds} 顺序；没出现的是 0，但仍受覆盖限制）
 * @param scannedColumns 已扫的列数
 * @param skippedColumns 因区块未加载而跳过的列数
 */
public record BlockCountPayload(String dimension, Bounds box, java.util.List<String> blockIds,
                                Map<String, Integer> counts, int scannedColumns, int skippedColumns,
                                java.util.List<String> samplePositions)
        implements ObservationPayload {

    /** 兼容旧调用：不带位置样本。 */
    public BlockCountPayload(String dimension, Bounds box, java.util.List<String> blockIds,
                             Map<String, Integer> counts, int scannedColumns, int skippedColumns) {
        this(dimension, box, blockIds, counts, scannedColumns, skippedColumns, java.util.List.of());
    }

    /**
     * @param samplePositions 命中的**少量位置样本**（形如 {@code <spec>@x,y,z}），给"要去哪一格"用；
     *                        计数仍是全量，但样本有上限——不把整个范围搬进观测
     */
    public BlockCountPayload {
        blockIds = blockIds == null ? java.util.List.of() : java.util.List.copyOf(blockIds);
        samplePositions = samplePositions == null ? java.util.List.of() : java.util.List.copyOf(samplePositions);
        Map<String, Integer> copy = new LinkedHashMap<>();
        if (counts != null) {
            copy.putAll(counts);
        }
        for (String id : blockIds) {
            copy.putIfAbsent(id, 0);
        }
        counts = java.util.Collections.unmodifiableMap(copy);
    }

    @Override
    public Topic topic() {
        return Topic.BLOCKS;
    }

    /** 过滤条件的规范串（与 {@code ObservationRequest.filter} 同口径）。 */
    public String filterId() {
        // 与 ObservationRequest.blocks(...) 生成的规范串保持一致（它发 block=）。
        return "block=" + String.join(",", blockIds);
    }

    /** 所有点名方块的合计（注意仍受覆盖限制）。 */
    public int total() {
        int sum = 0;
        for (int v : counts.values()) sum += v;
        return sum;
    }
}
