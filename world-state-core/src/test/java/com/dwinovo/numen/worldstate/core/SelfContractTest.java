package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.model.*;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload.*;
import com.dwinovo.numen.worldstate.core.query.QueryService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline B2a contract checks, 2026-11-06 (no provider, host, game, or deployment).
 * WHY: fixed slot identity and unknown-vs-empty must not be inferred from missing JSON fields.
 * USAGE: :world-state-core:test; mutation of input collections exercises detached ownership.
 * Not host mocks: this suite proves data behavior only, not sampling or delivery.
 */
class SelfContractTest {
    private static final String DIM = "minecraft:overworld";
    private static final UUID ID = UUID.fromString("12345678-abcd-abcd-abcd-123456789012");

    private static List<Slot> slots(int start, int count) {
        List<Slot> result = new ArrayList<>();
        for (int i = start; i < start + count; i++) result.add(Slot.empty(i));
        return result;
    }

    private static SelfPayload sample(List<Slot> main, List<Slot> armor, Slot offhand,
                                      Integer selected, List<Effect> effects) {
        return new SelfPayload(DIM, ID, main, armor, offhand, selected, 18f, 20f, 19, 3f,
                effects, new Vehicle("minecraft:boat", ID),
                new BodyTask("task-1", "follow", "following", true, 100L));
    }

    private static SelfPayload full() {
        List<Slot> main = slots(0, 36);
        main.set(8, new Slot(8, "minecraft:iron_sword", 1, 7, 250, true));
        return sample(main, slots(36, 4), new Slot(40, "minecraft:shield", 1, 4, 336, true), 8,
                List.of(new Effect("minecraft:speed", 0, 200, false, 300L),
                        new Effect("minecraft:night_vision", 1, null, true, null)));
    }

    private static Observation observation(SelfPayload self) {
        return new Observation(ObservationRequest.self(DIM, ID).keyFor("world-a"), self,
                "test-only", Origin.DIRECT, 100, 1_000, Coverage.COMPLETE, Freshness.CURRENT, 5, 1, "session-a");
    }

    private static JsonObject stored(SelfPayload self) {
        return JsonParser.parseString(ObservationJson.toJson(List.of(observation(self))))
                .getAsJsonObject();
    }

    private static JsonObject entry(JsonObject root) {
        return root.getAsJsonArray("observations").get(0).getAsJsonObject();
    }

    @Test void copiesCollectionsAndExposesOnlyImmutableValues() {
        List<Slot> main = slots(0, 36), armor = slots(36, 4);
        List<Effect> effects = new ArrayList<>(full().effects());
        SelfPayload self = sample(main, armor, Slot.empty(40), 0, effects);
        main.clear(); armor.clear(); effects.clear();
        assertEquals(36, self.main().size());
        assertEquals(4, self.armor().size());
        assertEquals(2, self.effects().size());
        assertThrows(UnsupportedOperationException.class, () -> self.main().clear());
        assertThrows(UnsupportedOperationException.class, () -> self.armor().clear());
        assertThrows(UnsupportedOperationException.class, () -> self.effects().clear());
    }

    @Test void fixed41SlotsIncludeHotbarEquipmentAndDerivedMainHand() {
        SelfPayload self = full();
        assertEquals(Topic.SELF, self.topic());
        assertSame(self.main().get(8), self.mainHand());
        assertEquals("minecraft:iron_sword", self.mainHand().id());
        assertEquals(7, self.mainHand().damage());
        assertEquals(36, self.armor().getFirst().slot());
        assertEquals(39, self.armor().getLast().slot());
        assertEquals(40, self.offhand().slot());
    }

