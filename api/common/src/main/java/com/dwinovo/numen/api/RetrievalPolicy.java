package com.dwinovo.numen.api;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.NumenPaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G5d：统一检索的<b>provider 级开关</b>（75 号 §15.5「按 provider 级开关回退」的最小实现）。
 *
 * <p>宿主只认一个配置位：{@code <config/numen>/retrieval-policy.json} 里的
 * {@code experienceRetrievalMode}。取值为 {@code legacy}（默认）或 {@code unified}。
 *
 * <ul>
 *   <li><b>legacy</b> —— 旧路（{@code ExperienceKnowledgeSource.recall}）照跑，
 *       统一检索一次都不调；<b>不配置的人行为零变化</b>。</li>
 *   <li><b>unified</b> —— 经验贡献改走 G5 统一检索（{@code RetrievalService.buildContext}），
 *       旧路的经验部分关掉，避免同一条经验被注入两遍。</li>
 * </ul>
 *
 * <p><b>热重载</b>：与 {@code budget-policy.json} 同款做法 —— 每次读都看 mtime+size，
 * 变了就重新解析并在日志里报一次；<b>不重启游戏</b>。文件不存在 / 读坏 / 值不认识
 * 一律退回 {@code legacy}（失败要往安全的一侧倒）。
 *
 * <p>为什么放在宿主 API 而不是经验插件里：开关是「provider 级」的，将来接
 * tool-catalog 等别的 provider 时读同一个位，不需要再发明第二个配置名。
 */
public final class RetrievalPolicy {

    /** 生效模式。 */
    public enum Mode {
        /** 旧路照跑（默认；不配置的人行为零变化）。 */
        LEGACY,
        /** 经验贡献走统一检索。 */
        UNIFIED
    }

    /** 配置文件（放在 {@code config/numen/} 下，与 budget-policy.json 同级）。 */
    public static final String FILE_NAME = "retrieval-policy.json";

    /** 配置键。 */
    public static final String EXPERIENCE_MODE_KEY = "experienceRetrievalMode";

    /** 按文件缓存最近一次解析结果（mtime+size 变了才重读）。 */
    private static final Map<Path, Cache> CACHE = new ConcurrentHashMap<>();

    private record Cache(long modified, long size, Mode mode) {}

    private RetrievalPolicy() {}

    /**
     * 生产入口：读 {@code config/numen/retrieval-policy.json} 的当前值。
     *
     * <p>任何异常（路径没初始化、文件被占用）都退 {@link Mode#LEGACY}——
     * 读不到配置时「保持旧行为」比「悄悄切新链路」安全。
     */
    public static Mode experienceRetrievalMode() {
        try {
            return mode(NumenPaths.config().resolve(FILE_NAME));
        } catch (RuntimeException e) {
            return Mode.LEGACY;
        }
    }

    /**
     * 读一个具体文件的模式（可测入口）。
     *
     * @param file 配置文件；{@code null} 或不存在 → {@link Mode#LEGACY}
     */
    public static Mode mode(Path file) {
        if (file == null) return Mode.LEGACY;
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException missing) {
            CACHE.remove(file);
            return Mode.LEGACY;
        }
        Cache cached = CACHE.get(file);
        if (cached != null && cached.modified() == modified && cached.size() == size) {
            return cached.mode();
        }
        Mode parsed = parse(read(file));
        CACHE.put(file, new Cache(modified, size, parsed));
        if (cached == null || cached.mode() != parsed) {
            Constants.LOG.info("[numen] retrieval-policy: {}={}（{}）",
                    EXPERIENCE_MODE_KEY, parsed.name().toLowerCase(java.util.Locale.ROOT), file);
        }
        return parsed;
    }

    /**
     * 纯解析：{@code {"experienceRetrievalMode":"unified"}} → UNIFIED。
     *
     * <p>坏 JSON / 非对象 / 缺键 / 值不认识 一律 LEGACY（宁可退回旧路，也不猜）。
     */
    static Mode parse(String json) {
        if (json == null || json.isBlank()) return Mode.LEGACY;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return Mode.LEGACY;
            JsonObject object = root.getAsJsonObject();
            JsonElement value = object.get(EXPERIENCE_MODE_KEY);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return Mode.LEGACY;
            String text = value.getAsString();
            return text != null && "unified".equalsIgnoreCase(text.trim())
                    ? Mode.UNIFIED : Mode.LEGACY;
        } catch (RuntimeException bad) {
            return Mode.LEGACY;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }
}
