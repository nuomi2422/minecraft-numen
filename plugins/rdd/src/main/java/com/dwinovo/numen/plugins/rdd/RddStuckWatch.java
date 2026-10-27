package com.dwinovo.numen.plugins.rdd;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 空转检测的状态机（纯逻辑，可单测）。
 *
 * <p>跟踪「当前二级 + 起始时刻 + 起始资产数」；同一个二级持续超过 {@link RddStuckText#STUCK_SECONDS}
 * 且资产数没变，就返回提示块。二级一变就重置 —— 换了步骤不算空转。
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

    /**
     * @param companion 同伴
     * @param subtaskId 当前二级 id（为空则清掉状态）
     * @param assets    当前资产数
     * @param nowMs     当前时刻
     * @param stepDesc  二级描述（生成对症动作）
     * @return 提示块；不该提示时返回空串
     */
    String tick(UUID companion, String subtaskId, int assets, long nowMs, String stepDesc) {
        if (companion == null) return "";
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
        return RddStuckText.block(minutes, assets, sameAssets, stepDesc);
    }

    /** 二级完成/切换时清状态（由调用方在检测到 id 变化时自然重置，这里只提供显式清理）。 */
    void clear(UUID companion) {
        if (companion != null) marks.remove(companion);
    }
}
