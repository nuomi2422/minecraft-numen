package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationBatch;
import com.dwinovo.numen.api.observation.WorldObservationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 4 主题合并成 1 次 batchRead（2026-11-07）：查询构造与逐条结果解释，纯逻辑可测。 */
final class RddObservationBatch {
    private RddObservationBatch() {}

    static final String TOPIC_ENV = "environment";
    static final String TOPIC_BLOCKS = "blocks";
    static final String TOPIC_ENTITIES = "entities";

    static ObservationBatch.Query query(String dim, int px, int py, int pz) {
        String box = (px - 8) + "," + (py - 12) + "," + (pz - 8) + ","
                + (px + 8) + "," + (py + 12) + "," + (pz + 8);
        List<WorldObservationService.Request> reqs = List.of(
                new WorldObservationService.Request("rdd-env", dim, TOPIC_ENV, Map.of(), 0L, 1, 0L),
                new WorldObservationService.Request("rdd-env", dim, TOPIC_BLOCKS,
                        Map.of("box", box, "blocks", RddObservation.NEARBY_IDS), 0L, 32, 0L),
                new WorldObservationService.Request("rdd-env", dim, TOPIC_ENTITIES,
                        Map.of("box", dangerBox(px,py,pz, RddDangerText.SCAN_RADIUS), "category", "hostile"), 0L, 64, 0L),
                new WorldObservationService.Request("rdd-env", dim, TOPIC_ENTITIES,
                        Map.of("box", dangerBox(px,py,pz, RddDangerText.CLOSE_RADIUS), "category", "hostile"), 0L, 64, 0L));
        return new ObservationBatch.Query("rdd-env-batch", "", "", reqs, 0L, ObservationBatch.MAX_DEADLINE_MS);
    }

    private static String dangerBox(int x,int y,int z,int r){
        return (x-r)+","+(y-r)+","+(z-r)+","+(x+r)+","+(y+r)+","+(z+r);
    }

    /** 逐条判断：缺哪个主题、哪个主题 ERROR、observedTick 是否同一轮。 */
    record Summary(List<String> missingTopics, List<String> errorTopics, List<String> topics,
                   boolean sameTick, int observedCount) {}

    static Summary summarize(ObservationBatch.Result result) {
        List<WorldObservationService.Response> responses =
                result == null ? List.of() : result.responses();
        List<String> missing = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> topics = new ArrayList<>();
        List<Long> ticks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String topic = i == 0 ? TOPIC_ENV : i == 1 ? TOPIC_BLOCKS : TOPIC_ENTITIES;
            String label = i == 3 ? TOPIC_ENTITIES + "#close" : i == 2 ? TOPIC_ENTITIES + "#scan" : topic;
            if (i >= responses.size() || responses.get(i) == null) {
                missing.add(label);
                continue;
            }
            WorldObservationService.Response r = responses.get(i);
            topics.add(label);
            ticks.add(r.observedTick());
            if (r.status() == WorldObservationService.Status.ERROR) errors.add(label);
        }
        boolean sameTick = !ticks.isEmpty() && ticks.stream().allMatch(t -> t == ticks.get(0));
        return new Summary(List.copyOf(missing), List.copyOf(errors), List.copyOf(topics), sameTick, responses.size());
    }
}
