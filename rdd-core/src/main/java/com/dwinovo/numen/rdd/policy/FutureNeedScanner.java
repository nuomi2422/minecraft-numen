package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「路上顺手看见后面要用的东西」扫描器（2026-10-28，主人第六点）。
 *
 * <h2>它回答什么</h2>
 * 一级清单是一次生成的（Stage-A 就给 2~12 个），但每一级的 {@code wait_for} 只有在那级
 * <b>激活时</b>才被依赖门读（{@code TaskChain.dependencyGateSatisfied}）。于是出现一个空档：
 * 她第三级才需要 10 个黑曜石，可她现在就在废墟门边上 —— 没人告诉她"顺手拿"，
 * 也没有任何东西把"后面要用的东西已经到手了"这件事记下来。
 *
 * <h2>它不做什么（刻意）</h2>
 * <ul>
 *   <li><b>不改任何状态</b>：纯函数，只产"看见了什么"；改不改链是规划器/宿主的决定
 *       （主人的口径：「通知规划者…我要不要拿回来，是规划侧判断」）。</li>
 *   <li><b>不判"提前完成"</b>：{"拿到核心物 = 目标达成"} 需要一个只有主人能定的口径，
 *       这里只把"够/不够"如实报出来，不替它下结论。</li>
 *   <li><b>不猜</b>：读不到背包就传空表，调用方必须自己区分 UNKNOWN 与"确实没有"
 *       （IC04 同款纪律：未知 ≠ 零）。</li>
 * </ul>
 *
 * <p>纯 JVM、无 MC 依赖，可离线单测。
 */
public final class FutureNeedScanner {

    /** 一次扫描最多报几条：够规划器看清趋势，又不至于把上下文撑爆。 */
    public static final int MAX_SIGHTINGS = 12;

    /** 东西在哪看见的。{@code INVENTORY} 优先于 {@code NEARBY}（同一物品只报一次）。 */
    public enum Where {
        /** 已经在背包里（含副手/身上）。 */
        INVENTORY,
        /** 在附近容器里（宿主只扫身边 8 格，与 {@code RddContainerMemory} 同口径）。 */
        NEARBY
    }

    /**
     * 一条"看见"。
     *
     * @param primaryId    她后面要走的那一级
     * @param theme        那一级的主题（人话，直接进提示词）
     * @param assetKey     看见的物品
     * @param needed       那级的前置要求数量（{@code wait_for.minimum}）
     * @param held         实际看见多少
     * @param where        在背包还是在附近容器
     * @param enough       是否已够那一级的门槛（{@code held >= needed}）
     */
    public record Sighting(String primaryId, String theme, String assetKey,
                           int needed, int held, Where where, boolean enough) {
        public Sighting {
            primaryId = primaryId == null ? "" : primaryId;
            theme = theme == null ? "" : theme;
            assetKey = assetKey == null ? "" : assetKey;
        }

        /** 一行渲染（宿主发事件/进提示词都用它，格式统一）。 */
        public String render() {
            return assetKey + " ×" + held + "/" + needed
                    + (enough ? "（够）" : "（不够）")
                    + (where == Where.NEARBY ? "［附近容器］" : "［背包］");
        }
    }

    private FutureNeedScanner() {}

    /**
     * 扫描"后面几级要用的东西，现在手上/身边有没有"。
     *
     * <p>只报 {@code assetKey} 形状合法的条目（{@code wait_for} 在规划期已被过滤过一次，
     * 这里再挡一次：脏键进提示词只会制造噪音）。
     *
     * @param futurePrimaries 当前一级<b>之后</b>的一级（顺序即先后；空 → 无"后面"可谈）
     * @param inventory       背包真实计数（读不到请传空表，并在调用侧标注"未观测"）
     * @param nearby          身边容器里的计数（同上）
     * @return 有序、去重、有界（≤ {@link #MAX_SIGHTINGS}）的看见列表
     */
    public static List<Sighting> scan(List<PrimaryGoal> futurePrimaries,
                                      Map<String, Integer> inventory,
                                      Map<String, Integer> nearby) {
        if (futurePrimaries == null || futurePrimaries.isEmpty()) {
            return List.of();
        }
        List<Sighting> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PrimaryGoal primary : futurePrimaries) {
            if (primary == null || out.size() >= MAX_SIGHTINGS) {
                continue;
            }
            for (AssetRequirement req : primary.waitFor()) {
                if (out.size() >= MAX_SIGHTINGS) {
                    break;
                }
                if (req == null || req.assetKey() == null || req.assetKey().isBlank()) {
                    continue;
                }
                int needed = Math.max(1, req.minimum());
                int inBag = count(inventory, req.assetKey());
                int around = count(nearby, req.assetKey());
                Where where;
                int held;
                if (inBag > 0) {
                    where = Where.INVENTORY;
                    held = inBag;
                } else if (around > 0) {
                    where = Where.NEARBY;
                    held = around;
                } else {
                    continue;
                }
                if (!seen.add(primary.id() + "|" + req.assetKey())) {
                    continue;
                }
                out.add(new Sighting(primary.id(), primary.description(), req.assetKey(),
                        needed, held, where, held >= needed));
            }
        }
        return List.copyOf(out);
    }

    /** 精确键计数；读不到（null / 缺键）一律 0 —— 但调用侧要知道这是"没看见"，不是"世界上没有"。 */
    private static int count(Map<String, Integer> counts, String key) {
        if (counts == null || key == null) {
            return 0;
        }
        Integer v = counts.get(key);
        return v == null ? 0 : Math.max(0, v);
    }
}