    @Test void rejectsCompactedDuplicateMisorderedAndNullSlots() {
        assertThrows(IllegalArgumentException.class, () -> sample(slots(0, 35), slots(36, 4), Slot.empty(40), 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> sample(slots(0, 36), slots(36, 3), Slot.empty(40), 0, List.of()));
        List<Slot> duplicate = slots(0, 36); duplicate.set(1, Slot.empty(0));
        assertThrows(IllegalArgumentException.class, () -> sample(duplicate, null, null, 0, null));
        List<Slot> misordered = slots(0, 36); Collections.swap(misordered, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> sample(misordered, null, null, 0, null));
        List<Slot> withNull = slots(0, 36); withNull.set(2, null);
        assertThrows(NullPointerException.class, () -> sample(withNull, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> sample(null, null, Slot.empty(0), 0, null));
        assertThrows(IllegalArgumentException.class, () -> Slot.empty(41));
    }

    @Test void rejectsInvalidSelectionAndScalarValues() {
        for (int selected : new int[]{-1, 9, 36})
            assertThrows(IllegalArgumentException.class, () -> sample(null, null, null, selected, null));
        assertThrows(IllegalArgumentException.class, () -> new Slot(0, "x", -1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Slot(0, "", 1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SelfPayload(DIM, ID, null, null, null,
                null, Float.NaN, null, null, null, null, null, null));
    }

    @Test void wearAndSameAmplifierRenewalRemainDifferentValues() {
        Slot old = full().mainHand();
        assertNotEquals(old, new Slot(8, old.id(), 1, 8, 250, true));
        Effect oldEffect = full().effects().getFirst();
        assertNotEquals(oldEffect, new Effect(oldEffect.id(), 0, 400, false, 500L));
        assertThrows(IllegalArgumentException.class, () -> new Effect("x", 0, null, true, 300L));
        assertThrows(IllegalArgumentException.class, () -> new Effect("x", 0, -1, false, null));
        assertThrows(IllegalArgumentException.class, () -> new Vehicle("minecraft:boat", null));
        assertThrows(IllegalArgumentException.class, () -> new Vehicle("", ID));
        assertThrows(IllegalArgumentException.class, () -> new Vehicle(null, null));
    }

    @Test void selfRequestCanonicalizesAndSeparatesSubjectsWorldsDimensionsAndTopics() {
        ObservationRequest req = ObservationRequest.self(" MINECRAFT:OVERWORLD ", ID);
        assertEquals(DIM, req.dimension());
        assertEquals("player:" + ID, req.target());
        assertEquals(req, new ObservationRequest(Topic.SELF, DIM, "player:" + ID.toString().toUpperCase(Locale.ROOT), "", null));
        assertNotEquals(req.keyFor("a"), req.keyFor("b"));
        assertNotEquals(req.keyFor("a"), ObservationRequest.self("minecraft:the_end", ID).keyFor("a"));
        assertNotEquals(req.keyFor("a"), ObservationRequest.self(DIM, UUID.randomUUID()).keyFor("a"));
        assertNotEquals(req.keyFor("a"), ObservationRequest.containers(DIM, req.target()).keyFor("a"));
        assertEquals(Topic.SELF, Topic.byId(" SELF "));
        assertNull(req.bounds()); assertEquals(0, req.volume()); assertEquals("", req.filter());
    }

    @Test void selfRequestRejectsImplicitOrNonCanonicalSelectors() {
        assertThrows(IllegalArgumentException.class, () -> ObservationRequest.self(DIM, null));
        assertThrows(IllegalArgumentException.class, () -> ObservationRequest.self("", ID));
        for (String target : List.of("", "player:1-1-1-1-1", "block:0,0,0", "player:not-a-uuid"))
            assertThrows(IllegalArgumentException.class, () -> new ObservationRequest(Topic.SELF, DIM, target, "", null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationRequest(Topic.SELF, DIM, "player:" + ID, "x", null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationRequest(Topic.SELF, DIM, "player:" + ID, "", Bounds.of(0,0,0,1,1,1)));
    }

    @Test void jsonRoundTripRetainsTypedValuesAndOriginalObservationIdentityAsHistory() {
        Observation original = observation(full());
        Observation decoded = ObservationJson.fromJson(ObservationJson.toJson(List.of(original))).getFirst();
        assertTrue(ObservationJson.canDecode(Topic.SELF));
        assertEquals(original.payload(), decoded.payload());
        assertEquals(original.key(), decoded.key());
        assertEquals(100, decoded.gameTick()); assertEquals(1_000, decoded.epochMs());
        assertEquals("session-a", decoded.serverSession()); assertEquals(5, decoded.revision());
        assertEquals(Freshness.HISTORICAL, decoded.freshness());
        SelfPayload self = (SelfPayload) decoded.payload();
        assertThrows(UnsupportedOperationException.class, () -> self.main().clear());
    }

    @Test void jsonMissingOptionalGroupsStayUnknownNotEmptyOrZero() {
        JsonObject json = stored(full());
        JsonObject payload = entry(json).getAsJsonObject("payload");
        for (String field : List.of("main", "armor", "offhand", "selected", "hp", "maxHp", "food", "saturation", "effects", "vehicle", "bodyTask"))
            payload.remove(field);
        SelfPayload self = (SelfPayload) ObservationJson.fromJson(json.toString()).getFirst().payload();
        assertNull(self.main()); assertNull(self.armor()); assertNull(self.offhand()); assertNull(self.mainHand());
        assertNull(self.selected()); assertNull(self.hp()); assertNull(self.maxHp()); assertNull(self.food());
        assertNull(self.saturation()); assertNull(self.effects()); assertNull(self.vehicle()); assertNull(self.bodyTask());
    }

    @Test void jsonNestedMissingScalarsStayUnknownAndMissingSlotIdentityIsRejected() {
        JsonObject json = stored(full());
        JsonObject payload = entry(json).getAsJsonObject("payload");
        JsonObject slot = payload.getAsJsonArray("main").get(8).getAsJsonObject();
        for (String field : List.of("count", "damage", "maxDamage", "isDamageable")) slot.remove(field);
        JsonObject effect = payload.getAsJsonArray("effects").get(0).getAsJsonObject();
        effect.remove("amplifier"); effect.remove("infinite");
        SelfPayload decoded = (SelfPayload) ObservationJson.fromJson(json.toString()).getFirst().payload();
        assertNull(decoded.mainHand().count()); assertNull(decoded.mainHand().damage());
        assertNull(decoded.mainHand().maxDamage()); assertNull(decoded.mainHand().isDamageable());
        assertNull(decoded.effects().getFirst().amplifier()); assertNull(decoded.effects().getFirst().infinite());
        JsonObject absentVehicle = stored(full());
        entry(absentVehicle).add("payload", payload.deepCopy());
        entry(absentVehicle).getAsJsonObject("payload").add("vehicle", new JsonObject());
        assertTrue(ObservationJson.fromJson(absentVehicle.toString()).isEmpty());
        slot.remove("slot");
        assertTrue(ObservationJson.fromJson(json.toString()).isEmpty());
    }

    @Test void jsonKnownEmptyRemainsDistinctFromUnknown() {
        SelfPayload self = new SelfPayload(DIM, ID, slots(0,36), slots(36,4), Slot.empty(40), 0,
                0f, 20f, 0, 0f, List.of(), new Vehicle("", null), new BodyTask("", "", "", false, 0L));
        SelfPayload decoded = (SelfPayload) ObservationJson.fromJson(ObservationJson.toJson(List.of(observation(self)))).getFirst().payload();
        assertEquals(self, decoded); assertTrue(decoded.effects().isEmpty());
        assertEquals(0, decoded.food()); assertEquals("", decoded.bodyTask().taskId());
        assertNotNull(decoded.vehicle()); assertEquals("", decoded.mainHand().id());
    }

    @Test void jsonRejectsMalformedSelfWithoutDiscardingValidLegacySibling() {
        JsonObject json = stored(full());
        entry(json).getAsJsonObject("payload").addProperty("selected", 9);
        JsonObject legacy = JsonParser.parseString("""
                {"worldId":"old","dimension":"minecraft:overworld","topic":"environment",
                 "payload":{"dimension":"minecraft:overworld","dayTime":1,"raining":false,"thundering":false}}
                """).getAsJsonObject();
        json.getAsJsonArray("observations").add(legacy);
        List<Observation> decoded = ObservationJson.fromJson(json.toString());
        assertEquals(1, decoded.size()); assertEquals(Topic.ENVIRONMENT, decoded.getFirst().key().topic());
        assertEquals("", decoded.getFirst().serverSession()); assertEquals(Freshness.HISTORICAL, decoded.getFirst().freshness());
    }

    @Test void jsonRejectsMismatchedOrMissingPayloadIdentityAndAllowsUnknownFutureFields() {
        for (String field : List.of("dimension", "companionUUID")) {
            JsonObject json = stored(full()); entry(json).getAsJsonObject("payload").remove(field);
            assertTrue(ObservationJson.fromJson(json.toString()).isEmpty());
        }
        JsonObject json = stored(full()); entry(json).addProperty("target", "player:" + UUID.randomUUID());
        assertTrue(ObservationJson.fromJson(json.toString()).isEmpty());
        json = stored(full()); entry(json).addProperty("dimension", "minecraft:the_end");
        assertTrue(ObservationJson.fromJson(json.toString()).isEmpty());
        json = stored(full()); entry(json).getAsJsonObject("payload").addProperty("futureField", 42);
        assertEquals(full(), ObservationJson.fromJson(json.toString()).getFirst().payload());
    }

    @Test void candidateTtlAndStrictSessionNeverMakeHistoryCurrent() {
        TopicFreshnessPolicy policy = TopicFreshnessPolicy.defaults();
        Observation o = observation(full());
        // 2026-11-06：TTL 1s → 3s。实测锚点（同日 12:45 外部 MCP 消费者，同伴 overworld）：
        // read self 未刷新 → PENDING；refresh → 成功且 CURRENT；约 1 秒后再 read 已判 STALE；
        // 跨主题 batch_read(environment+self) 报 "sample expired before batch delivery"，
        // 而同一次 environment 的 validUntil 是 +30000。1s 短于消费者的一次往返，等于"刚采就过期"。
        assertEquals(3_000, policy.ttlOf(Topic.SELF));
        // 边界例：观测于 1000ms。
        assertEquals(Freshness.CURRENT, policy.classify(o, 3_000), "2s 内必须 CURRENT");
        assertEquals(Freshness.CURRENT, policy.classify(o, 4_000), "恰好 3s 仍 CURRENT");
        assertEquals(Freshness.STALE, policy.classify(o, 4_001), ">3s 必须 STALE");
        assertEquals(Freshness.UNKNOWN, policy.classify(o, 999));
        assertEquals(Freshness.HISTORICAL, policy.classify(o.withFreshness(Freshness.HISTORICAL), 1_000));
        InMemoryObservationStore store = new InMemoryObservationStore(); store.put(o);
        QueryService query = new QueryService(store, policy, null, () -> 1_000);
        ObservationRequest request = ObservationRequest.self(DIM, ID);
        assertEquals(Freshness.CURRENT, query.read("world-a", "session-a", request).freshness());
        assertEquals(Freshness.HISTORICAL, query.read("world-a", "session-b", request).freshness());
        assertFalse(query.read("world-b", "session-a", request).present());
        assertFalse(query.read("world-a", "session-a", ObservationRequest.self(DIM, UUID.randomUUID())).present());
    }
}
