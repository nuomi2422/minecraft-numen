package com.dwinovo.numen.agent.retrieval;

/**
 * G5c（§15.8-B5「工具/ACX/支线目录」）—— 工具目录侧的 SPI。
 *
 * <p>与 {@link ExperienceEntrySource} 同形状：{@code ai} 模块不 import acx/catalog，
 * 宿主接线批（真 host）把 {@code AcxBlockCatalog} 的积木目录灌进来。
 * 本批离线用合成 source 测。
 */
public interface ToolCatalogEntrySource {

    /** 全部工具/积木目录条目（离线合成；宿主侧由真实目录适配）。 */
    java.util.List<ExperienceEntryRead> allEntries();
}
