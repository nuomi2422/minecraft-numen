package com.dwinovo.numen.ac.api;

import java.util.List;
import java.util.Optional;

/**
 * AC 自己的原子工具注册表。宿主或自编译产出的工具在此注册；主 AI / AI authoring
 * 经 {@link #toolNames()} / {@link #schemas()} 读取稳定目录后再生成 AC。
 *
 * <p>这是 AC 的 registry，<b>不是</b> Numen 的 {@code agent.tool.ToolRegistry}。
 * 二者语义不同，未来通过外部 adapter 转换，不在本接口混用。
 */
public interface ToolRegistry {

    /** 注册工具（无显式 schema → 生成宽松默认 schema）。重复注册抛异常。 */
    void register(String name, AcTool tool);

    /**
     * 注册工具并绑定显式 schema。schema.name() 必须与 name 一致。
     *
     * <p>默认实现<b>不静默吞掉 schema</b>：不会 schema 绑定的实现必须自行覆盖此方法，
     * 否则抛 {@link UnsupportedOperationException} 硬失败——静默丢 schema 会让 AI 读到
     * 的参数校验/目录失真，属于契约破坏，宁可当场报错。
     */
    default void register(String name, AcTool tool, ToolSchema schema) {
        throw new UnsupportedOperationException(
                "schema binding not supported by this ToolRegistry; override register(name, tool, schema)");
    }

    Optional<AcTool> find(String name);

    /** 工具 schema；未注册或未绑定 schema 返回 empty。 */
    default Optional<ToolSchema> schema(String name) {
        return Optional.empty();
    }

    /** 全部工具名（稳定排序）。 */
    default List<String> toolNames() {
        return List.of();
    }

    /** 全部工具 schema 目录（稳定排序，与 {@link #toolNames()} 一致）。 */
    default List<ToolSchema> schemas() {
        return List.of();
    }

    default boolean contains(String name) {
        return find(name).isPresent();
    }
}
