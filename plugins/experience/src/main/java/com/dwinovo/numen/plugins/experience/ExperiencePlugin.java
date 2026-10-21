package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.PlanningQuery;
import com.dwinovo.numen.api.RetrievalPolicy;
import com.dwinovo.numen.experience.core.ExperienceDirectory;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
import com.dwinovo.numen.experience.core.PresentationReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NUMEN 宿主适配器：注册经验工具，并把每只同伴的经验库挂进运行时状态。
 *
 * <p>经验按主人/同伴隔离，落盘 {@code config/numen/experience-<uuid>.jsonl}。
 * 第一版检索用纯词法（零外部依赖）；将来接 ChatCore/Numen 已有向量记忆时，
 * 把 {@link #memory} 的 retriever 换成向量实现即可，模型与工具不动。
 */
public final class ExperiencePlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(ExperiencePlugin.class);

    private static final Map<UUID, ExperienceMemory> MEMORIES = new ConcurrentHashMap<>();
    private static volatile Path configDir;

    @Override
    public void setup(NumenApi numen) {
        configDir = numen.configDir();
        numen.registerTool(new ExperienceLearnTool());
        numen.registerTool(new ExperienceRecallTool());
        numen.registerTool(new ExperienceVerifyTool());
        numen.registerTool(new ExperienceRetractTool());
        // 规划知识：让任务链规划器(Stage-A/Stage-B/回退)真正拿到 guide + builtin + 该同伴经验。
        // 规划器与本插件互不可见，只能通过这扇宿主门通信；知识只是参考资料，不改任务结构。
        //
        // ★ G5d：mode=unified 时经验这一类改走统一检索（75 号 §15.5「同一请求只用一套新检索视图」），
        //   旧路经验贡献关掉 → 同一条经验不会被注入两遍。默认 legacy，不配置的人行为零变化。
        numen.contributePlanningKnowledge(query -> {
            try {
                return knowledgeFor(query.companion(), query.objective(), query.stage(),
                        query.knownFailures(),
                        RetrievalPolicy.experienceRetrievalMode() == RetrievalPolicy.Mode.UNIFIED,
                        ExperiencePlugin::memory,
                        rendered -> ExperienceMonitor.publishContext("retrieval_snapshot",
                                rendered.asEvent()));
            } catch (Throwable t) {
                // 知识是可选增强：坏了就这段留空，规划照常（宿主侧还有一层隔离兜底）
                LOG.warn("[expmem] planning knowledge unavailable: {}", t.toString());
                return "";
            }
        });
        // ★ G5e：执行角色视图（进每轮 <runtime_state>，与规划知识分开）。
        //   只在 unified 下启用；EXECUTOR 视图只收 maturity≥VERIFIED、非撤回、非 WARNING 的条目
        //   （现库 4 条全是 OBSERVED ⇒ 通常为空集，这是**合法**结果：执行角色只信已验证的）。
        //   事件每轮都发：entries=0 也是证据（E3 要能看到 role=EXECUTOR 的快照）。
        numen.contributeExecutorKnowledge(companion -> {
            try {
                if (RetrievalPolicy.experienceRetrievalMode() != RetrievalPolicy.Mode.UNIFIED) {
                    return "";
                }
                // 用 memory(companion) 而不是 MEMORIES.get：这是**每轮都会走的读路径**，
                // 若等工具/规划先建库，执行视图会在会话前几分钟静默缺席（2026-10-21 实机踩到）。
                ExperienceMemory m = memory(companion);
                ExperienceStoreRetrievalBridge.Rendered rendered =
                        ExperienceStoreRetrievalBridge.render(m, "", 6, 800,
                                com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);
                ExperienceMonitor.publishContext("retrieval_snapshot", rendered.asEvent());
                return rendered.text();
            } catch (Throwable t) {
                // 执行知识是可选增强：坏了只丢这一段，本轮请求照常（宿主还有一层隔离兜底）
                LOG.warn("[expmem] executor knowledge unavailable: {}", t.toString());
                return "";
            }
        });
        numen.contributeState(companion -> {
            ExperienceMemory m = MEMORIES.get(companion);
            if (m == null) {
                return "";
            }
            try {
                // ★ E6：不再是「只有三个计数」，而是 L0 目录层（59 号 §8.1）。
                // contributeState 只拿得到同伴 UUID —— 拿不到当前任务，所以这里排的是
                // 「可信度兜底序」（经验库 recall("", …) 的 fallbackScore 路径：
                // 成熟度 × 优先级 × 近期性），**不是**按当前任务筛的；
                // 块里的 <note> 明说了这一点，别让 AI 把顺序读成相关性。
                // ★ E7：reportable=false —— 目录是每轮自动印的，同伴很可能压根没看；
                //   让它进待回报清单会产出满屏「本任务没有结论」的噪音。
                //   它仍然计入「被呈现过几次」那个长期读数。
                ExperienceDirectory.Block block = ExperienceDirectory.render(
                        m.recall("", ExperienceDirectory.DEFAULT_MAX_ROWS, null, java.util.List.of(),
                                PresentationReceipt.SURFACE_DIRECTORY, false),
                        m.size(), m.stats().verified(), m.stats().generalized(),
                        m.loadStats(), m.stats(),
                        ExperienceDirectory.DEFAULT_MAX_ROWS,
                        ExperienceDirectory.DEFAULT_MAX_CHARS);
                return block.text();
            } catch (Throwable t) {
                // 目录是可选增强：坏了就这段留空，每轮上下文照常
                LOG.warn("[expmem] experience directory unavailable: {}", t.toString());
                return "";
            }
        });
        LOG.info("[expmem] experience memory plugin ready");
    }

    /**
     * 规划知识分流（G5d 判据 S2/S3 的唯一分叉点）。
     *
     * <p>规则与可测实现都在 {@link ExperiencePlanningRouter}（它不实现宿主接口，
     * 离线测试不需要把整条宿主 API 拉进类路径）；这里只把生产依赖接上。
     */
    static String knowledgeFor(UUID companion, String objective, String stage,
                               java.util.List<String> knownFailures, boolean unified,
                               java.util.function.Function<UUID, ExperienceMemory> memories,
                               java.util.function.Consumer<ExperienceStoreRetrievalBridge.Rendered> observer) {
        return ExperiencePlanningRouter.knowledgeFor(companion, objective, stage, knownFailures,
                unified, memories, observer);
    }

    /** 取（或惰性创建）某同伴的经验记忆。 */
    public static ExperienceMemory memory(UUID companionId) {
        Path dir = configDir;
        if (dir == null) {
            throw new IllegalStateException("experience plugin not set up");
        }
        return MEMORIES.computeIfAbsent(companionId,
                uuid -> ExperienceMemory.at(
                        dir.resolve("experience-" + uuid + ".jsonl"),
                        new LexicalExperienceRetriever()));
    }

    /**
     * {@code config/numen/} 目录；未 setup 时为 null。
     *
     * <p><b>E7：{@code ExperienceVerifyTool} 只读列待回报清单时要读
     * {@code monitor/events.jsonl}（任务收尾结果）来给每条呈现配上判据，
     * 那是纯只读旁路，不经过 {@link #memory}。</b>返 null 而不是抛异常：
     * 读不到结果源时如实报「读不到」，不该让整个工具挂掉。</p>
     */
    static Path configDir() {
        return configDir;
    }
}
