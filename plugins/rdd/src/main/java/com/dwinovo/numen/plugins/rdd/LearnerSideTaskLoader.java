package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskTrigger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 学习者 SIDE_TASK 产物的**消费端**（2026-10-27，权利缺口②）。
 *
 * <p>契约：{@code config/numen/artifact-outbox/SIDE_TASK/<companion>/<id>.json}
 * 的 {@code body} 字段是 {@code {"type_id","when":{"is_dark":bool},"nudge":"…"}}。
 * 跨插件 import 被封死，所以按**文件形状**读（与 acx.AcxAcxArtifactAdopter 同一方式）。
 *
 * <p>支持度（诚实边界）：当前引擎端口只判得了昼夜与睡眠——
 * {@code when} 只支持 {@code is_dark}（布尔）；写了别的条件字段 → 该条被拒收并说明，
 * 不许静默丢弃（learner 侧 prompt 规则 17 已对齐）。
 *
 * <p>触发语义：条件满足 → 引擎开一条 {@code learner_side} 型支线（每游戏日一次，
 * 由引擎统一去重），nudge 用草稿里的提示语——这就是「学习者派的活」最小闭环。
 */
final class LearnerSideTaskLoader {
    private static final Logger LOG = LoggerFactory.getLogger(LearnerSideTaskLoader.class);

    private LearnerSideTaskLoader() {
    }

    /**
     * 扫投递箱并注册触发器。返回导入结果（观测用）。
     *
     * @param outboxRoot artifact-outbox 目录
     * @param engine      支线引擎（registerTrigger/registerType）
     */
    static List<String> loadAll(Path outboxRoot, SideTaskEngine engine) {
        List<String> report = new ArrayList<>();
        if (outboxRoot == null || engine == null) {
            return report;
        }
        Path dir = outboxRoot.resolve("SIDE_TASK");
        if (!Files.isDirectory(dir)) {
            return report;
        }
        try (var comps = Files.list(dir)) {
            for (Path compDir : comps.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(compDir)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                        try {
                            JsonObject o = JsonParser.parseString(
                                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                            if (!"PENDING".equals(str(o, "status"))) {
                                continue; // ADOPTED/REJECTED 不重复导入
                            }
                            String body = str(o, "body");
                            JsonObject draft = JsonParser.parseString(body).getAsJsonObject();
                            String err = importOne(draft, engine, f.toString());
                            report.add(err == null ? "OK " + f.getFileName() : "REJECTED " + f.getFileName() + ": " + err);
                        } catch (Exception e) {
                            report.add("UNREADABLE " + f.getFileName() + ": " + e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[rdd-side] learner SIDE_TASK 扫描失败: {}", e.toString());
        }
        if (!report.isEmpty()) {
            LOG.info("[rdd-side] learner SIDE_TASK 导入: {}", report);
        }
        return report;
    }

    /** 导入一条；返回 null=成功，否则是拒收原因。 */
    private static String importOne(JsonObject draft, SideTaskEngine engine, String from) {
        String typeId = str(draft, "type_id");
        if (typeId.isBlank()) {
            return "缺 type_id";
        }
        String nudge = str(draft, "nudge");
        if (nudge.isBlank()) {
            return "缺 nudge（触发时对同伴说的话）";
        }
        boolean isDark = false;
        boolean hasIsDark = false;
        if (draft.has("when") && draft.get("when").isJsonObject()) {
            JsonObject when = draft.getAsJsonObject("when");
            for (var e : when.entrySet()) {
                String k = e.getKey();
                if ("is_dark".equals(k)) {
                    isDark = e.getValue().isJsonPrimitive() && e.getValue().getAsBoolean();
                    hasIsDark = true;
                } else {
                    // 诚实边界：不支持的条件不许静默忽略
                    return "不支持的条件字段 '" + k + "'（当前只支持 is_dark）";
                }
            }
        }
        if (!hasIsDark) {
            return "when 里必须有 is_dark";
        }
        final boolean dark = isDark;
        final String id = "learner_" + typeId;
        engine.registerType(new LearnerSideTaskType(id, nudge));
        engine.registerTrigger(new SideTaskTrigger() {
            @Override
            public String typeId() {
                return id;
            }

            @Override
            public boolean fires(Context ctx) {
                // is_dark 由昼夜端口推导；无昼夜维度永远不触发
                if (!ctx.hasDayNightCycle()) {
                    return false;
                }
                long t = Math.floorMod(ctx.dayTime(), 24_000L);
                boolean nowDark = t >= 13_000L && t < 23_000L;
                return nowDark == dark;
            }
        });
        return null;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
