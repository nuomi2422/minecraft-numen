package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.policy.RequirementManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义资产的持久层（S1 写路径，2026-10-24）——「这件东西对这个目标为什么重要」。
 *
 * <p><b>语义从哪来（不发明、不让主人维护）</b>：从需求清单**派生**。RddFactContext 在
 * manifest 生成/缓存时调用 {@link #deriveAndSave}：CRITICAL 需求 → {@code IMPORTANT}
 * （进本一级的门），其余 → {@code SECONDARY}；{@code servesGoal} = 当前 goalId；
 * {@code nature} 用 {@link AssetSemantics#guessNature} 的保守分类兜底。
 * 换目标/换一级时重派生（新清单覆盖旧清单的键；不再服务旧目标的键降为 IRRELEVANT
 * —— 只改标记不删，与「撤回不删」同一纪律：历史可查，假删除才是丢数据）。
 *
 * <p><b>为什么现在做</b>：AssetSemantics/LossImpactAnalyzer 自 75 号 D20 起完整带单测，
 * 但零生产调用（#167）——用户 2026-10-24 点名「资产库没标重要度、死了不按重要度、
 * 丢了东西没重新规划的显示」。本类 + RddRepairDispatch 的接线把这条链真正跑起来。
 *
 * <p><b>形状</b>：{@code config/numen/rdd-semantics/<companionId>.json}，
 * tmp + 原子替换（与 RddAssetStore 同纪律）。文件损坏 → 空表重算（fail-soft，
 * 语义是派生物，源头 manifest 一直都在，丢的只是缓存）。
 *
 * <p>纯逻辑（合并/派生/IRRELEVANT 化）在本类静态方法里，可单测；IO 包 try，
 * 任何失败只丢这一次持久化，不碰任务链（观测/增强旁路纪律）。
 */
final class RddSemanticsStore {

    private RddSemanticsStore() {}

    /** companionId → 内存语义表（键 = assetKey 小写）。 */
    private static final Map<UUID, Map<String, AssetSemantics>> SEMANTICS = new ConcurrentHashMap<>();

    /** 最多保留多少条语义（防长会话 + 旧目标残留无限增长；派生源头只有几十种物品键）。 */
    private static final int MAX_ENTRIES = 256;

    // ── 派生逻辑（纯函数，可单测）──────────────────────────────────

    /**
     * 从需求清单派生语义表（S1 写路径核心）。
     *
     * <p>规则（保守，全部可从清单复核）：
     * <ul>
     *   <li>CRITICAL 需求 → IMPORTANT（D01：重要性是「资产对这个目标」的关系——
     *       清单里的 CRITICAL 就是「没有它整条链卡死」的实测表达）；</li>
     *   <li>NORMAL / OPPORTUNISTIC → SECONDARY；</li>
     *   <li>本次清单没出现但旧表里 IMPORTANT 的键 → IRRELEVANT（目标换向了；
     *       只降标记不删除，LossImpactAnalyzer 对 IRRELEVANT 不触发动作）；</li>
     *   <li>同一物品出现在多个需求键时（替代品）逐条覆盖取**高**档——
     *       与 RddRequirementDetector 的「同键取高」同口径，两处不一致清单与语义会互相矛盾。</li>
     * </ul>
     *
     * @param previous 旧表（可 null = 首次）；用于把消失的键降为 IRRELEVANT
     * @return 新表（键小写）
     */
    static Map<String, AssetSemantics> derive(RequirementManifest.Manifest manifest, String goalId,
                                              Map<String, AssetSemantics> previous) {
        Map<String, AssetSemantics> out = new LinkedHashMap<>();
        if (manifest != null) {
            for (RequirementManifest.Requirement r : manifest.requirements()) {
                if (r == null || r.key() == null || r.key().isBlank()) continue;
                String key = r.key().toLowerCase(Locale.ROOT);
                AssetSemantics.Importance imp = r.importance() == RequirementManifest.Importance.CRITICAL
                        ? AssetSemantics.Importance.IMPORTANT
                        : AssetSemantics.Importance.SECONDARY;
                AssetSemantics cur = out.get(key);
                if (cur != null && cur.importance() == AssetSemantics.Importance.IMPORTANT) {
                    continue;   // 已是高档，不降级（同键取高）
                }
                out.put(key, new AssetSemantics(key, imp, AssetSemantics.guessNature(key),
                        goalId == null ? "" : goalId, String.valueOf(r.minimum()), "derived:requirement_manifest",
                        List.copyOf(r.alternatives())));
            }
        }
        // 旧表里仍被清单引用的键：保留旧 source（不谎称这次派生的）
        // ——上面循环已重建这些键，这里只处理「消失的键」
        if (previous != null) {
            for (Map.Entry<String, AssetSemantics> e : previous.entrySet()) {
                if (out.containsKey(e.getKey())) continue;
                AssetSemantics old = e.getValue();
                if (old == null) continue;
                if (old.importance() == AssetSemantics.Importance.IRRELEVANT) {
                    out.put(e.getKey(), old);   // 已经是 IRRELEVANT 的原样带过来（不重建时间戳）
                } else {
                    out.put(e.getKey(), new AssetSemantics(old.assetKey(),
                            AssetSemantics.Importance.IRRELEVANT, old.nature(),
                            old.servesGoal(), old.requiredCapability(), old.source() + ";stale",
                            old.substitutes()));
                }
            }
        }
        // 有界：超出按插入序截断（LinkedHashMap 头部最老）
        if (out.size() > MAX_ENTRIES) {
            List<String> keys = new ArrayList<>(out.keySet());
            for (int i = 0; i < out.size() - MAX_ENTRIES; i++) {
                out.remove(keys.get(i));
            }
        }
        return out;
    }

    /** 供 LossImpactAnalyzer 消费的形态：assetKey 原样键（分析器自己试小写）。 */
    static Map<String, AssetSemantics> forAnalyzer(Map<String, AssetSemantics> table) {
        Map<String, AssetSemantics> out = new LinkedHashMap<>();
        if (table != null) {
            for (Map.Entry<String, AssetSemantics> e : table.entrySet()) {
                if (e.getValue() != null) out.put(e.getValue().assetKey(), e.getValue());
            }
        }
        return out;
    }

    // ── 宿主侧（IO + 缓存）──────────────────────────────────────

    /** 派生 + 更新内存 + 落盘（RddFactContext 在清单重建时调）。IO 失败静默（fail-soft）。 */
    static void deriveAndSave(Path directory, UUID companionId,
                              RequirementManifest.Manifest manifest, String goalId) {
        if (companionId == null || manifest == null) return;
        try {
            Map<String, AssetSemantics> prev = SEMANTICS.get(companionId);
            Map<String, AssetSemantics> next = derive(manifest, goalId, prev);
            SEMANTICS.put(companionId, next);
            save(directory, companionId, next);
        } catch (RuntimeException | IOException ex) {
            // 语义是派生缓存：失败只丢持久化，内存表照常供 LossImpactAnalyzer 用
        }
    }

    /** 只读视图（RddRepairDispatch 死亡时调；无表 = 空表，分析器走 SECONDARY 兜底）。 */
    static Map<String, AssetSemantics> semantics(UUID companionId) {
        Map<String, AssetSemantics> t = companionId == null ? null : SEMANTICS.get(companionId);
        return t == null ? Map.of() : t;
    }

    /** 清同伴（forget 路径，与 RddFactContext.forget 同步）。 */
    static void forget(UUID companionId) {
        if (companionId != null) SEMANTICS.remove(companionId);
    }

    static void save(Path directory, UUID companionId, Map<String, AssetSemantics> table) throws IOException {
        if (directory == null || companionId == null || table == null) return;
        Files.createDirectories(directory);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (AssetSemantics s : table.values()) {
            if (s == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            sb.append("\"assetKey\":").append(json(s.assetKey())).append(',');
            sb.append("\"importance\":").append(json(s.importance().name())).append(',');
            sb.append("\"nature\":").append(json(s.nature().name())).append(',');
            sb.append("\"servesGoal\":").append(json(s.servesGoal())).append(',');
            sb.append("\"requiredCapability\":").append(json(s.requiredCapability())).append(',');
            sb.append("\"source\":").append(json(s.source())).append(',');
            sb.append("\"substitutes\":[");
            for (int i = 0; i < s.substitutes().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(json(s.substitutes().get(i)));
            }
            sb.append("]}");
        }
        sb.append(']');
        Path target = directory.resolve(companionId + ".json");
        Path temporary = directory.resolve(companionId + ".json.tmp");
        Files.writeString(temporary, sb.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 最小字符串 JSON 转义（语义字段全是受控短文本，但别赌）。 */
    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
