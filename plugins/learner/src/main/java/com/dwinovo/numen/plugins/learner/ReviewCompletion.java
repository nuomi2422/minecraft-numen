package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import java.util.*;
import java.util.function.Function;

/** 2026-11-11 C1: same completion decision gates artifacts and queue settlement.
 * Terminal skips are not learned verdicts. Only the existing gate's three definite reasons retire a memo.
 */
final class ReviewCompletion {
    private final Map<String, ReviewTargetGate.Check> targets = new LinkedHashMap<>();
    ReviewCompletion(List<Memo> batch, Function<Memo, ReviewTargetGate.Check> check) {
        for (Memo memo : batch) targets.put(memo.id(), check.apply(memo));
    }
    boolean permits(String memoId) {
        var target = targets.get(memoId);
        return target != null && target.ok();
    }
    boolean terminal(String memoId) {
        var target = targets.get(memoId);
        return target != null && target.terminal();
    }
    ReviewTargetGate.Check target(String memoId) { return targets.get(memoId); }
    /** Production uses this before contract/register side effects and verdict rendering. */
    boolean effective(String memoId, Set<String> judged) {
        return permits(memoId) && judged.contains(memoId);
    }
    /** restored counts this batch's exact durable retry memos, never total queue depth or new insertions. */
    record Settlement(int committed, int restored, int terminalSkipped) {}
    Settlement settle(MemoQueue queue, List<Memo> batch, Set<String> judged) {
        List<Memo> valid = new ArrayList<>(), retired = new ArrayList<>(), retry = new ArrayList<>();
        for (Memo memo : batch) {
            if (terminal(memo.id())) retired.add(memo);
            else if (permits(memo.id()) && judged.contains(memo.id())) valid.add(memo);
            else retry.add(memo);
        }
        int committed = valid.isEmpty() ? 0 : queue.commitDrain(valid);
        int terminalSkipped = retired.isEmpty() ? 0 : queue.commitDrain(retired);
        int restored = 0;
        if (!retry.isEmpty()) {
            queue.restore(retry); // Legacy return is total queue depth; keep every other consumer's contract.
            List<Memo> durable = queue.all(); // Real post-write read; failures propagate, never invent success.
            for (Memo original : retry) {
                int occurrences = 0;
                boolean exact = false;
                for (Memo stored : durable) {
                    if (original.id().equals(stored.id())) {
                        occurrences++;
                        exact = original.equals(stored);
                    }
                }
                if (occurrences == 1 && exact) restored++;
            }
        }
        return new Settlement(committed, restored, terminalSkipped);
    }
}
