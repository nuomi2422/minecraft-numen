package com.dwinovo.numen.agent.retrieval;

import java.util.List;
import java.util.stream.Collectors;

/**
 * G5b 最小 {@code RoleViewSelector}（75 号 §15.5 IC08 的「三视图不同」）。
 *
 * <p>同一 snapshot 按 PLANNER / EXECUTOR / LEARNER 三种角色取回的内容<b>不该相同</b>：
 * <ul>
 *   <li><b>PLANNER</b>：全部保留（待办的 OBSERVED / 撤回经验 / WARNING 都呈现，作待核实线索）。</li>
 *   <li><b>EXECUTOR</b>：只留「当前有效」条目 —— maturity ≥ VERIFIED、<b>非撤回</b>、
 *       kind ≠ WARNING（警告走必知区）。</li>
 *   <li><b>LEARNER</b>：全量（含撤回），便于反思/复盘。</li>
 * </ul>
 * <p>纯函数，不触碰 provider；调用方自行提供 snapshot（装配器产物）。
 */
public final class RoleViewSelector {

    private RoleViewSelector() {}

    public static ContextSnapshot select(ContextSnapshot snapshot, RetrievalRole role) {
        if (snapshot == null) throw new NullPointerException("snapshot 不能为空");
        if (role == null) throw new NullPointerException("role 不能为空");
        List<RetrievedEntry> filtered = snapshot.directory().stream()
                .filter(e -> visibleToRole(e, role))
                .collect(Collectors.toList());
        ContextSnapshot.Status status = filtered.size() == snapshot.directory().size()
                ? snapshot.status()
                : ContextSnapshot.Status.PARTIAL;
        return new ContextSnapshot(
                snapshot.snapshotId(), snapshot.queryId(), role,
                snapshot.mandatoryConstraints(), List.copyOf(filtered),
                snapshot.gaps(), snapshot.truncationReasons(), status);
    }

    private static boolean visibleToRole(RetrievedEntry e, RetrievalRole role) {
        EntryDescriptor d = e.descriptor();
        if (!d.roles().contains(role)) return false;   // role 过滤是 provider 的职责，也在 view再做一次兜底
        if (role == RetrievalRole.EXECUTOR) {
            // EXECUTOR 只见「当前有效」
            if (d.retracted()) return false;
            if (d.kind() == EntryKind.WARNING) return false;   // 警告走必知区，不塞目录
            // ★ G5g Y2：只读目录条目（`tool:`）没有「成熟度」这个概念 —— 它讲的是「有这个工具、
            //   参数长这样」，不是「这条经验被验证过」。所以它**不吃** VERIFIED 那道门，
            //   但仍然只读（viewOnly=true，执行入口会拒）。经验的成熟度门不变。
            if (d.viewOnly()) return true;
            // ★ G8：试用条目（trial=true）允许 maturity<VERIFIED —— 否则「进不了视图→没证据→
            //   永远进不了」成环。PUBLISHED 经验仍须 >=VERIFIED（G5g 冻结语义不变）。
            if (d.maturity() == null) return false;
            if (!d.trial() && d.maturity().rank < Maturity.VERIFIED.rank) return false;
        }
        return true;
    }
}
