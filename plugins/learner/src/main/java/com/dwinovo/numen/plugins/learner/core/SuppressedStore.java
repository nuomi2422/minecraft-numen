package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 被风暴抑制的候选的「降级暂存」（2026-10-26）。
 *
 * <h2>为什么要有它</h2>
 * {@link StormGuard} 原来把冷却窗口内的候选<b>直接丢掉</b>：读数是诚实的，但样本永久消失。
 * 实机代价很具体：{@code bedDistance=2.83, isNight=true, sleeping=false} 这条最干净的
 * 「床边没睡」样本被 {@code storm_suppressed=1} 挡掉，手动 intake 又撞 10 分钟冷却 ——
 * 防噪声的闸门把唯一的真信号也一起拦了。
 *
 * <h2>改成什么</h2>
 * 抑制 = <b>暂存 + 冷却后自动放回</b>，不是丢弃：
 * <ol>
 *   <li>被抑制时把整条 {@link Memo} 原样写到 {@code learner-suppressed-<uuid>.jsonl}（一行一条）；</li>
 *   <li>冷却窗口（{@link StormGuard#COOLDOWN_MILLIS}）过去后，下一轮 intake 由
 *       {@code LearnerAutoIntake} 取回并入队，带 {@code deferred_admitted} 读数；</li>
 *   <li>队列满写不进去就放回暂存区（{@code at} 拨到当前），不丢。</li>
 * </ol>
 *
 * <h2>边界</h2>
 * 纯 JVM、无 MC 依赖；读写都 best-effort（IO 失败只让这一条晚一点回来，不抛给 tick）。
 * 暂存区是<b>候选</b>不是经验，永不进经验库。
 */
public final class SuppressedStore {

    private static final Gson GSON = new Gson();

    private static final String PREFIX = "learner-suppressed-";
    private static final String SUFFIX = ".jsonl";

    private SuppressedStore() {}

    /** 暂存文件名：{@code learner-suppressed-<uuid>.jsonl}（与 memo 队列同目录）。 */
    public static Path fileFor(Path configDir, UUID companion) {
        return configDir.resolve(PREFIX + companion + SUFFIX);
    }

    /** 取回出来的一条：够重建 {@link Memo} + 审计用的元数据。 */
    public record Entry(Memo memo, String fingerprint, String reason, long at) {}

    /**
     * 记一条被抑制的候选。{@code reason} 用 {@link StormGuard.Ruling#code()}（如 SUPPRESSED_COOLDOWN），
     * 不编造、不合并。
     */
    public static synchronized void append(Path configDir, UUID companion, Memo memo,
                                           String fingerprint, String reason, long nowMillis) {
        if (configDir == null || companion == null || memo == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", memo.id());
        o.addProperty("problem", memo.problem());
        o.addProperty("stage", memo.stage());
        o.addProperty("tried", memo.tried());
        o.addProperty("snapshot", memo.snapshot());
        o.addProperty("createdAt", memo.createdAt());
        o.addProperty("category", memo.category());
        o.addProperty("fingerprint", fingerprint == null ? "" : fingerprint);
        o.addProperty("reason", reason == null ? "" : reason);
        o.addProperty("at", nowMillis);
        try {
            Files.createDirectories(configDir);
            Files.writeString(fileFor(configDir, companion), o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 暂存失败 = 这条样本没了（和旧行为一样），但不许因此打断 tick
        }
    }

    /**
     * 取出「已经过了冷却」的候选（最多 {@code cap} 条），剩下的留在文件里。
     * 取回的条目会从文件里删掉 —— 调用方负责入队；写不进队列时要 {@link #append} 放回。
     */
    public static synchronized List<Entry> drainDue(Path configDir, UUID companion,
                                                    long nowMillis, long olderThanMillis, int cap) {
        List<Entry> due = new ArrayList<>();
        if (configDir == null || companion == null || cap <= 0) {
            return due;
        }
        Path f = fileFor(configDir, companion);
        if (!Files.isRegularFile(f)) {
            return due;
        }
        List<String> keep = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                Entry e = parse(line);
                if (e == null) {
                    continue;   // 坏行: 丢弃（读不出来的东西留着只会每次卡一下）
                }
                if (due.size() < cap && nowMillis - e.at() >= olderThanMillis) {
                    due.add(e);
                } else {
                    keep.add(line);
                }
            }
            Files.writeString(f, keep.isEmpty() ? "" : String.join("\n", keep) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
        return due;
    }

    /** 暂存区里还有多少条（读数用）。 */
    public static synchronized int size(Path configDir, UUID companion) {
        Path f = fileFor(configDir, companion);
        if (configDir == null || companion == null || !Files.isRegularFile(f)) {
            return 0;
        }
        try {
            int n = 0;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line != null && !line.isBlank() && parse(line) != null) {
                    n++;
                }
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 哪些同伴的暂存区里有东西（按文件名反推，与 {@code companionsWithMemos} 同一口径）。 */
    public static List<UUID> companionsWithSuppressed(Path configDir) {
        Set<UUID> out = new LinkedHashSet<>();
        if (configDir == null || !Files.isDirectory(configDir)) {
            return List.of();
        }
        try (var stream = Files.list(configDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
                    return;
                }
                String mid = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
                try {
                    out.add(UUID.fromString(mid));
                } catch (IllegalArgumentException ignored) {
                    // 不是同伴文件，跳过
                }
            });
        } catch (IOException ignored) {
            // 读不到目录 = 没有暂存区
        }
        return new ArrayList<>(out);
    }

    private static Entry parse(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String id = str(o, "id");
            if (id.isBlank()) {
                return null;
            }
            Memo memo = new Memo(id, str(o, "problem"), str(o, "stage"), str(o, "tried"),
                    str(o, "snapshot"), num(o, "createdAt"), str(o, "category"));
            return new Entry(memo, str(o, "fingerprint"), str(o, "reason"), num(o, "at"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
