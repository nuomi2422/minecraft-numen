package com.dwinovo.numen.plugins.rdd;

/**
 * 「安家流程」提示：让 AI 自己选地、自己整平、自己点灯，而不是硬挤村民的房子。
 *
 * <h2>为什么需要（2026-10-25 主人指出）</h2>
 * MC 地形复杂，直接找块地放床经常放不下（坡、草、水、怪）；村民的房子也不合适
 * （有村民、有门、有床占位、动不得）。主人要的是：**自己找地方、自己整平一块较宽的地、
 * 插上火把、把床放上去**——这样重生点才稳。
 *
 * <p>这些工具其实早就有（{@code settlement_platform} 出整平蓝图、{@code blueprint action=build}
 * 施工、{@code build} 放火把/方块、{@code sleep} 绑重生点、{@code settlement_base} 登记基地），
 * 缺的只是**把流程按顺序说清楚**这一句话。所以这里不新增工具、不新增门禁，只补提示。
 *
 * <p>只在当前目标/阶段提到"基地/安家/房子/据点"时注入，避免每轮都占 token。
 */
final class RddBasePlaybook {

    private static final String[] TRIGGERS = {
            "基地", "安家", "安身", "据点", "房子", "屋子", "建家", "落脚", "重生点", "床"
    };

    private RddBasePlaybook() {}

    /** 这段话是否在谈"安家/基地"。 */
    static boolean relevant(String text) {
        if (text == null || text.isBlank()) return false;
        for (String t : TRIGGERS) {
            if (text.contains(t)) return true;
        }
        return false;
    }

    /** 注入用的提示块；与当前目标无关时返回空串。 */
    static String block(String objectiveOrStage) {
        if (!relevant(objectiveOrStage)) return "";
        return "<base_playbook>要安家时别硬挤村民的房子、也别在斜坡上凑合："
                + "① 先在你附近选一块**相对平坦的空地**（草/土/沙都行，避开水和悬崖）；"
                + "② 用 settlement_platform 出整平蓝图（size 11~15，floor_block 用你背包里真有的方块），"
                + "它会回一个 anchor；③ 拿那个 anchor 执行 blueprint action=build 把平台真造出来；"
                + "④ 平台上每隔 5 格放火把（build，torch/火把），保证夜里不刷怪；"
                + "⑤ 在平台中央放床，并 sleep 一次把重生点绑在这（白天点也行，重生点照样生效，只是不跳过时间）；"
                + "⑥ 床边放箱子与合成台，把备用装备收进去——这才是「死了能回来取」的基地。"
                + "</base_playbook>";
    }
}
