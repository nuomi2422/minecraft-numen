package com.dwinovo.numen.task;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

/**
 * 她现在在做的事,活过服务器重启。
 *
 * <h2>为什么要有</h2>
 * 「去钓鱼」是一个<b>没有被收回的意图</b>。服务器重启对主人来说是不可见的实现细节,
 * 不该让他的指令蒸发——回来发现她站在湖边发呆、得再说一遍,那不是陪伴是打卡。
 *
 * <p>(主人单纯下线<b>不需要</b>这个:身体还在服务器里 tick,任务照样在跑,收尾走
 * {@code NumenEvents} 的离线出箱。这里只管重启。)
 *
 * <h2>重建配方 = 那次工具调用本身</h2>
 * 存两个字符串:{@code toolName} 与当时的 {@code args}。重建就是<b>把那次调用重放
 * 一遍</b>——工具作者一行都不用写,不需要给每个任务实现一套状态序列化。
 *
 * <p>代价是<b>进度不保</b>:「挖 64 块」挖到 30 块重启,重放会重新挖 64 块。
 * 相比"回来发现啥也没干",多挖三十块是明显更小的损失;真在意精度的任务可以在
 * 自己的工具里把进度写进 args(那时它就是一次更精确的重放)。
 *
 * <p>重建失败(鱼塘被填了、目标方块没了)不静默:任务自己走 FAILED,收尾事件照发。
 *
 * <p>服务端专用。
 */
public final class TaskPersistence {

    /** 合成的调用 id 前缀——重放出来的任务不属于任何一次真实的 tool_call。 */
    private static final String REPLAY_CALL_ID = "restored";

    private TaskPersistence() {}

    /**
     * 重建决策。<b>纯函数、零 Minecraft 依赖</b>——工具还在不在由调用方用
     * {@code toolExists} 注入。
     *
     * <p><b>为什么要抽出来</b>：{@link #restore} 整个方法要 {@code MinecraftServer}
     * 与同伴对象，单测够不着，于是「两条走不通的路都给了结吗」这道判断只能靠读码。
     * 而这道判断正是本类类注释承诺的不变式（「重建失败不静默：任务自己走 FAILED，
     * 收尾事件照发」）——<b>承诺必须能被测到，否则它只是一句注释</b>。
     */
    public sealed interface RestoreDecision {
        /** 接得回来：工具在、参数也读得动。 */
        record Replay(String tool, JsonObject args) implements RestoreDecision {}

        /**
         * 接不回来。<b>但必须给一个了结</b>。
         *
         * <p>她的历史里还留着「已受理，后台执行中」那句回执；不给终态她会一直干等，
         * 而干等不会自己变成「没发生」。
         */
        record GiveUp(String tool, String reason) implements RestoreDecision {}
    }

    /**
     * 这件活接不接得回来。{@code toolName} 必须非空（空 = 闲着，由 {@link #restore} 先滤掉）。
     *
     * @param toolExists 那个工具这一版里还在吗。由调用方注入，好让本方法不碰静态注册表。
     */
    public static RestoreDecision decideRestore(String toolName, String rawArgs,
                                                java.util.function.Predicate<String> toolExists) {
        String tool = toolName == null ? "" : toolName;
        if (!toolExists.test(tool)) {
            // 工具在版本更新里没了(比如两个攻击工具并成了一个)。不做兼容转接——
            // 旧参数未必对得上新工具的语义,猜错了她会去打错的东西。
            return new RestoreDecision.GiveUp(tool,
                    "这件活没能接回来:" + tool + " 这个工具在这一版里已经不存在了。"
                            + "看看现在有哪些工具,需要的话重新派一次。");
        }
        String raw = rawArgs == null ? "" : rawArgs;
        if (raw.isBlank()) {
            return new RestoreDecision.Replay(tool, new JsonObject());
        }
        try {
            return new RestoreDecision.Replay(tool, JsonParser.parseString(raw).getAsJsonObject());
        } catch (RuntimeException bad) {
            // ★ 以前这条只 LOG.warn 然后 forget —— 任务凭空消失、终态事件不发,
            //   而工具消失那条路是发的。同一个类里两条失败路两种待遇,读的人无从判断。
            return new RestoreDecision.GiveUp(tool,
                    "这件活没能接回来:" + tool + " 存下的参数读不了("
                            + bad.getClass().getSimpleName() + ")。她手上那件活已经没了,"
                            + "需要的话重新派一次。");
        }
    }

