package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.Submission;
import com.dwinovo.numen.plugins.learner.sample.SampleFingerprint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把"过了契约闸门的产物"登记进 Skill 目录（159 G 的入口）。
 *
 * <h2>为什么只登记<b>通过校验</b>的产物</h2>
 * 158 §7 要求「质量门通过后可自动登记 Skill 目录」。如果连没过的也登记，
 * 目录就会变成"草稿堆"，而检索看到的是目录 —— 等于让没过门的东西进了执行视图。
 * 所以调用方必须传 {@code accepted=true} 的提交；本类只做登记与落盘。
 *
 * <h2>为什么 skillId 由指纹算</h2>
 * 同一件产物在不同复议里被再次提交时，必须落到<b>同一条</b> Skill 上（走 REUSE/REVISE），
 * 而不是每次新建。id 由 `kind + 指纹` 派生，天然满足"同一件事同一个 id"。
 *
 * <h2>代价（必须知道）</h2>
 * 登记是"旁路写"：写盘失败不能让复盘失败。所以异常一律吞掉并返回 0，
 * 由调用方按 0 处理 —— 但目录会因此少一条，这是可接受的降级（复盘结果本身不丢）。
 *
 * <p>纯 JVM（只有文件 IO），可用临时目录离线测试。
 */
public final class SkillRegistrar {

    /** 每个 configDir 一份目录实例（避免每次复盘都重建索引）。 */
    private static final Map<Path, SkillRegistry> REGISTRIES = new ConcurrentHashMap<>();

    private SkillRegistrar() {
    }

    /** 测试隔离。 */
    public static void resetForTest() {
        REGISTRIES.clear();
    }

    /**
     * 取该 configDir 的**唯一目录实例**（2026-11-06 修“丢失更新”）。
     *
     * <p>事故：管理侧 retire/promote 各自 open 一份新目录改完写盘，而复盘侧用的是本缓存的
     * 旧实例；复盘一登记就把整份内存图写回，管理侧的撤回/升格被**静默覆盖**。实测 10:32
     * 的 6 条 retire + 3 条 promote 全部没有落盘，事件却都发了。
     *
     * <p>修法：所有写入口共用同一个实例，一个体内只有一个写者，不再互踩。
     */
    public static SkillRegistry registryFor(Path configDir) {
        return REGISTRIES.computeIfAbsent(configDir, d -> openQuietly(d.resolve("skills.jsonl")));
    }

    /** 登记结果。 */
    public record Report(int registered, int reused, int revised, int conflicted, String error) {
        public Report {
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return error.isEmpty();
        }

        public int total() {
            return registered + reused + revised + conflicted;
        }
    }

    /**
     * 登记一次提交里的全部产物。
     *
     * @param accepted 这次提交是否已通过契约校验；<b>false 时直接不登记</b>
     */
    public static Report register(Path configDir, Submission.Draft draft, boolean accepted) {
        return register(configDir, draft, accepted, java.util.Set.of());
    }

    /**
     * 带权限裁决的登记：被 `PermissionMatrix` 判成 `TRIAL_ONLY` 的产物，进目录时状态是
     * {@link SkillStatus#TRIAL} 而不是 `DRAFT`。
     *
     * <p>为什么必须区分：`trialOnly` 如果只算不落，等于"矩阵判了但下游不知道"——
     * 158 §7 要的是<b>可见范围不同</b>，而不是日志里多一行。落到目录状态上，
     * 检索侧（`retrievable()`）才有可能真的区别对待。
     *
     * @param trialIndexes 第几个产物只准进试用态（对应 `artifact#i:trial_only:…` 里的 i）
     */
    public static Report register(Path configDir, Submission.Draft draft, boolean accepted,
                                  java.util.Set<Integer> trialIndexes) {
        if (configDir == null) {
            return new Report(0, 0, 0, 0, "configDir 为空");
        }
        if (draft == null || draft.artifacts().isEmpty()) {
            return new Report(0, 0, 0, 0, "");
        }
        if (!accepted) {
            // 没过门的产物不进目录：目录是检索看的世界。
            return new Report(0, 0, 0, 0, "");
        }
        try {
            SkillRegistry registry = registryFor(configDir);
            int reg = 0, reuse = 0, revise = 0, conflict = 0;
            for (int i = 0; i < draft.artifacts().size(); i++) {
                ArtifactDraft a = draft.artifacts().get(i);
                String skillId = skillIdOf(a);
                // bodyRef 必须包含**内容**指纹：否则正文改了 bodyRef 不变，
                // SkillRegistry 会判成 REUSE（同一份内容），修订永远发生不了。
                String bodyRef = "draft:" + skillId + ":" + a.contentHash();
                boolean trial = trialIndexes != null && trialIndexes.contains(i);
                SkillRegistry.Decision d = registry.register(a, skillId, bodyRef, trial);
                switch (d.outcome()) {
                    case REGISTERED -> reg++;
                    case REUSE -> reuse++;
                    case REVISE -> revise++;
                    case CONFLICT -> conflict++;
                }
            }
            return new Report(reg, reuse, revise, conflict, "");
        } catch (RuntimeException e) {
            // 旁路写失败不许炸复盘：目录少一条比复盘失败好。
            return new Report(0, 0, 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 稳定 id：同 kind 同指纹 → 同一条 Skill。 */
    public static String skillIdOf(ArtifactDraft a) {
        return "sk-" + SampleFingerprint.shortHash(a.kind().name() + "|" + a.fingerprint());
    }

    private static SkillRegistry openQuietly(Path file) {
        try {
            return SkillRegistry.open(file);
        } catch (IOException e) {
            // 目录读不出来就从空目录开始（后面写盘会重建），但要留痕。
            return new SkillRegistry();
        }
    }
}
