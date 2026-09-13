package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.NumenTool;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 插件手里的那个对象——<b>扩展 Numen 的唯一一扇门</b>。
 *
 * <pre>{@code
 * NumenPlugins.register(numen -> {
 *     numen.registerTool(new MyTool());
 *     numen.bundleSkills(myJarSkillsRoot());
 *     numen.on(CompanionEvent.SPAWN, body -> ...);
 * });
 * }</pre>
 *
 * <h2>为什么收成一扇门</h2>
 * 能力散在 {@code ToolRegistry} / {@code SkillRegistry} / 生命周期监听各处时,第三方得先猜今天这件事属于哪一派、类在哪个包、是静态方法还是单例。
 * 收到一处之后,"我要扩展 Numen"只有一个答案。引擎内部照旧用原来那些类,
 * 这里只是它们对外的那一面。
 *
 * <h2>专用服务器上会安静地少几样</h2>
 * 技能和头像喂的是 LLM 与界面,而两者都只活在玩家自己的客户端上。在专用服务器上
 * {@link #bundleSkills} 与 {@link #registerPortrait} 是<b>空操作</b>,不报错。
 * 这样插件不必自己写 {@code if (dist == CLIENT)} ——那种判断写在每个插件里,
 * 就是四个插件四种写法。
 */
public interface NumenApi {

    /**
     * 订阅同伴身上的事。同一个事件可以订阅多次,按注册顺序调用。
     *
     * @param event   见 {@link CompanionEvent} 的常量
     * @param handler 处理器抛异常不会打断其他订阅者,但会被记进日志
     */
    <T> void on(CompanionEvent<T> event, Consumer<T> handler);

    /**
     * 注册一个大模型可以调用的工具。它和内置工具一视同仁——同样进目录、同样被
     * 渐进披露、同样按名字调用。
     */
    void registerTool(NumenTool tool);

    /**
     * 把一个目录里的技能交给引擎。就地读,不复制:你的 jar 一卸载技能跟着消失。
     * 玩家在 {@code config/numen/skills/} 放同名目录可以覆盖你这份。
     *
     * <p>通常传你自己 jar 里的 {@code skills/}。专用服务器上是空操作。
     */
    void bundleSkills(Path skillsRoot);

    /**
     * 跑一段<b>只在客户端才有意义</b>的代码。专用服务器上整块不执行。
     *
     * <p>界面、渲染、头像这类东西只活在玩家的客户端上,而引擎的公共部分刻意不引用
     * 任何客户端类(那条线是有意划的)。所以它们的注册入口在客户端那一侧
     * (如 {@code NumenGateway.registerPortrait}),你在这个块里去调:
     *
     * <pre>{@code
     * numen.onClient(() -> NumenGateway.registerPortrait(new MyPortrait()));
     * }</pre>
     *
     * <p>"现在是不是客户端"这个判断由引擎自己回答——它每个加载器一个写法,
     * 让每个插件各写一遍就是每个插件一种写法。
     */
    void onClient(Runnable clientOnly);

    /**
     * {@code config/numen/} ——引擎和插件共用的配置目录。你的持久数据放这儿,
     * 文件名带上自己的 mod id(如 {@code numen_tlm-wardrobe.json})。
     *
     * <p>别自己从游戏目录往下拼:客户端、专用服务器、开发环境三种情况下拼法不同,
     * 每个插件各拼一遍就是每个插件一种答案。目录<b>不保证已存在</b>,写之前
     * 自己 {@code createDirectories}。
     */
    Path configDir();

    /**
     * 每次发请求时现算一段,挂进这只同伴的 {@code <runtime_state>}。
     *
     * <p>解决的是这么个事:你的工具把同伴改了(换了外观、接了什么设备),她只在
     * <b>调用工具那一轮</b>知道,下一轮、下一次进游戏就忘了。挂在这儿的东西每轮都在,
     * 她随时知道自己现在是什么状态。
     *
     * <pre>{@code
     * numen.contributeState(companion -> wearing(companion) == null ? ""
     *         : "<maid_look>你现在穿着「" + name + "」</maid_look>");
     * }</pre>
     *
     * <p>自己带一个标签;这轮没什么好说的就返回空串。一个字都不入会话历史,
     * 所以随便变——它挂在请求末端,不在字节级稳定的系统提示里,打不碎 prompt 缓存。
     * 抛异常不会打断别的贡献者,但会记进日志。
     */
    void contributeState(Function<UUID, String> fragment);

    /**
     * 把一句话交给同伴的内置大脑,效果和主人亲手打字一样。
     *
     * <p>这是<b>进</b>的方向。出的方向不在这里:同伴要说什么、要做什么,是它自己
     * 调用工具的结果——注册一个工具,它有话说的时候会调你。
     */
    Delivery enqueue(UUID companion, String message);

    /**
     * 每次<b>任务链规划请求</b>现算一段参考资料,拼进真正发给模型的请求正文。
     *
     * <p>与 {@link #contributeState} 同构,只是消费方不同:那份进同伴每轮的
     * {@code <runtime_state>},这份进任务链规划器(Stage-A 首次规划 / Stage-B 阶段展开 /
     * 回退重规划)的 user 请求。
     *
     * <p>为什么要有这条缝:规划器和知识提供方是<b>两个互不可见的插件</b>——联动之间
     * 只看得见这扇门,不能互相 import。没有这条缝,规划器就只能自己再实现一遍检索,
     * 或者把别人的核心模块再打包一份进自己的 jar(那会撞重复包)。
     *
     * <pre>{@code
     * numen.contributePlanningKnowledge(q ->
     *         q.objective().contains("钻石") ? "<experience>…</experience>" : "");
     * }</pre>
     *
     * <p>实现方要自己保证预算受限、只读、不抛异常;算炸了只丢这一段,规划照常进行。
     * 返回值不会改变任务结构,也不授予任何工具权限——它只是参考资料。
     */
    void contributePlanningKnowledge(PlanningKnowledgeContributor contributor);

}
