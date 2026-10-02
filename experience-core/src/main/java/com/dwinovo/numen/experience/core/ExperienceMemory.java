package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceQuery;
import com.dwinovo.numen.experience.api.ExperienceRetriever;

import java.nio.file.Path;
import java.util.List;

/**
 * 经验记忆的门面：一个主人/同伴一份，负责“学”、“验”、“查”。
 *
 * <p>{@code at(file, retriever)} 构造：文件用于持久化，retriever 是检索接缝
 * （第一版用 {@link LexicalExperienceRetriever}，将来接向量记忆换它）。
 */
public final class ExperienceMemory {

    private final ExperienceStore store;
    private final ExperienceRetriever retriever;

    private ExperienceMemory(Path file, ExperienceRetriever retriever) {
        this.store = ExperienceStore.at(file);
        this.retriever = retriever;
    }

    public static ExperienceMemory at(Path file, ExperienceRetriever retriever) {
        return new ExperienceMemory(file, retriever);
    }

    /** 学一条经验（同 stable key 合并）。 */
    public ExperienceEntry learn(ExperienceEntry entry) {
        return store.learn(entry);
    }

    /**
     * 记录一次真实结果。返回更新后的经验；id 不存在返回 {@code null}。
     *
     * @see ExperienceStore#recordEvidence
     */
    public ExperienceEntry recordEvidence(String id, boolean success, String note) {
        return store.recordEvidence(id, success, note);
    }

    /** 查经验：按当前任务/失败/异常的自然语言检索相关经验。 */
    public List<ExperienceHit> recall(String text, int limit, ExperienceMaturity minMaturity, List<String> tags) {
        return retriever.retrieve(new ExperienceQuery(text, limit, minMaturity, tags), store.all());
    }

    /** 全量经验（调试/交接用）。 */
    public List<ExperienceEntry> all() {
        return store.all();
    }

    public int size() {
        return store.size();
    }

    /**
     * 本次加载的读数（{@code loadedLine/loadedLegacy/loadedFailed/rekeyed/duplicate}）。
     *
     * <p><b>为什么要透传</b>：{@code store} 是 private，外部拿不到加载统计 ⇒
     * 注入侧与监测台此前只能<b>自己按磁盘形状猜</b>「能读出多少条」
     * （监测台 {@code chain-view.mjs} 就是这么干的），那不是代码的读数。
     * E6 的验收判据 A5 要求「注入侧报的条数 = store 条数」，
     * 面板要核这个数就得从代码里拿，而不是猜。</p>
     */
    public ExperienceStore.LoadStats loadStats() {
        return store.loadStats();
    }

    public ExperienceStats stats() {
        int verified = 0;
        int generalized = 0;
        for (ExperienceEntry e : store.all()) {
            if (e.maturity() == ExperienceMaturity.GENERALIZED) {
                generalized++;
            }
            if (e.maturity() == ExperienceMaturity.VERIFIED) {
                verified++;
            }
        }
        return new ExperienceStats(store.size(), verified, generalized);
    }
}
