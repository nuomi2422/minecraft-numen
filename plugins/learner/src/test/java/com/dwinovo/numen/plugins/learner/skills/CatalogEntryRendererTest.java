package com.dwinovo.numen.plugins.learner.skills;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 056 §20: pure rendering only; no delivery or execution is inferred. */
class CatalogEntryRendererTest {
    private static CatalogEntryRenderer.Candidate candidate(String id, String name, String purpose) {
        return new CatalogEntryRenderer.Candidate("skill:" + id + "@v1", name, purpose,
                "EXECUTOR", "TRIAL", true, true, true);
    }
    @Test void liveSizedCandidatesFitThreeWithoutTruncation() {
        List<CatalogEntryRenderer.Candidate> entries = new ArrayList<>();
        for (int i = 0; i < 39; i++) entries.add(candidate("sk-"+i, "name-"+i, "purpose".repeat(26)));
        var out = CatalogEntryRenderer.render(entries, "EXECUTOR", 1160);
        assertEquals(3, out.entries().size());
        assertEquals(39, out.decisions().size());
        assertEquals("over-limit", out.decisions().get(3).reason());
        assertTrue(out.entries().stream().allMatch(e -> e.length() <= 320));
        assertTrue(out.fragment().length() <= 1160);
        assertTrue(out.fragment().contains("status=\"TRIAL\""));
        assertTrue(out.fragment().startsWith("<retrieved_knowledge>"));
        assertTrue(out.fragment().endsWith("</retrieved_knowledge>"));
    }
    @Test void emptyLibraryIsNoMatchAndNoPlaceholder() {
        var out = CatalogEntryRenderer.render(List.of(), "EXECUTOR", 1200);
        assertEquals("no-match", out.reason());
        assertTrue(out.fragment().isEmpty());
    }
    @Test void xmlEscapesOriginalFieldsWithoutPretendingConditionsAreInBody() {
        var out = CatalogEntryRenderer.render(List.of(candidate("safe", "<&>\"'", "A & B < C > D \"Q\" 'S'")), "EXECUTOR", 1200);
        assertEquals(1, out.entries().size());
        String xml = out.fragment();
        assertTrue(xml.contains("&lt;&amp;&gt;&quot;&apos;"));
        assertTrue(xml.contains("A &amp; B &lt; C &gt; D &quot;Q&quot; &apos;S&apos;"));
        assertFalse(xml.contains("see body"));
    }
    @Test void overlongEntryIsRejectedWithoutTruncationAndLaterCandidateSurvives() {
        var out = CatalogEntryRenderer.render(List.of(candidate("long", "n", "x".repeat(400)),
                candidate("good", "n", "short")), "EXECUTOR", 1200);
        assertEquals("over-limit", out.decisions().get(0).reason());
        assertEquals(1, out.entries().size());
        assertTrue(out.fragment().contains("skill:good@v1"));
        assertFalse(out.fragment().contains("skill:long@v1"));
    }
    @Test void knownConditionsRankBeforeUnknownAndUnknownRemainsDiscoverable() {
        var unknownA = new CatalogEntryRenderer.Candidate("skill:u1@v1", "u1", "purpose", "EXECUTOR", "TRIAL", true, false, true);
        var unknownB = new CatalogEntryRenderer.Candidate("skill:u2@v1", "u2", "purpose", "EXECUTOR", "TRIAL", true, false, true);
        var known = candidate("known", "known", "purpose");
        var mixed = CatalogEntryRenderer.render(List.of(unknownA, known), "EXECUTOR", 1200);
        assertTrue(mixed.fragment().indexOf("skill:known@v1") < mixed.fragment().indexOf("skill:u1@v1"));
        var onlyUnknown = CatalogEntryRenderer.render(List.of(unknownA, unknownB), "EXECUTOR", 1200);
        assertEquals(2, onlyUnknown.entries().size());
        assertEquals("condition-unknown", onlyUnknown.decisions().get(1).reason());
        assertTrue(onlyUnknown.fragment().contains("条件未知"));
    }
    @Test void oversizedKnownDoesNotHideLaterUnknownCandidates() {
        var huge = candidate("huge", "known", "x".repeat(400));
        var a = new CatalogEntryRenderer.Candidate("skill:u1@v1", "u1", "x", "EXECUTOR", "TRIAL", true, false, true);
        var b = new CatalogEntryRenderer.Candidate("skill:u2@v1", "u2", "x", "EXECUTOR", "TRIAL", true, false, true);
        var out = CatalogEntryRenderer.render(List.of(huge, a, b), "EXECUTOR", 1200);
        assertEquals("over-limit", out.decisions().get(0).reason());
        assertEquals(2, out.entries().size());
        assertEquals("condition-unknown", out.decisions().get(2).reason());
    }
    @Test void invalidReferencesAndXmlControlCharactersCannotBeAdvertised() throws Exception {
        for (String ref : List.of("skill:bad.id@v1", "skill:<id>@v1", "skill:id@v2147483648")) {
            var bad = new CatalogEntryRenderer.Candidate(ref, "x", "y", "EXECUTOR", "TRIAL", true, true, true);
            assertTrue(CatalogEntryRenderer.render(List.of(bad), "EXECUTOR", 1200).entries().isEmpty());
        }
        var badText = candidate("bad", "bad\u0001name", "purpose");
        assertTrue(CatalogEntryRenderer.render(List.of(badText), "EXECUTOR", 1200).entries().isEmpty());
        var valid = CatalogEntryRenderer.render(List.of(candidate("good", "emoji😀", "safe & sound")), "EXECUTOR", 1200);
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(valid.fragment().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void allShownConditionsRemainUnknownEvenWhenMetadataKnown() {
        var a = candidate("known1", "n", "purpose");
        var b = candidate("known2", "n", "purpose");
        var u = new CatalogEntryRenderer.Candidate("skill:u1@v1", "u", "purpose", "EXECUTOR", "TRIAL", true, false, true);
        var v = new CatalogEntryRenderer.Candidate("skill:u2@v1", "u", "purpose", "EXECUTOR", "TRIAL", true, false, true);
        var out = CatalogEntryRenderer.render(List.of(u, a, v, b), "EXECUTOR", 1200);
        assertEquals(3, out.entries().size());
        assertTrue(out.entries().stream().allMatch(x -> x.contains("conditions=\"UNKNOWN\"")));
        assertEquals("over-limit", out.decisions().get(2).reason());
        assertFalse(out.fragment().contains("authorized"));
        assertFalse(out.fragment().contains("ready"));
        assertTrue(out.fragment().contains("执行前须以当前观测复核"));
    }
    @Test void xmlCarriageReturnsRoundTripWithoutNormalization() throws Exception {
        String name = "name\rnext\r\nlast";
        var out = CatalogEntryRenderer.render(List.of(candidate("valid", name, "purpose")), "EXECUTOR", 1200);
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(out.fragment().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(name, document.getElementsByTagName("name").item(0).getTextContent());
    }
    @Test void emptyAudienceIsWildcardUnsafePurposeRejectedAndRefDeduplicated() {
        var empty = new CatalogEntryRenderer.Candidate("skill:empty@v1", "n", "purpose", "", "TRIAL", true, true, true);
        var script = candidate("script", "n", "{\"steps\":[1]}");
        var unknown = new CatalogEntryRenderer.Candidate("skill:dup@v1", "n", "purpose", "EXECUTOR", "TRIAL", true, false, true);
        var known = candidate("dup", "n", "purpose");
        var out = CatalogEntryRenderer.render(List.of(empty, script, unknown, known), "EXECUTOR", 1200);
        assertTrue(out.decisions().get(0).included());
        assertEquals("unsafe-purpose", out.decisions().get(1).reason());
        assertEquals("duplicate-enriched", out.decisions().get(2).reason());
        assertTrue(out.decisions().get(3).included());
        assertEquals(2, out.entries().size());
    }
    @Test void sameReferenceWithConflictingFactsRejectsWholeGroup() {
        var a = candidate("same", "name", "purpose A");
        var b = candidate("same", "name", "purpose B");
        var out = CatalogEntryRenderer.render(List.of(a, b), "EXECUTOR", 1200);
        assertTrue(out.entries().isEmpty());
        for (var decision : out.decisions()) {
            assertEquals("ref-conflict", decision.reason());
            assertTrue(decision.differences().contains("purpose"));
        }
    }
    @Test void identicalReferencesDeduplicateWithoutUsingAnotherSlot() {
        var a = candidate("same", "name", "purpose");
        var out = CatalogEntryRenderer.render(List.of(a, a), "EXECUTOR", 1200);
        assertEquals(1, out.entries().size());
        assertEquals("duplicate", out.decisions().get(1).reason());
    }
    @Test void omittedMetadataIsUnknownNotInferredFromBody() {
        var unknown = new CatalogEntryRenderer.Candidate("skill:unknown@v1", "n", "purpose", "EXECUTOR", "TRIAL", true, false, true);
        var out = CatalogEntryRenderer.render(List.of(unknown), "EXECUTOR", 1200);
        assertEquals("condition-unknown", out.decisions().get(0).reason());
        assertEquals(1, out.entries().size());
        assertTrue(out.fragment().contains("conditions=\"UNKNOWN\""));
        assertTrue(out.fragment().contains("条件未知"));
        assertFalse(out.fragment().contains("see body"));
    }
}
