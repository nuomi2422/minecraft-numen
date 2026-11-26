package com.dwinovo.numen.api;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;

/** Internal trusted server bridge; neither model input nor proof of execution. */
public interface TaskFocusLeaseService {
    /** Validate the current task, world and declaration before acquiring. Null means no lease. */
    Lease acquire(NumenPlayer companion, TaskRecord task, String owner);
    /** Cheap managed-revision check; false includes unavailable/UNKNOWN and releases only this lease. */
    default boolean stillEligible(NumenPlayer companion, TaskRecord task, Lease lease) { return false; }
    /** Idempotently release only the captured owner/lease pair. */
    void release(Lease lease);
    record Lease(java.util.UUID companionId, String owner, String leaseId, long expiresAtMs) { }
}
