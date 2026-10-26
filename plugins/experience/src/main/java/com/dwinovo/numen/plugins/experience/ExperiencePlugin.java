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
        // LC3（78 号 §7「日常样本能复算」）：五态样本全字段镜像进 expmem.jsonl。
        // 事件是重算的唯一原料；出口失败不影响记账（TrialRuntime 内部已吞）。
        TrialRuntime.eventSink((type, data) -> {
            try {
                ExperienceMonitor.publish(type, data);
            } catch (Throwable ignored) {
                // 观测失败不拖垮主流程（ExperienceMonitor 同款纪律）
            }
        });
        numen.registerTool(new ExperienceLearnTool());
        numen.registerTool(new ExperienceRecallTool());
        numen.registerTool(new ExperienceVerifyTool());
        numen.registerTool(new ExperienceRetractTool());
        numen.registerTool(new ExperienceExpandTool());
        numen.registerTool(new ExperiencePublishTool());
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
        // G6c：任务收尾 → 试用归因（只报事实，不判好坏）
        numen.on(com.dwinovo.numen.api.CompanionEvent.TASK_FINISHED, f -> {
            try {
                LOG.info("[expmem] TASK_FINISHED received id={} status={} success={}", f.taskId(), f.status(), f.success());
                // 2026-10-23 诊断（#271）：把任务终态写进 monitor 事件，让 MCP 侧也能看到
                // 「哪个任务、什么终态、成功与否」。只报事实，不做判断。
                java.util.Map<String, Object> fin = new java.util.LinkedHashMap<>();
                fin.put("task_id", f.taskId());
                fin.put("task_name", f.taskName());
                fin.put("status", f.status());
                fin.put("success", f.success());
                ExperienceMonitor.publish("task_finished_detail", fin);
                // G6a-ledger：终态字符串 → 五态（取消/中断不再并进失败）；拿不到状态才退回布尔口径
                com.dwinovo.numen.experience.api.UsageState st =
                        com.dwinovo.numen.experience.api.UsageState.parse(f.status());
                if (st == com.dwinovo.numen.experience.api.UsageState.UNKNOWN && f.success()) {
                    st = com.dwinovo.numen.experience.api.UsageState.SUCCESS;
                }
                java.util.List<com.dwinovo.numen.experience.api.TrialUse> uses =
                        TrialRuntime.onTaskFinished(f.companionId(), st, f.taskId(), System.currentTimeMillis());
                for (com.dwinovo.numen.experience.api.TrialUse u : uses) {
                    LOG.info("[expmem] trial use {} -> {} (stats={}, usage={})", u.entryRef(), u.outcome(),
                            TrialRuntime.stats(u.entryRef()), TrialRuntime.usageStats(u.entryRef()));
                    // 2026-10-23：归因只进日志、从不进 monitor —— 8776 的「试用归因」因此永远读到 0，
                    // 被误当成"链路断了"。补一条 trial_used，让那个数字变成真的归因数。
                    try {
                        java.util.Map<String, Object> td = new java.util.LinkedHashMap<>();
                        td.put("ref", u.entryRef());
                        td.put("outcome", String.valueOf(u.outcome()));
                        td.put("task_id", f.taskId());
                        td.put("stats", String.valueOf(TrialRuntime.stats(u.entryRef())));
                        ExperienceMonitor.publish("trial_used", td);
                    } catch (Throwable ignore) {
                        // 观测失败不影响归因与主流程
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[expmem] trial attribution failed: {}", t.toString());
            }
        });

        numen.contributeExecutorKnowledge((companion, objective) -> {
            try {
                if (RetrievalPolicy.experienceRetrievalMode() != RetrievalPolicy.Mode.UNIFIED) {
                    return "";
                }
                // 用 memory(companion) 而不是 MEMORIES.get：这是**每轮都会走的读路径**，
                // 若等工具/规划先建库，执行视图会在会话前几分钟静默缺席（2026-10-21 实机踩到）。
                ExperienceMemory m = memory(companion);
                // G8 诊断：源快照里有几条（provider 之前的那一层）
                LOG.info("[expmem] snapshot size={}", ExperienceStoreRetrievalBridge.snapshot(m).size());
                // ★ 2026-10-26（V-20261026-04 实测）：原来 1100 字符的硬上限只够装 1 条
                //   （objective 先进必知区占预算，一条正文 ~500 就顶满）——新条目即使 TRIAL
                //   也永远被 max_chars 截掉，等于「学了一条，永远轮不到它上场」。
                //   提到 2200：objective(~300) + 2 条正文(~500×2) 可同时在场，仍是有界预算。
                ExperienceStoreRetrievalBridge.Rendered rendered =
                        ExperienceStoreRetrievalBridge.render(m, objective, 6, 2200,
                                com.dwinovo.numen.agent.retrieval.RetrievalRole.EXECUTOR);
                // ★ 2026-10-25（我实测发现）：上面这条是**执行视图**（role=EXECUTOR，每轮都发），
                //   它用的是裸 rendered —— 指纹只在规划路径（ExperiencePlanningRouter）补过，
                //   于是 context.jsonl 里role=executor 的 retrieval_snapshot **一条指纹都没有**
                //   （实测：重启后 6 条新事件 task_fingerprint 全缺）。A6 判据在 EXECUTOR 视图上仍然断着。
                //   这里补同一套算法：objective 为主，没有 stage/knownFailures 就不塞（不编造）。
                java.util.List<String> fpParts = new java.util.ArrayList<>();
                if (objective != null && !objective.isBlank()) fpParts.add(objective);
                ExperienceMonitor.publishContext("retrieval_snapshot",
                        rendered.withFingerprints(
                                com.dwinovo.numen.experience.core.TaskFingerprint.of(fpParts),
                                com.dwinovo.numen.experience.core.TaskFingerprint.sceneFingerprints(fpParts)).asEvent());
                // G6c：这一轮端出来的试用条目，记下来等任务收尾归因
                String txt0 = rendered.text();
                LOG.info("[expmem] executor head={}", txt0.substring(0, Math.min(360, txt0.length())));
                // G8 诊断（2026-10-22）：执行视图按 objective 做词法匹配，不记 objective 就分不清
                // 「没检索到」还是「检索到但被截断」。只记前 120 字。
                LOG.info("[expmem] executor objective={}", objective == null ? "" : objective.substring(0, Math.min(120, objective.length())));
                // G8 诊断（2026-10-22）：把执行视图里实际出现的 ref 打出来，一次分清是 provider 丢的还是预算挤的。
                java.util.regex.Matcher mr = java.util.regex.Pattern.compile("ref=\"([^\"]+)\"").matcher(txt0);
                java.util.List<String> shown = new java.util.ArrayList<>();
                while (mr.find()) shown.add(mr.group(1));
                LOG.info("[expmem] executor refs={}", shown);
                java.util.List<String> refs0 = TrialRuntime.trialRefsIn(rendered.text());
                LOG.info("[expmem] trial refs={} len={} hasMarker={}", refs0,
                        rendered.text().length(), rendered.text().contains("（试用中）"));
                // ★ LC1（78 号 §2.1）：渲染不等于送达 —— 本呈现仍按原窗口归因记账（弱证据），
                //   但版本指纹如实带上；真正的「收到」回执在 ExposureLedger（工具回复通道）。
                java.util.List<TrialRuntime.VersionedPresentation> vps = new java.util.ArrayList<>();
                for (String ref : refs0) {
                    String version = "";
                    try {
                        String id = ref.startsWith("exp:") ? ref.substring(4) : ref;
                        var entry = ExperiencePlugin.memory(companion).findById(id);
                        if (entry != null) {
                            version = entry.contentFingerprint();
                            // ★ LC1（78 号 §3 exposureKind 三分类）：目录/渲染层曝光同帧记
                            //   ExposureLedger——只审计（「在目录里出现过」是可对账事实），
                            //   不过晋级门（countsAsReceipt 不认 DIRECTORY：渲染段可能被
                            //   预算裁掉，「呈现过」推不出「收到过」）。
                            com.dwinovo.numen.experience.api.ExposureLedger.record(companion,
                                    new com.dwinovo.numen.experience.api.ExposureLedger.Delivery(
                                            ref, version,
                                            com.dwinovo.numen.experience.api.ExposureLedger.KIND_DIRECTORY,
                                            "", false, System.currentTimeMillis()));
                        }
                    } catch (RuntimeException ignore) {
                        // 解析不到条目：版本留空串 = LEGACY，归因照旧，不猜
                    }
                    vps.add(new TrialRuntime.VersionedPresentation(ref, version, System.currentTimeMillis()));
                }
                TrialRuntime.noteVersionedPresented(companion, vps, System.currentTimeMillis());
                return rendered.text();
            } catch (Throwable t) {
                // 执行知识是可选增强：坏了只丢这一段，本轮请求照常（宿主还有一层隔离兜底）
                LOG.warn("[expmem] executor knowledge unavailable: {}", t.toString());
                return "";
            }
        });
        numen.contributeState(companion -> {
            // ★ A1（契约 §2/§4①，2026-10-26 实测）：unified 下经验已由
            //   contributeExecutorKnowledge 的 <retrieved_knowledge role="executor"> 承担；
            //   这里再印一遍旧 <experience> 目录 = 双通道（实测 82/83 条请求同一 ref 两处并存，
            //   「同一条经验不会被注入两遍」的承诺没兑现）。legacy 行为逐字不变。
            if (RetrievalPolicy.experienceRetrievalMode() == RetrievalPolicy.Mode.UNIFIED) {
                return "";
            }
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
