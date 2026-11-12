package com.dwinovo.numen.acx.test;

import com.dwinovo.numen.acx.core.AcxArtifactAdopter;
import com.dwinovo.numen.acx.core.AcxLoader;
import com.dwinovo.numen.acx.core.FileAcxLibrary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12 046: old PENDING artifacts use real parser/adopter/library and persisted feedback. */
class AcxArtifactLocationScopeTest {
    @TempDir Path dir;
    private FileAcxLibrary library() {
        var blocks = new Fake.Registry().add(Fake.fixed("goto", Map.of()));
        var lib = new FileAcxLibrary(dir.resolve("acx/library.json"), blocks); lib.load(); return lib;
    }
    private static String body(String params) {
        return "{\"name\":\"table\",\"version\":\"1\",\"description\":\"old-world/old-session historical2,88,-3\","
            + "\"steps\":[{\"id\":\"go\",\"block\":\"goto\",\"params\":" + params + "}]}";
    }
    private Path pending(String id, String body) throws Exception {
        Path file = dir.resolve("artifact-outbox/AC_SCRIPT/c1/" + id + ".json");
        Files.createDirectories(file.getParent());
        JsonObject o = new JsonObject();
        o.addProperty("artifact_id", id); o.addProperty("kind", "AC_SCRIPT");
        o.addProperty("companion_id", "c1"); o.addProperty("memo_id", "memo");
        o.addProperty("body", body); o.addProperty("status", "PENDING");
        o.addProperty("content_hash", "original-hash");
        Files.writeString(file, o.toString()); return file;
    }
    @Test void oldPendingStaticGotoIsRejectedWithOriginalBodyAndFeedback() throws Exception {
        String original = body("{\"x\":2,\"y\":88,\"z\":-3}");
        Path file = pending("old", original); var lib = library();
        var adopter = new AcxArtifactAdopter(dir, lib, Set.of());
        var report = adopter.adoptAll();
        assertEquals(0, report.adopted()); assertEquals(1, report.rejected());
        assertFalse(lib.hasAnyVersion("table"));
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("REJECTED", saved.get("status").getAsString());
        assertEquals(original, saved.get("body").getAsString());
        assertEquals("original-hash", saved.get("content_hash").getAsString());
        assertTrue(saved.get("status_detail").getAsString().contains("location-binding-required"));
        assertTrue(saved.has("history")); assertEquals(0, adopter.adoptAll().scanned());
    }
    @Test void parsedChildrenAndHistoricalFallbackCannotPublish() throws Exception {
        String nested = "{\"name\":\"table\",\"version\":\"1\",\"steps\":[{\"id\":\"gate\",\"block\":\"if\",\"params\":{\"field\":\"$input.ready\",\"op\":\"==\",\"value\":true},\"children\":[{\"id\":\"go\",\"block\":\"goto\",\"params\":{\"x\":2,\"y\":88,\"z\":-3}}]}]}";
        pending("children", nested);
        pending("fallback", body("{\"x\":{\"$from\":\"$input.table.x\",\"$default\":2},\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}"));
        var lib = library(); var report = new AcxArtifactAdopter(dir, lib, Set.of()).adoptAll();
        assertEquals(0, report.adopted()); assertEquals(2, report.rejected());
        assertFalse(lib.hasAnyVersion("table"));
    }
    @Test void boundProcedureStillPublishesThroughExistingAdoption() throws Exception {
        String original = body("{\"x\":\"$input.table.x\",\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}");
        Path file = pending("bound", original); var lib = library();
        var report = new AcxArtifactAdopter(dir, lib, Set.of()).adoptAll();
        assertEquals(1, report.adopted()); assertEquals(0, report.rejected());
        assertNotNull(lib.active("table"));
        assertEquals(original, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("body").getAsString());
    }
    @Test void normalDescriptorWithoutHistoricalDefaultStillPublishes() throws Exception {
        pending("descriptor", body("{\"x\":{\"$from\":\"$input.table.x\"},\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}"));
        var lib = library();
        assertEquals(1, new AcxArtifactAdopter(dir, lib, Set.of()).adoptAll().adopted());
    }
    @Test void manualWorldSpecificLibraryPublicationKeepsExistingContract() {
        var lib = library();
        var definition = AcxLoader.parseJson(body("{\"x\":2,\"y\":88,\"z\":-3}"), null);
        lib.publish(definition, "manual world-specific location");
        assertNotNull(lib.active("table"));
    }
}
