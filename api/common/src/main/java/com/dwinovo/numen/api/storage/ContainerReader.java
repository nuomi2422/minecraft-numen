package com.dwinovo.numen.api.storage;

import java.util.Map;
import java.util.UUID;

/**
 * 窄能力：读一个方块容器的内容（037/T8b，2026-11-04）。
 *
 * <p><b>为什么需要它</b>：RDD 插件现在直接 `new` 了 core 的内部工具类
 * （`com.dwinovo.numen.core.tools.QueryExtraOps`）去读箱子，再把返回的**人读文本**用字符串
 * 解析回物品数量。两个问题：①联动插件依赖了引擎内部类型，改内部就断；②数量来自解析文本，
 * 格式一变就静默读错。这条窄能力把"读容器"变成公开契约，并直接返回结构化数量。
 *
 * <p><b>调用约定</b>：
 * <ul>
 *   <li>必须在<b>服务端线程</b>调用（读方块实体不是线程安全的）；</li>
 *   <li>实现<b>不得抛异常</b>：读不到就用 {@link Result#unknown(String)} 说明原因；</li>
 *   <li>"容器是空的"和"读不到"必须是两种结果——空 Map 表示**已知为空**，
 *       {@code unknown} 表示不知道，调用方不许把后者当成前者。</li>
 * </ul>
 */
public interface ContainerReader {

    /**
     * 读一个容器。
     *
     * @param companionId 谁的视角（权限/背包上下文；不可为 null）
     * @param dimension   维度 id，例如 {@code minecraft:overworld}
     * @param x,y,z       方块坐标
     */
    Result read(UUID companionId, String dimension, int x, int y, int z);

    /**
     * 读到的东西。
     *
     * @param known  是否真的读到了（false 时 {@code items} 必须为空、{@code reason} 必须给原因）
     * @param items  物品 id → 数量（不可变；已知为空时是空 Map）
     * @param reason 读不到的原因（known=true 时为 null）
     */
    record Result(boolean known, Map<String, Integer> items, String reason) {

        public Result {
            if (items == null) {
                items = Map.of();
            } else {
                items = Map.copyOf(items);
            }
            if (!known && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("unknown container read requires a reason");
            }
        }

        /** 读到了（可能是空容器）。 */
        public static Result known(Map<String, Integer> items) {
            return new Result(true, items, null);
        }

        /** 没读到：不知道里面有什么，不要当成空。 */
        public static Result unknown(String reason) {
            return new Result(false, Map.of(), reason);
        }
    }
}
