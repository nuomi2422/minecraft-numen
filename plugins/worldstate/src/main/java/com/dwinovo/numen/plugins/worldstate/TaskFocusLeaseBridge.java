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
        return pin==null ? null : new Lease(companion.getUUID(),owner,pin.leaseId(),pin.pin().expiresAtMs());
    }
    @Override public void release(Lease lease) {
        if (lease!=null) world.focusPins().release(lease.companionId(),lease.owner(),lease.leaseId());
    }
    private static String sha256(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
