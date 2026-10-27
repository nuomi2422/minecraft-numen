package com.dwinovo.numen.plugins.rdd;

/**
 * 「下矿流程」提示（2026-10-27，goal 第 13 轮）：把已经存在的工具按顺序串成一套下矿姿势。
 *
 * <h2>为什么需要（死亡数据指的方向）</h2>
 * `exp-session-stats` 扫最近 3 天 52 次死亡：**mob 41 / unknown 8 / fall 1 / suffocate 1 / drown 1**。
 * 再看死前一刻在做什么：`eat`、`rdd_get_inventory`、`task_status`、`rdd_whereami`、`goto`。
 * 也就是说——她不是不会挖矿，是**没有"下矿前该带什么、什么时候该撤"的固定姿势**：
 * 空着水桶下矿（岩浆）、垂直往下挖（窒息/摔）、夜里还在洞口转（骷髅）。
 *
 * <h2>边界（与既有纪律一致）</h2>
 * <ul>
 *   <li>**不新增工具、不新增门禁**：水桶/垫脚方块/火把/撤退都在现有 build/goto/attack 能力里；</li>
 *   <li><b>只给顺序与判据</b>，不写具体坐标、不写"挖 N 个"（实况会打脸，且会盖过经验库路线）；</li>
 *   <li>只在当前目标/阶段谈"挖矿/矿洞/钻石/铁/下矿"时注入，避免每轮占 token；</li>
 *   <li>纯文本、可单测（不读世界）。</li>
 * </ul>
 */
final class RddMinePlaybook {

    private static final String[] TRIGGERS = {
            "挖矿", "下矿", "矿洞", "矿道", "钻石", "铁矿", "挖铁", "矿脉", "煤", "挖石", "采石", "深层"
    };

    private RddMinePlaybook() {}

    /** 这段话是否在谈下矿。 */
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
        return "<mine_playbook>下矿前先把这四件事做完，比多带一把镐有用："
                + "① **带水桶**（有水就不怕岩浆：倒水能把岩浆变黑曜石/石头站上去），没有水桶就别往岩浆层去；"
                + "② **带垫脚方块**（土/圆石一叠）—— 遇到垂直落差用垫脚上下，别直接跳；"
                + "③ **不垂直往下挖**：一格一格斜着开、或先挖两格看脚下，垂直挖穿岩浆/洞穴是摔死与窒息的主因；"
                + "④ **夜里不下矿**：天黑了先在洞口或基地待着（有床就 sleep 过夜），白天再下——"
                + "最近 41 次死亡里绝大多数死在怪手里，而洞里遇怪没有退路。"
                + "⑤ 在洞里每隔几格插火把（build），既不刷怪也认得来路；听到怪先处理或往上撤，"
                + "不要边查背包边被围住。"
                + "</mine_playbook>";
    }
}
