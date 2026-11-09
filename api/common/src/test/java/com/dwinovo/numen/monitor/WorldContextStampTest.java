package com.dwinovo.numen.monitor;

import com.dwinovo.numen.api.WorldContextStamp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextStampTest {
    @TempDir Path dir;

    @AfterEach void resetStamp() { WorldContextStamp.clear(); }

    @Test
    void sameWorldDifferentSessionStaysDistinct() {
        WorldContextStamp.set("same-world", "session-1");
        var a = WorldContextStamp.capture();
        WorldContextStamp.set("same-world", "session-2");
        var b = WorldContextStamp.capture();
        assertEquals("session-1", a.identity().get("server_session"));
        assertEquals("session-2", b.identity().get("server_session"));
    }

    @Test
    void tokenCapturedBeforeStopRemainsOldIdentity() {
        WorldContextStamp.set("world-A", "session-A");
        var beforeStop = WorldContextStamp.capture();
        WorldContextStamp.clear();
        var afterStop = WorldContextStamp.capture();
        assertEquals("world-A", beforeStop.identity().get("world_id"));
        assertFalse(afterStop.identity() != null);
    }

    @Test
    void differentServersDoNotShareIdentity() {
        WorldContextStamp.set("world-1", "session-1");
        var one = WorldContextStamp.capture();
        WorldContextStamp.set("world-2", "session-2");
        var two = WorldContextStamp.capture();
        assertFalse(one.identity().get("world_id").equals(two.identity().get("world_id")));
        assertFalse(one.identity().get("server_session").equals(two.identity().get("server_session")));
    }

    @Test
    void tokenSurvivesWorldSwitchAndNullTokenIsUnknown() throws Exception {
        WorldContextStamp.set("world-A", "session-A");
        WorldContextStamp.Token tokenA = WorldContextStamp.capture();
        WorldContextStamp.set("world-B", "session-B");
        WorldContextStamp.Token tokenB = WorldContextStamp.capture();

        MonitoringJournal journal = new MonitoringJournal(() -> dir, null);
        journal.publish("tools", "a", Map.of("v", 1), tokenA);
        journal.publish("tools", "b", Map.of("v", 2), tokenB);
        journal.publish("tools", "c", Map.of("v", 3), (WorldContextStamp.Token) null);
        journal.publish("tools", "d", Map.of("v", 4));
        Path out = dir.resolve("monitor/tools.jsonl");
        for (int i = 0; i < 100 && (!Files.exists(out) || Files.readAllLines(out).size() < 3); i++) {
            Thread.sleep(20);
        }
        journal.close();
        journal.awaitStopped(2000);

        var lines = Files.readAllLines(out);
        assertEquals(4, lines.size());
        JsonObject a = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        JsonObject b = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        JsonObject c = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        JsonObject d = JsonParser.parseString(lines.get(3)).getAsJsonObject();
        assertEquals("world-A", a.get("world_id").getAsString());
        assertEquals("session-A", a.get("server_session").getAsString());
        assertEquals(tokenA.epoch(), a.get("world_epoch").getAsLong());
        assertTrue(a.get("stamp_conflict").getAsBoolean());
        assertEquals("world-B", a.get("stamp_world_id").getAsString());
        assertEquals("world-B", b.get("world_id").getAsString());
        assertFalse(c.has("world_id"));
        assertFalse(c.has("server_session"));
        assertFalse(c.has("world_epoch"));
        assertEquals("world-B", d.get("world_id").getAsString());
        assertEquals("session-B", d.get("server_session").getAsString());
    }
}
