package com.dwinovo.numen.rdd.core;

import java.util.List;
import java.util.Map;

/**
 * 计划补丁（G3c，75号 §15.3 / IC06 的核心形状）。
 *
 * <p><b>为什么需要它</b>：现在的重规划走 {@link TaskChain#replaceCurrentSubtasks}，
 * 那条路会<b>删掉旧状态、旧 attempt 计数与 acquire 基线</b>——它适合「整段计划作废重来」，
 * 不适合「只改还没开始的那两个节点」。75号 §15.3 明确要求：COMPLETED 节点与已发生的消费
 * 不可删除或改写；改验收语义要<b>新建后继节点</b>，不能原地改。
 *
 * <p><b>本类只描述意图，不判断对错</b>：合法性由 {@link TaskChain#proposePatch} 按
 * 当前链状态校验，落盘由 {@link TaskChain#commitPatch} 在版本 CAS 保护下原子完成。
 *
 * <p><b>首版允许的操作</b>（故意只开这一点）：
 * <ul>
 *   <li>{@link OpKind#ADD_NODE}：在<b>当前一级</b>末尾追加一个待执行节点；</li>
 *   <li>{@link OpKind#REPLACE_PENDING}：替换一个<b>还没开始</b>的节点（同 id，改描述/条件）；</li>
 *   <li>{@link OpKind#DROP_PENDING}：删掉一个<b>还没开始</b>的节点。</li>
 * </ul>
 * 已完成 / 在跑 / 已暂停的节点一律不可被补丁触碰；补丁不得让当前一级变空。
 *
 * @param patchId          补丁身份（幂等键；同一 patchId 重投按已提交处理）
 * @param primaryId        目标一级（必须是当前一级）
 * @param baseStateVersion 提出补丁时看到的状态版本（CAS 基线）
 * @param ops              操作列表（按顺序应用）
 * @param reason           人类可读理由（进事件，便于事后追因为什么改）
 */
public record PlanPatch(String patchId, String primaryId, long baseStateVersion, List<Op> ops, String reason) {

    public enum OpKind { ADD_NODE, REPLACE_PENDING, DROP_PENDING }

    /**
     * 单个操作。
     *
     * @param nodeId      目标节点 id（ADD 时是新 id，REPLACE/DROP 时是既有 id）
     * @param description 新描述（ADD/REPLACE 必填；DROP 忽略）
     * @param condition   新完成条件（ADD/REPLACE 必填；DROP 忽略）
     */
    public record Op(OpKind kind, String nodeId, String description, Map<String, Object> condition) {

        public static Op add(String nodeId, String description, Map<String, Object> condition) {
            return new Op(OpKind.ADD_NODE, nodeId, description, condition);
        }

        public static Op replace(String nodeId, String description, Map<String, Object> condition) {
            return new Op(OpKind.REPLACE_PENDING, nodeId, description, condition);
        }

        public static Op drop(String nodeId) {
            return new Op(OpKind.DROP_PENDING, nodeId, null, null);
        }
    }

    /**
     * 提出/提交的回执。{@code ok=false} 时 {@code code} 是机器可读原因：
     * {@code BAD_INPUT} / {@code NOT_CURRENT_PRIMARY} / {@code STALE_VERSION} /
     * {@code DUPLICATE_ID} / {@code NODE_NOT_PENDING} / {@code WOULD_EMPTY} /
     * {@code NODE_MISSING} / {@code ALREADY_COMMITTED}。
     */
    public record Receipt(boolean ok, String code, String why, long stateVersion) {

        public static Receipt ok(long version) {
            return new Receipt(true, "OK", "", version);
        }

        public static Receipt rejected(String code, String why) {
            return new Receipt(false, code, why, -1L);
        }
    }

    public PlanPatch {
        ops = ops == null ? List.of() : List.copyOf(ops);
        reason = reason == null ? "" : reason;
    }
}
