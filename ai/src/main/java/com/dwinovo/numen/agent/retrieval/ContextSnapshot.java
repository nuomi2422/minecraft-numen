package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * 75 号 §15.5 `ContextSnapshot` —— 检索服务的**一次性上下文取景**（不可变）。
 *
 * <p>只给下一次模型请求消费；不能改在途请求、不能无限追加历史。
 *
 * <p><b>编排顺序（IC09）</b>：先留必知区（当前目标/缺口/危险/权限），
 * 再压缩可选目录；必知区本身装不下 → <b>status=DENIED + CONSTRAINT_OVERFLOW 且
 * 不吞危险信息</b>（objective 原文可见，靠 {@code mandatoryConstraints}）。
 * 可选目录 maxChars/maxCandidates 硬上限到了 → GAP + truncationReasons，
 * <b>不许无声丢条目</b>。
 */
public record ContextSnapshot(
        String snapshotId,
        String queryId,
        RetrievalRole role,
        List<String> mandatoryConstraints,
        List<RetrievedEntry> directory,
        List<Gap> gaps,
        List<String> truncationReasons,
        Status status) {

    public enum Status { OK, PARTIAL, DENIED }

    public ContextSnapshot {
        mandatoryConstraints = frozen(mandatoryConstraints);
        directory = directory == null ? List.copyOf(List.<RetrievedEntry>of()) : List.copyOf(directory);
        gaps = gaps == null ? List.copyOf(List.<Gap>of()) : List.copyOf(gaps);
        truncationReasons = frozen(truncationReasons);
        if (status == null) throw new NullPointerException("status 不能为空");
    }

    private static List<String> frozen(List<String> v) {
        return v == null ? List.of() : List.copyOf(v);
    }
}
