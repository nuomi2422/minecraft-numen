package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.policy.FutureNeedScanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「后面几级要用的东西，现在手上/身边有没有」的计时扫描（2026-10-28，主人第六点）。
 *
 * <h2>为什么不是一次性算完</h2>
 * 一级清单确实是一次生成的（Stage-A 给 2~12 个），但每级的 {@code wait_for} 只在**那级激活时**
 * 才被依赖门读。于是「第三级要 10 个黑曜石，而她此刻正站在废墟门边上」这种机会，
 * 没有任何东西会告诉她 —— 这一层就是那台"偶尔扫一下"的计时器（跟随 30 秒的资产拍）。
 *
 * <h2>它做什么 / 不做什么</h2>
 * <ul>
 *   <li>做：扫背包 + 身边 8 格容器，对照后面几级的 {@code wait_for}，把结果</li>
 *   <li>做：集合变化时发一条 {@code future_need_spotted}（不是每拍刷，防事件洪水）</li>
 *   <li>做：渲染一块 {@code <future_need>} 进执行侧上下文（她看得到"够了/还差多少"）</li>
 *   <li><b>不做</b>：改链、判"提前完成"、自己决定去不去拿 —— 主人的口径是"通知规划者，
 *       要不要拿回来是规划侧判断"。这里只陈述事实。</li>
 * </ul>
 *
 * <p>读不到（没扫过容器）就不写「没有」：{@link FutureNeedScanner} 只报"看见"的，
 * 没看见的不进列表 —— 未知 ≠ 零（IC04 同款纪律）。
 */
final class RddFutureNeeds {

    /** 每个同伴渲染好的 {@code <future_need>} 块（只读缓存，上下文构建可能在非主线程）。 */
    private static final Map<UUID, String> BLOCK = new ConcurrentHashMap<>();
    /** 上一次发事件时的集合签名：只有"看见的东西变了"才发事件，避免 30 秒一条洪水。 */
    private static final Map<UUID, String> SIGNATURE = new ConcurrentHashMap<>();

    private RddFutureNeeds() {}

    /**
     * 每 30 秒一拍（跟 {@code RddDetector} 的资产拍同频）。
     *
     * @param inventory 本拍真实背包计数（唯一真相，别拿注册表缓存代替）
     */
    static void tick(UUID companionId, TaskChain chain, Map<String, Integer> inventory) {
        try {
            if (companionId == null || chain == null) {
                return;
            }
            List<PrimaryGoal> future = futurePrimaries(chain);
            if (future.isEmpty()) {
                BLOCK.remove(companionId);
                SIGNATURE.remove(companionId);
                return;
            }
            Map<String, Integer> nearby = RddContainerMemory.parseSlotLines(
                    RddContainerMemory.cached(companionId));
            List<FutureNeedScanner.Sighting> hits = FutureNeedScanner.scan(future, inventory, nearby);
            if (hits.isEmpty()) {
                BLOCK.remove(companionId);
                SIGNATURE.remove(companionId);
                return;
            }
            BLOCK.put(companionId, render(hits));
            String sig = signature(hits);
            if (!sig.equals(SIGNATURE.put(companionId, sig))) {
                publish(companionId, hits);
            }
        } catch (RuntimeException ignored) {
            // 观测增强：任何异常都不许拖垮检测 tick（同 RddContainerMemory / RddCarryHint 的纪律）
        }
    }

    /** 执行侧上下文块；没有"看见"时返回空串（不写空标签）。 */
    static String block(UUID companionId) {
        return companionId == null ? "" : BLOCK.getOrDefault(companionId, "");
    }

    static void clear(UUID companionId) {
        if (companionId == null) {
            return;
        }
        BLOCK.remove(companionId);
        SIGNATURE.remove(companionId);
    }

    static void clearAll() {
        BLOCK.clear();
        SIGNATURE.clear();
    }

    /** 当前一级之后的一级（顺序即先后）。 */
    static List<PrimaryGoal> futurePrimaries(TaskChain chain) {
        List<PrimaryGoal> all = chain.goal().primaryGoals();
        PrimaryGoal current = chain.currentPrimary();
        int idx = all.indexOf(current);
        if (idx < 0 || idx + 1 >= all.size()) {
            return List.of();
        }
        return List.copyOf(all.subList(idx + 1, all.size()));
    }

    private static String signature(List<FutureNeedScanner.Sighting> hits) {
        StringBuilder sb = new StringBuilder();
        for (FutureNeedScanner.Sighting h : hits) {
            sb.append(h.primaryId()).append('|').append(h.assetKey()).append('|')
                    .append(h.held()).append('|').append(h.where()).append(';');
        }
        return sb.toString();
    }

    private static void publish(UUID companionId, List<FutureNeedScanner.Sighting> hits) {
        long enough = hits.stream().filter(FutureNeedScanner.Sighting::enough).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("count", hits.size());
        data.put("enough", enough);
        List<String> lines = new ArrayList<>();
        for (FutureNeedScanner.Sighting h : hits) {
            lines.add(h.assetKey() + " ×" + h.held() + "/" + h.needed()
                    + (h.enough() ? " 够" : " 不够")
                    + (h.where() == FutureNeedScanner.Where.NEARBY ? " 附近容器" : " 背包")
                    + " → " + h.theme());
        }
        data.put("items", lines);
        RddMonitor.publish("future_need_spotted", data);
    }

    /**
     * 渲染进上下文的那一块。
     *
     * <p>刻意写成"事实 + 一句可选的建议"，不写成命令 —— 与 {@code <carry>}/{@code <danger>} 同口径：
     * 路怎么走是她的事，链只负责让她知道"后面要用的东西现在就在手边"。
     */
    private static String render(List<FutureNeedScanner.Sighting> hits) {
        StringBuilder sb = new StringBuilder("<future_need>后面几级要用的东西，现在就能看见这些：");
        int shown = 0;
        for (FutureNeedScanner.Sighting h : hits) {
            if (shown >= 4) {
                break;
            }
            if (shown > 0) {
                sb.append('；');
            }
            sb.append(h.assetKey()).append(" ×").append(h.held()).append('/').append(h.needed())
                    .append(h.enough() ? "（够）" : "（还差）")
                    .append(h.where() == FutureNeedScanner.Where.NEARBY ? "［附近容器］" : "［背包］");
            shown++;
        }
        if (hits.size() > shown) {
            sb.append("；另有 ").append(hits.size() - shown).append(" 项");
        }
        sb.append("。顺手拿到的东西不用再专门跑一趟；够不够、要不要现在补，按现场判断。");
        sb.append("</future_need>");
        return sb.toString();
    }
}
