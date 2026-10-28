package com.dwinovo.numen.api;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionEvents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 插件的登记处:{@code NumenPlugins.register(numen -> …)}。
 *
 * <h2>时机不必你操心</h2>
 * 插件在自己模组的构造期登记就行。引擎内部该就绪的东西各有各的时机(工具注册表在
 * 类加载时就有,技能与头像要等客户端起来),这里替你等——先登记的先跑,能跑的立刻跑,
 * 跑不了的等它就绪。
 *
 * <h2>专用服务器上会安静地少几样</h2>
 * 技能和头像只活在玩家的客户端上。它们由客户端启动时{@linkplain #bindClient 接上来};
 * 专用服务器上没人接,于是 {@code bundleSkills} / {@code registerPortrait} 就是空操作。
 * <b>"有没有接上"本身就是判据</b>,不必再去问一遍"我现在是不是客户端"——那种问法
 * 每个加载器一个写法,写在插件里就是每个插件一个写法。
 */
public final class NumenPlugins {

    /** 客户端接上来的那几样;专用服务器上一直是 null,于是相关调用自然成空操作。 */
    private static volatile Consumer<Path> skills;
    private static volatile BiFunction<UUID, String, Delivery> enqueue;

    /**
     * G4c S107b：系统事件通道（「目标还在，接着干」这类），与 {@link #enqueue}（主人话语）分开。
     *
     * <p>为 {@code null} 时 `enqueueSystem` 退回 `enqueue` —— 老宿主不接也不会坏。
     */
    private static volatile BiFunction<UUID, String, Delivery> enqueueSystem;
    /** 客户端接上来了没有。它同时就是"我现在是不是客户端"的答案。 */
    private static volatile boolean clientReady;

    private static final NumenApi API = new Impl();

    private NumenPlugins() {}

    /** 登记一个插件。可在任何时候调用,通常在你模组的构造期。 */
    public static void register(NumenPlugin plugin) {
        if (plugin == null) return;
        try {
            plugin.setup(API);
        } catch (RuntimeException e) {
            Constants.LOG.error("[numen] 插件登记失败,它挂的东西可能只生效了一半", e);
        }
    }

    /**
     * 客户端起来时把只在客户端存在的能力接上来。<b>引擎内部调用</b>,插件不该碰。
     */
    public static void bindClient(Consumer<Path> skillSink,
                                  BiFunction<UUID, String, Delivery> enqueueFn) {
        bindClient(skillSink, enqueueFn, null);
    }

    /**
     * 同上，但额外把<b>系统事件</b>通道接上（G4c S107b）。{@code systemFn} 为 null 时退回主人通道。
     */
    public static void bindClient(Consumer<Path> skillSink,
                                  BiFunction<UUID, String, Delivery> enqueueFn,
                                  BiFunction<UUID, String, Delivery> enqueueSystemFn) {
        skills = skillSink;
        enqueue = enqueueFn;
        enqueueSystem = enqueueSystemFn;
        clientReady = true;
        for (Runnable r : PENDING) runClientBlock(r);
        for (Path root : PENDING_SKILLS) skillSink.accept(root);
        PENDING.clear();
        PENDING_SKILLS.clear();
    }

    /**
     * 客户端还没接上时先攒着,接上再跑。
     *
     * <p>插件在自己的 {@code @Mod} 构造器里登记,而引擎的客户端入口也是一个
     * {@code @Mod} 构造器——谁先谁后由加载器的模组排序决定。不攒的话,插件生不生效
     * 就成了排序的函数:同一份代码换个加载器、加个别的模组就可能整块静默失效,
     * 而且没有任何报错。专用服务器上没人来接,这两个表原样留着不跑,正是要的行为。
     */
    /**
     * 插件挂在 {@code <runtime_state>} 上的现算片段。见 {@link NumenApi#contributeState}。
     * 用 CopyOnWriteArrayList:登记发生在加载期,读发生在每次请求,读远多于写。
     */
    private static final List<Function<UUID, String>> STATE = new CopyOnWriteArrayList<>();

    /**
     * 汇总所有插件对这只同伴的现算片段。<b>引擎内部调用</b>。
     *
     * <p>某个插件算炸了不能连累整条请求——它自己那段丢掉,别人的照常挂上。
     */
    public static String stateFragments(UUID companion) {
        if (STATE.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Function<UUID, String> f : STATE) {
            try {
                String x = f.apply(companion);
                if (x != null && !x.isBlank()) sb.append(x);
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] 插件的运行期状态算不出来,这一段跳过", e);
            }
        }
        return sb.toString();
    }

    private static final List<Runnable> PENDING = new ArrayList<>();
    private static final List<Path> PENDING_SKILLS = new ArrayList<>();

    /**
     * 插件挂在任务链规划请求上的知识片段。见 {@link NumenApi#contributePlanningKnowledge}。
     * 与 {@link #STATE} 同样的读多写少,同样在加载期登记、每次规划读。
     */
    private static final List<PlanningKnowledgeContributor> PLANNING = new CopyOnWriteArrayList<>();

    /** 各贡献者结果之间的稳定分隔符:两条知识必须能被分开读,不能粘成一段。 */
    public static final String PLANNING_KNOWLEDGE_SEPARATOR = "\n";

    /**
     * 宿主聚合层的知识总字符上限。<b>不能只信任各贡献者自觉限流</b>——
     * 插件是第三方代码,它们的预算只对它们自己负责。
     */
    public static final int MAX_PLANNING_KNOWLEDGE_CHARS = 2000;

    /**
     * 汇总所有插件为这次规划贡献的知识正文。<b>引擎/规划器调用</b>。
     *
     * <p>三重兜底都在这一层,不依赖贡献者自律:
     * <ul>
     *   <li><b>稳定分隔符</b>——非空片段之间固定用 {@link #PLANNING_KNOWLEDGE_SEPARATOR},
     *       首尾不加,拼出来逐字节可预期。</li>
     *   <li><b>总字符上限</b>——超过 {@link #MAX_PLANNING_KNOWLEDGE_CHARS} 就截断,
     *       后面的贡献者不再追加。</li>
     *   <li><b>故障隔离</b>——某个插件算炸了(含 {@code Error},例如缺类)只丢它自己那段,
     *       其余贡献者与整条规划请求都不受影响。</li>
     * </ul>
     *
     * <p>没有任何贡献者(比如知识插件没装)时返回空串,规划器据此走无知识路径。
     */
    public static String planningKnowledge(PlanningQuery query) {
        return aggregatePlanningKnowledge(PLANNING, query);
    }

    /**
     * 聚合本体（纯函数，注册表由调用方传入）。抽出来的唯一目的是可单测——
     * {@link #PLANNING} 是静态全局表，测试没法隔离它。
     *
     * @param contributors 贡献者列表（可为 null / 空）
     * @param query        本次规划背景（null → 视为没有知识可取）
     */
    public static String aggregatePlanningKnowledge(List<PlanningKnowledgeContributor> contributors,
                                                    PlanningQuery query) {
        if (contributors == null || contributors.isEmpty() || query == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int accepted = 0;
        int failed = 0;
        int truncated = 0;
        for (PlanningKnowledgeContributor c : contributors) {
            String fragment;
            try {
                fragment = c.knowledgeFor(query);
            } catch (Throwable e) {
                failed++;
                Constants.LOG.error("[numen] 插件的规划知识算不出来,这一段跳过", e);
                continue;
            }
            if (fragment == null || fragment.isBlank()) {
                continue;
            }
            String piece = sb.length() == 0 ? fragment : PLANNING_KNOWLEDGE_SEPARATOR + fragment;
            int room = MAX_PLANNING_KNOWLEDGE_CHARS - sb.length();
            if (piece.length() > room) {
                if (room > 0) {
                    sb.append(piece, 0, room);
                }
                truncated++;
                break; // 预算已满,后面的贡献者不再追加
            }
            sb.append(piece);
            accepted++;
        }
        if (truncated > 0 || failed > 0) {
            // 简短观测:只报计数与预算,不含任何正文
            Constants.LOG.info("[numen] planning knowledge aggregated: contributors={} failed={} truncated={} chars={}/{}",
                    accepted, failed, truncated, sb.length(), MAX_PLANNING_KNOWLEDGE_CHARS);
        }
        return sb.toString();
    }

    // ═══ G5g+/80号契约：执行知识的「交付账本」载荷（2026-10-27 离线补写）═══
    //
    // 背景（83 号 §9）：「经验没用」和「经验没送到」从外面看一模一样 —— 1200/2400 字
    // 静默截断那次，A/B 直接判错方向。80 号契约 §2 要求：最终请求裁完、装配定案后，
    // 每个真保留的经验条目记一次 KIND_REQUEST_DELIVERED。
    //
    // 为什么是中性记录而不是直接调 ExposureLedger：api/common 不依赖 experience-core
    // （build.gradle 刻意封死），所以 api 只定义「装了什么」，由经验插件自己落账。

    /** G5g+：执行知识贡献者（正文 + 引用清单）。等价于 {@code BiFunction<UUID,String,KnowledgeFragment>}。 */
    @FunctionalInterface
    public interface KnowledgeContributor {
        KnowledgeFragment contribute(UUID companion, String objective);
    }

    /** 一条被送进最终请求的知识条目引用。truncated=true 表示片段被预算切断，不算完整交付。 */
    public record DeliveredRef(String entryRef, String version, boolean truncated) {
        public DeliveredRef {
            entryRef = entryRef == null ? "" : entryRef;
            version = version == null ? "" : version;
        }
    }

    /**
     * 一个贡献者片段：正文 + 它引用的条目。
     * 旧贡献者（只回字符串）用 {@link #ofText} 包装，refs 为空 —— 聚合器不会为它记账。
     */
    public record KnowledgeFragment(String text, List<DeliveredRef> refs) {
        public KnowledgeFragment {
            text = text == null ? "" : text;
            refs = refs == null ? List.<DeliveredRef>of() : List.copyOf(refs);
        }

        public static KnowledgeFragment ofText(String text) {
            return new KnowledgeFragment(text, List.of());
        }
    }

    // ═══ G5e：执行角色的检索/参考知识（进 <runtime_state>，与规划知识分开）═══

    /**
     * 插件挂在**执行请求**（{@code <runtime_state>}）上的知识片段。见
     * {@link NumenApi#contributeExecutorKnowledge}。
     *
     * <p>为什么与 {@link #PLANNING} 分开而不是共用一条：同一份 snapshot 在 EXECUTOR 与 PLANNER
     * 视角<b>本来就该不同</b>（75 号 §15.5 IC08）——EXECUTOR 只信 VERIFIED、非撤回、非 WARNING；
     * 合成一条缝就等于把两种视图混成一个，判据再也测不出来。
     */
    private static final List<KnowledgeContributor> EXECUTOR = new CopyOnWriteArrayList<>();

    /**
     * 执行角色知识的总字符上限。
     *
     * <p><b>2026-10-27：1200 → 2400</b>（与 {@code PlanningKnowledge} 2026-10-07 的改动同一理由）。
     * 实机代价很具体：夜间那条新经验排第 4，被 1200 字<b>静默截掉</b>——
     * 请求正文里只剩 3 条，而「经验没用」和「经验没送到」从外面看一模一样。
     * 条目正文上限 400 字（{@code ExperienceStoreRetrievalBridge.MAX_BODY_CHARS}），
     * 1200 只够装 3 条多一点；2400 能装 5–6 条，且仍远小于规划侧。
     *
     * <p><b>2026-10-27 第二次：2400 → 4000。</b> 2400 时 6 条渲染出来仍被截在</p>
     * {@code </retrieved_knowledge>} 之前（请求里缺闭合标签，模型看到半截 XML）；
     * 4000 让 6–8 条完整在场。截断本身仍是硬切，见 {@code aggregateExecutorKnowledge}。
     */
    public static final int MAX_EXECUTOR_KNOWLEDGE_CHARS = 4000;

    /** 老形态：拿不到 objective（G5e 起的插件都这么注册）。等价于「objective 永远为空」。 */
    public static void contributeExecutorKnowledge(Function<UUID, String> fragment) {
        if (fragment != null) EXECUTOR.add((uuid, objective) -> KnowledgeFragment.ofText(fragment.apply(uuid)));
    }

    /**
     * G5g：带 objective 的形态 —— 工具目录类贡献靠它做相关性过滤。
     * 不传 objective 时，目录 provider 会一条都不放（防止把经验挤掉）。
     */
    public static void contributeExecutorKnowledge(BiFunction<UUID, String, String> fragment) {
        if (fragment != null) EXECUTOR.add(
                (uuid, objective) -> KnowledgeFragment.ofText(fragment.apply(uuid, objective)));
    }

    /** G5g+（80 号契约 §2）：片段形态注册 —— 贡献者自带 refs，聚合层据此记账。 */
    public static void contributeExecutorKnowledge(KnowledgeContributor contributor) {
        if (contributor != null) EXECUTOR.add(contributor);
    }

    /** 汇总所有插件为这只同伴的执行请求贡献的知识。<b>引擎内部调用</b>。 */
    public static String executorKnowledge(UUID companion) {
        return executorKnowledge(companion, "");
    }

    /** 引擎内部调用：当前注册的执行知识贡献者快照（聚合时用，避免聚合中被并发注册）。 */
    public static List<KnowledgeContributor> executorContributors() {
        return List.copyOf(EXECUTOR);
    }

    /** G5g：带上「她此刻在干什么」（objective）—— 目录 provider 按它过滤。 */
    public static String executorKnowledge(UUID companion, String objective) {
        return aggregateExecutorKnowledgeFragment(EXECUTOR, companion, objective == null ? "" : objective).text();
    }

    /**
     * G5g+（80 号契约 §2）：片段版聚合 —— 正文 + 被装进/被裁明细。
     * 记账规则（83 号 §9 冻结）：整体接受→refs 全记 delivered；被切断→记 truncated=true；
     * 预算满后不再处理→一条不记（先裁后记）。
     */
    public static KnowledgeFragment aggregateExecutorKnowledgeFragment(
            List<KnowledgeContributor> contributors, UUID companion, String objective) {
        if (contributors == null || contributors.isEmpty() || companion == null) {
            return KnowledgeFragment.ofText("");
        }
        String obj = objective == null ? "" : objective;
        StringBuilder sb = new StringBuilder();
        List<DeliveredRef> delivered = new ArrayList<>();
        int accepted = 0;
        int failed = 0;
        int truncated = 0;
        for (KnowledgeContributor c : contributors) {
            KnowledgeFragment f;
            try {
                f = c.contribute(companion, obj);
            } catch (Throwable e) {
                failed++;
                Constants.LOG.error("[numen] 插件的执行知识算不出来,这一段跳过", e);
                continue;
            }
            if (f == null || f.text() == null || f.text().isBlank()) {
                continue;
            }
            String piece = sb.length() == 0 ? f.text() : PLANNING_KNOWLEDGE_SEPARATOR + f.text();
            int room = MAX_EXECUTOR_KNOWLEDGE_CHARS - sb.length();
            if (piece.length() > room) {
                if (room > 0) {
                    sb.append(piece, 0, room);
                    // 装了一部分：算「截断交付」——refs 记 truncated=true（审计可见，不算回执）
                    truncated++;
                    for (DeliveredRef r : f.refs()) {
                        delivered.add(new DeliveredRef(r.entryRef(), r.version(), true));
                    }
                }
                // room<=0：预算已满、这个片段一个字节都没装进去 ——
                // 按 83 号 §9「预算满后不再处理 → 一条不记（先裁后记）」，不记 truncated 也不记 delivered。
                break;
            }
            sb.append(piece);
            accepted++;
            for (DeliveredRef r : f.refs()) {
                delivered.add(new DeliveredRef(r.entryRef(), r.version(), false));
            }
        }
        if (truncated > 0 || failed > 0) {
            Constants.LOG.info("[numen] executor knowledge aggregated: contributors={} failed={} truncated={} chars={}/{}",
                    accepted, failed, truncated, sb.length(), MAX_EXECUTOR_KNOWLEDGE_CHARS);
        }
        return new KnowledgeFragment(sb.toString(), delivered);
    }

    /** 落账终点：经验插件注册后，每轮把「真保留的 refs」交给它记 ExposureLedger。 */
    private static final List<java.util.function.BiConsumer<UUID, List<DeliveredRef>>> DELIVERED_SINKS
            = new CopyOnWriteArrayList<>();

    /** 注册交付账本终点（api 不依赖 experience-core，由插件侧自己调 ExposureLedger.record）。 */
    public static void sinkRequestDelivered(java.util.function.BiConsumer<UUID, List<DeliveredRef>> sink) {
        if (sink != null) DELIVERED_SINKS.add(sink);
    }

    /**
     * 引擎在 runtime_state 定案后调用（先裁后记）：refs 只来自「真保留在最终正文里」的片段。
     * 每个终点单独 try/catch —— 账本挂了不能挡请求（观测旁路纪律）。
     */
    public static void requestDelivered(UUID companion, List<DeliveredRef> refs) {
        if (companion == null || refs == null || refs.isEmpty() || DELIVERED_SINKS.isEmpty()) {
            return;
        }
        List<DeliveredRef> kept = new ArrayList<>();
        for (DeliveredRef r : refs) {
            if (!r.truncated()) {
                kept.add(r);
            }
        }
        if (kept.isEmpty()) {
            return;
        }
        for (java.util.function.BiConsumer<UUID, List<DeliveredRef>> sink : DELIVERED_SINKS) {
            try {
                sink.accept(companion, kept);
            } catch (Throwable e) {
                Constants.LOG.error("[numen] request_delivered 落账失败（观测旁路，不挡请求）", e);
            }
        }
    }

    /**
     * 聚合本体（纯函数，注册表由调用方传入，便于单测隔离静态表）。
     *
     * <p>三重兜底与 {@link #aggregatePlanningKnowledge} 同口径：稳定分隔符、总字符上限、
     * 故障隔离（含 {@code Error}，例如缺类）。没有贡献者/UUID 为空 → 返回空串。
     */
    /** 兼容旧形态（{@code Function<UUID,String>}）：等价于「objective 永远为空」。 */
    public static String aggregateExecutorKnowledge(List<Function<UUID, String>> contributors, UUID companion) {
        if (contributors == null) {
            return "";
        }
        List<BiFunction<UUID, String, String>> wrapped = new ArrayList<>();
        for (Function<UUID, String> f : contributors) {
            if (f != null) {
                wrapped.add((uuid, objective) -> f.apply(uuid));
            }
        }
        return aggregateExecutorKnowledge(wrapped, companion, "");
    }

    /**
     * 聚合本体（纯函数，注册表由调用方传入，便于单测隔离静态表）。
     *
     * <p>三重兜底与 {@link #aggregatePlanningKnowledge} 同口径：稳定分隔符、总字符上限、
     * 故障隔离（含 {@code Error}，例如缺类）。没有贡献者/UUID 为空 → 返回空串。
     *
     * <p><b>G5g</b>：多一个 {@code objective}。工具目录类贡献靠它做相关性过滤 ——
     * 没有它就只剩「全塞」或「全不塞」，前者会把经验挤掉。
     */
    public static String aggregateExecutorKnowledge(List<BiFunction<UUID, String, String>> contributors,
                                                    UUID companion, String objective) {
        if (contributors == null) {
            return "";
        }
        List<KnowledgeContributor> wrapped = new ArrayList<>();
        for (BiFunction<UUID, String, String> f : contributors) {
            if (f != null) {
                wrapped.add((uuid, obj) -> KnowledgeFragment.ofText(f.apply(uuid, obj)));
            }
        }
        return aggregateExecutorKnowledgeFragment(wrapped, companion, objective == null ? "" : objective).text();
    }

    /** 旧签名保留（既有单测在用）；实现并入片段版聚合。 */
    private static String aggregateExecutorKnowledgeOld(List<BiFunction<UUID, String, String>> contributors,
                                                    UUID companion, String objective) {
        if (contributors == null || contributors.isEmpty() || companion == null) {
            return "";
        }
        String obj = objective == null ? "" : objective;
        StringBuilder sb = new StringBuilder();
        int accepted = 0;
        int failed = 0;
        int truncated = 0;
        for (BiFunction<UUID, String, String> c : contributors) {
            String fragment;
            try {
                fragment = c.apply(companion, obj);
            } catch (Throwable e) {
                failed++;
                Constants.LOG.error("[numen] 插件的执行知识算不出来,这一段跳过", e);
                continue;
            }
            if (fragment == null || fragment.isBlank()) {
                continue;
            }
            String piece = sb.length() == 0 ? fragment : PLANNING_KNOWLEDGE_SEPARATOR + fragment;
            int room = MAX_EXECUTOR_KNOWLEDGE_CHARS - sb.length();
            if (piece.length() > room) {
                if (room > 0) {
                    sb.append(piece, 0, room);
                }
                truncated++;
                break;
            }
            sb.append(piece);
            accepted++;
        }
        if (truncated > 0 || failed > 0) {
            Constants.LOG.info("[numen] executor knowledge aggregated: contributors={} failed={} truncated={} chars={}/{}",
                    accepted, failed, truncated, sb.length(), MAX_EXECUTOR_KNOWLEDGE_CHARS);
        }
        return sb.toString();
    }

    private static void runClientBlock(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Constants.LOG.error("[numen] 插件的客户端初始化出错", e);
        }
    }

    private static final class Impl implements NumenApi {

        @Override
        public <T> void on(CompanionEvent<T> event, Consumer<T> handler) {
            CompanionEvents.subscribe(event, handler);
        }

        @Override
        public void registerTool(NumenTool tool) {
            ToolRegistry.register(tool);
        }

        @Override
        public void bundleSkills(Path skillsRoot) {
            if (skillsRoot == null) return;
            Consumer<Path> sink = skills;
            if (sink != null) sink.accept(skillsRoot); else PENDING_SKILLS.add(skillsRoot);
        }

        @Override
        public void onClient(Runnable clientOnly) {
            if (clientOnly == null) return;
            if (clientReady) runClientBlock(clientOnly); else PENDING.add(clientOnly);
        }

        @Override
        public void contributeState(Function<UUID, String> fragment) {
            if (fragment != null) STATE.add(fragment);
        }

        @Override
        public void contributePlanningKnowledge(PlanningKnowledgeContributor contributor) {
            if (contributor != null) PLANNING.add(contributor);
        }

        @Override
        public void contributeExecutorKnowledge(Function<UUID, String> fragment) {
            // G5e：执行角色知识（每轮 runtime_state）——与 STATE 同为读多写少
            NumenPlugins.contributeExecutorKnowledge(fragment);
        }

        @Override
        public void contributeExecutorKnowledge(BiFunction<UUID, String, String> fragment) {
            // G5g：带 objective 的形态（工具目录靠它做相关性过滤）
            NumenPlugins.contributeExecutorKnowledge(fragment);
        }

        @Override
        public void contributeExecutorKnowledge(KnowledgeContributor contributor) {
            // G5g+（80 号契约 §2）：片段形态 —— refs 随正文一起进聚合器
            NumenPlugins.contributeExecutorKnowledge(contributor);
        }

        @Override
        public void sinkRequestDelivered(java.util.function.BiConsumer<UUID, List<DeliveredRef>> sink) {
            // G5g+：经验插件经这里注册落账终点
            NumenPlugins.sinkRequestDelivered(sink);
        }

        @Override
        public Path configDir() {
            return com.dwinovo.numen.NumenPaths.config();
        }

        @Override
        public Delivery enqueue(UUID companion, String message) {
            BiFunction<UUID, String, Delivery> fn = enqueue;
            return fn == null ? Delivery.REJECTED : fn.apply(companion, message);
        }

        /**
         * G4c S107b：系统事件（不冒充主人）。**未接系统通道时退回 {@link #enqueue}** ——
         * 行为与旧版一致，只是「主人回合豁免」那条判断会更宽（= 旧行为）。
         */
        @Override
        public Delivery enqueueSystem(UUID companion, String message) {
            BiFunction<UUID, String, Delivery> fn = enqueueSystem;
            return fn != null ? fn.apply(companion, message) : enqueue(companion, message);
        }
    }
}
