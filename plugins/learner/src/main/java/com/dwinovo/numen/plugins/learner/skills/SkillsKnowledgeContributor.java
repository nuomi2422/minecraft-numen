package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.KnowledgeGapException;
import com.dwinovo.numen.api.NumenPlugins;
import java.io.IOException;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 2026-11-09（046-A）：把真实可检索 Skill 适配成统一检索的 KnowledgeFragment。
 *
 * <p>不另立选择器：条目来自 {@link SkillRegistry#retrievable()}；正文来自
 * {@link SkillBodyStore#read(Path, String)}（读不到就不发并计数，不编造）；引用格式
 * {@code skill:<skillId>} + {@code v<n>}。audience 是自由文本（草稿自带），
 * 未知取值一律不匹配、记为 gap，不猜测 BOTH 之类语义。
 */
public final class SkillsKnowledgeContributor implements NumenPlugins.KnowledgeContributor {

    private static final Logger LOG = Logger.getLogger("numen.skills.retrieval");

    private final Path configDir;
    private final String view;
    private final int maxChars;

    public SkillsKnowledgeContributor(Path configDir, String view, int maxChars) {
        this.configDir = configDir;
        this.view = view;
        this.maxChars = maxChars;   // <=0 = 不自截断，交给消费方 ContextBudget（避免双重截断）
    }

    @Override
    public NumenPlugins.KnowledgeFragment contribute(UUID companion, String objective) {
        return contribute(companion, objective, false);
    }

    /** Discovery metadata only: seeing this catalog is not delivery of a skill body. */
    public NumenPlugins.KnowledgeFragment contributeCatalog(UUID companion, String objective) {
        return contribute(companion, objective, true);
    }

    private static final String CATALOG_HEAD = "<retrieved_knowledge role=\"executor\" note=\"Catalog only; author conditions are unverified clues. Read one body using load_skill with only ref set to the exact entry ref.\">\n";
    private static final String CATALOG_TAIL = "</retrieved_knowledge>";

    private NumenPlugins.KnowledgeFragment contribute(UUID companion, String objective, boolean catalog) {
        List<NumenPlugins.DeliveredRef> refs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        java.util.Map<String, Integer> gaps = new java.util.LinkedHashMap<>();
        SkillRegistry registry;
        try {
            registry = SkillRegistry.open(configDir.resolve("skills.jsonl"));
        } catch (IOException | RuntimeException e) {
            // 读失败 ≠ 没有技能：留一条脱敏可观测痕迹（只有异常类型，不含正文）
            LOG.warning("[skills-retrieval] registry unavailable: " + e.getClass().getSimpleName());
            throw new KnowledgeGapException(KnowledgeGapException.Reason.READ_FAILED, "read_failed");
        }
        int index = 0;
        for (SkillEntry e : registry.retrievable()) {
            index++;
            if (!audienceMatches(e.audience())) continue;
            String ref = "skill:" + e.skillId();
            String body = SkillBodyStore.read(configDir, e.bodyRef());
            if (body == null || body.isBlank()) {
                gaps.merge("body-missing", 1, Integer::sum);
                LOG.warning("[skills-retrieval] body missing ref=" + ref + " bodyRef=" + e.bodyRef());
                continue;
            }
            if (!com.dwinovo.numen.plugins.learner.core.LearnedLocationBinding.problem(body).isEmpty()) {
                gaps.merge("location-binding-required", 1, Integer::sum);
                continue; // original body/version remains on disk; no ref receipt for suppressed text
            }
            // 条目边界是统一检索按条记账的契约（NumenPlugins.refFullyIncluded 依赖 ref=…</entry>）
            // 2026-11-12: model-facing text must expose the registry vN required by adopt_skill;
            // DeliveredRef metadata is not model-visible, and a body JSON version is not this version.
            String version = "v" + e.version();
            if (catalog) {
                try {
                    com.dwinovo.numen.api.LearnedSkillBodyResolver.Reference.parse(ref + "@" + version);
                } catch (IllegalArgumentException invalidReference) {
                    gaps.merge("invalid-reference", 1, Integer::sum);
                    continue;
                }
            }
            String block = catalog ? catalogEntry(e, ref + "@" + version)
                    : "<entry ref=\"" + ref + "\">@" + version + " [" + e.name() + "] " + e.purpose() + "\n" + body + "\n</entry>\n";
            int envelopeChars = catalog ? CATALOG_HEAD.length() + CATALOG_TAIL.length() : 0;
            if (maxChars > 0 && text.length() + block.length() + envelopeChars > maxChars) { truncated = true; break; }
            text.append(block);
            if (!catalog) refs.add(new NumenPlugins.DeliveredRef(ref, version, false));
        }
        if (text.isEmpty() && gaps.isEmpty()) return null;   // 完全无结果
        String rendered = catalog && !text.isEmpty() ? CATALOG_HEAD + text + CATALOG_TAIL : text.toString();
        return new NumenPlugins.KnowledgeFragment(rendered, refs, gaps);
    }


    private static String catalogEntry(SkillEntry entry, String exactRef) {
        return "<entry ref=\"" + xml(exactRef) + "\" kind=\"" + xml(entry.kind().name())
                + "\" status=\"" + xml(entry.status().name()) + "\">\n"
                + "<name>" + xml(entry.name()) + "</name>\n"
                + "<purpose>" + xml(entry.purpose()) + "</purpose>\n"
                + catalogConditions("trigger", entry.triggerConditions())
                + catalogConditions("exclude", entry.excludeConditions())
                + catalogConditions("preconditions", entry.preconditions())
                + "<risk>" + xml(entry.risk()) + "</risk>\n</entry>\n";
    }

    private static String catalogConditions(String label, List<String> conditions) {
        StringBuilder out = new StringBuilder("<" + label + ">");
        for (String condition : conditions) out.append("<item>").append(xml(condition)).append("</item>");
        return out.append("</").append(label).append(">\n").toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** audience 为空 = 两视图都可见；否则逗号 token 精确匹配；未知取值不匹配。 */
    boolean audienceMatches(String audience) {
        if (audience == null || audience.isBlank()) return true;
        for (String part : audience.split(",")) {
            if (part.trim().equalsIgnoreCase(view)) return true;
        }
        return false;
    }
}
