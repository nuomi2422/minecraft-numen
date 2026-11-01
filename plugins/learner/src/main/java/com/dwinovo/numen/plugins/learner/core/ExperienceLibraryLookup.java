package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * G6b：**只读**地看一眼经验库里已经有什么。
 *
 * <h2>为什么学习者可以读、不可以写</h2>
 * 红线是「学习者不写经验库」（草稿只能走 `experience_learn`）。读没有这个风险，
 * 而且不读就没法回答「这条跟已有的什么关系」—— 那正是 G6b 要的。
 *
 * <h2>为什么直接读 jsonl 而不加跨插件 API</h2>
 * 经验库是 `config/numen/experience-<companion>.jsonl`，一行一条，格式稳定。
 * 为「读一眼」去动 `NumenApi` 加一个查询服务，收益远小于代价。
 *
 * <h2>相似度口径（刻意简单、可解释）</h2>
 * <ul>
 *   <li><b>high</b>：标题相同（忽略大小写/空白）<b>或</b> 触发词有交集 —— 这就是「同一件事」。</li>
 *   <li><b>low</b>：只有 tag 交集 —— 相关但不同事。</li>
 * </ul>
 * high 是 Z2「不许 NEW」的唯一依据，所以它只能由这里给，**不能由 LLM 自己填**。
 */
public final class ExperienceLibraryLookup {

    /** 最多回头几条 —— 太多了会把 prompt 撑爆，而且「同主题」本来就该是少数。 */
    public static final int MAX_HITS = 5;

    /**
     * 触发词作为「同一件事」证据的最小长度（2026-11-02）。
     *
     * <p>实测事故：库里有 3 条 debug_kill 策略把裸词 {@code death} 写进了 trigger_strings，
     * 而 {@code lookupByText} 只要求触发词是文本子串 —— 任何死亡 memo 的正文都含
     * "deathCauseId"，于是三条真实死亡教训（lava/fall/zombie）全被判「同一件事」，
     * 被迫 REVISE 到 debug_kill 条目上、topicMismatch 后丢弃。
     *
     * <p>口径：短于 6 字符的单 token 不构成身份证据（只会降召回，不会造假命中）。
     * 中文短语触发词通常远超这个长度；英文单词触发词会少数命中，这是已知代价。
     */
    public static final int MIN_TRIGGER_CHARS = 6;

    private ExperienceLibraryLookup() {}

