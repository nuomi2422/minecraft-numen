package com.dwinovo.numen.plugins.learner.blocks;

import com.dwinovo.numen.plugins.learner.sample.LearningSample;
import com.dwinovo.numen.plugins.learner.sample.SampleStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取会话的<strong>注册表</strong>：把五个原语包成「按同伴开一次会话、多次调用」的模型（159 D4）。
 *
 * <h2>为什么需要"会话"这一层</h2>
 * 五个原语单独看都能用，但它们必须共享同一个上下文：
 * 这次打开的是<b>哪条样本</b>、<b>已经展开过哪些块</b>、翻页<B>锚点</B>在哪。
 * 如果每次都从头查一遍，就会出现「展开用的样本和列目录用的样本不是同一条」——
 * 而那种错在回执里看起来完全正常。
 *
 * <h2>一条安全红线：不许读别人的样本</h2>
 * 会话按 {@code companionId} 归属。A 同伴打开 B 的样本必须被拒绝 ——
 * 这不是权限装饰：样本里有 B 的死亡位置、背包和决策上下文，
 * 串味之后"A 学到的东西"会永远无法解释清楚。161 §2 记过同类教训（跨同伴指纹计数）。
 *
 * <p>纯 JVM、无 IO、无 MC 依赖，可离线单测。
 */
public final class BlockSessionRegistry {

    /** 打开后的会话。 */
    private record Session(BlockReader reader, String windowNote) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 一次调用的结果。
     *
     * @param data 成功时的读数；失败时为空 Map（不塞半截数据，免得调用方拿它当结果）
     */
    public record Outcome(boolean ok, String error, Map<String, Object> data) {
        public Outcome {
            error = error == null ? "" : error;
            data = data == null ? Map.of() : Map.copyOf(data);
        }

        public static Outcome ok(Map<String, Object> data) {
            return new Outcome(true, "", data);
        }

        public static Outcome fail(String error) {
            return new Outcome(false, error, Map.of());
        }
    }

    /** 测试隔离。 */
    public void reset() {
        sessions.clear();
    }

    public boolean active(String companionId) {
        return companionId != null && sessions.containsKey(companionId);
    }

    /**
     * 打开样本的读取会话。
     *
     * @param lines 由调用方从 {@code context.jsonl} 读来的行（本类不做 IO）
     */
    public Outcome open(String companionId, String sampleId, SampleStore store,
                        List<String> lines, long nowMillis) {
        if (companionId == null || companionId.isBlank()) {
            return Outcome.fail("missing companion");
        }
        if (store == null) {
            return Outcome.fail("sample store unavailable (样本层未启用？)");
        }
        Optional<LearningSample> found = store.get(sampleId);
        if (found.isEmpty()) {
            return Outcome.fail("unknown sample_id: " + sampleId);
        }
        LearningSample sample = found.get();
        if (!sample.companionId().equals(companionId)) {
            // 绝不返回"它的样本长什么样"这类信息，连摘要都不给。
            return Outcome.fail("sample " + sampleId + " 不属于当前同伴");
        }

        SampleBlockAssembler.Assembled assembled =
                SampleBlockAssembler.assemble(sample, lines, nowMillis);
        BlockReader reader = BlockReader.open(sample.sampleId(), assembled.blocks(), new ReadLedger(), nowMillis);
        sessions.put(companionId, new Session(reader, assembled.windowNote()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sample_id", sample.sampleId());
        data.put("trigger", sample.triggerKind());
        data.put("state", sample.state().name());
        data.put("occurrences", sample.occurrenceCount());
        data.put("blocks", assembled.blockCount());
        data.put("window", assembled.windowNote());
        data.put("note", "用 list 看目录、expand 读正文、close 结束（结束时会给未展开的块补记跳过）");
        return Outcome.ok(data);
    }

    /** 列目录（只给摘要，不给正文）。 */
    public Outcome list(String companionId, long nowMillis) {
        Session s = sessions.get(companionId);
        if (s == null) {
            return Outcome.fail("no open session; 先 open 一个 sample_id");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EnvBlock b : s.reader().list(nowMillis)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("block_id", b.blockId());
            row.put("kind", b.kind().name());
            row.put("summary", b.summary());
            row.put("coverage", b.coverage().name());
            row.put("freshness", b.freshness().name());
            row.put("confidence", b.confidence().name());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("window", s.windowNote());
        data.put("blocks", rows);
        data.put("count", rows.size());
        return Outcome.ok(data);
    }

    /** 展开正文。 */
    public Outcome expand(String companionId, String blockId, long nowMillis) {
        Session s = sessions.get(companionId);
        if (s == null) {
            return Outcome.fail("no open session; 先 open 一个 sample_id");
        }
        Optional<EnvBlock> b = s.reader().expand(blockId, nowMillis);
        if (b.isEmpty()) {
            return Outcome.fail("unknown block_id: " + blockId);
        }
        EnvBlock block = b.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block_id", block.blockId());
        data.put("kind", block.kind().name());
        data.put("source", block.source());
        data.put("captured_at", block.capturedAt());
        data.put("coverage", block.coverage().name());
        data.put("freshness", block.freshness().name());
        data.put("confidence", block.confidence().name());
        data.put("truncated", block.truncated());
        data.put("body", block.body() == null ? "" : block.body());
        return Outcome.ok(data);
    }

    /** 时间线翻页。 */
    public Outcome step(String companionId, String fromBlockId, boolean forward, long nowMillis) {
        Session s = sessions.get(companionId);
        if (s == null) {
            return Outcome.fail("no open session; 先 open 一个 sample_id");
        }
        Optional<String> target = forward
                ? s.reader().timelineNext(fromBlockId, nowMillis)
                : s.reader().timelinePrevious(fromBlockId, nowMillis);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", fromBlockId == null ? "" : fromBlockId);
        data.put("direction", forward ? "next" : "previous");
        data.put("target", target.orElse(""));
        data.put("eof", target.isEmpty());
        return Outcome.ok(data);
    }

    /** 结束会话：给未展开的块补记跳过，然后移除会话。 */
    public Outcome close(String companionId, long nowMillis) {
        Session s = sessions.remove(companionId);
        if (s == null) {
            return Outcome.fail("no open session");
        }
        s.reader().endSession(nowMillis);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expanded", s.reader().expandedIds().size());
        data.put("skipped", s.reader().ledger().countOf(ReadLedger.Action.BLOCK_SKIP));
        data.put("note", "账本已记：没展开的块算作没读");
        return Outcome.ok(data);
    }
}
