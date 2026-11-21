package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RddEnvBlockTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long NOW=100_000;
    private static Map<String,Object> slot(int i,String id,int count) {
        return Map.of("slot",new BigDecimal(i),"id",id,"count",new BigDecimal(count));
    }
    private static Map<String,Object> values() {
        var v=new LinkedHashMap<String,Object>();
        var slots=new ArrayList<Map<String,Object>>();
        for(int i=0;i<36;i++) slots.add(slot(i,i==0?"minecraft:bread":"",i==0?3:0));
        v.put("main",slots); v.put("mainHand",slots.get(0));
        v.put("offhand",slot(40,"minecraft:shield",1));
        v.put("armor",List.of(slot(38,"minecraft:iron_chestplate",1)));
        v.put("hp",new BigDecimal("7.5")); v.put("food",new BigDecimal("6"));
        v.put("dimension","minecraft:overworld"); v.put("companionUUID",ID.toString());
        return v;
    }
    private static WorldObservationService.Response response(Map<String,Object> v,
            ObservationMetadata.Freshness freshness,long observed,long until,String subject) {
        var meta=new ObservationMetadata("world","session","minecraft:overworld",subject,
                "self-provider","key",1,observed,freshness,"SERVER_LOADED",until);
        return new WorldObservationService.Response(WorldObservationService.Status.OK,"self","sample",12,
                WorldObservationService.Coverage.FULL,null,List.of(WorldObservationService.Item.of(v)),0,meta);
    }
    private static String render(Map<String,Object> v) {
        return render(v, true);
    }
    private static String render(Map<String,Object> v, boolean includeBackpackCount) {
        return RddEnvBlock.render(ID,response(v,ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+2900,ID.toString()),NOW,includeBackpackCount);
    }
    @Test void usesDecimalProjectionWithoutDoubleCountingHandsOrArmor() {
        String s=render(values());
        assertTrue(s.contains("背包 1种 3件"),s);
        assertTrue(s.contains("minecraft:bread×3")); assertTrue(s.contains("血量7.5"));
        assertFalse(s.contains("背包 3种"));
        assertTrue(s.contains("持续掉血 未观测")); assertFalse(s.contains("正在持续掉血"));
    }
    @Test void executionViewDropsBackpackCountButKeepsOtherFacts() {
        String full = render(values(), true);
        String exec = render(values(), false);
        assertTrue(exec.length() < full.length(), "执行视图应至少少一行背包计数");
        assertFalse(exec.contains("背包 "), "执行视图不得重复背包计数: " + exec);
        assertTrue(exec.contains("身体 血量"), "血量必须在: " + exec);
        assertTrue(exec.contains("饥饿"), "饥饿必须在: " + exec);
        assertTrue(exec.contains("关键物品"), "关键物品行必须仍在: " + exec);
        assertTrue(exec.contains("位置/脚下/周边/持续掉血 未观测"), "位置行必须仍在: " + exec);
    }

    @Test void missingInventoryIsUnknownButObservedEmptyIsZero() {
        var v=values(); v.remove("main"); assertTrue(render(v).contains("背包 未知"));
        var slots=new ArrayList<Map<String,Object>>(); for(int i=0;i<36;i++)slots.add(slot(i,"",0));
        v.put("main",slots); assertTrue(render(v).contains("背包 0种 0件"));
    }
    @Test void staleFutureExpiredAndDifferentSubjectNeverBecomeCurrentFacts() {
        for(var freshness:List.of(ObservationMetadata.Freshness.STALE,ObservationMetadata.Freshness.HISTORICAL,ObservationMetadata.Freshness.UNKNOWN))
            assertTrue(RddEnvBlock.render(ID,response(values(),freshness,NOW-10,NOW+100,ID.toString()),NOW).contains("不可用"));
        assertTrue(RddEnvBlock.render(ID,response(values(),ObservationMetadata.Freshness.CURRENT,NOW+1,NOW+100,ID.toString()),NOW).contains("不可用"));
        assertTrue(RddEnvBlock.render(ID,response(values(),ObservationMetadata.Freshness.CURRENT,NOW-10,NOW,ID.toString()),NOW).contains("不可用"));
        assertTrue(RddEnvBlock.render(ID,response(values(),ObservationMetadata.Freshness.CURRENT,NOW-10,NOW+100,UUID.randomUUID().toString()),NOW).contains("不可用"));
    }
    @Test void payloadIdentityMismatchIsUnavailable() {
        var v=values(); v.put("dimension","minecraft:the_nether"); assertTrue(render(v).contains("不可用"));
        v=values(); v.put("companionUUID",UUID.randomUUID().toString()); assertTrue(render(v).contains("不可用"));
    }
    @Test void unknownHealthDoesNotBecomeZeroOrOngoingDamage() {
        var v=values(); v.remove("hp"); assertTrue(render(v).contains("血量未知"));
        assertFalse(render(v).contains("血量0"));
    }
    @Test void outputIsBoundedAndWellFormedEvenWithLongItemIds() {
        var v=values();v.put("mainHand",slot(0,"mod:"+"x".repeat(600),1));
        String s=render(v);assertTrue(s.length()<=400,s.length()+"");
        assertTrue(s.endsWith("</env>"));assertTrue(s.contains("位置/脚下/周边/持续掉血 未观测"));
    }
    @Test void unknownAndStaleFieldsDoNotHideUnrelatedKnownFacts() {
        var base=response(values(),ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+100,ID.toString());
        var item=new WorldObservationService.Item(values(),Set.of("main[0].count","hp"),Set.of("armor[0].damage"));
        var r=new WorldObservationService.Response(base.status(),base.topic(),base.snapshotId(),base.observedTick(),
                base.coverage(),base.reason(),List.of(item),0,base.metadata());
        String s=RddEnvBlock.render(ID,r,NOW);
        assertTrue(s.contains("背包 未知")); assertTrue(s.contains("血量未知"));
        assertTrue(s.contains("饥饿6")); assertTrue(s.contains("minecraft:bread×3"));
    }
    @Test void countOverflowIsUnknownNotNegativeAndKeepsHealth() {
        var v=values(); var slots=new ArrayList<Map<String,Object>>();
        for(int i=0;i<36;i++)slots.add(i<2 ? Map.of("slot",i,"id","minecraft:bread","count",Long.MAX_VALUE) : slot(i,"",0));
        v.put("main",slots); String s=render(v);
        assertTrue(s.contains("背包 未知"));assertTrue(s.contains("血量7.5"));assertFalse(s.contains("-2件"));
    }
    @Test void weatherRequiresSameWorldSessionAndUnexpiredSample() {
        var self=response(values(),ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+100,ID.toString()).metadata();
        var m=new ObservationMetadata("world","session","minecraft:overworld","","environment","env-key",1,
                NOW-100,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",NOW+100);
        var w=new WorldObservationService.Response(WorldObservationService.Status.OK,"environment","env",10,
                WorldObservationService.Coverage.FULL,null,List.of(WorldObservationService.Item.of(Map.of("day_time",6000,"weather","clear"))),0,m);
        assertEquals("时间 白天 · 天气 clear",RddEnvBlock.weather(self,w,NOW));
        assertEquals("时间/天气 未观测",RddEnvBlock.weather(self,w,NOW+100));
        var other=new ObservationMetadata("world","other-session","minecraft:overworld","","environment","env-key",1,
                NOW-100,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",NOW+100);
        w=new WorldObservationService.Response(w.status(),w.topic(),w.snapshotId(),w.observedTick(),w.coverage(),null,w.items(),0,other);
        assertEquals("时间/天气 未观测",RddEnvBlock.weather(self,w,NOW));
    }
    @Test void expiryIsCheckedAfterProviderReturns() {
        var clock=new java.util.concurrent.atomic.AtomicLong(NOW);
        WorldObservationService service=new WorldObservationService(){
            public Response read(Request q){return Response.error("environment","missing");}
            public Response readSelf(String c,UUID id,long age){clock.set(NOW+200);
                return response(values(),ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+100,ID.toString());}
        };
        assertTrue(RddEnvBlock.build(ID,service,clock::get).contains("不可用"));
    }
    @Test void throwingOptionalWeatherDoesNotEraseKnownSelf() {
        WorldObservationService service=new WorldObservationService(){
            public Response read(Request q){throw new IllegalStateException("weather provider failed");}
            public Response readSelf(String c,UUID id,long age){
                return response(values(),ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+100,ID.toString());}
        };
        String s=RddEnvBlock.build(ID,service,NOW);
        assertTrue(s.contains("血量7.5"),s); assertTrue(s.contains("背包 1种 3件"),s);
        assertTrue(s.contains("时间/天气 未观测"),s);
    }
    @Test void executionReadsFreshNightAndNearbyBedWithoutClaimingTravelOrSleep() {
        String s = executionRest(bedResponse("world", "session", NOW+1, WorldObservationService.Coverage.FULL, 2, 1, List.of("2,65,9")), NOW);
        assertTrue(s.contains("时间 夜晚 · 天气 clear"),s);
        assertTrue(s.contains("身边床(±4/±2格) 有，见未占床头 · 可达/可睡未知 · 已睡 否"),s);
        // Four blocks away: a location has been observed, but reach and sleep are still unknown.
        assertTrue(s.contains("床头候选 (2,65,9)（采样，可达/可睡未核）"),s);
        assertTrue(s.length() <= 400,s);
    }

    @Test void movingToAnotherBoxCannotReuseOldBedObservation() {
        var selfValues = values();
        selfValues.put("rest", Map.of("x", 8, "y", 65, "z", 9, "sleeping", false, "bedWorks", true));
        var self = response(selfValues, ObservationMetadata.Freshness.CURRENT, NOW-100, NOW+100, ID.toString());
        WorldObservationService svc = new WorldObservationService() {
            public Response readSelf(String consumer, UUID id, long age) { return self; }
            public Response read(Request q) {
                if (q.topic().equals("environment")) return Response.error("environment", "unknown");
                assertEquals("4,63,5,12,67,13", q.selector().get("box"));
                return new Response(Status.PENDING, "blocks", "", 0, Coverage.UNKNOWN, "no observation", List.of(), 0);
            }
        };
        String result = RddEnvBlock.build(ID, svc, NOW);
        assertTrue(result.contains("身边床(±4/±2格) 未知"), result);
        assertFalse(result.contains("床头候选"), result);
    }

    @Test void invalidOrOutOfBoxSamplesDoNotProduceCoordinates() {
        var full = WorldObservationService.Coverage.FULL;
        for (List<?> samples : List.of(List.of(), List.of("3,65,9"), List.of("bad,65,9"),
                List.of("-2,65,9", "-1,65,9"))) {
            String s = executionRest(bedResponse("world", "session", NOW+1, full, 2, 1, samples), NOW);
            assertTrue(s.contains("有，床头坐标未知"),s);
            assertFalse(s.contains("床头候选"),s);
        }
    }

    @Test void coveredZeroIsNoBedButExpiredPartialAndForeignSamplesStayUnknown() {
        var full = WorldObservationService.Coverage.FULL;
        assertTrue(executionRest(bedResponse("world", "session", NOW+1, full, 0, 0),NOW).contains("身边床(±4/±2格) 无"));
        for (var beds : List.of(
                bedResponse("world", "session", NOW, full, 2, 1, List.of("-2,65,9")),
                bedResponse("other", "session", NOW+1, full, 2, 1, List.of("-2,65,9")),
                bedResponse("world", "old", NOW+1, full, 2, 1, List.of("-2,65,9")),
                bedResponse("world", "session", NOW+1, WorldObservationService.Coverage.PARTIAL, 2, 1, List.of("-2,65,9")),
                bedResponse("world", "session", NOW+1, WorldObservationService.Coverage.PARTIAL, 0, 0),
                WorldObservationService.Response.error("blocks", "missing"))) {
            String s = executionRest(beds,NOW);
            assertTrue(s.contains("时间 夜晚"),s);
            assertTrue(s.contains("身边床(±4/±2格) 未知"),s);
            assertFalse(s.contains("床头候选"),s);
        }
    }

    private static String executionRest(WorldObservationService.Response beds, long renderAt) {
        var v = values();
        v.put("mainHand", slot(0, "mod:" + "a".repeat(200), 1));
        v.put("offhand", slot(40, "mod:" + "b".repeat(200), 1));
        v.put("rest", Map.of("x", -2, "y", 65, "z", 9, "sleeping", false, "bedWorks", true));
        var self = response(v,ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+100,ID.toString());
        var clock = new java.util.concurrent.atomic.AtomicLong(NOW - 1);
        WorldObservationService service = new WorldObservationService() {
            @Override public Response readSelf(String consumer, UUID subject, long maxAgeMs) {
                assertEquals(ID,subject);
                return self;
            }
            @Override public Response read(Request q) {
                assertEquals("minecraft:overworld",q.dimension());
                if (q.topic().equals("environment")) {
                    var meta = new ObservationMetadata("world","session",q.dimension(),"","environment","night",7,
                            NOW-100,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",NOW+100);
                    return new Response(Status.OK,"environment","night",14,Coverage.FULL,null,
                            List.of(Item.of(Map.of("day_time",16000,"weather","clear"))),0,meta);
                }
                assertEquals("blocks",q.topic());
                assertEquals("-6,63,5,2,67,13",q.selector().get("box"));
                assertEquals("#minecraft:beds,#minecraft:beds[part=head,occupied=false]",q.selector().get("blocks"));
                assertEquals("1",q.selector().get("samples"));
                clock.set(renderAt);
                return beds;
            }
            @Override public Response refresh(Request q) { throw new AssertionError("execution must be cache-only"); }
        };
        return RddEnvBlock.build(ID, service, clock::get, false);
    }

    private static WorldObservationService.Response bedResponse(String world, String session, long until,
            WorldObservationService.Coverage coverage, int total, int free) {
        return bedResponse(world, session, until, coverage, total, free, List.of());
    }

    private static WorldObservationService.Response bedResponse(String world, String session, long until,
            WorldObservationService.Coverage coverage, int total, int free, List<?> samples) {
        var meta = new ObservationMetadata(world,session,"minecraft:overworld","","blocks","beds",8,
                NOW-100,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",until);
        return new WorldObservationService.Response(WorldObservationService.Status.OK,"blocks","beds",14,coverage,null,
                List.of(WorldObservationService.Item.of(Map.of("block","#minecraft:beds","count",total)),
                        WorldObservationService.Item.of(Map.of("block","#minecraft:beds[part=head,occupied=false]","count",free,
                                "samples",samples))),0,meta);
    }

    @Test void onlyCacheReadIsUsedAndProviderAbsenceIsUnknown() {
        WorldObservationService service=new WorldObservationService(){
            public Response read(Request q){assertEquals("environment",q.topic()); return Response.error("environment","not sampled");}
            public Response refresh(Request q){throw new AssertionError("never refresh from rendering");}
            public Response readSelf(String consumer,UUID id,long maxAgeMs){
                assertEquals(ID,id);assertEquals(3000,maxAgeMs);
                return response(values(),ObservationMetadata.Freshness.CURRENT,NOW-100,NOW+100,ID.toString());
            }
        };
        assertTrue(RddEnvBlock.build(ID,service,NOW).contains("血量7.5"));
        assertTrue(RddEnvBlock.build(ID,null,NOW).contains("不可用"));
    }
}
