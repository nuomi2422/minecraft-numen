package com.dwinovo.numen.worldstate.core.observation.payload;

import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;

import java.util.Map;

/**
 * 单个容器的内容快照（039 U3 新增，第一个 {@code containers} 载荷）。
 *
 * <p><b>"容器是空的"和"读不到"必须是两种结果</b>：本载荷只在**真的读到**时才存在；
 * 读不到时采集器抛错，不落盘一条空快照——否则下游会把"不知道"当成"没有"。
 *
 * @param dimension 维度 id
 * @param target    容器标识：{@code block:x,y,z} 或 {@code player:<uuid>}
 * @param slots     容器槽位数（0 表示未知/不适用）
 * @param items     物品 id → 数量（不可变；已知为空时是空表）
 */
public record ContainerPayload(String dimension, String target, int slots, Map<String, Integer> items)
        implements ObservationPayload {

    public ContainerPayload {
        target = target == null ? "" : target;
        items = items == null ? Map.of() : Map.copyOf(items);
        if (slots < 0) slots = 0;
    }

    @Override
    public Topic topic() {
        return Topic.CONTAINERS;
    }

    /** 物品总件数（用于"满没满"这类判断；空容器是 0，不是未知）。 */
    public int totalItems() {
        int sum = 0;
        for (int v : items.values()) sum += v;
        return sum;
    }
}
