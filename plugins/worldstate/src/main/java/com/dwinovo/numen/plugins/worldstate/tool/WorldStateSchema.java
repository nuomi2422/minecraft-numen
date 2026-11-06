package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.agent.tool.Schema;

import java.util.Map;

/**
 * `world_state` 的**公布 schema**（042 R1）。
 *
 * <p>为什么单独一个类：`WorldStateTool` 依赖 `NumenPlayer`，离线测试一加载就
 * `NoClassDefFoundError`，于是"schema 长什么样"这件事一直没法被测试钉住。
 * 抽出来之后它只依赖 `Schema`（纯 Map 构建器），可以在没有 MC 环境的地方断言。
 *
 * <p>口径（与 042 R1 对齐）：
 * <ul>
 *   <li>`container` 必须声明——不然模型没法表达"查哪个容器"；</li>
 *   <li>`after` 的说明**不写 revision**：R3-2 要把观测 revision 与日志 streamSeq 拆开，
 *       说明先写死 revision 会骗人；</li>
 *   <li>`topic` 目前仍是 required（改 optional 随 R3，不在这里赌）。</li>
 * </ul>
 */
public final class WorldStateSchema {

    private WorldStateSchema() {}

    public static Map<String, Object> build() {
        Map<String, Object> schema = Schema.object()
                .enumStr("action", "read=只读已有观测 / refresh=主动采集 / changes=增量读（041）",
                        "read", "refresh", "changes", "catalog", "batch_read")
                // 中性措辞：具体是"观测 revision"还是"日志 streamSeq"，等 042 R3 定（R3-2 要求拆开）。
                .optionalInteger("after", "changes 用：增量游标（上次读到的位置），0=从头", 0, Integer.MAX_VALUE)
                .optionalInteger("limit", "changes 用：最多返回几条（默认 64，上限 256）", 1, 256)
                .optionalString("topics", "changes 用：主题过滤，逗号分隔，如 blocks,containers")
                // 042 R1-2：topic 改为**操作条件必需**——changes 不需要主题，read/refresh 需要。
                // schema 层不强制 required（否则 changes 永远过不了校验）；缺 topic 时由 onServerCall
                // 明确报 "topic is required for action=read|refresh"（R1-3 的可诊断失败）。
                .optionalEnum("topic", "观测主题（action=read|refresh 必需）：environment / entities / blocks / containers / self",
                        "environment", "entities", "blocks", "containers", "self")
                .optionalString("dimension", "维度 id，默认同伴当前维度，如 minecraft:overworld")
                .optionalInteger("samples", "blocks 用：每个规格带回几个位置样本（0~32，默认 0）", 0, 32)
                .optionalInteger("x1", "范围角1 x（entities/blocks 用）", -30000000, 30000000)
                .optionalInteger("y1", "范围角1 y", -64, 320)
                .optionalInteger("z1", "范围角1 z", -30000000, 30000000)
                .optionalInteger("x2", "范围角2 x", -30000000, 30000000)
                .optionalInteger("y2", "范围角2 y", -64, 320)
                .optionalInteger("z2", "范围角2 z", -30000000, 30000000)
                .optionalString("entity_type", "entities 用：只数该实体种，如 minecraft:sheep")
                .optionalEnum("category", "entities 用：passive=动物 / hostile=怪物 / all=全部（默认 all）",
                        "passive", "hostile", "all")
                .optionalString("block", "blocks 用：目标方块 id 或 id[属性=值]，逗号分隔多个")
                .optionalString("target", "self 必需：player:<当前同伴UUID>；只读当前同伴当前维度")
                .optionalString("container", "containers 用：block:x,y,z 或 player:<uuid>")
                .optionalInteger("max_age_ms", "batch_read：允许年龄，默认2000毫秒", 1, 60000)
                .optionalInteger("max_skew_ms", "batch_read：各观测采样偏差上限，默认100毫秒", 0, 60000)
                .optionalInteger("max_items", "batch_read：每个查询条目上限，默认64", 1, 256)
                .optionalInteger("deadline_ms", "batch_read：合作式读取预算，默认50毫秒，不强行中断采集", 1, 1000)
                .build();
        // 2026-11-05: reuse the published query fields, without recursively building another tool schema.
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> queryProperties = new java.util.LinkedHashMap<>(properties);
        for (String key : java.util.List.of("action", "after", "limit", "topics", "max_age_ms", "max_skew_ms", "max_items", "deadline_ms"))
            queryProperties.remove(key);
        properties.put("queries", Map.of("type", "array", "minItems", 1, "maxItems", 16,
                "description", "batch_read必需；只读当前同伴各轴±32格立方近场缓存/自身背包，不扫描，不修改世界",
                "items", Map.of("type", "object", "properties", queryProperties,
                        "required", java.util.List.of("topic"), "additionalProperties", false)));
        return schema;
    }
}
