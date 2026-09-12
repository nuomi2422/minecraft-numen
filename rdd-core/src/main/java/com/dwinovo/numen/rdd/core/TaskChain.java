package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;

/** Stateful owner of task progress; adapters may only report into this object. */
public final class TaskChain {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // 非 final：懒展开注入二级时需要重建 Goal（把当前未展开一级替换为已展开版本）。
    private Goal goal;
    private final Map<String, SubtaskStatus> statuses = new LinkedHashMap<>();
    private int primaryIndex;
    private int subtaskIndex;
    private PrimaryGoalStatus primaryStatus = PrimaryGoalStatus.PENDING;

    public TaskChain(Goal goal) {
        this.goal = Objects.requireNonNull(goal);
        for (PrimaryGoal primary : goal.primaryGoals()) for (Subtask subtask : primary.subtasks()) statuses.put(subtask.id(), SubtaskStatus.PENDING);
    }
    public Goal goal() { return goal; }
    public synchronized PrimaryGoalStatus primaryStatus() { return primaryStatus; }

    /** 当前二级的状态；当前一级"已到达但未展开"（无二级可运行）时为 null。 */
    public synchronized SubtaskStatus currentSubtaskStatus() {
        Subtask s = currentSubtask();
        return s == null ? null : statuses.get(s.id());
    }

    /**
     * 当前指针指向的二级目标。当前一级为未展开（懒加载、0 个二级）时返回 null——
     * 链上没有可运行的当前二级，宿主应先把已生成二级注入再激活；null 不是合法可执行节点。
     */
    public synchronized Subtask currentSubtask() {
        PrimaryGoal cur = currentPrimary();
        return cur.unexpanded() ? null : cur.subtasks().get(subtaskIndex);
    }
    public synchronized PrimaryGoal currentPrimary() { return goal.primaryGoals().get(primaryIndex); }

    /** 当前一级的所有前置资产(waitFor)是否都被 counts 满足（无 waitFor → true）。 */
    public synchronized boolean currentPrimaryReady(Map<String, Integer> counts) {
        List<AssetRequirement> wf = currentPrimary().waitFor();
        if (wf.isEmpty()) return true;
        if (counts == null) return false;
        for (AssetRequirement r : wf) {
            if (counts.getOrDefault(r.assetKey(), 0) < r.minimum()) return false;
        }
        return true;
    }

