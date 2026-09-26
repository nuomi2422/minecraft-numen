package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.LocalRepairTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * \u652f\u7ebf\u4efb\u52a1\uff08\u76f4\u7ebf\u4efb\u52a1 / Local Repair Task\uff09\u5206\u53d1\u5668 \u2014\u2014 \u63d2\u4ef6\u4fa7\u63a5\u7ebf\u3002
 *
 * <p>\u3010\u7528\u6237\u67b6\u6784\u6982\u5ff5 1/4 \u7684\u63a5\u7ebf\u3011\u6b7b\u4ea1/\u7269\u54c1\u4e22\u5931\u7b49\u7a81\u53d1\u65f6\uff0c\u751f\u6210\u4e00\u6761"\u4e0d\u6574\u94fe\u91cd\u89c4\u5212"\u7684\u652f\u7ebf\u4efb\u52a1\uff0c
 * \u7ecf\u4e2d\u65ad\u98ce\u66b4\u62a4\u680f\u5224\u5b9a\u540e\uff0c\u7528 nudge \u6ce8\u5165\u6267\u884c\u4f53\uff1a"\u5148\u5904\u7406\u773c\u524d\u95ee\u9898\uff0c\u522b\u52a8\u4e3b\u94fe"\u3002
 * \u7eaf\u903b\u8f91\u5728 rdd-core \u7684 {@link LocalRepairTask}\uff1b\u672c\u7c7b\u53ea\u505a\u5bbf\u4e3b\u4fa7\u72b6\u6001\u4e0e\u6295\u653e\u3002
 *
 * <p>\u4e0d\u6539 TaskChain \u6838\u5fc3\uff1b\u4e0d\u6d3e\u5de5\uff08nudge \u662f\u63d0\u793a\uff0c\u6267\u884c\u4f53\u81ea\u51b3\uff09\uff1b\u7ea2\u7ebf\u4e0d\u52a8\u3002
 */
final class RddRepairDispatch {

    private RddRepairDispatch() {}

    /** \u6bcf\u540c\u4f34\u7684\u6700\u8fd1\u652f\u7ebf\u4efb\u52a1\uff08\u89c2\u6d4b/\u751f\u6210\u53bb\u91cd\u7528\uff09\u3002 */
    private static final Map<UUID, LocalRepairTask.Task> LAST_REPAIR = new ConcurrentHashMap<>();
    /** \u6bcf\u540c\u4f34\u7684\u62a4\u680f\u8ffd\u8e2a\uff08\u51b7\u5374/\u9891\u7387/\u8fde\u7eed\u5931\u8d25\uff09\u3002 */
    private static final Map<UUID, LocalRepairTask.Guard.Tracker> TRACKERS = new ConcurrentHashMap<>();
    private static final AtomicLong ID_SEQ = new AtomicLong();
    private static final LocalRepairTask.Guard GUARD = LocalRepairTask.Guard.defaults();

    /**
     * \u751f\u6210\u5e76\uff08\u7ecf\u62a4\u680f\uff09\u6295\u653e\u4e00\u6761\u6b7b\u4ea1\u652f\u7ebf\u4efb\u52a1\u3002\u8fd4\u56de true = \u5df2\u6295\u653e\uff08nudge \u6ce8\u5165\uff09\u3002
     * \u6b7b\u4ea1\u89e6\u53d1\u4f18\u5148\u7ea7 CRITICAL\uff08\u62a4\u680f\u653e\u884c\uff09\uff0c\u76ee\u6807\uff1a\u53d6\u5907\u7528\u88c5\u5907 / \u6361\u56de\u6389\u843d / \u56de\u4e3b\u94fe\u3002
     */
    static boolean onDeath(UUID companionId, String goalId) {
        if (companionId == null) return false;
        long now = RddInstrumentation.currentGameTimeTicks();
        LocalRepairTask.Priority pri = LocalRepairTask.defaultPriority(LocalRepairTask.Trigger.DEATH);
        LocalRepairTask.Guard.Tracker tr = TRACKERS.computeIfAbsent(companionId, k -> new LocalRepairTask.Guard.Tracker());
        LocalRepairTask.Guard.Decision d = GUARD.evaluate(tr, LocalRepairTask.Trigger.DEATH, now, pri);
        if (d != LocalRepairTask.Guard.Decision.ALLOW) {
            RddMonitor.publish("repair_task_suppressed", Map.of(
                    "companionId", companionId.toString(), "trigger", "DEATH", "decision", d.name()));
            return false;
        }
        String id = "repair-" + companionId + "-" + ID_SEQ.incrementAndGet();
        String parent = (goalId == null || goalId.isBlank()) ? ("goal-" + companionId) : goalId;
        LocalRepairTask.Task task = new LocalRepairTask.Task(id, parent, "death-event",
                LocalRepairTask.Trigger.DEATH, pri, 5 * 60 * 20,
                LocalRepairTask.ResumePolicy.RESEARCH_TARGET,
                "\u53d6\u56de\u5907\u7528\u88c5\u5907\uff1b\u8d70\u5230\u6b7b\u4ea1\u70b9\u6361\u56de\u6389\u843d\uff1b\u6062\u590d\u539f\u4e3b\u94fe",
                "ESCALATE",
                "\u6b7b\u4ea1\u652f\u7ebf\uff1a\u5148\u5904\u7406\u773c\u524d(\u53d6\u5907\u7528/\u6361\u5305)\uff0c\u4e0d\u6574\u94fe\u91cd\u89c4\u5212\uff1b\u5b8c\u6210\u540e\u56de\u4e3b\u94fe");
        LAST_REPAIR.put(companionId, task);
        tr.recordFire(LocalRepairTask.Trigger.DEATH, now);
        RddMonitor.publish("repair_task_dispatched", Map.of(
                "companionId", companionId.toString(), "repairId", id, "priority", pri.name()));
        RddPlugin.nudge(companionId, "[\u652f\u7ebf\u4efb\u52a1] \u4f60\u521a\u6b7b\u8fc7\uff1a\u5148\u5904\u7406\u773c\u524d\u2014\u2014\u4f18\u5148\u53d6\u56de\u5907\u7528\u88c5\u5907/\u6361\u56de\u6389\u843d\u7269\uff0c\u4e0d\u8981\u4e3a\u8fd9\u4ef6\u4e8b\u91cd\u89c4\u5212\u6574\u6761\u4e3b\u94fe\uff1b\u5904\u7406\u5b8c\u56de\u4e3b\u94fe\u7ee7\u7eed\u3002");
        return true;
    }

    /** \u652f\u7ebf\u6210\u529f\uff1a\u6e05\u8fde\u7eed\u5931\u8d25\u8ba1\u6570\u5e76\u79fb\u9664\u3002 */
    static void onRepairSuccess(UUID companionId) {
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordSuccess(LocalRepairTask.Trigger.DEATH);
        LAST_REPAIR.remove(companionId);
    }

    /** \u652f\u7ebf\u5931\u8d25\uff1a\u7d2f\u8ba1\u8fde\u7eed\u5931\u8d25\uff08\u8fbe\u9608\u503c\u89e6\u53d1\u5347\u7ea7\u91cd\u89c4\u5212\uff09\u3002 */
    static void onRepairFailure(UUID companionId) {
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordFailure(LocalRepairTask.Trigger.DEATH);
    }

    static LocalRepairTask.Task lastRepair(UUID companionId) { return LAST_REPAIR.get(companionId); }

    static void remove(UUID companionId) { LAST_REPAIR.remove(companionId); TRACKERS.remove(companionId); }
}
