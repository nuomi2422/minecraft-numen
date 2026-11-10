package com.dwinovo.numen.plugins.rdd;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11: production point check -> real registry/claims/store; no live Minecraft scan. */
class MachinePointRecheckTest {
    private static final MachinePointRecheck.Point ORIGIN=new MachinePointRecheck.Point(0,64,0);
    private static String add(AssetRegistry r,int x,String block,String world,String dim,String type) {
        var p=new MachinePointRecheck.Point(x,64,0);String id="machine|"+dim+"|"+block+"|"+MachinePointRecheck.packed(p);
        Map<String,Object> v=new LinkedHashMap<>(Map.of("x",x,"y",64,"z",0,"block",block,"dimension",dim));
        if(world!=null)v.put("world_id",world);v.put("server_session","old-session");
        r.apply(new Observation("o"+x,type,"fixture",dim,1,v),id,AssetScope.GLOBAL,null);return id;
    }
    private static int check(AssetRegistry r,String world,int budget,MachinePointRecheck.Reader reader) {
        return MachinePointRecheck.check(r,world,"new-session","overworld",ORIGIN,budget,reader,e->assertEquals("new-session",e.get("server_session")));
    }
    private static MachinePointRecheck.Reader reader(String block) {return new MachinePointRecheck.Reader(){
        public boolean loaded(MachinePointRecheck.Point p){return true;}public String block(MachinePointRecheck.Point p){return block;}};}
    @Test void removalInvalidatesClaimsAndPersistsWithoutInventingObservation(@TempDir Path dir) throws Exception {
        AssetRegistry r=new AssetRegistry();String id=add(r,0,"minecraft:furnace","world","overworld","world_machine");
        assertEquals(1,check(r,"world",32,reader("minecraft:air")));assertEquals(AssetStatus.INVALID,r.get(id).orElseThrow().status());
        assertFalse(PlanningAssetSnapshot.worldClaims(r).containsKey(id));UUID c=UUID.randomUUID();
        RddAssetStore.save(dir,c,r);assertEquals(AssetStatus.INVALID,RddAssetStore.load(dir,c).get(id).orElseThrow().status());
    }
    @Test void replacementKeepsNewIdentityAndReobservationRestoresOld() {
        AssetRegistry r=new AssetRegistry();String old=add(r,0,"minecraft:furnace","world","overworld","world_machine");
        check(r,"world",32,reader("minecraft:crafting_table"));String fresh=add(r,0,"minecraft:crafting_table","world","overworld","world_machine");
        assertFalse(PlanningAssetSnapshot.worldClaims(r).containsKey(old));assertTrue(PlanningAssetSnapshot.worldClaims(r).containsKey(fresh));
        add(r,0,"minecraft:furnace","world","overworld","world_machine");assertTrue(PlanningAssetSnapshot.worldClaims(r).containsKey(old));
    }
    @Test void unchangedAcrossSessionsStaysObserved() {
        AssetRegistry r=new AssetRegistry();String id=add(r,0,"minecraft:furnace","world","overworld","world_machine");
        assertEquals(0,check(r,"world",32,reader("minecraft:furnace")));assertEquals(AssetStatus.OBSERVED,r.get(id).orElseThrow().status());
    }
    @Test void scopeLegacyRangePortalAndNonMachineDoNotRead() {
        AssetRegistry r=new AssetRegistry();add(r,0,"minecraft:furnace",null,"overworld","world_machine");
        add(r,1,"minecraft:furnace","other","overworld","world_machine");add(r,2,"minecraft:furnace","world","nether","world_machine");
        add(r,9,"minecraft:furnace","world","overworld","world_machine");add(r,3,"minecraft:nether_portal","world","overworld","world_machine");
        add(r,4,"minecraft:furnace","world","overworld","world_base");
        var spy=new MachinePointRecheck.Reader(){public boolean loaded(MachinePointRecheck.Point p){fail("scope before reader");return false;}
            public String block(MachinePointRecheck.Point p){fail("must not read");return null;}};
        assertEquals(0,check(r,"world",32,spy));assertEquals(0,check(r,null,32,spy));
    }
    @Test void unloadedFailureAndBudgetUncheckedAreNotAbsence() {
        AssetRegistry r=new AssetRegistry();String a=add(r,0,"minecraft:furnace","world","overworld","world_machine"),b=add(r,1,"minecraft:furnace","world","overworld","world_machine");
        AtomicInteger loaded=new AtomicInteger();
        check(r,"world",1,new MachinePointRecheck.Reader(){public boolean loaded(MachinePointRecheck.Point p){loaded.incrementAndGet();return false;}
            public String block(MachinePointRecheck.Point p){fail("loaded before read");return null;}});
        assertEquals(1,loaded.get());assertEquals(AssetStatus.OBSERVED,r.get(b).orElseThrow().status());
        check(r,"world",32,new MachinePointRecheck.Reader(){public boolean loaded(MachinePointRecheck.Point p){return true;}
            public String block(MachinePointRecheck.Point p){throw new IllegalStateException("partial");}});
        assertEquals(AssetStatus.OBSERVED,r.get(a).orElseThrow().status());
    }
    @Test void malformedCoordinateOrIdNeverReadsBlock() {
        AssetRegistry r=new AssetRegistry();
        r.apply(new Observation("bad","world_machine","fixture","overworld",1,
                Map.of("world_id","world","dimension","overworld","block","minecraft:furnace","x",0.5,"y",64,"z",0)),
                "machine|overworld|minecraft:furnace|64",AssetScope.GLOBAL,null);
        r.apply(new Observation("bad-id","world_machine","fixture","overworld",1,
                Map.of("world_id","world","dimension","overworld","block","minecraft:furnace","x",0,"y",64,"z",0)),
                "not-an-exact-machine-id",AssetScope.GLOBAL,null);
        assertEquals(0,check(r,"world",32,new MachinePointRecheck.Reader(){
            public boolean loaded(MachinePointRecheck.Point p){fail("bad identity must not probe");return false;}
            public String block(MachinePointRecheck.Point p){fail("bad identity must not read");return null;}}));
    }
    @Test void observerPinsIndependentCheckAndLoadedPositiveGuard() throws Exception {
        Path cur=Paths.get("").toAbsolutePath(),source=null;
        for(;cur!=null;cur=cur.getParent()){Path p=cur.resolve("src/main/java/com/dwinovo/numen/plugins/rdd/RddWorldAssetObserver.java");if(Files.isRegularFile(p)){source=p;break;}}
        assertNotNull(source);String s=Files.readString(source);assertTrue(s.contains("identityProvider.snapshot(level.getServer())"));
        assertTrue(s.indexOf("MachinePointRecheck.check(")<s.indexOf("if (++count >= MAX_MACHINES_PER_SCAN)"));
        assertTrue(s.indexOf("if (!level.hasChunkAt(cursor)) continue;")<s.indexOf("var state = level.getBlockState(cursor)"));
    }
}
