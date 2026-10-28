package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.CapabilityWatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「刚拿到能改变计划的东西」的 30 秒监视（2026-10-28，主人第六点后半）。
 *
 * <h2>它是"简易的、依据实际状态而变化的规划"的<b>扳机</b>，不是规划本身</h2>
 * 主人原话：「这就相当于一个简易的、依据实际状态而变化的规划」。
 * 所以这里只做三件很轻的事：
 * <ol>
 *   <li>盯背包差分：<b>从无到有</b>才叫（量变不叫）；</li>
 *   <li>把"这是什么/能干什么/可替代什么"（语义表）连同事件一起发出去；</li>
 *   <li>拍她一下，把三个选项摆出来：<b>收口某一级 / 重写后面的路 / 什么都不做</b>。</li>
 * </ol>
 * <b>不判断、不改链、不替她决定</b> —— 那是 {@code rdd_settle_stage} / {@code rdd_replan_chain} 的使用者（规划者）的事。
 *
 * <p>读的是本拍真实背包 counts（与资产拍同频），语义表来自 {@link RddSemanticsStore}
 * （由需求清单派生，不凭空发明）。
 */
final class RddCapabilityWatch {

    /** 上一次的真实背包（只要基线，不落盘：重启后第一拍重建基线即可）。 */
    private static final Map<UUID, Map<String, Integer>> LAST = new ConcurrentHashMap<>();

    private RddCapabilityWatch() {}

    static void tick(UUID companionId, Map<String, Integer> counts) {
        try {
            if (companionId == null) {
                return;
            }
            Map<String, Integer> now = counts == null ? Map.of() : Map.copyOf(counts);
            Map<String, Integer> before = LAST.put(companionId, now);
            if (before == null) {
                // 第一拍只建立基线：否则"当前整包库存"会被当成"刚到手"，一次叫醒里塞十几件东西。
                return;
            }
            List<CapabilityWatch.Upgrade> ups = CapabilityWatch.newlyObtained(
                    before, now, RddSemanticsStore.semantics(companionId));
            if (ups.isEmpty()) {
                return;
            }
            publish(companionId, ups);
            wake(companionId, ups);
        } catch (RuntimeException ignored) {
            // 观测增强：任何异常都不许拖垮检测 tick
        }
    }

    static void clear(UUID companionId) {
        if (companionId != null) {
            LAST.remove(companionId);
        }
    }

    static void clearAll() {
        LAST.clear();
    }

    private static void publish(UUID companionId, List<CapabilityWatch.Upgrade> ups) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("count", ups.size());
        List<String> items = new ArrayList<>();
        for (CapabilityWatch.Upgrade u : ups) {
            items.add(u.render());
        }
        data.put("items", items);
        RddMonitor.publish("capability_upgrade_spotted", data);
    }

    /**
     * 拍醒一次：把"三个选项"写清楚，且明确"什么都不做"也是合法选择 ——
     * 这条与 {@code <carry>}/{@code <danger>} 同口径：只提醒，不派活。
     *
     * <p>节流交给 {@link RddPlugin#nudge}（3 分钟 + 需要监督开关），这里不再加一层。
     */
    private static void wake(UUID companionId, List<CapabilityWatch.Upgrade> ups) {
        StringBuilder sb = new StringBuilder("[能力变化] 你刚拿到：");
        int shown = 0;
        for (CapabilityWatch.Upgrade u : ups) {
            if (shown >= 3) {
                break;
            }
            if (shown > 0) {
                sb.append("；");
            }
            sb.append(u.render());
            shown++;
        }
        sb.append("。这可能让计划里某一步变得没有意义（比如钻石镐能挖黑曜石 → 石镐/铁镐那一级不必走），")
          .append("也可能只是换了个更省的做法（有钻石镐但缺铁 → 直接用钻石镐挖铁，跳过石镐/铁镐的顺序）。")
          .append("怎么处理由你判断：① 某一步不用做了 → rdd_settle_stage（写清理由）；")
          .append("② 后面的路整体不成立 → rdd_replan_chain；③ 照旧继续 → 什么都不用做。");
        RddPlugin.nudge(companionId, sb.toString());
    }
}