    /**
     * 激活"刚推进到、还没开工"的当前一级（PENDING/WAITING → ACTIVE 并启动其首个二级）。
     * 前置资产未到位 → 置 WAITING 并返回 false（监督方此时不该把它派给 AI，等资产到了再调）。
     */
    public synchronized boolean activateCurrent(Map<String, Integer> counts) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            // 未展开 ≠ 依赖未满足：绝不能把"该展开了"误判成 WAITING(缺资产)，也不得进 ACTIVE。
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        if (!currentPrimaryReady(counts)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
        }
        return true;
    }

    public synchronized void startCurrent() {
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before start: "
                    + currentPrimary().id());
        }
        if (primaryStatus == PrimaryGoalStatus.PENDING) primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (primaryStatus != PrimaryGoalStatus.ACTIVE || statuses.get(currentSubtask().id()) != SubtaskStatus.PENDING) throw new IllegalStateException("current subtask cannot start");
        statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
    }

    /**
     * 懒加载唯一合法的注入点：宿主目标驱动器把已为"当前一级"生成的二级集合注入该一级。
     * 保持一级的 id/description/waitFor 与 {@link #primaryStatus} 不变，仅 unexpanded→false；
     * 注入后照常走依赖门/激活/硬检测。当前一级未展开之外的调用一律拒绝（不重写其他任何状态）。
     */
    public synchronized void expandCurrentPrimary(List<Subtask> generated) {
        PrimaryGoal cur = currentPrimary();
        if (!cur.unexpanded()) {
            throw new IllegalStateException("current primary already expanded: " + cur.id());
        }
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may be expanded: " + primaryStatus);
        }
        if (generated == null || generated.isEmpty()) {
            throw new IllegalArgumentException("expansion requires at least one legal subtask");
        }
        Set<String> ids = new HashSet<>();
        for (Subtask s : generated) {
            if (s == null) {
                throw new IllegalArgumentException("null subtask in expansion");
            }
            if (!ids.add(s.id())) {
                throw new IllegalArgumentException("duplicate subtask id in expansion: " + s.id());
            }
            if (statuses.containsKey(s.id())) {
                throw new IllegalArgumentException("subtask id already used in chain: " + s.id());
            }
        }
        List<PrimaryGoal> primaries = new ArrayList<>(goal.primaryGoals());
        primaries.set(primaryIndex,
                new PrimaryGoal(cur.id(), cur.description(), List.copyOf(generated), cur.waitFor(), false));
        goal = new Goal(goal.id(), goal.description(), primaries);
        for (Subtask s : generated) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
        }
        subtaskIndex = 0;
    }

    public synchronized boolean applyHardCodedResult(String subtaskId, boolean satisfied) {
        requireCurrent(subtaskId);
        if (currentSubtask().detectionMode() != DetectionMode.HARD_CODED) throw new IllegalStateException("current subtask is not hard-coded");
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        if (!satisfied) return false;
        statuses.put(subtaskId, SubtaskStatus.COMPLETED);
        advanceOrAwait();
        return true;
    }

    public synchronized boolean applyAiAssistedResult(String subtaskId, String result) {
        requireCurrent(subtaskId);
        if (currentSubtask().detectionMode() != DetectionMode.AI_ASSISTED) throw new IllegalStateException("current subtask is not AI-assisted");
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        Objects.requireNonNull(result);
        if (!"CONFIRMED".equals(result)) return false;
        statuses.put(subtaskId, SubtaskStatus.COMPLETED);
        advanceOrAwait();
        return true;
    }

    public synchronized void applySupervisorDecision(SupervisorDecision decision) {
        Objects.requireNonNull(decision);
        if (primaryStatus != PrimaryGoalStatus.AWAITING_SUPERVISOR || !currentPrimary().id().equals(decision.targetNodeId())) throw new IllegalStateException("supervisor decision does not match current primary goal");
        switch (decision.type()) {
            case CONFIRM -> {
                primaryStatus = PrimaryGoalStatus.COMPLETED;
                if (primaryIndex + 1 < goal.primaryGoals().size()) {
                    primaryIndex++;
                    subtaskIndex = 0;
                    primaryStatus = PrimaryGoalStatus.PENDING;
                }
            }
            case REJECT, REPLAN -> primaryStatus = PrimaryGoalStatus.REPLANNING;
            case NEED_MORE_EVIDENCE, DEFER -> primaryStatus = PrimaryGoalStatus.WAITING;
        }
    }

    public synchronized void markFailed(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not running/stalled");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("failure reason required");
        statuses.put(subtaskId, SubtaskStatus.FAILED);
    }

    /** 监督检测到卡死（资产/工具长时间无变化）→ 置 STALLED，等待外部拍醒或升级。 */
    public synchronized void markStalled(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("stall reason required");
        statuses.put(subtaskId, SubtaskStatus.STALLED);
    }

    /** 监督拍醒后，检测到行为恢复 → 从 STALLED 回到 RUNNING（任务继续）。 */
    public synchronized void resumeFromStalled(String subtaskId) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not stalled");
        statuses.put(subtaskId, SubtaskStatus.RUNNING);
    }

    /** Level 2 局部恢复：失败的当前二级重置为 PENDING，可重新 startCurrent（AI 换策略再试）。 */
    public synchronized void retrySubtask(String subtaskId) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.FAILED) throw new IllegalStateException("only a FAILED subtask may retry");
        statuses.put(subtaskId, SubtaskStatus.PENDING);
    }

    public synchronized Map<String, SubtaskStatus> subtaskStatuses() {
        return Map.copyOf(statuses);
    }

    /** Read-only structured view for monitoring; it is derived from the state owner. */
    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> primaries = new ArrayList<>();
        for (int p = 0; p < goal.primaryGoals().size(); p++) {
            PrimaryGoal primary = goal.primaryGoals().get(p);
            List<Map<String, Object>> subtasks = new ArrayList<>();
            for (int s = 0; s < primary.subtasks().size(); s++) {
                Subtask subtask = primary.subtasks().get(s);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", subtask.id());
                item.put("description", subtask.description());
                item.put("status", statuses.get(subtask.id()).name());
                item.put("detectionMode", subtask.detectionMode().name());
                item.put("condition", subtask.condition());
                item.put("current", p == primaryIndex && s == subtaskIndex);
                subtasks.add(item);
            }
            Map<String, Object> primaryView = new LinkedHashMap<>();
            primaryView.put("id", primary.id());
            primaryView.put("description", primary.description());
            primaryView.put("current", p == primaryIndex);
            primaryView.put("unexpanded", primary.unexpanded());
            if (!primary.waitFor().isEmpty()) {
                List<Map<String, Object>> wf = new ArrayList<>();
                for (AssetRequirement r : primary.waitFor()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asset_key", r.assetKey());
                    m.put("minimum", r.minimum());
                    wf.add(m);
                }
                primaryView.put("waitFor", wf);
            }
            primaryView.put("subtasks", subtasks);
            primaries.add(primaryView);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("goalId", goal.id());
        view.put("description", goal.description());
        view.put("primaryStatus", primaryStatus.name());
        view.put("primaryIndex", primaryIndex);
        view.put("subtaskIndex", subtaskIndex);
        PrimaryGoal current = currentPrimary();
        view.put("currentPrimaryId", current.id());
        // 当前一级未展开时诚实报"无当前二级"，而不是伪造占位节点。
        view.put("currentSubtaskId", current.unexpanded() ? null : currentSubtask().id());
        if (primaryStatus == PrimaryGoalStatus.WAITING && !current.waitFor().isEmpty()) {
            view.put("waitingFor", currentPrimary().waitFor().stream().map(AssetRequirement::assetKey).toList());
        }
        view.put("primaries", primaries);
        return view;
    }

    /** 导出任务链状态为 JSON（持久化用：goal 结构 + 全部状态 + 当前指针）。 */
    public synchronized String toJson() {
        JsonObject o = new JsonObject();
        o.add("goal", GSON.toJsonTree(goal));
        JsonObject st = new JsonObject();
        for (Map.Entry<String, SubtaskStatus> e : statuses.entrySet()) {
            st.addProperty(e.getKey(), e.getValue().name());
        }
        o.add("statuses", st);
        o.addProperty("primaryIndex", primaryIndex);
        o.addProperty("subtaskIndex", subtaskIndex);
        o.addProperty("primaryStatus", primaryStatus.name());
        return GSON.toJson(o);
    }

    /** 从 JSON 恢复任务链状态（游戏重启后 RECOVERING）。 */
    public static TaskChain fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonObject goalObj = o.getAsJsonObject("goal");
        // 旧链兼容（A-1 前无 unexpanded 字段）：解析树里给缺该字段的一级补 false=已展开，
        // 不依赖 Gson 对缺失原始类型组件的默认行为。waitFor 等引用组件缺失 → 构造器已置空。
        JsonArray primaries = goalObj.getAsJsonArray("primaryGoals");
        for (JsonElement el : primaries) {
            JsonObject pg = el.getAsJsonObject();
            if (!pg.has("unexpanded")) {
                pg.addProperty("unexpanded", false);
            }
        }
        Goal goal = GSON.fromJson(goalObj, Goal.class);
        TaskChain chain = new TaskChain(goal);
        JsonObject st = o.getAsJsonObject("statuses");
        if (st == null) {
            throw new IllegalArgumentException("restored status table missing");
        }
        chain.statuses.clear();
        for (String k : st.keySet()) {
            chain.statuses.put(k, SubtaskStatus.valueOf(st.get(k).getAsString()));
        }
        chain.primaryIndex = o.get("primaryIndex").getAsInt();
        chain.subtaskIndex = o.get("subtaskIndex").getAsInt();
        chain.primaryStatus = PrimaryGoalStatus.valueOf(o.get("primaryStatus").getAsString());
        chain.validateRestoredState();
        return chain;
    }

    private void advanceOrAwait() {
        if (subtaskIndex + 1 < currentPrimary().subtasks().size()) { subtaskIndex++; return; }
        primaryStatus = PrimaryGoalStatus.AWAITING_SUPERVISOR;
    }
    private void requireCurrent(String id) { if (id == null || !currentSubtask().id().equals(id)) throw new IllegalArgumentException("stale subtask: " + id); }

    /** Reject corrupt handoff state before it can reach a live executor or monitor. */
    private void validateRestoredState() {
        if (primaryIndex < 0 || primaryIndex >= goal.primaryGoals().size()) {
            throw new IllegalArgumentException("primary index out of range: " + primaryIndex);
        }
        Set<String> expected = new LinkedHashSet<>();
        for (PrimaryGoal primary : goal.primaryGoals()) {
            for (Subtask subtask : primary.subtasks()) {
                if (!expected.add(subtask.id())) {
                    throw new IllegalArgumentException("duplicate subtask id in restored goal: " + subtask.id());
                }
            }
        }
        if (!statuses.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(statuses.keySet());
            Set<String> unknown = new LinkedHashSet<>(statuses.keySet());
            unknown.removeAll(expected);
            throw new IllegalArgumentException("restored status table mismatch; missing=" + missing + ", unknown=" + unknown);
        }
        PrimaryGoal current = goal.primaryGoals().get(primaryIndex);
        if (current.unexpanded()) {
            if (subtaskIndex != 0) {
                throw new IllegalArgumentException("unexpanded primary must have subtask index 0: " + subtaskIndex);
            }
        } else if (subtaskIndex < 0 || subtaskIndex >= current.subtasks().size()) {
            throw new IllegalArgumentException("subtask index out of range: " + subtaskIndex);
        }
    }
}
