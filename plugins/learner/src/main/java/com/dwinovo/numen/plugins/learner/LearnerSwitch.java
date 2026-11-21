package com.dwinovo.numen.plugins.learner;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置学习者总开关（2026-11-22，主人止损口径）。
 *
 * <p>主人的话：内部学习者和外置学习者重复，平时白烧钱；<b>默认关</b>，回归测试时才开。
 *
 * <p>开关文件 {@code config/numen/learner.json}：{@code {"enabled": false}}。
 * 缺文件/读失败都按 <b>关</b>（宁可少学，不悄悄花钱）。每 10 秒最多重读一次，改文件即生效。
 *
 * <p>关闭时：自动入队、复盘提醒、兜底复盘全停；{@code learner_note} / {@code learner_intake} /
 * {@code learner_review} 直接拒绝并说明原因；只读工具（{@code learner_status} 等）与已有数据不动。
 *
 * <p>与 {@code learner-auto-intake.json} 的关系：那份只管「自动入队」开关；这份是**总闸**，
 * 总闸关时自动入队开关不再被读取。
 */
final class LearnerSwitch {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerSwitch.class);
    static final String FILE_NAME = "learner.json";
    /** 与 auto-intake 开关同节奏：10 秒检查一次文件。 */
    static final long RELOAD_INTERVAL_MS = 10_000L;

    private static volatile boolean enabled = false;
    private static volatile long lastReloadAt = 0L;
    private static volatile boolean loggedOnce = false;

    private LearnerSwitch() {}

    /** 当前是否启用内置学习者。 */
    static boolean enabled() {
        return enabled;
    }

    /** 按节流重读；tick 每跳都能安全调。 */
    static void reloadIfDue(Path dir) {
        long now = System.currentTimeMillis();
        if (lastReloadAt > 0L && now - lastReloadAt < RELOAD_INTERVAL_MS) return;
        lastReloadAt = now;
        reload(dir);
    }

    /** 立即重读（setup 用），不受节流限制。状态变化打一条 INFO，便于实机取证。 */
    static void reload(Path dir) {
        boolean next;
        if (dir == null) {
            next = false;
            apply(next, "configDir-null");
            return;
        }
        Path f = dir.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(f)) {
                // 首次运行写一份带说明的默认文件：默认关。
                Files.createDirectories(dir);
                JsonObject def = new JsonObject();
                def.addProperty("_note", "内置学习者总开关：true=开启（回归测试时用）；false=关闭（默认，外置学习者为准）。");
                def.addProperty("enabled", false);
                Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(def) + "\n",
                        StandardCharsets.UTF_8);
                next = false;
            } else {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                // 只有 JSON 布尔 true 才开；字符串 "true"、数字 1、null、缺键一律关（Gson getAsBoolean 会把 "true" 也当 true，不能用）。
                next = o.has("enabled")
                        && o.get("enabled").isJsonPrimitive()
                        && o.get("enabled").getAsJsonPrimitive().isBoolean()
                        && o.get("enabled").getAsBoolean();
            }
        } catch (RuntimeException | java.io.IOException e) {
            next = false;
            LOG.warn("[learner-switch] read {} failed, keeping learner OFF: {}", f, e.toString());
        }
        apply(next, f.toString());
    }

    /** 只在状态或首次加载时打日志，避免 10 秒一刷。 */
    private static void apply(boolean next, String source) {
        if (!loggedOnce || next != enabled) {
            LOG.info("[learner-switch] internal learner is {} (source={})", next ? "ENABLED" : "DISABLED", source);
            loggedOnce = true;
        }
        enabled = next;
    }

    /** 给工具用的拒绝文案（统一口径，便于日志检索）。 */
    static String disabledMessage(String tool) {
        return tool + ": 内置学习者已关闭（config/numen/learner.json enabled=false；外置学习者为准）。"
                + "需要回归测试时把 enabled 改 true，或直接联系主人。数据与队列保持原样，没有删除。";
    }
}
