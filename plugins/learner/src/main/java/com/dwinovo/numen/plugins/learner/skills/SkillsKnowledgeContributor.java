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
    private final java.util.function.Function<UUID, OvernightSkillInput.WorldHint> overnightWorld;

    public SkillsKnowledgeContributor(Path configDir, String view, int maxChars) {
        this(configDir, view, maxChars, companion -> OvernightSkillInput.WorldHint.UNKNOWN);
    }

    public SkillsKnowledgeContributor(Path configDir, String view, int maxChars,
            java.util.function.Supplier<com.dwinovo.numen.api.observation.WorldObservationService> observations) {
        this(configDir, view, maxChars, companion -> {
            try { return OvernightSkillInput.read(observations.get(), companion); }
            catch (RuntimeException | LinkageError unavailable) { return OvernightSkillInput.WorldHint.UNKNOWN; }
        });
    }

    SkillsKnowledgeContributor(Path configDir, String view, int maxChars,
            java.util.function.Function<UUID, OvernightSkillInput.WorldHint> overnightWorld) {
        this.configDir = configDir;
        this.view = view;
        this.maxChars = maxChars;
        this.overnightWorld = overnightWorld;
    }

    @Override
    public NumenPlugins.KnowledgeFragment contribute(UUID companion, String objective) {
        // Preserve the existing body-delivery path for planner and legacy consumers.
        return contribute(companion, objective, false);
    }

    /** Discovery metadata only: seeing this catalog is not delivery of a skill body. */
    public NumenPlugins.KnowledgeFragment contributeCatalog(UUID companion, String objective) {
        return contribute(companion, objective, true);
    }

    private static final String CATALOG_HEAD = "<retrieved_knowledge role=\"executor\" note=\"Catalog only; author conditions are unverified clues. Read one body using load_skill with only ref set to the exact entry ref.\">\n";
    private static final String PLANNER_HEAD = CATALOG_HEAD.replace("role=\"executor\"", "role=\"planner\"");
    private static final String CATALOG_TAIL = "</retrieved_knowledge>";

    private NumenPlugins.KnowledgeFragment contribute(UUID companion, String objective, boolean catalog) {
        List<NumenPlugins.DeliveredRef> refs = new ArrayList<>();
        List<CatalogEntryRenderer.Candidate> candidates = new ArrayList<>();
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
        var resolver = new LearnedSkillBodyResolverImpl(configDir);
        var consumerView = "PLANNER".equalsIgnoreCase(view)
                ? com.dwinovo.numen.api.LearnedSkillBodyResolver.View.PLANNER
                : com.dwinovo.numen.api.LearnedSkillBodyResolver.View.EXECUTOR;
        List<SkillEntry> entries = new ArrayList<>(registry.retrievable());
        boolean hasOvernight = entries.stream().anyMatch(e -> audienceMatches(e.audience()) && "overnight".equals(SceneSkillCatalog.scene(e)));
        var worldHint = hasOvernight ? overnightWorld.apply(companion) : OvernightSkillInput.WorldHint.UNKNOWN;
        boolean worldCue = OvernightSkillInput.worldCue(worldHint);
        boolean sceneCue = entries.stream().anyMatch(e -> isScene(e) && (sceneCue(objective, e)
                || (worldCue && "overnight".equals(SceneSkillCatalog.scene(e)))));
        if (sceneCue && catalog) {
            // Preserve planner body order until its retrieval contract is proven.
            entries.sort(java.util.Comparator.comparing(e -> !(sceneCue(objective, e)
                    || (worldCue && "overnight".equals(SceneSkillCatalog.scene(e))))));
        }
        for (SkillEntry e : entries) {
            if (!audienceMatches(e.audience())) continue;
            String scene = SceneSkillCatalog.kind(e);
            if ("invalid".equals(scene)) {
                gaps.merge("invalid-scene-tag", 1, Integer::sum);
                continue;
            }
            // No keyword hit is unknown relevance, not proof of inapplicability.
            // Keep unmatched entries behind matching candidates within the existing budget.
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
            if ("package".equals(scene) && SceneSkillCatalog.missingRequired(e, resolver, companion, consumerView)) {
                gaps.merge("missing-required-component", 1, Integer::sum);
                continue;
            }
            if (catalog) {
                // Raw ACX purpose is not a verified authority projection; do not infer from its text.
                if (e.kind() == com.dwinovo.numen.plugins.learner.contract.ArtifactKind.ACX_REQUEST) {
                    gaps.merge("not-verified", 1, Integer::sum);
                    continue;
                }
                String availability = scene.isEmpty() ? "" :
                        ("degraded".equals(SceneSkillCatalog.availability(e, resolver, companion, consumerView))
                                ? "degraded" : "available");
                // Nonempty author text is not proof that conditions are verified.
                boolean conditionsKnown = false;
                // Existing Java body hash is not a provenance signature: keep source UNKNOWN.
                String recall = scene.isEmpty() ? "" :
                        (worldCue && "overnight".equals(SceneSkillCatalog.scene(e)) ? "world_hint=" + worldHint + " " : "")
                        + "candidate_only applicability=UNKNOWN"
                        + ("overnight".equals(SceneSkillCatalog.scene(e)) ? " bed=UNKNOWN" : "");
                candidates.add(new CatalogEntryRenderer.Candidate(ref + "@" + version, e.name(), e.purpose(),
                        e.audience(), e.status().name(), true, conditionsKnown, false, availability, recall));
                continue;
            }
            String block = catalog ? catalogEntry(e, ref + "@" + version, scene, resolver, companion, consumerView)
                    : "<entry ref=\"" + ref + "\">@" + version + " [" + e.name() + "] " + e.purpose() + "\n" + body + "\n</entry>\n";
            if (catalog && !scene.isEmpty()) {
                String cue = worldCue && "overnight".equals(SceneSkillCatalog.scene(e))
                        ? "world_hint=" + worldHint + " " : "";
                String note = "candidate_only applicability=UNKNOWN"
                        + ("overnight".equals(SceneSkillCatalog.scene(e)) ? " bed=UNKNOWN" : "");
                block = block.replace("</entry>\n", "<recall_note>" + cue + note
                        + "</recall_note>\n</entry>\n");
            }
            int envelopeChars = catalog ? CATALOG_HEAD.length() + CATALOG_TAIL.length() : 0;
            if (maxChars > 0 && text.length() + block.length() + envelopeChars > maxChars) {
                if (!catalog) { truncated = true; break; } // preserve legacy planner budget behavior
                gaps.merge("budget-rejected", 1, Integer::sum);
                continue; // skip whole entry; a later smaller candidate may still fit
            }
            text.append(block);
            if (!catalog) refs.add(new NumenPlugins.DeliveredRef(ref, version, false));
        }
        if (catalog) {
            var renderedCatalog = CatalogEntryRenderer.render(candidates, view, maxChars > 0 ? maxChars : 1160);
            for (var decision : renderedCatalog.decisions()) {
                if (!"included".equals(decision.reason())) gaps.merge(decision.reason(), 1, Integer::sum);
            }
            if (renderedCatalog.fragment().isEmpty() && gaps.isEmpty()) return null;
            return new NumenPlugins.KnowledgeFragment(renderedCatalog.fragment(), List.of(), gaps);
        }
        if (text.isEmpty() && gaps.isEmpty()) return null;   // 完全无结果
        String head = "PLANNER".equalsIgnoreCase(view) ? PLANNER_HEAD : CATALOG_HEAD;
        String rendered = catalog && !text.isEmpty() ? head + text + CATALOG_TAIL : text.toString();
        return new NumenPlugins.KnowledgeFragment(rendered, refs, gaps);
    }


    private static boolean isScene(SkillEntry entry) {
        return !SceneSkillCatalog.kind(entry).isEmpty();
    }

    private static boolean sceneCue(String objective, SkillEntry entry) {
        String scene = SceneSkillCatalog.scene(entry);
        return objective != null && !scene.isEmpty() && java.util.regex.Pattern.compile(
                "(?i)(?<![a-z])" + java.util.regex.Pattern.quote(scene) + "(?![a-z])|"
                        + "(?:" + SceneSkillCatalog.goalAlias(scene) + ")")
                .matcher(objective).find();
    }

    private static String catalogEntry(SkillEntry entry, String exactRef, String scene, com.dwinovo.numen.api.LearnedSkillBodyResolver resolver, UUID companion, com.dwinovo.numen.api.LearnedSkillBodyResolver.View consumerView) {
        return "<entry ref=\"" + xml(exactRef) + "\" kind=\"" + xml(entry.kind().name())
                + "\" status=\"" + xml(entry.status().name())
                + (scene.isEmpty() ? "" : "\" scene=\"" + xml(scene)
                    + "\" availability=\"" + SceneSkillCatalog.availability(entry, resolver, companion, consumerView)) + "\">\n"
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
