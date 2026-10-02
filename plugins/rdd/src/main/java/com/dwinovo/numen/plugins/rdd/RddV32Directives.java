package com.dwinovo.numen.plugins.rdd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RDD v3.2 \u7b2c\u4e00\u6279\u6536\u5c3e\u7684\u5168\u91cf\u6ce8\u5165\u6587\u6848\uff08\u4e34\u65f6\u65b9\u6848\uff0c\u6cbb\u6807\uff1b\u4e8c\u6279\u6cbb\u672c\uff09\u3002
 *
 * <p>\u80cc\u666f\uff08\u7528\u6237 2026-09-26 \u624b\u5199\u7b14\u8bb0\uff09\uff1a\u7b2c\u4e00\u6279\u6ca1\u6709\u7b2c\u4e8c\u6279\u8bb0\u5fc6\u5e93\uff0cNumen \u6267\u884c\u529b\u5dee\u3001
 * RDD \u89c4\u5212\u4e0d\u884c\uff0c\u6545\u91c7\u7528\u300c\u52a8\u6001\u5168\u91cf\u6ce8\u5165\u5df2\u9a8c\u8bc1\u53ef\u884c\u7684\u4e34\u65f6\u65b9\u6848\u300d\u4fdd\u8bc1\u4efb\u52a1\u5b9e\u73b0\u3002\u672c\u7c7b\u628a
 * \u7528\u6237\u53e3\u8ff0\u7684\u4e24\u4efd\u6587\u6848\u96c6\u4e2d\u5728\u6b64\uff0c\u63a5\u5230\u65e2\u6709\u6ce8\u5165\u901a\u9053\uff1a
 * <ul>
 *   <li>{@link #annexTaskSpec()} \u7ed9 Supervisor/\u89c4\u5212\u5c42\u7684\u4efb\u52a1\u8981\u6c42\uff08\u5b89\u8eab\u4efb\u52a1\u94fe\uff0c
 *       \u66ff\u4ee3"\u901a\u5173MC"\u4f5c\u4e3a\u7b2c\u4e00\u6279\u9a8c\u6536\u76ee\u6807\uff09\uff0c\u63a5\u5230 {@link RddStagePlanner} / {@link RddDecomposer} \u7684 prompt\u3002</li>
 *   <li>{@link #harnessHints()} \u7ed9 Numen \u6267\u884c\u4f53\u7684\u64cd\u4f5c\u63d0\u793a\uff08\u5b9e\u6d4b\u6536\u96c6\u7684\u6267\u884c\u95ee\u9898\uff0c\u6cbb\u6807\uff09\uff0c
 *       \u63a5\u5230\u6267\u884c AI \u4e0a\u4e0b\u6587\uff08{@code attachHarness}\uff09\u3002</li>
 * </ul>
 *
 * <p>\u7eaa\u5f8b\uff1a\u7eaf\u6587\u672c\u5e38\u91cf\uff0c\u4e0d\u78b0 Minecraft\uff1b\u4e0d\u6539\u4efb\u52a1\u94fe\u72b6\u6001\u673a\uff1b\u7ea2\u7ebf\u4e0d\u52a8\u3002\u6587\u6848\u968f\u7528\u6237\u53e3\u4ee4\u6f14\u8fdb\uff0c
 * \u6539\u8fd9\u91cc\u5373\u53ef\uff08\u4e0d\u6563\u843d\u5728\u5404\u5904\uff09\u3002
 */
final class RddV32Directives {

    private RddV32Directives() {}

    /** \u7b2c\u4e00\u6279\u9a8c\u6536\u76ee\u6807\uff1a\u5b89\u987f\u81ea\u5df1\uff08\u505a\u597d\u8fdb\u5730\u72f1\u524d\u7684\u51c6\u5907\uff09\uff0c\u800c\u975e\u901a\u5173 MC\u3002 */
    static final String ANNEX_OBJECTIVE =
            "\u5728\u65b0\u4e16\u754c\u5b89\u987f\u81ea\u5df1\uff1a\u505a\u597d\u8fdb\u5165\u4e0b\u754c\u4e4b\u524d\u7684\u5168\u90e8\u51c6\u5907\uff08\u77f3\u5668\u2192\u98df\u7269\u2192\u6751\u5e84\u5e72\u8349\u2192\u94c1\u5957\u2192\u4e34\u65f6\u636e\u70b9\u2192"
                    + "\u7ed1\u5b9a\u91cd\u751f\u5e8a\u2192\u9c7c\u9aa8\u6316\u77ff\u81f3\u94bb\u77f3\u5c42\u2192\u4e24\u5957\u94bb\u77f3\u5957\uff08\u5b58\u4e00\u5957\uff09\u219214 \u9ed1\u66dc\u77f3\u2192\u9644\u9b54\u53f0+\u5168\u5957\u4fdd\u62a4IV\u2192"
                    + "\u5c0f\u578b\u5237\u602a\u673a\u5237\u7ecf\u9a8c\u2192\u5728\u5237\u602a\u673a\u65c1\u5efa\u6700\u7ec8\u57fa\u5730\uff08\u5e8a/\u5730\u72f1\u95e8/\u9644\u9b54\u53f0/\u7bb1\u5b50/\u5de5\u4f5c\u65b9\u5757\uff09\u2192"
                    + "\u5b58\u597d\u94c1\u5957\u4e0e\u94bb\u5957\u5404\u4e00\u5957\u5e76\u6807\u8bb0\u2192\u706b\u628a\u9632\u5237\u602a\u3002\u6700\u7ec8\u5f85\u547d\uff0c\u4e0d\u8fdb\u5730\u72f1\u3002";

    /**
     * \u2460 \u7ed9 Supervisor / \u89c4\u5212\u5c42\u7684\u4efb\u52a1\u8981\u6c42\uff08\u7528\u6237\u9875\u4e00\u2460\uff09\u3002
     * \u8fd9\u662f"\u5b89\u8eab\u4efb\u52a1"\u7684\u56fa\u5b9a\u6d41\u7a0b\u4e0e\u4f9d\u8d56\u5e8f\uff0c\u6ce8\u5165\u89c4\u5212 prompt\uff0c\u5f15\u5bfc StagePlanner/Decomposer \u6309\u6b64\u5c55\u5f00\u3002
     */
    static String annexTaskSpec() {
        return "\u3010\u7b2c\u4e00\u6279\u5b89\u8eab\u4efb\u52a1\u00b7\u56fa\u5b9a\u6d41\u7a0b\u8981\u6c42\uff08\u6309\u4f9d\u8d56\u5e8f\uff0c\u66ff\u4ee3\u901a\u5173MC\u4f5c\u4e3a\u672c\u6b21\u9a8c\u6536\uff09\u3011\n"
                + "1) \u5148\u505a\u57fa\u7840\u77f3\u88c5\u5907\uff08\u77f3\u65a7/\u77f3\u9550\uff09\uff0c\u4e0d\u6025\u7740\u8fdb\u6d1e\u3002\n"
                + "2) \u6740\u51e0\u53ea\u725b\u7f8a\u5403\uff0c\u5148\u53bb\u6751\u5e84\u62ff\u6389\u5168\u90e8\u5e72\u8349\u5757\u2192\u505a\u6210\u98df\u7269\uff08\u6b64\u540e\u4e0d\u7f3a\u5403\u7684\uff09\u3002\n"
                + "3) \u6316\u94c1\u505a\u4e00\u5957\u94c1\u5957\uff0c\u5e76\u591a\u6316 20~30 \u94c1\u5b58\u4e3a\u5907\u7528\u94c1\u88c5\u5907\u3002\n"
                + "4) \u5efa\u4e34\u65f6\u636e\u70b9\uff08\u6751\u6c11\u623f\u5b50\u91cc\uff09\uff0c\u5b58\u653e\u7528\u98df\u7269\u4e0e\u5907\u7528\u94c1\u88c5\u5907\u3002\n"
                + "5) \u4e0b\u77ff\u524d\u5148\u62a2\u5360\u6751\u6c11\u5e8a\u5e76\u53f3\u952e\u7ed1\u5b9a\u51fa\u751f\u70b9\uff08\u91cd\u751f\u70b9\uff09\u3002\n"
                + "6) \u9c7c\u9aa8\u6316\u77ff\uff1a\u4e00\u6bb5\u6bb5\u6316\u3001\u9632\u6709\u6c34\u3001\u4e0d\u8fdb\u6d1e\u7a74\uff1b\u4e00\u76f4\u5782\u76f4\u4e0b\u6316\u5230\u94bb\u77f3\u5c42\u3002\n"
                + "7) \u6316\u94bb\u77f3\u505a\u4e24\u5957\u94bb\u77f3\u5957\uff0c\u5148\u653e\u4e00\u5957\u56de\u57fa\u5730\u3002\n"
                + "8) \u6316\u9ed1\u66dc\u77f3 14 \u4e2a\uff08\u505a\u9644\u9b54\u53f0\u548c\u5730\u72f1\u95e8\u4e24\u8005\uff09\u3002\n"
                + "9) \u56de\u53bb\u5148\u9644\u5168\u5957\u4fdd\u62a4 IV\uff08\u7ecf\u9a8c\u4e0d\u591f\u5c31\u53bb\u5237\u602a\uff09\u3002\n"
                + "10) \u627e\u5237\u602a\u7b3c\uff0c\u7528\u706b\u628a\u5c01\u5370\u540e\u4fee\u6210\u5c0f\u578b\u5237\u602a\u673a\uff08\u6c34\u6d41\u805a\u602a\uff09\uff1b\u5728\u5237\u602a\u673a\u65c1\u5efa\u6700\u7ec8\u57fa\u5730\uff1a\n"
                + "    \u5e8a\u5b9a\u51fa\u751f\u70b9\u3001\u5730\u72f1\u95e8\u5728\u65c1\u8fb9\u3001\u653e\u4e0b\u9644\u9b54\u53f0/\u7bb1\u5b50/\u5de5\u4f5c\u65b9\u5757\u3002\n"
                + "11) \u5f04\u4e24\u5957\u5168\u5957\u4fdd\u62a4 IV \u94bb\u5957\u548c\u5de5\u5177\uff08\u542b\u950b\u5229\uff09\u3001\u76fe\u724c\uff08\u4fdd\u547d\uff0c\u522b\u4e0d\u5e26\uff09\u3002\n"
                + "12) \u628a\u94c1\u5957\u548c\u94bb\u5957\u5404\u4e00\u5957\u5b58\u8fdb\u7bb1\u5b50\u5e76\u6807\u8bb0\uff1b\u8bb0\u5f97\u70b9\u706b\u628a\u9632\u57fa\u5730\u5237\u602a\u3002\u81f3\u6b64\u5b8c\u6210\uff0c\u5f85\u547d\u4e0d\u8fdb\u5730\u72f1\u3002\n"
                + "\u3010\u4f9d\u8d56\u94c1\u5f8b\u3011\u5e72\u8349\u2192\u98df\u7269 \u5148\u4e8e\u8fdc\u5f81\uff1b\u94c1\u5957+\u5b58\u94c1 \u5148\u4e8e\u94bb\u77f3\uff1b\u94bb\u77f3\u5b58\u4e00\u5957 \u5148\u4e8e\u6316\u9ed1\u66dc\u77f3\uff1b"
                + "\u9ed1\u66dc\u77f3+\u94bb\u77f3+\u4e66 \u5148\u4e8e\u9644\u9b54\u53f0\uff1b\u5e8a\u7ed1\u5b9a \u5148\u4e8e\u9ad8\u98ce\u9669\u5916\u51fa\uff1b\u5237\u602a\u7b3c\u5c01\u5370 \u5148\u4e8e\u6539\u9020\u5237\u602a\u573a\u3002";
    }

    /**
     * \u2461 \u7ed9 Numen \u6267\u884c\u4f53\u7684\u64cd\u4f5c\u63d0\u793a\uff08\u7528\u6237\u9875\u4e8c\u2461\uff0c\u5168\u91cf\u6ce8\u5165\uff0c\u6cbb\u6807\uff09\u3002
     * 11 \u6761\u5747\u4e3a\u5b9e\u6d4b\u6536\u96c6\u7684\u6267\u884c\u95ee\u9898\u4e0e\u6b63\u786e\u505a\u6cd5\u3002
     */
    static String harnessHints() {
        return harnessHints(null);
    }

    /**
     * 按任务描述筛选后的执行提示。{@code task} 为 null/空 -> 只发无条件那几条。
     *
     * <p>2026-10-01 从「恒定全量注入」改成「按当前任务关键词筛选」：实机请求体实测这 11 条
     * 实占约 500 token/请求，且每轮原样重发，哪怕当前在挖矿、跟战斗毫无关系——本文件原注释
     * 自己写的就是「全量注入，治标」。现在只发「无条件该知道的」+「命中当前任务描述的」。
     *
     * <p><b>已知代价（写清楚，不藏着）</b>：关键词匹配是字面的，模型用隐喻描述任务时可能命中
     * 不到，那时只剩无条件那几条。它治标——真正的护栏是工具自己的报错文案（见 ToolArgs /
     * ToolCallLoopWatch），不是这 11 条。
     */
    static String harnessHints(String task) {
        List<String> lines = new ArrayList<>();
        for (Hint h : HINTS) {
            if (h.keywords == null || matches(task, h.keywords)) {
                lines.add(h.text);
            }
        }
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【执行提示·按当前任务挑选（照做，治标）】\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(") ").append(lines.get(i));
            sb.append(i == lines.size() - 1 ? "" : "\n");
        }
        return sb.toString();
    }

    private static boolean matches(String task, String[] keywords) {
        if (task == null || task.isBlank()) return false;
        String lower = task.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    /** 一条执行提示。{@code keywords == null} = 无条件，每轮都发。 */
    private record Hint(String text, String[] keywords) {}

    private static final Hint[] HINTS = {
            new Hint("保护床和刷怪笼：不要把它们挖掉（床=重生点，刷怪笼=刷怪机核心）。", null),
            new Hint("背包满了要及时清理或把多余东西放回基地箱子，否则捡不起新掉落物。",
                    new String[] {"捡", "背包", "掉落", "箱子", "满", "拾"}),
            new Hint("多余的物品也是有用物资，别乱丢（可能正是后面要的原料）。",
                    new String[] {"扔", "丢", "清理", "仓库", "存", "material"}),
            new Hint("不要让自己缺血/饿到发虚，及时吃满（食物充足就吃满，别省）。",
                    new String[] {"食物", "吃", "饥饿", "血", "血量", "生命", "战斗", "怪", "刷怪"}),
            new Hint("战斗优先举盾防御（盾很重要，能保命），别只用剑莽；遇怪要修墙推进，别硬冲。",
                    new String[] {"战斗", "打", "杀", "怪", "僵尸", "刷怪", "守卫", "攻击", "护甲"}),
            new Hint("刷怪机靠水流聚怪；不插火把抑制刷怪（火把是给基地防刷怪用的，别插到刷怪机里）。",
                    new String[] {"刷怪", "刷怪笼", "刷怪机", "水流", "水", "火把", "聚怪"}),
            new Hint("挖黑曜石/钻石要封闭着安全挖：先做鱼骨通道、分段推进、小心地下水；遇到水先试一下（能堵就堵，不能就换段）。",
                    new String[] {"黑曜石", "钻石", "挖", "矿", "鱼骨", "采"}),
            new Hint("挖自己的方块时若放了水挡路/挡住了掉落：先堵住水源（用方块填源头），再去拾取掉落物；挖不掉就先清障再挖。",
                    new String[] {"挖", "水", "掉落", "挡住", "堵", "清障"}),
            new Hint("寻路/搭方块时别把自己垫脚的方块收掉（会摔死/掉下去）；高空下落可用方块或落地水缓冲。",
                    new String[] {"搭", "建", "塔", "柱", "垫", "下落", "摔", "路"}),
            new Hint("知道右键床可绑定出生点（重生点），进高风险前先绑定。",
                    new String[] {"重生", "床", "死亡", "spawn", "基地"}),
    };

    /** \u628a\u6267\u884c\u63d0\u793a\u62fc\u5230\u4efb\u4e00\u6ce8\u5165\u6587\u672c\u5c3e\u90e8\uff08\u7a7a\u6587\u672c/\u63d0\u793a\u672c\u8eab\u4e3a\u7a7a\u65f6\u539f\u6837\u8fd4\u56de\uff09\u3002 */
    static String attachHarness(String text) {
        String hints = harnessHints();
        if (hints == null || hints.isBlank()) {
            return text == null ? "" : text;
        }
        if (text == null || text.isBlank()) {
            return hints;
        }
        return text + "\n\n" + hints;
    }
}
