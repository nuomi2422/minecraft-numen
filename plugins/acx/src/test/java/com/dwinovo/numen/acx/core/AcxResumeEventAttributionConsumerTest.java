package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxRunBinding;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same event.toMap serialization used by AcxMonitor, not its game-directory I/O. */
class AcxResumeEventAttributionConsumerTest {
    private static final String SCRIPT = """
            {"name":"resume_event_owner","version":"1","steps":[
              {"id":"wait","block":"guard","params":{"condition":{"field":"$input.ready","op":"==","value":true}}},
              {"id":"end","block":"guard","params":{"condition":{"field":true,"op":"==","value":true}}}
            ]}
            """;

    @Test
    void sameNamedRunsCanEachConsumeTheirOwnSerializedResumeStartedEvent() {
        Fixture f = new Fixture();
        String first = f.start();
        String second = f.start();
        assertNotEquals(first, second);
        f.resume(first);
        f.resume(second);
        assertAll("a consumer filtering by the actual run must see its own resume",
                () -> f.state(first, AcxStatus.SUCCESS, 2),
                () -> f.state(second, AcxStatus.SUCCESS, 2),
                () -> assertEquals(1, f.events(first, AcxEvent.Kind.RESUME_STARTED).size()),
                () -> assertEquals(1, f.events(second, AcxEvent.Kind.RESUME_STARTED).size()),
                () -> assertEquals(0, f.events("resume", AcxEvent.Kind.RESUME_STARTED).size()),
                () -> assertEquals(1, f.events(first, AcxEvent.Kind.RUN_FINISHED).stream()
                        .filter(row -> "SUCCESS".equals(row.get("status").getAsString())).count()),
                () -> assertEquals(1, f.events(second, AcxEvent.Kind.RUN_FINISHED).stream()
                        .filter(row -> "SUCCESS".equals(row.get("status").getAsString())).count()));
    }

    @Test
    void rejectedDefinitionCanBeExplainedByTheOriginalRunsSerializedResumeEvent() {
        Fixture f = new Fixture();
        String run = f.start();
        AcxRunRecord prior = f.sessions.session(run).record();
        AcxDefinition changed = AcxLoader.parseJson(SCRIPT.replace("\"end\"", "\"changed_end\""), f.registry);
        assertNotEquals(AcxFingerprint.of(f.definition), AcxFingerprint.of(changed));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> f.runner.resume(changed, prior, Map.of("ready", true)));
        assertTrue(failure.getMessage().contains("内容已变"), failure.getMessage());
        List<JsonObject> rejection = f.events(run, AcxEvent.Kind.RESUME_REJECTED);
        assertAll("rejected runner requests retain known ownership rather than a placeholder",
                () -> assertEquals(1, rejection.size()),
                () -> assertEquals(0, f.events("resume", AcxEvent.Kind.RESUME_REJECTED).size()),
                () -> assertEquals(0, f.events(run, AcxEvent.Kind.RESUME_STARTED).size()),
                () -> assertSame(prior, f.sessions.session(run).record()),
                () -> f.state(run, AcxStatus.PAUSED, 0),
                () -> {
                    if (!rejection.isEmpty()) {
                        assertEquals("resume_event_owner", rejection.get(0).get("ac_name").getAsString());
                        assertEquals(failure.getMessage(), rejection.get(0).get("reason").getAsString());
                    }
                });
    }

    @Test
    void aRequestWithoutAPriorRecordRemainsUnattributedInsteadOfInventingARun() {
        Fixture f = new Fixture();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> f.runner.resume(f.definition, null, Map.of("ready", true)));
        assertTrue(failure.getMessage().contains("没有可续传"), failure.getMessage());
        List<JsonObject> rejected = f.events("resume", AcxEvent.Kind.RESUME_REJECTED);
        assertEquals(1, rejected.size(), "without a known record the old unattributed diagnostic remains explicit");
        assertEquals(failure.getMessage(), rejected.get(0).get("reason").getAsString());
        assertEquals(1, f.serialized.size(), "a rejected request cannot manufacture a start or completion");
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final AcxDefinition definition = AcxLoader.parseJson(SCRIPT, registry);
        final List<String> serialized = new ArrayList<>();
        final Gson gson = new Gson();
        final AcxRunBinding binding = new AcxRunBinding("resume-event-consumer", new Object());
        final AcxRunner runner = AcxRunner.builder().tools(registry)
                .events(event -> serialized.add(gson.toJson(event.toMap()))).build();
        final AcxSessionManager sessions = new AcxSessionManager(runner, Runnable::run);
        final AcxFacade facade = new AcxFacade(sessions,
                name -> definition.name().equals(name) ? definition : null, registry, null);

        String start() {
            Map<String, Object> accepted = data(facade.executeBound(
                    Map.of("ac_name", definition.name(), "input", Map.of("ready", false)), binding));
            assertEquals("RUNNING", accepted.get("status"), "execute reports acceptance, not completion");
            String run = assertInstanceOf(String.class, accepted.get("run_id"));
            state(run, AcxStatus.PAUSED, 0);
            return run;
        }

        void resume(String run) {
            Map<String, Object> accepted = data(facade.resumeBound(
                    Map.of("run_id", run, "input", Map.of("ready", true)), binding));
            assertEquals(run, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume reports acceptance, not completion");
            state(run, AcxStatus.SUCCESS, 2);
        }

        void state(String run, AcxStatus expected, int checkpoint) {
            AcxSessionManager.Session session = sessions.session(run);
            assertNotNull(session);
            assertTrue(session.matchesBinding(binding));
            assertFalse(session.isRunning());
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String, Object> queried = data(facade.statusBound(Map.of("run_id", run), binding));
            assertEquals(run, queried.get("run_id"));
            assertEquals(expected.name(), queried.get("state"));
        }

        List<JsonObject> events(String run, AcxEvent.Kind kind) {
            return serialized.stream().map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> run.equals(row.get("run_id").getAsString())
                            && kind.name().equals(row.get("kind").getAsString())).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
}