    /**
     * @param configDir  `config/numen`
     * @param companionId 同伴 uuid（库文件名里就是它）
     * @param title      候选标题
     * @param triggers   候选触发词
     * @param tags       候选 tag（可空）
     */
    public static List<RetrievalDecision.Hit> lookup(Path configDir, String companionId,
                                                     String title, List<String> triggers,
                                                     List<String> tags) {
        if (configDir == null || companionId == null || companionId.isBlank()) {
            return List.of();
        }
        Path file = configDir.resolve("experience-" + companionId + ".jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();   // 还没有库 = 没有命中（不是错）
        }
        Set<String> wantTriggers = specific(normSet(triggers));
        // tag 只产生 low（相关≠同一件事），但仍过 identityWorthy：
        // 以后谁把裸 "death" 写进 tags，也不会再制造假命中（审核窗口 04:22）。
        Set<String> wantTags = specific(normSet(tags));
        String wantTitle = norm(title);

        List<Scored> scored = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o;
                try {
                    JsonElement e = JsonParser.parseString(line);
                    if (!e.isJsonObject()) continue;
                    o = e.getAsJsonObject();
                } catch (RuntimeException bad) {
                    continue;   // 坏行跳过：一条坏行不该让整次查询失败
                }
                String id = str(o, "id");
                if (id.isBlank()) continue;
                String entryTitle = str(o, "title");
                Set<String> entryTriggers = specific(normSet(arr(o, "trigger_strings")));
                Set<String> entryTags = specific(normSet(arr(o, "tags")));

                boolean sameTitle = !wantTitle.isBlank() && wantTitle.equals(norm(entryTitle));
                Set<String> sharedTriggers = new LinkedHashSet<>(wantTriggers);
                sharedTriggers.retainAll(entryTriggers);
                Set<String> sharedTags = new LinkedHashSet<>(wantTags);
                sharedTags.retainAll(entryTags);

                if (!sameTitle && sharedTriggers.isEmpty() && sharedTags.isEmpty()) {
                    continue;
                }
                String why = sameTitle ? "同标题"
                        : !sharedTriggers.isEmpty() ? "同触发词: " + String.join("/", sharedTriggers)
                        : "同 tag: " + String.join("/", sharedTags);
                boolean high = sameTitle || !sharedTriggers.isEmpty();
                int score = (sameTitle ? 100 : 0) + sharedTriggers.size() * 10 + sharedTags.size();
                scored.add(new Scored(score, new RetrievalDecision.Hit("exp:" + id, entryTitle, why, high)));
            }
        } catch (IOException io) {
            return List.of();   // 读失败 = 没有命中，但调用方会看到空而不是假数据
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<RetrievalDecision.Hit> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= MAX_HITS) break;
            out.add(s.hit);
        }
        return out;
    }

    /**
     * 按**自由文本**找命中（复盘时手上有的是 memo 正文，不是结构化的标题/触发词）。
     *
     * <p>口径：库里某条的标题或任一触发词（≥2 字）出现在文本里 → high；
     * 只有 tag 出现 → low。与 {@link #lookup} 同一套语义，只是匹配方向反过来。
     */
    public static List<RetrievalDecision.Hit> lookupByText(Path configDir, String companionId, String text) {
        if (configDir == null || companionId == null || companionId.isBlank() || text == null || text.isBlank()) {
            return List.of();
        }
        Path file = configDir.resolve("experience-" + companionId + ".jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        String hay = norm(text);
        List<Scored> scored = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o;
                try {
                    JsonElement e = JsonParser.parseString(line);
                    if (!e.isJsonObject()) continue;
                    o = e.getAsJsonObject();
                } catch (RuntimeException bad) {
                    continue;
                }
                String id = str(o, "id");
                if (id.isBlank()) continue;
                String entryTitle = str(o, "title");
                String titleKey = norm(entryTitle);
                boolean titleHit = titleKey.length() >= 2 && hay.contains(titleKey);
                int triggerHits = 0;
                for (String tr : arr(o, "trigger_strings")) {
                    String k = norm(tr);
                    // 见 identityWorthy：裸 "death" 这类纯英文短 token 不算身份证据
                    // （任何死亡 memo 正文都含 deathCauseId，会误判「同一件事」）。
                    if (identityWorthy(k) && hay.contains(k)) triggerHits++;
                }
                int tagHits = 0;
                for (String tg : arr(o, "tags")) {
                    String k = norm(tg);
                    if (identityWorthy(k) && hay.contains(k)) tagHits++;
                }
                if (!titleHit && triggerHits == 0 && tagHits == 0) continue;
                boolean high = titleHit || triggerHits > 0;
                String why = titleHit ? "文本里出现了这条的标题"
                        : triggerHits > 0 ? "文本里出现了它的触发词" : "只撞了 tag";
                int score = (titleHit ? 100 : 0) + triggerHits * 10 + tagHits;
                scored.add(new Scored(score, new RetrievalDecision.Hit("exp:" + id, entryTitle, why, high)));
            }
        } catch (IOException io) {
            return List.of();
        }
        scored.sort((x, y) -> Integer.compare(y.score, x.score));
        List<RetrievalDecision.Hit> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= MAX_HITS) break;
            out.add(s.hit);
        }
        return out;
    }

    private record Scored(int score, RetrievalDecision.Hit hit) {}

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return "";
        return o.get(key).getAsString();
    }

    private static List<String> arr(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        JsonArray a = o.getAsJsonArray(key);
        for (JsonElement e : a) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    /** 去掉不构成身份证据的触发词（见 {@link #identityWorthy}）。 */
    private static Set<String> specific(Set<String> s) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : s) {
            if (identityWorthy(k)) out.add(k);
        }
        return out;
    }

    /**
     * 一个触发词能不能当作「同一件事」的证据。
     *
     * <p>规则：长度 ≥ {@link #MIN_TRIGGER_CHARS} → 可以；短词只看**是否含非 ASCII**
     * （中文短词 岩浆/摔落/僵尸 保留，它们是领域词）；**纯英文短 token**（death/kill/lava）
     * 一律不算 —— 实测事故就是裸 "death" 命中了任何死亡 memo 的 deathCauseId。
     *
     * <p>已知代价：纯英文短触发词（如 lava）不再参与召回；这是精确性换召回的取舍，写在 §7。
     */
    private static boolean identityWorthy(String k) {
        if (k == null || k.isBlank()) return false;
        if (k.length() >= MIN_TRIGGER_CHARS) return true;
        for (int i = 0; i < k.length(); i++) {
            if (k.charAt(i) > 127) return true;   // 中文等非 ASCII 短词是领域词，保留
        }
        return false;
    }

    private static Set<String> normSet(List<String> v) {
        Set<String> out = new LinkedHashSet<>();
        if (v == null) return out;
        for (String s : v) {
            String n = norm(s);
            if (!n.isBlank()) out.add(n);
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
