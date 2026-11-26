package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.TaskFocusLeaseService;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Internal server-thread bridge. A pin is observation context, not an action grant. */
final class TaskFocusLeaseBridge implements TaskFocusLeaseService {
    private final WorldStateService world;
    private record Validation(long revision, String ref, boolean eligible) {}
    private final java.util.concurrent.ConcurrentMap<String,Validation> validations = new java.util.concurrent.ConcurrentHashMap<>();
    TaskFocusLeaseBridge(WorldStateService world) { this.world=world; }
    @Override public Lease acquire(NumenPlayer companion, TaskRecord task, String owner) {
        if (companion==null || task==null || task.getState()!=TaskState.RUNNING || owner==null || owner.isBlank()) return null;
        var server=companion.level().getServer();
        if (!WorldStateMod.isActiveServer(server) || !server.isSameThread()) return null;
        var selection=task.skillSelection(); var stamp=WorldContextStamp.capture(server);
        if (selection==null || !companion.getUUID().toString().equals(selection.companionId())
                || !TaskRecord.completeIdentity(stamp) || !stamp.equals(WorldContextStamp.capture())
                || !stamp.equals(task.identityToken()) || !stamp.equals(selection.token())) return null;
        var resolver=NumenPlugins.capability(LearnedSkillBodyResolver.class);
        if (resolver==null) return null;
        String ref=selection.skillRef()+"@"+selection.version();
        var body=resolver.resolve(companion.getUUID(),ref,LearnedSkillBodyResolver.View.EXECUTOR);
        if (!body.available() || !ref.equals(body.exactRef()) || !sha256(body.body()).equals(selection.bodyDigest())) return null;
        var declaration=resolver.resolveFocusDeclaration(companion.getUUID(),ref,LearnedSkillBodyResolver.View.EXECUTOR);
        if (!declaration.available() || !ref.equals(declaration.packageRef())
                || !"environment".equals(declaration.topic()) || !"v1".equals(declaration.contractVersion())) return null;
        // Recheck the global identity immediately before the mutating store call.
        if (!WorldStateMod.isActiveServer(server) || !stamp.equals(WorldContextStamp.capture())) return null;
        String dim=companion.serverLevel().dimension().location().toString();
        var pin=world.focusPins().acquire(companion.getUUID(),stamp.identity().get("world_id"),stamp.identity().get("server_session"),dim,
                "environment","v1",owner,System.currentTimeMillis(),0,0,0,0);
        if (pin==null) return null;
        var lease=new Lease(companion.getUUID(),owner,pin.leaseId(),pin.pin().expiresAtMs());
        try {
            var eligibility=resolver.managedFocusEligibility(companion.getUUID(),ref,LearnedSkillBodyResolver.View.EXECUTOR);
            if (valid(eligibility,ref) && resolver.managedRevision()==eligibility.revision()) {
                validations.put(lease.leaseId(),new Validation(eligibility.revision(),ref,true));
                return lease;
            }
        } catch (RuntimeException | LinkageError unavailable) { /* UNKNOWN: undo only our acquisition */ }
        world.focusPins().release(lease.companionId(),lease.owner(),lease.leaseId());
        return null;
    }
    @Override public boolean stillEligible(NumenPlayer companion, TaskRecord task, Lease lease) {
        if (companion==null || task==null || lease==null || !companion.getUUID().equals(lease.companionId()) || task.skillSelection()==null) return false;
        String ref=task.skillSelection().skillRef()+"@"+task.skillSelection().version();
        var prior=validations.get(lease.leaseId());
        var resolver=NumenPlugins.capability(LearnedSkillBodyResolver.class);
        if (prior==null || resolver==null || !prior.ref().equals(ref)) return false;
        var server=companion.level().getServer(); var stamp=WorldContextStamp.capture(server);
        if (!WorldStateMod.isActiveServer(server) || !server.isSameThread() || !TaskRecord.completeIdentity(stamp)
                || !stamp.equals(WorldContextStamp.capture()) || !stamp.equals(task.identityToken())
                || !stamp.equals(task.skillSelection().token())) return false;
        long revision=resolver.managedRevision();
        if (revision < 0) return false;
        if (revision==prior.revision() && resolver.managedRevision()==revision) return prior.eligible();
        var current=resolver.managedFocusEligibility(companion.getUUID(),ref,LearnedSkillBodyResolver.View.EXECUTOR);
        boolean ok=valid(current,ref) && resolver.managedRevision()==current.revision();
        if (!ok) org.slf4j.LoggerFactory.getLogger(TaskFocusLeaseBridge.class).info("[focus] managed declaration unavailable: {}", current == null || current.declaration() == null ? "unknown" : current.declaration().gap());
        if (current != null) validations.put(lease.leaseId(),new Validation(current.revision(),ref,ok));
        return ok;
    }
    private static boolean valid(LearnedSkillBodyResolver.ManagedFocusEligibility value,String ref) {
        if (value==null || value.revision()<0 || value.declaration()==null) return false;
        var d=value.declaration(); return d.available() && ref.equals(d.packageRef()) && "environment".equals(d.topic()) && "v1".equals(d.contractVersion());
    }
    @Override public void release(Lease lease) {
        if (lease!=null) { validations.remove(lease.leaseId()); world.focusPins().release(lease.companionId(),lease.owner(),lease.leaseId()); }
    }
    private static String sha256(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
