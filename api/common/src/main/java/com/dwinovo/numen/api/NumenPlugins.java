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
        skills = skillSink;
        enqueue = enqueueFn;
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
        public Path configDir() {
            return com.dwinovo.numen.NumenPaths.config();
        }

        @Override
        public Delivery enqueue(UUID companion, String message) {
            BiFunction<UUID, String, Delivery> fn = enqueue;
            return fn == null ? Delivery.REJECTED : fn.apply(companion, message);
        }
    }
}
