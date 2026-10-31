package com.dwinovo.numen.plugins.learner.sample;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 学习样本链上的统一事件（160 §4）。
 *
 * <h2>为什么样本层必须发事件，而不是"存了就行"</h2>
 * 160 §4 要求「每个事件至少带 eventId、eventType、at、runId、actor、sourceRef、关联 ID、
 * 状态、证据引用和 schemaVersion」，§9 把「只有单测或文档却宣称已统一」列为停工条件。
 * 样本写进 JSONL 只说明<b>存储</b>发生了，说明不了「谁在什么时候因为什么把它登记进来的」。
 * 没有事件，事后无法回答「这条样本是哪次运行、哪个入口创建的」—— 而那正是 161 审计里
 * 发现的现存问题（三条 intake 口规则不齐、无统一因果链）。
 *
 * <h2>为什么 eventId 是算出来的，不是 UUID</h2>
 * 离线回放要能<b>重放同一批事件</b>并得到同一份账。UUID 每次不同，重放就永远对不上。
 * 所以 eventId = 短哈希(eventType@at#sampleId#occurrenceId)：同一次登记重复执行得到同一个 id，
 * 天然幂等；真的两个不同事件撞上全部字段，它们本来就是同一件事。
 *
 * <h2>缺 runId 时怎么办</h2>
 * 160 §3 要求「所有学习事件至少带 runId」，同时禁止「伪造补齐」。历史入口（learner_note、
 * 老的 instrumentation 行）没有 runId，这里写死字面量 {@link #UNKNOWN}，
 * 让"这条事件没法回到 run"变成一个<b>可统计的事实</b>，而不是悄悄编一个 id 蒙过去。
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public record SampleEvent(
        String eventId,
        String eventType,
        long at,
        String runId,
        String actor,
        String sampleId,
        String occurrenceId,
        String state,
        List<String> evidenceRefs,
        int schemaVersion
) {

    /** 事件外壳版本。加字段不升版本；删改语义必须升。 */
    public static final int SCHEMA_VERSION = 1;

    // —— 160 §4 事件族里属于样本层的那几个 ——
    public static final String SAMPLE_CREATED = "SAMPLE_CREATED";
    public static final String SAMPLE_DEDUPED = "SAMPLE_DEDUPED";
    public static final String SAMPLE_OCCURRENCE_ADDED = "SAMPLE_OCCURRENCE_ADDED";

    /** 缺 runId 时的显式标记（160 §3：旧数据缺 ID 用 legacyRef/UNKNOWN，不许伪造）。 */
    public static final String UNKNOWN = "UNKNOWN";

    public static final String ACTOR_INTERNAL_LEARNER = "learner:internal";

    public SampleEvent {
        eventId = eventId == null ? "" : eventId.trim();
        eventType = eventType == null ? "" : eventType.trim();
        runId = (runId == null || runId.isBlank()) ? UNKNOWN : runId.trim();
        actor = (actor == null || actor.isBlank()) ? UNKNOWN : actor.trim();
        sampleId = sampleId == null ? "" : sampleId.trim();
        occurrenceId = occurrenceId == null ? "" : occurrenceId.trim();
        state = state == null ? "" : state.trim();
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("事件必须有 eventType");
        }
        if (sampleId.isBlank()) {
            // 160 §3：样本链事件必须带 sampleId。没有它的事件无法回到样本，等于没有接线。
            throw new IllegalArgumentException("样本链事件必须带 sampleId");
        }
    }

    /** 构造一条样本事件；eventId 由内容决定，可重放。 */
    public static SampleEvent of(String eventType,
                                 long at,
                                 String runId,
                                 String actor,
                                 LearningSample sample,
                                 String occurrenceId,
                                 List<String> evidenceRefs) {
        if (sample == null) {
            throw new IllegalArgumentException("事件必须绑定样本");
        }
        String evId = "ev-" + SampleFingerprint.shortHash(
                eventType + "@" + at + "#" + sample.sampleId() + "#" + occurrenceId);
        return new SampleEvent(evId, eventType, at,
                (runId == null || runId.isBlank()) ? sample.runId() : runId,
                actor, sample.sampleId(), occurrenceId, sample.state().name(),
                evidenceRefs, SCHEMA_VERSION);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", schemaVersion);
        o.addProperty("eventId", eventId);
        o.addProperty("eventType", eventType);
        o.addProperty("at", at);
        o.addProperty("runId", runId);
        o.addProperty("actor", actor);
        o.addProperty("sampleId", sampleId);
        o.addProperty("occurrenceId", occurrenceId);
        o.addProperty("state", state);
        JsonArray refs = new JsonArray();
        for (String r : evidenceRefs) {
            refs.add(r);
        }
        o.add("evidenceRefs", refs);
        return o;
    }

    /** 事件能不能回到某个 run（门诊指标：为 0 说明整条链缺 run 关联）。 */
    public boolean hasRun() {
        return !UNKNOWN.equals(runId);
    }

    public static List<String> refs(String... rs) {
        List<String> out = new ArrayList<>();
        for (String r : rs) {
            if (r != null && !r.isBlank()) {
                out.add(r);
            }
        }
        return out;
    }
}
