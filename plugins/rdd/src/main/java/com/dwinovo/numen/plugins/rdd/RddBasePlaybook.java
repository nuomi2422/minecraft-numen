package com.dwinovo.numen.plugins.rdd;

/**
 * 当前安家任务的可选提示：依托选定村庄，先实查复用，再按缺口补建。
 *
 * <p>原因/案例/日期（2026-11-12）：旧提示让同伴避开村屋、默认另起平台，
 * 与唯一 survival-doctrine.md 的村中大屋路线及主人强调的农田/交易依托冲突。
 * 这里纠正选择顺序，保留安全、照明、绑床和真实备用要求；不是搬迁已有基地的指令。
 *
 * <p>不用固定距离或自动选址器：是否安全可用、是否可达必须由已有观测与现场核对，
 * 缺证保持未知。本类不读世界、不登记资产、不改任务，也不把提示当选址已获证明。
 * 只在安家相关目标/当前步骤命中时注入；仍复用既有建造与睡眠工具。
 */
final class RddBasePlaybook {

    private static final String[] TRIGGERS = {
            "基地", "安家", "安身", "据点", "房子", "屋子", "核心屋", "建家", "落脚", "重生点", "床"
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
        return "<base_playbook>基地选址依托选定村庄，便于利用已有农田和村民交易；不搬迁已可用基地。"
                + "① 先实查选定村庄的村屋、床房、储物和农田是否安全可用；未观测不等于没有，"
                + "村庄与基地的关系缺证时保持未知，不能仅凭当前站位判断家在村内或村外。"
                + "② 优先复用安全可用的较大村屋作为核心屋，利用已有床房与储物；"
                + "已有农田可用时不另建农田，收获后补种，不强占不适合使用的房屋。"
                + "③ 只有实际确认没有合适房屋，才在村内或村旁选择能安全往返村民与已有农田的可达空地补建核心屋；"
                + "避开水和悬崖，按地形需要用 settlement_platform 出整平蓝图，floor_block 用背包里真有的方块，"
                + "再拿返回的 anchor 执行 blueprint action=build；不要为找空地脱离村庄另起独立营地。"
                + "④ 无论复用还是补建，都核对入口、照明与夜间安全，按缺口补火把；"
                + "保留核心屋内的床，用 sleep 绑定并核对实际重生点，不拆已绑定的床；"
                + "需要时补箱子与合成台，把备用装备和食物实际存入箱子，按真实存货核对补给，不把计划当已有备用。"
                + "</base_playbook>";
    }
}
