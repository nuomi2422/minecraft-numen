package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.core.ExperienceMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * G5d 规划知识的<b>分流器</b>：决定这一轮经验贡献走旧路还是统一检索。
 *
 * <p>刻意从 {@link ExperiencePlugin} 里抽出来 —— 那个类实现了宿主
 * {@code NumenPlugin}，在离线测试里光加载它就要把整条宿主 API 拉进类路径；
 * 分流规则本身只依赖 JDK 类型 + 经验库 + 桥，抽出来就能用假 memory 单测。
 *
 * <p>两条路互斥（判据 S2）：
 * <ul>
 *   <li>{@code unified=false}（默认）—— 旧路 {@code ExperienceKnowledgeSource.recall}；
 *       {@code memories} <b>一次都不会被调用</b>，新检索零调用；</li>
 *   <li>{@code unified=true} —— {@link ExperienceStoreRetrievalBridge#render}，
 *       旧路经验部分不执行（不会双注入）。</li>
 * </ul>
 *
 * <p>任何一路炸了都只让这段知识变空，规划本身照常（知识是可选增强）。
 */
final class ExperiencePlanningRouter {

    private static final Logger LOG = LoggerFactory.getLogger(ExperiencePlanningRouter.class);

    private ExperiencePlanningRouter() {}

    /**
     * @param companion      同伴 UUID（可为 null → 没有经验库）
     * @param memories       同伴 → 经验库；生产是 {@link ExperiencePlugin#memory(UUID)}，测试注入假货
     * @param observer       只在 unified 路径被调用（生产发 {@code context.retrieval_snapshot}）
     */
    static String knowledgeFor(UUID companion, String objective, String stage,
                               List<String> knownFailures, boolean unified,
                               Function<UUID, ExperienceMemory> memories,
                               Consumer<ExperienceStoreRetrievalBridge.Rendered> observer) {
        if (unified) {
            ExperienceMemory memory = null;
            try {
                if (companion != null && memories != null) {
                    memory = memories.apply(companion);
                }
            } catch (Throwable t) {
                LOG.warn("[expmem] unified retrieval memory unavailable: {}", t.toString());
            }
            ExperienceStoreRetrievalBridge.Rendered rendered = ExperienceStoreRetrievalBridge.render(
                    memory, objective,
                    PlanningKnowledge.DEFAULT_MAX_ITEMS, PlanningKnowledge.DEFAULT_MAX_CHARS);
            if (observer != null) {
                observer.accept(rendered);
            }
            return rendered.text();
        }
        try {
            PlanningKnowledge.Request req = PlanningKnowledge.Request.of(
                    objective, stage, knownFailures == null ? List.of() : knownFailures);
            return ExperienceKnowledgeSource.recall(companion, req).text();
        } catch (Throwable t) {
            LOG.warn("[expmem] planning knowledge unavailable: {}", t.toString());
            return "";
        }
    }
}
