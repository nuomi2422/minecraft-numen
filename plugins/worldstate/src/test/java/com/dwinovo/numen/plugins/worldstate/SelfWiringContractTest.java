package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.*;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.dwinovo.numen.plugins.worldstate.tool.*;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload.*;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit SELF adapter contracts, 2026-11-06: actual parser/projection/TaskResult JSON.
 * WHY: a typed cache is not proof that model tools receive structured caller-bound facts.
 * USAGE: :plugins:worldstate:test; no Minecraft world or automatic sampler.
 * Not a duplicate oracle: calls production pure adapters used by read/refresh and batch.
 */
class SelfWiringContractTest {
    static final String DIM = "minecraft:overworld";
    static final UUID UUID_SELF = UUID.fromString("a43d753e-0000-0000-0000-000000000001");
    static final String TARGET = "player:" + UUID_SELF;
    static final WorldStateBatch.Caller CALLER = new WorldStateBatch.Caller(UUID_SELF.toString(), "world", "session", DIM, 0,64,0);
    static JsonObject args(String target) {
        JsonObject json = new JsonObject(); json.addProperty("topic", "self"); json.addProperty("target", target); return json;
    }
    static SelfPayload sample() {
        List<Slot> main = new ArrayList<>(), armor = new ArrayList<>();
        for(int i=0;i<36;i++) main.add(Slot.empty(i));
        for(int i=36;i<40;i++) armor.add(Slot.empty(i));
        main.set(0,new Slot(0,"minecraft:iron_sword",1,7,250,true));
        return new SelfPayload(DIM,UUID_SELF,main,armor,Slot.empty(40),0,20f,20f,20,5f,
                List.of(new Effect("minecraft:speed",0,100,false,200L),new Effect("minecraft:night_vision",0,null,true,null)),
                new Vehicle("",null),new BodyTask("","","",false,0L));
    }
    static Response response(Item item) {
        return new Response(Status.OK,"self","snapshot",100,Coverage.FULL,null,List.of(item),0,
                new ObservationMetadata("world","session",DIM,UUID_SELF.toString(),"self","key",1,1000,
                        ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",2000));
    }
    static ObservationBatch.Query query() {
        JsonObject root = new JsonObject(); JsonArray q = new JsonArray(); q.add(args(TARGET)); root.add("queries",q);
        return WorldStateBatch.parse(root,CALLER,"q");
    }

    @Test void schemaAndParserUseOneCanonicalTarget() {
        var request = WorldStateArgs.forReadRefresh(Topic.SELF,args(TARGET),DIM).request();
        assertEquals(TARGET,request.target());
        assertEquals(Map.of("target",TARGET),query().requests().getFirst().selector());
        WorldStateArgs.requireOwnSelf(request,UUID_SELF.toString(),DIM);
        @SuppressWarnings("unchecked") var properties=(Map<String,Object>)WorldStateSchema.build().get("properties");
        assertTrue(properties.containsKey("target"));
        assertTrue(((List<?>)((Map<?,?>)properties.get("topic")).get("enum")).contains("self"));
    }

    @Test void missingMalformedOtherSubjectAndOtherDimensionAreRejected() {
        assertFalse(WorldStateArgs.forReadRefresh(Topic.SELF,new JsonObject(),DIM).ok());
        assertFalse(WorldStateArgs.forReadRefresh(Topic.SELF,args("player:bad"),DIM).ok());
        for (var request : List.of(com.dwinovo.numen.worldstate.core.request.ObservationRequest.self(DIM,UUID.randomUUID()),
                com.dwinovo.numen.worldstate.core.request.ObservationRequest.self("minecraft:the_end",UUID_SELF)))
            assertThrows(IllegalArgumentException.class,()->WorldStateArgs.requireOwnSelf(request,UUID_SELF.toString(),DIM));
        for (String target : List.of("player:"+UUID.randomUUID(), "", "block:0,0,0")) {
            JsonObject root=new JsonObject(); JsonArray queries=new JsonArray(); queries.add(args(target)); root.add("queries",queries);
            assertThrows(IllegalArgumentException.class,()->WorldStateBatch.parse(root,CALLER,"q"));
        }
        JsonObject root=new JsonObject(); JsonArray queries=new JsonArray(); JsonObject entry=args(TARGET);
        entry.addProperty("dimension","minecraft:the_end"); queries.add(entry); root.add("queries",queries);
        assertThrows(IllegalArgumentException.class,()->WorldStateBatch.parse(root,CALLER,"q"));
    }

    @Test void completeTypedProjectionAdmitsStrictBatchAndRealJsonRemainsStructured() {
        Item item=SelfObservationProjection.item(sample());
        assertTrue(item.unknownFields().isEmpty(),item.unknownFields().toString());
        var response=response(item);
        var batch=ObservationBatchReader.read(r->response,query(),()->1100,()->0);
        assertTrue(batch.coherent(),batch.reason());
        int[] calls = {0,0};
        WorldObservationService service = new WorldObservationService() {
            @Override public Response read(Request request) { calls[0]++; return response; }
            @Override public Response refresh(Request request) { calls[1]++; return response; }
        };
        for(String action:List.of("read","refresh")) {
            JsonObject data=JsonParser.parseString(SelfObservationProjection.toolReply(action,query().requests().getFirst(),service))
                    .getAsJsonObject().getAsJsonObject("data");
            assertTrue(data.get("metadata").isJsonObject());
            JsonObject values=data.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("values");
            assertEquals(36,values.getAsJsonArray("main").size());
            assertEquals(7,values.getAsJsonObject("mainHand").get("damage").getAsInt());
            assertTrue(values.get("bodyTask").isJsonObject());
        }
        assertArrayEquals(new int[]{1,1},calls);
        String wire=TaskResult.ok("batch",Map.of("result",WorldStateBatch.resultData(batch))).toJson();
        assertTrue(JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("data").get("result").isJsonObject());
    }

    @Test void unknownGroupsAndNestedScalarsAreNotZeroFilledOrCoherent() {
        SelfPayload missing=new SelfPayload(DIM,UUID_SELF,null,null,null,null,null,null,null,null,null,null,null);
        Item item=SelfObservationProjection.item(missing);
        assertTrue(item.unknownFields().containsAll(Set.of("main","hp","bodyTask","vehicle")));
        assertFalse(item.values().containsKey("hp"));
        assertFalse(ObservationBatchReader.read(r->response(item),query(),()->1100,()->0).coherent());
    }

    @Test void missingRequiredFieldsAndWrongSubjectFailEvenWithoutUnknownFlags() {
        Map<String,Object> base=SelfObservationProjection.item(sample()).values();
        for(String field:List.of("main","armor","selected","hp","effects","vehicle","bodyTask","companionUUID")) {
            Map<String,Object> broken=new HashMap<>(base); broken.remove(field);
            assertFalse(ObservationBatchReader.read(r->response(Item.of(broken)),query(),()->1100,()->0).coherent(),field);
        }
        Map<String,Object> broken=new HashMap<>(base); broken.put("companionUUID",UUID.randomUUID().toString());
        assertFalse(ObservationBatchReader.read(r->response(Item.of(broken)),query(),()->1100,()->0).coherent());
    }
}
