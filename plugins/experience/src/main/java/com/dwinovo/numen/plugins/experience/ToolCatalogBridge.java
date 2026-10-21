package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.retrieval.EntryKind;
import com.dwinovo.numen.agent.retrieval.ExperienceEntryRead;
import com.dwinovo.numen.agent.retrieval.Maturity;
import com.dwinovo.numen.agent.retrieval.ToolCatalogEntrySource;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * G5g 接 host：把**真工具注册表**变成检索能吃的目录条目（`tool:<name>`）。
 *
 * <h2>它只做形状转换</h2>
 * 读 {@link ToolRegistry#all()}（注册顺序稳定），一条工具 → 一条
 * {@link ExperienceEntryRead}：ref = {@code tool:<name>}、标题 = 工具名、摘要 = 描述首句、
 * 正文 = 名字 + 描述 + 参数名清单。**不解析参数、不调工具、不派任务** —— 那是执行侧的事，
 * 而执行侧有 {@code ExecutionGate} 拦着（只读条目恒拒）。
 *
 * <h2>为什么正文只放参数名</h2>
 * 完整 JSON Schema 动辄几百字符，而执行/规划块的预算是 800–2400 字。放参数名（`x,y,z`）
 * 足够回答「这个工具要几个参数」，真要细节她会用 `find_tools` 取完整定义 —— 那才是权威来源。
 */
public final class ToolCatalogBridge {

    /** provider id（与 {@code ToolCatalogRetrievalProvider.PROVIDER_ID} 对齐）。 */
    public static final String SOURCE_ID = "tool-catalog";

    /** 正文里最多列几个参数名（再多就该用 find_tools 了）。 */
    private static final int MAX_PARAMS = 8;

    /** 摘要长度上限：它只用来给模型一眼认出「这是干嘛的」。 */
    private static final int MAX_SUMMARY = 80;

    private ToolCatalogBridge() {}

    /** 交给 {@code ToolCatalogRetrievalProvider} 的 source（每次检索现读，工具表可热插拔）。 */
    public static ToolCatalogEntrySource source() {
        return ToolCatalogBridge::entries;
    }

    /** 全量目录条目（注册顺序）。 */
    static List<ExperienceEntryRead> entries() {
        List<ExperienceEntryRead> out = new ArrayList<>();
        try {
            for (NumenTool tool : ToolRegistry.all()) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) {
                    continue;
                }
                String name = tool.name();
                String desc = tool.description() == null ? "" : tool.description().trim();
                String params = paramNames(tool);
                String body = name + "\n" + desc + (params.isBlank() ? "" : "\n参数: " + params);
                out.add(new ExperienceEntryRead(
                        "tool:" + name,
                        EntryKind.PROCEDURE,
                        name,
                        clip(desc, MAX_SUMMARY),
                        List.of(),
                        List.of(),
                        List.of(),
                        Maturity.VERIFIED,   // 目录条目没有「被验证过」的概念；它能不能用由 viewOnly/闸门管
                        0,
                        false,
                        body,
                        "h" + Integer.toHexString((name + "|" + body).hashCode()),
                        SOURCE_ID,
                        true,
                        true));
            }
        } catch (Throwable t) {
            // 工具表读不到（没进世界 / 类路径上没有 core 的注册表）⇒ 目录为空，
            // **不让它拖垮整次检索**：经验那一半照常出。这与 G5 的「降级而不是炸」同口径。
            return List.of();
        }
        return out;
    }

    /** 参数名清单（按 schema 的键顺序，最多 {@value #MAX_PARAMS} 个）。 */
    static String paramNames(NumenTool tool) {
        Map<String, Object> schema;
        try {
            schema = tool.parameterSchema();
        } catch (RuntimeException t) {
            return "";   // 读不到 schema 不该让整条目录消失：正文少一段而已
        }
        if (schema == null || schema.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String key : schema.keySet()) {
            if (key == null || key.isBlank()) continue;
            if (n >= MAX_PARAMS) {
                sb.append(",…");
                break;
            }
            if (n > 0) sb.append(',');
            sb.append(key);
            n++;
        }
        return sb.toString();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