    /**
     * 接不回来的那些事,在收尾事件里用的 id。<b>刻意做成合成值而不是 {@code t1} 这种
     * 真实任务编号</b>——真实编号的计数器每次重启归零,拿它当「重启前那件事的编号」
     * 会和这一局刚起的另一个任务撞号。
     *
     * <p>⚠️ <b>本类的已知边界（刻意不在这里补）</b>：<b>接得回来的那些事拿不到原来那个
     * 编号</b>——{@code taskTool}/{@code taskArgs} 是「重建配方 = 那次工具调用本身」，
     * 配方里没有编号，而补一个编号进存档等于改世界存档格式。
     * ⇒ 重放任务收尾时带的是这一局新分配的 {@code t<N>}，<b>与重启前那次「已受理」对不上</b>。
     * 这个差距是公开的（这里写明了、收尾日志里也带工具名），不是静默的错配；
     * 真要闭合它属于「存档格式变一次」，该单独一次提交，不该混在一次静默修复里。
     */
    public static String restoredTaskId(String toolName) {
        return REPLAY_CALL_ID + "-" + (toolName == null ? "" : toolName);
    }

    /** 记下她现在在做什么(换槽时调)。{@code toolName} 为 null = 记为空闲。 */
    public static void remember(NumenPlayer companion, String toolName, JsonObject args) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry e = reg.find(companion.getUUID());
        if (e == null) {
            return;
        }
        reg.put(companion.getUUID(), e.doing(
                toolName == null ? "" : toolName,
                args == null ? "" : args.toString()));
    }

    /** 她做完了 / 被换掉了 —— 清掉记录,免得重启后凭空捡回一件旧活。 */
    public static void forget(NumenPlayer companion) {
        remember(companion, null, null);
    }

    /**
     * 重启后把她手上的活接回来。接得回来就重放那次工具调用;接不回来就发一条
     * {@code task_finished(failed)} 再清记录——<b>接不回来也必须有终态</b>，
     * 否则她那句「已受理,后台执行中」永远没有下文。
     * 无论哪条路都不阻断:身体照样起来,她只是空着手。
     *
     * <p>「接不回来」有两条路(工具没了 / 参数读不了),两条都必须了结——
     * 判定本身抽在 {@link #decideRestore},单测直接钉它。
     */
    public static void restore(NumenPlayer companion) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companion.getUUID());
        if (e == null || e.taskTool().isBlank()) {
            return;
        }
        NumenTool tool = ToolRegistry.get(e.taskTool());
        RestoreDecision plan = decideRestore(e.taskTool(), e.taskArgs(), name -> tool != null);
        if (plan instanceof RestoreDecision.GiveUp giveUp) {
            // ★ 两条走不通的路都要了结。少了任何一条,她的历史里那句「已受理,后台执行中」
            //   就永远没有下文 —— 而本类的类注释把「不静默」写成了不变式。
            Constants.LOG.warn("[numen-task] {} 接不回来:{}", e.taskTool(), giveUp.reason());
            NumenEvents.taskFinished(companion, restoredTaskId(e.taskTool()), e.taskTool(), "failed",
                    giveUp.reason());
            forget(companion);
            return;
        }
        RestoreDecision.Replay replay = (RestoreDecision.Replay) plan;
        Constants.LOG.info("[numen-task] {} 接回重启前的活:{} {}",
                companion.getUUID(), replay.tool(), replay.args());
        // 重放。回执直接丢:它本来是给某一次 tool_call 的,而那次调用早就随上一个
        // 会话结束了——真正会送到模型手里的是这件活干完时的 task_finished。
        tool.onServerCall(restoredTaskId(e.taskTool()), replay.args(), companion, reply -> { });
    }
}
