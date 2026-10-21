package com.dwinovo.numen.plugins.experience;

import com.google.gson.Gson;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * expmem 经验模块的观测出口：往 {@code config/numen/monitor/} 下追加 JSON 行。
 *
 * <p>不依赖引擎内部类，插件自己写文件，行格式与 MonitoringJournal 对齐，
 * 监测台可原样解析。
 *
 * <p>两个分区：
 * <ul>
 *   <li>{@code expmem.jsonl}（category=expmem）—— 经验模块自己的事件；</li>
 *   <li>{@code context.jsonl}（category=context）—— 与引擎的 {@code budget_gate}
 *       同一个分区。G5d 的检索稿要能和「这一轮的上下文长什么样」并排看，
 *       分开写会让人对时间线对不上。</li>
 * </ul>
 */
public final class ExperienceMonitor {

    private static final Gson GSON = new Gson();

    private ExperienceMonitor() {}

    /** expmem 自己的事件（{@code monitor/expmem.jsonl}）。 */
    public static void publish(String type, Map<String, ?> data) {
        write("expmem", "expmem", type, data);
    }

    /**
     * 上下文分区事件（{@code monitor/context.jsonl}，category=context）。
     *
     * <p>G5d 的 {@code retrieval_snapshot} 走这里：它描述的是「这一轮检索给了什么」，
     * 与 budget_gate 属于同一条时间线。
     */
    public static void publishContext(String type, Map<String, ?> data) {
        write("context", "context", type, data);
    }

    private static void write(String fileStem, String category, String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve(fileStem + ".jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", fileStem + "-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", category);
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响 expmem 主流程
        }
    }
}
