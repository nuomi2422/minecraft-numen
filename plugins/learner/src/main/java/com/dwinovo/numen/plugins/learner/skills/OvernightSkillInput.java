package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.api.observation.WorldObservationService.Response;

import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Read-only recall cues. No bed availability, movement or sleep permission is inferred. */
final class OvernightSkillInput {
    static final String PACKAGE = "scene:overnight:package";
    static final String COMPONENT = "scene:overnight:component";
    private static final Pattern GOAL = Pattern.compile(
            "安家|过夜|入睡|睡前|睡觉|就寝|避一晚|\\b(?:overnight|sleep|bedtime|settle in|spend the night)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String OVERWORLD = "minecraft:overworld";
    enum WorldHint { UNKNOWN, NO_CUE, LATE_DAY, THUNDER }

    private OvernightSkillInput() {}

    static String sceneKind(SkillEntry entry) {
        boolean pack = entry.preconditions().contains(PACKAGE);
        boolean component = entry.preconditions().contains(COMPONENT);
        return pack && component ? "invalid" : pack ? "package" : component ? "component" : "";
    }

    static boolean goalCue(String objective) {
        return objective != null && GOAL.matcher(objective).find();
    }

    static WorldHint read(WorldObservationService service, UUID companion) {
        return read(service, companion, WorldContextStamp::capture, System::currentTimeMillis);
    }

    static WorldHint read(WorldObservationService service, UUID companion,
                          Supplier<WorldContextStamp.Token> identity, LongSupplier clock) {
        if (service == null || companion == null) return WorldHint.UNKNOWN;
        var stamp = identity.get();
        if (stamp == null || stamp.identity() == null || stamp.epoch() <= 0) return WorldHint.UNKNOWN;
        Response self = service.readSelf("learner-overnight", companion, 3_000);
        if (!valid(self, "self", stamp, clock.getAsLong()) ||
                !companion.toString().equalsIgnoreCase(self.metadata().subjectId())) return WorldHint.UNKNOWN;
        // SELF supplies the sampled dimension; do not select the overworld by default.
        if (!OVERWORLD.equals(self.metadata().dimension())) return WorldHint.NO_CUE;
        Response env = service.read(new WorldObservationService.Request(
                "learner-overnight", OVERWORLD, "environment", Map.of(), 30_000, 1, 0));
        long now = clock.getAsLong();
        if (!stamp.equals(identity.get()) || !valid(self, "self", stamp, now)
                || !valid(env, "environment", stamp, now)
                || !OVERWORLD.equals(env.metadata().dimension())) return WorldHint.UNKNOWN;
        var item = env.items().getFirst();
        if (!OVERWORLD.equals(known(item, "dimension"))) return WorldHint.UNKNOWN;
        Object thunder = known(item, "thundering");
        if (Boolean.TRUE.equals(thunder)) return WorldHint.THUNDER;
        Object dayTime = known(item, "day_time");
        if (!(dayTime instanceof Long || dayTime instanceof Integer)) return WorldHint.UNKNOWN;
        long ticks = ((Number) dayTime).longValue();
        if (ticks < 0) return WorldHint.UNKNOWN;
        // A broad clock cue for preparation only; vanilla sleep uses additional server checks.
        long timeOfDay = ticks % 24_000L;
        if (timeOfDay >= 12_000 && timeOfDay < 23_000) return WorldHint.LATE_DAY;
        return Boolean.FALSE.equals(thunder) ? WorldHint.NO_CUE : WorldHint.UNKNOWN;
    }

    private static boolean valid(Response response, String topic, WorldContextStamp.Token stamp, long now) {
        if (response == null || !topic.equals(response.topic()) ||
                response.status() != WorldObservationService.Status.OK ||
                response.coverage() != WorldObservationService.Coverage.FULL || response.items().size() != 1)
            return false;
        var m = response.metadata();
        return m.identified() && m.freshness() == ObservationMetadata.Freshness.CURRENT &&
                m.observedAtMs() <= now && now < m.validUntilMs() &&
                m.worldId().equals(stamp.identity().get("world_id")) &&
                m.serverSession().equals(stamp.identity().get("server_session"));
    }

    private static Object known(WorldObservationService.Item item, String field) {
        return item.unknownFields().contains(field) || item.staleFields().contains(field)
                ? null : item.values().get(field);
    }

    static boolean worldCue(WorldHint hint) {
        return hint == WorldHint.LATE_DAY || hint == WorldHint.THUNDER;
    }
}
