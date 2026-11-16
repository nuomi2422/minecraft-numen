package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.NodeTypePolicy;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks ordered patch operations on a private working list, never on the live plan.
 * TaskChain calls this only after its input/primary/version/receipt guards, under its monitor.
 * Statuses are the owner's read-only input, not a second state store; new nodes stay absent
 * until commit. A generic patch engine or a copied chain state would widen that contract.
 * State publication, version bumps, safe points and persistence remain with the owner.
 */
final class TaskChainPatchValidator {
    private TaskChainPatchValidator() {}

    record PatchCheck(PlanPatch.Receipt receipt, List<Subtask> working) {}

    static PatchCheck checkOperations(List<PlanPatch.Op> ops, List<Subtask> subtasks,
                                      Map<String, SubtaskStatus> statuses, int subtaskIndex, long stateVersion) {
        List<Subtask> working = new ArrayList<>(subtasks);
        Set<String> allIds = new HashSet<>(statuses.keySet());
        for (PlanPatch.Op op : ops) {
            if (op == null || op.kind() == null || op.nodeId() == null || op.nodeId().isBlank()) {
                return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT", "operation requires kind and nodeId"), null);
            }
            switch (op.kind()) {
                case ADD_NODE -> {
                    if (op.condition() == null || op.description() == null || op.description().isBlank()) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT",
                                "ADD_NODE requires description and condition: " + op.nodeId()), null);
                    }
                    if (allIds.contains(op.nodeId()) || working.stream().anyMatch(s -> s.id().equals(op.nodeId()))) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("DUPLICATE_ID",
                                "node id already used in chain: " + op.nodeId()), null);
                    }
                    working.add(Subtask.hardCoded(op.nodeId(), op.description(), op.condition()));
                }
                case REPLACE_PENDING, DROP_PENDING -> {
                    int idx = -1;
                    for (int i = 0; i < working.size(); i++) {
                        if (working.get(i).id().equals(op.nodeId())) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx < 0) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("NODE_MISSING",
                                "node not found in current primary: " + op.nodeId()), null);
                    }
                    if (statuses.get(op.nodeId()) != SubtaskStatus.PENDING) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("NODE_NOT_PENDING",
                                "only not-yet-started nodes may be patched: " + op.nodeId()
                                        + " is " + statuses.get(op.nodeId())), null);
                    }
                    // G3d 安全边界：只能改「当前节点及其之后」的待执行节点。
                    // 改到当前索引之前的节点会让 subtaskIndex 错位（删一个前面的节点 =
                    // 当前节点被挪到前一位，指针指向别人），那不是补丁，是重排。
                    if (idx < subtaskIndex) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("NODE_BEFORE_CURSOR",
                                "cannot patch a node before the current pointer: " + op.nodeId()), null);
                    }
                    if (op.kind() == PlanPatch.OpKind.DROP_PENDING) {
                        if (working.size() <= 1) {
                            return new PatchCheck(PlanPatch.Receipt.rejected("WOULD_EMPTY",
                                    "patch would leave the current primary with no nodes"), null);
                        }
                        working.remove(idx);
                    } else {
                        if (op.condition() == null || op.description() == null || op.description().isBlank()) {
                            return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT",
                                    "REPLACE_PENDING requires description and condition: " + op.nodeId()), null);
                        }
                        Subtask old = working.get(idx);
                        // 保留原 body（身体指令不属于补丁范围）；只换描述与完成条件
                        working.set(idx, Subtask.hardCoded(op.nodeId(), op.description(), op.condition(), old.body()));
                    }
                }
            }
        }
        if (working.isEmpty()) {
            return new PatchCheck(PlanPatch.Receipt.rejected("WOULD_EMPTY", "patch left no nodes"), null);
        }
        if (working.stream().noneMatch(sub -> NodeTypePolicy.countsTowardMainline(sub.nodeType()))) {
            return new PatchCheck(PlanPatch.Receipt.rejected("NO_MAINLINE", "DAILY sources cannot be the entire main plan"), null);
        }
        return new PatchCheck(PlanPatch.Receipt.ok(stateVersion), List.copyOf(working));
    }
}
