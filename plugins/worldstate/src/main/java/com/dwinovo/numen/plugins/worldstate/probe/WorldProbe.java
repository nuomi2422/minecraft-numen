package com.dwinovo.numen.plugins.worldstate.probe;

import java.util.List;

/**
 * 一个命名只读探针。实现必须<b>不加载区块、不改世界、不写经验</b>；
 * 拿不到就返回 {@link ProbeResult#failed}，不拿默认值冒充。
 */
public interface WorldProbe {

    /** 稳定 id；改名等于破坏目录，必须走 schema 升级。 */
    String id();

    /** 给目录看的一句标题。 */
    String title();

    /** 给目录看的说明：回答什么问题、什么时候该用。 */
    String description();

    /** 参数名（只用于 action=list 的展示，真正解析在 WorldProbeTool）。 */
    default List<String> params() {
        return List.of();
    }

    /** 返回字段名（只用于 action=list 的展示）。 */
    default List<String> returns() {
        return List.of();
    }

    ProbeResult collect(ProbeContext ctx);
}
