package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.SyncSkillCorrelation;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistrar;
import com.dwinovo.numen.plugins.learner.core.LearnedLocationBinding;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Process-local, bounded correlation only; never grants tool execution. */
public final class SyncSkillFeedback implements SyncSkillCorrelation {
    public static final SyncSkillFeedback INSTANCE = new SyncSkillFeedback();
    private static final int MAX = 256;
    private static final long TTL_NANOS = 120_000_000_000L;
    private final Map<String, Selection> selections = new LinkedHashMap<>();
    private static final class Selection {
        final UUID owner, companion;
        final WorldContextStamp.Token token;
        final String ref, version, bodyRef, bodyDigest, tool, kind, name;
        final UsageLedger ledger;
        final long expires;
        String wire;
        boolean active, recorded;
        Selection(UUID owner, UUID companion, WorldContextStamp.Token token, String ref, String version,
                  String bodyRef, String bodyDigest, String tool, String kind, String name, UsageLedger ledger) {
            this.owner=owner; this.companion=companion; this.token=token; this.ref=ref; this.version=version;
            this.bodyRef=bodyRef; this.bodyDigest=bodyDigest; this.tool=tool; this.kind=kind; this.name=name;
            this.ledger=ledger; this.expires=System.nanoTime()+TTL_NANOS;
        }
    }
    void adopt(JsonObject args, Path dir, UsageLedger ledger, LearnerUsageTool.TaskAccess host, Consumer<String> reply) {
        try {
            if (host == null || dir == null || ledger == null) throw new IllegalArgumentException("UNKNOWN: missing host");
            UUID owner=host.ownerId(), companion=host.companionId();
            WorldContextStamp.Token token=host.identity();
            if (owner==null || companion==null || !TaskRecord.completeIdentity(token))
                throw new IllegalArgumentException("UNKNOWN: missing owner/companion/world identity");
            token = new WorldContextStamp.Token(Map.copyOf(token.identity()), token.epoch());
            String ref=required(args,"skill_ref"), version=required(args,"version"), tool=required(args,"target_tool");
            if (!ref.matches("skill:[^\\s:@]+") || !version.matches("v[1-9][0-9]*") || !tool.matches("[a-zA-Z0-9_]+"))
                throw new IllegalArgumentException("invalid skill/version/target_tool");
            var skill=SkillRegistrar.registryFor(dir).get(ref.substring(6)).orElse(null);
            if (skill==null || !version.equals("v"+skill.version()) || !skill.status().retrievable())
                throw new IllegalArgumentException("skill version/status mismatch");
            String body=SkillBodyStore.read(dir,skill.bodyRef());
            if (body==null || body.isBlank()) throw new IllegalArgumentException("missing body");
            String problem=LearnedLocationBinding.problem(body);
            if (!problem.isEmpty()) throw new IllegalArgumentException(problem);
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String id=UUID.randomUUID().toString();
            var choice=new Selection(owner,companion,token,ref,version,skill.bodyRef(),digest,tool,skill.kind().name(),skill.name(),ledger);
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("selection_id",id); data.put("skill_ref",ref); data.put("version",version);
            data.put("target_tool",tool); data.put("body_ref",skill.bodyRef()); data.put("body_digest",digest); data.put("body",body);
            data.put("meaning","仅短期关联，不是行动授权或有效性证明；回复不证明客户端收到");
            String json=TaskResult.ok("完整技能正文",data).toJson();
            if (json.length()>TaskResult.MAX_RESULT_JSON_LENGTH) throw new IllegalArgumentException("完整回复超长");
            synchronized (this) {
                purge();
                if (selections.size() >= MAX) throw new IllegalStateException("correlation capacity full");
                selections.put(id, choice); // Reserved but unusable until reply and identity recheck.
            }
            boolean activate = false;
            try {
                reply.accept(json);
                activate = owner.equals(host.ownerId()) && companion.equals(host.companionId()) && token.equals(host.identity());
            } catch (RuntimeException transportOrHostFailure) {
                // A failed transport or unavailable host can never activate this ID.
            } finally {
                synchronized (this) {
                    if (selections.get(id) == choice) {
                        if (activate && System.nanoTime() - choice.expires < 0) choice.active = true;
                        else selections.remove(id);
                    }
                }
            }
        } catch (Exception e) {
            try { reply.accept(TaskResult.fail("adopt_sync_skill 拒绝: "+e.getMessage()).toJson()); } catch (RuntimeException ignored) { }
        }
    }
    private static String required(JsonObject args,String key) {
        if (args==null || !args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString() || args.get(key).getAsString().isBlank()) throw new IllegalArgumentException("需要 "+key);
        return args.get(key).getAsString();
    }
    private void purge() { long now=System.nanoTime(); selections.values().removeIf(s -> now-s.expires>=0); }
    private boolean matches(Selection s, UUID owner, UUID companion, WorldContextStamp.Token token, String tool) {
        return s!=null && s.active && s.owner.equals(owner) && s.companion.equals(companion) && s.token.equals(token) && s.tool.equals(tool);
    }
    @Override public synchronized Binding bind(String id, UUID owner, UUID companion, WorldContextStamp.Token token, String wire, String tool) {
        if (id==null || id.isBlank()) return Binding.NONE;
        purge(); Selection s=selections.get(id);
        if (!matches(s,owner,companion,token,tool) || wire==null || wire.isBlank()) return Binding.REJECTED;
        if (s.wire!=null) return s.wire.equals(wire) ? Binding.DUPLICATE : Binding.REJECTED;
        s.wire=wire; return Binding.BOUND;
    }
    @Override public synchronized void receipt(String id, UUID owner, UUID companion, WorldContextStamp.Token token, String wire, String tool, Receipt receipt) {
        purge(); Selection s=selections.get(id);
        if (!matches(s,owner,companion,token,tool) || s.wire==null || !s.wire.equals(wire) || s.recorded) return;
        Receipt actual=receipt==null ? Receipt.UNKNOWN : receipt;
        JsonObject detail=new JsonObject();
        String key;
        try {
            key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        detail.addProperty("selection_hash",key); detail.addProperty("wire_id",wire); detail.addProperty("target_tool",tool);
        detail.addProperty("owner_id",owner.toString()); detail.addProperty("companion_id",companion.toString());
        detail.addProperty("world_id",token.identity().get("world_id")); detail.addProperty("server_session",token.identity().get("server_session")); detail.addProperty("epoch",token.epoch());
        detail.addProperty("skill_ref",s.ref); detail.addProperty("version",s.version); detail.addProperty("body_ref",s.bodyRef); detail.addProperty("body_digest",s.bodyDigest);
        detail.addProperty("receipt",actual.name()); detail.addProperty("meaning","真实回执待审；UNKNOWN不是效果，非有效性证据");
        UsageLedger.Outcome outcome=switch(actual) { case SUCCESS -> UsageLedger.Outcome.SUCCESS; case FAIL -> UsageLedger.Outcome.FAIL; case CANCELED -> UsageLedger.Outcome.CANCELED; case UNKNOWN -> UsageLedger.Outcome.UNKNOWN; };
        s.ledger.append(s.ref+"@"+s.version+"#sync="+key,s.kind,s.name,UsageLedger.Phase.RESULT,outcome,detail.toString(),"sync-skill:"+key,companion.toString());
        s.recorded=true;
    }
}
