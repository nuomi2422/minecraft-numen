package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 经验的 JSONL 持久化（一行一条 {@link ExperienceEntry}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>按稳定键去重</b>：同 {@code id} 的经验只合并，不无限追加——防止旧 DD 18K 条
 *       “同失败刷屏”问题复发。</li>
 *   <li><b>原子落盘</b>：每次变更写临时文件 + {@code ATOMIC_MOVE} 替换，不半写坏文件。</li>
 *   <li><b>best-effort</b>：IO 失败只记日志、保留内存态，绝不让经验写入拖垮宿主。</li>
 *   <li><b>线程安全</b>：本类所有变更方法 {@code synchronized}；内存镜像 + 全量重写，
 *       对经验这种小数据量是正确的换复杂度。</li>
 * </ul>
 *
 * <p>经验何时升级成熟度（详见 {@link #recordEvidence}）：真实成功累计证据；
 * 失败只加反例、最多把 OBSERVED 推到 ATTEMPTED，不降级已确认经验。
 */
public final class ExperienceStore {

    private static final Logger LOG = LoggerFactory.getLogger(ExperienceStore.class);

    /** 多少次真实成功从 VERIFIED 升级到 GENERALIZED。 */
    public static final int GENERALIZED_THRESHOLD = 3;
    /** 反例最多保留几条，防列表无限膨胀。 */
    public static final int MAX_COUNTEREXAMPLES = 5;

    private final Path file;
    private final List<ExperienceEntry> mirror = new ArrayList<>();
    private boolean loaded;

    private ExperienceStore(Path file) {
        this.file = file;
    }

    public static ExperienceStore at(Path file) {
        return new ExperienceStore(file);
    }

    public Path file() {
        return file;
    }

    /** 全部经验（插入序快照）。 */
    public synchronized List<ExperienceEntry> all() {
        ensureLoaded();
        return List.copyOf(mirror);
    }

    public synchronized int size() {
        ensureLoaded();
        return mirror.size();
    }

    /**
     * 写入或合并一条经验。同 {@code id} 已存在时合并：描述类字段取新值、触发词/工具/标签
     * 并集、成熟度与验证次数取更高者——合并绝不清掉已积累的证据。
     *
     * @return 实际落库的那条（可能是合并结果）
     */
    public synchronized ExperienceEntry learn(ExperienceEntry entry) {
        if (entry == null || entry.type() == null || entry.title() == null || entry.title().isBlank()
                || entry.description() == null || entry.description().isBlank()) {
            throw new IllegalArgumentException("experience requires type, title and description");
        }
        ensureLoaded();
        ExperienceEntry normalized = entry.id() == null || entry.id().isBlank()
                ? ExperienceEntry.builder().from(entry)
                .id(ExperienceEntry.stableKey(entry.type(), entry.title())).build()
                : entry;

        int index = indexOf(normalized.id());
        ExperienceEntry stored;
        if (index < 0) {
            stored = normalized;
            mirror.add(stored);
            LOG.info("[experience] learned '{}' ({})", stored.title(), stored.type());
        } else {
            stored = merge(mirror.get(index), normalized);
            mirror.set(index, stored);
            LOG.info("[experience] merged '{}' (now {})", stored.title(), stored.maturity());
        }
        persist();
        return stored;
    }

    /**
     * 记录一次真实世界的验证结果。
     *
     * <pre>{@code
     * success=true ：verifiedCount++；成熟度按证据累计升级
     *               (OBSERVED/ATTEMPTED→VERIFIED，verifiedCount≥3→GENERALIZED)
     * success=false：追加反例（封顶 MAX_COUNTEREXAMPLES）；OBSERVED→ATTEMPTED；不降级
     * }</pre>
     *
     * @return 更新后的经验；id 不存在返回 {@code null}
     */
    public synchronized ExperienceEntry recordEvidence(String id, boolean success, String note) {
        ensureLoaded();
        int index = indexOf(id);
        if (index < 0) {
            return null;
        }
        ExperienceEntry old = mirror.get(index);
        List<String> counterexamples = old.counterexamples();
        ExperienceMaturity maturity = old.maturity();
        int verifiedCount = old.verifiedCount();
        long verifiedAt = old.verifiedAt();

        if (success) {
            verifiedCount++;
            verifiedAt = System.currentTimeMillis();
            maturity = maturityFor(verifiedCount);
            // 永不降级：新证据只升不降。
            if (maturity.level() < old.maturity().level()) {
                maturity = old.maturity();
            }
        } else {
            String reason = (note == null || note.isBlank()) ? "unconfirmed" : note;
            Set<String> seen = new LinkedHashSet<>(counterexamples);
            seen.add(reason);
            if (seen.size() > MAX_COUNTEREXAMPLES) {
                seen.remove(seen.iterator().next());
            }
            counterexamples = List.copyOf(seen);
            if (old.maturity().level() < ExperienceMaturity.ATTEMPTED.level()) {
                maturity = ExperienceMaturity.ATTEMPTED;
            }
        }

        ExperienceEntry updated = old.withEvidence(maturity, verifiedCount, verifiedAt, counterexamples);
        mirror.set(index, updated);
        persist();
        return updated;
    }

    // ---- internals ----

    /** 按验证次数推出成熟度（成功视角）。 */
    private static ExperienceMaturity maturityFor(int verifiedCount) {
        if (verifiedCount >= GENERALIZED_THRESHOLD) {
            return ExperienceMaturity.GENERALIZED;
        }
        if (verifiedCount >= 1) {
            return ExperienceMaturity.VERIFIED;
        }
        return ExperienceMaturity.ATTEMPTED;
    }

    private int indexOf(String id) {
        for (int i = 0; i < mirror.size(); i++) {
            if (mirror.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private ExperienceEntry merge(ExperienceEntry old, ExperienceEntry fresh) {
        return ExperienceEntry.builder()
                .id(old.id())
                .type(fresh.type() == null ? old.type() : fresh.type())
                .title(fresh.title())
                .description(fresh.description())
                .rationale(blank(fresh.rationale()) ? old.rationale() : fresh.rationale())
                .rootCause(blank(fresh.rootCause()) ? old.rootCause() : fresh.rootCause())
                .recommendedResponse(blank(fresh.recommendedResponse()) ? old.recommendedResponse() : fresh.recommendedResponse())
                .triggerStrings(union(old.triggerStrings(), fresh.triggerStrings()))
                .toolNames(union(old.toolNames(), fresh.toolNames()))
                .tags(union(old.tags(), fresh.tags()))
                .maturity(ExperienceMaturity.max(old.maturity(), fresh.maturity()))
                .verifiedCount(Math.max(old.verifiedCount(), fresh.verifiedCount()))
                .priority(Math.max(old.priority(), fresh.priority()))
                .counterexamples(fresh.counterexamples().isEmpty() ? old.counterexamples() : fresh.counterexamples())
                .createdAt(old.createdAt())
                .verifiedAt(fresh.verifiedAt() > 0 ? fresh.verifiedAt() : old.verifiedAt())
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> seen = new LinkedHashSet<>(a);
        seen.addAll(b);
        return List.copyOf(seen);
    }

    /**
     * 加载统计。
     *
     * <p><b>三个数必须分开</b>，因为它们对应三种不同的病：
     * <ul>
     *   <li>{@code loadedLine} —— 逐行 JSONL 读出的（正常）</li>
     *   <li>{@code loadedLegacy} —— <b>形状与文件名不自洽</b>读出的
     *       （整个文件是一个 JSON 数组，或整个文件只有一条）。
     *       这些文件本该是「一行一条」，需要迁移。</li>
     *   <li>{@code loadedFailed} —— <b>真的解析失败</b>的（代码问题或数据被截断）。</li>
     * </ul>
     *
     * <p>⚠️ 历史教训：原先 {@link RuntimeException} 被 catch 后只记一行日志，
     * 于是「文件存在但一条都读不进来」表现为 {@code size()==0} ——
     * <b>不报错，只是不工作</b>。现在这三个数是公开读数，异常可见。
     */
    public record LoadStats(int loadedLine, int loadedLegacy, int loadedFailed) {
        /** 成功读出的总条数（不含失败）。 */
        public int total() {
            return loadedLine + loadedLegacy;
        }

        /** 是否有形状与后缀不自洽、需要迁移的文件。 */
        public boolean needsMigrate() {
            return loadedLegacy > 0;
        }

        /** 是否有真正解析失败的条目 —— 需要人看。 */
        public boolean degraded() {
            return loadedFailed > 0;
        }
    }

    private LoadStats loadStats = new LoadStats(0, 0, 0);

    /** 本次加载过程中的三个计数器（{@link #ensureLoaded()} 内部累加）。 */
    private int statLine;
    private int statLegacy;
    private int statFailed;

    /** 本次加载的统计（只读）。未加载过时是全 0。 */
    public synchronized LoadStats loadStats() {
        ensureLoaded();
        return loadStats;
    }

    /**
     * 加载磁盘经验，<b>兼容三种文件形状</b>。
     *
     * <p>为什么需要兼容：早期版本曾把整个 JSON 数组写进 {@code .jsonl} 后缀的文件
     * （一个文件只有 1 行、内容是数组）。读取端原先只认「逐行 JSONL」，
     * 对数组调 {@code getAsJsonObject()} 会抛 {@code IllegalStateException}，
     * 那唯一一行被静默跳过 ⇒ mirror 为空 ⇒ 注入侧 {@code total()==0} ⇒
     * <b>经验正文一条都进不了上下文</b>。
     *
     * <p>同类的坑学习者队列在 2026-09-29 已修（{@code MemoQueue} 改成非 {@code .jsonl}
     * 后缀），经验库当时漏了。
     */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (Files.isRegularFile(file)) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                LOG.warn("[experience] failed to read {}: {}", file, ex.toString());
                loaded = true;
                return;
            }
            statLine = 0;
            statLegacy = 0;
            statFailed = 0;

            // 先判形状：整个文件是一个 JSON 值时走兼容路径。
            JsonElement root = tryParse(text);
            if (root != null && root.isJsonArray()) {
                // 遗留：整个文件是一个 JSON 数组（.jsonl 后缀，但内容不是行分隔）
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        countLegacyOrFail(el.getAsJsonObject());
                    } else {
                        statFailed++;
                    }
                }
            } else if (root != null && root.isJsonObject()) {
                // 遗留：整个文件只有一条
                statLegacy++;
                addFromObject(root.getAsJsonObject());
            } else {
                // 正常路径：一行一条
                for (String line : text.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    statLine++;
                    JsonObject o = tryParseObject(line);
                    if (o == null) {
                        statFailed++;
                        continue;
                    }
                    addFromObject(o);
                }
            }
            loadStats = new LoadStats(statLine, statLegacy, statFailed);
            if (loadStats.needsMigrate()) {
                LOG.warn("[experience] {} is not line-delimited JSONL (loaded {} via legacy shape); "
                        + "call migrateLegacyShape() to convert it", file, statLegacy);
            }
            if (loadStats.degraded()) {
                LOG.warn("[experience] {}: {} entries failed to parse (store is degraded, not empty)",
                        file, statFailed);
            }
        }
        loaded = true;
    }

    private static JsonElement tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 逐行路径用：必须顶层是 object 才是「一条经验」。 */
    private static JsonObject tryParseObject(String line) {
        try {
            JsonElement e = JsonParser.parseString(line);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 遗留路径用：能加进镜像算 legacy，加不进算 failed。 */
    private void countLegacyOrFail(JsonObject o) {
        if (addFromObject(o)) {
            statLegacy++;
        } else {
            statFailed++;
        }
    }

    /**
     * 把一条 JSON object 加进镜像，按 id 去重。
     *
     * @return 是否真的加进去了（重复 id、缺 id/title、解析失败都算没加）
     */
    private boolean addFromObject(JsonObject o) {
        try {
            ExperienceEntry e = ExperienceEntry.fromJson(o);
            if (e.id() == null || e.id().isBlank() || e.title() == null || e.title().isBlank()) {
                return false;
            }
            if (indexOf(e.id()) < 0) {
                mirror.add(e);
                return true;
            }
            return false;
        } catch (RuntimeException ex) {
            LOG.warn("[experience] unparsable entry in {}: {}", file.getFileName(), ex.toString());
            return false;
        }
    }

    /**
     * 一次性迁移「形状与文件名不自洽」的遗留文件：整个 JSON 数组 → 逐行 JSONL。
     *
     * <p>安全约束（用户存档不可再丢）：
     * <ol>
     *   <li><b>先备份</b>，且<b>仅当备份不存在时</b>才建（否则反复迁移会刷一堆备份）</li>
     *   <li><b>原子替换</b>，走与 {@link #persist()} 同一条 temp + {@code ATOMIC_MOVE} 路径</li>
     *   <li><b>幂等</b>：迁完再读，走正常逐行路径，{@code loadedLegacy} 变 0</li>
     * </ol>
     *
     * @return 迁移后的条数；本来就不需要迁移时返回 {@code loadStats().total()}
     */
    public synchronized int migrateLegacyShape() {
        ensureLoaded();
        if (!loadStats.needsMigrate()) {
            return loadStats.total();
        }
        Path backup = file.resolveSibling(file.getFileName() + ".pre-migrate-" + System.currentTimeMillis() + ".bak");
        try {
            if (!Files.exists(backup)) {
                Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
                LOG.info("[experience] backed up legacy-shaped {} to {}", file, backup.getFileName());
            }
            persist();   // 逐行 JSONL + temp + ATOMIC_MOVE
            int n = loadStats.total();
            LOG.info("[experience] migrated {} from legacy array shape to line-delimited JSONL ({} entries)",
                    file.getFileName(), n);
            loaded = false;      // 让下一次读走正常路径，loadStats 随之刷新
            return n;
        } catch (IOException ex) {
            LOG.warn("[experience] failed to migrate {}: {}", file, ex.toString());
            return loadStats.total();
        }
    }

    /** 全量原子重写：temp 文件 → ATOMIC_MOVE 替换。 */
    private void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder(mirror.size() * 128);
            for (ExperienceEntry e : mirror) {
                sb.append(e.toJson()).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            LOG.warn("[experience] failed to persist {}: {}", file, ex.toString());
        }
    }
}
