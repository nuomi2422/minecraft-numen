package com.dwinovo.numen.plugins.rdd;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 空转检测的状态机（纯逻辑，可单测）。
 *
 * <p>跟踪「当前二级 + 起始时刻 + 起始资产数」；同一个二级持续超过 {@link RddStuckText#STUCK_SECONDS}
 * 且资产数没变，就返回提示块。二级一变就重置 —— 换了步骤不算空转。
 *
 * <p>2026-10-28 追加：把**条件类型**也传进来 —— 因为空间型判据（`block_nearby` / `position_at`）
 * 失败的最常见原因不是"没干活"，而是"**人不在判据要求的位置**"（实机：判据要"附近有黑曜石半径16"，
 * 她人在 200 格外的村庄）。这种情况下给的必须是"走过去"，不是"动手挖"。
 */
final class RddStuckWatch {

    private static final class Mark {
        final String subtaskId;
        final long startMs;
        final int assetsAtStart;

        Mark(String subtaskId, long startMs, int assetsAtStart) {
            this.subtaskId = subtaskId;
            this.startMs = startMs;
            this.assetsAtStart = assetsAtStart;
        }
    }

    private final Map<UUID, Mark> marks = new HashMap<>();

    /** 2026-10-28：跨步骤的"毫无产出"看门狗（步骤级计时会被频繁重规划重置，见下方注释）。 */
    private static final class Progress {
        int lastAssets;
        long lastChangeMs;

        Progress(int lastAssets, long lastChangeMs) {
            this.lastAssets = lastAssets;
            this.lastChangeMs = lastChangeMs;
        }
    }

    private final Map<UUID, Progress> progress = new HashMap<>();

    /** 背包连续这么多秒没有任何变化 → 认定"整条链在原地"（不是单个步骤的空转）。 */
    static final int NO_PROGRESS_SECONDS = 600;

    /** @deprecated 用带 condType 的重载；保留是为了不破坏旧调用点。 */
    @Deprecated
    String tick(UUID companion, String subtaskId, int assets, long nowMs, String stepDesc) {
        return tick(companion, subtaskId, assets, nowMs, stepDesc, "");
    }

    /**
     * @param companion 同伴
     * @param subtaskId 当前二级 id（为空则清掉状态）
     * @param assets    当前资产数
     * @param nowMs     当前时刻
     * @param stepDesc  二级描述（生成对症动作）
     * @param condType  条件类型（空/未知则按步骤文字给建议）
     * @return 提示块；不该提示时返回空串
     */
    String tick(UUID companion, String subtaskId, int assets, long nowMs, String stepDesc, String condType) {
        if (companion == null) return "";
        // ── 跨步骤看门狗 ────────────────────────────────────────────────
        // 实机教训（2026-10-28）：链每 2~4 分钟就重规划一次（r5→r11 一路换步），
        // 于是**步骤级的计时永远攒不满 5 分钟**，stuck_hint 一次都没触发 ——
        // 而她这半小时背包始终 157~159 件，等于"整条链在原地"。
        // 所以判定必须挂在**背包有没有变化**上，而不是挂在某一步上。
        Progress pg = progress.get(companion);
        if (pg == null) {
            progress.put(companion, new Progress(assets, nowMs));
        } else if (assets != pg.lastAssets) {
            pg.lastAssets = assets;
            pg.lastChangeMs = nowMs;
        } else if ((nowMs - pg.lastChangeMs) / 1000L >= NO_PROGRESS_SECONDS) {
            long mins = (nowMs - pg.lastChangeMs) / 60000L;
            pg.lastChangeMs = nowMs;   // 报一次就重置，别每轮刷屏
            return RddStuckText.noProgress((int) mins, assets, stepDesc);
        }
        if (subtaskId == null || subtaskId.isBlank()) {
            marks.remove(companion);
            return "";
        }
        Mark m = marks.get(companion);
        if (m == null || !m.subtaskId.equals(subtaskId)) {
            marks.put(companion, new Mark(subtaskId, nowMs, assets));
            return "";
        }
        long elapsedSec = (nowMs - m.startMs) / 1000L;
        if (elapsedSec < RddStuckText.STUCK_SECONDS) return "";
        boolean sameAssets = assets <= m.assetsAtStart;
        int minutes = (int) (elapsedSec / 60L);
        return RddStuckText.block(minutes, assets, sameAssets, stepDesc, condType);
    }

    /** 二级完成/切换时清状态（由调用方在检测到 id 变化时自然重置，这里只提供显式清理）。 */
    void clear(UUID companion) {
        if (companion != null) marks.remove(companion);
    }
}
