package com.dwinovo.numen.core.task.light;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the actual production-delegated decision loop, not a second model of it. */
class AutoLightSessionTest {
    static class World implements AutoLightSession.Port {
        Object identity=new Object();
        int brightness=0, materials=64, calls, approach=1, reads;
        boolean support=true, empty=true, loaded=true, permit=true, accept=true, illuminate;
        boolean consume=true, creative;
        Set<BlockPos> torches=new HashSet<>();
        public Object worldIdentity(){ return identity; }
        public boolean loaded(BlockPos p){return loaded;}
        public int maxLocalRawBrightness(BlockPos p){reads++;return brightness;}
        public boolean replaceable(BlockPos p){return empty && !torches.contains(p);}
        public boolean canSupportStandingTorch(BlockPos p){return support;}
        public boolean alreadyTorch(BlockPos p){return torches.contains(p);}
        public boolean permitted(BlockPos p){return permit;}
        public boolean materialPrivilegeValid(){return consume || creative;}
        public boolean hasMaterial(){return !consume || materials>0;}
        public int approach(BlockPos p){return approach;}
        public void place(BlockPos p){calls++;if(consume)materials--;if(accept){torches.add(p);if(illuminate)brightness=14;}}
    }
    AutoLightRequest request(int cells,int lights,int scans,int ticks){return new AutoLightRequest(BlockPos.ZERO,new BlockPos(cells-1,0,0),lights,scans,ticks);}
    AutoLightSession session(World w,int cells){return new AutoLightSession(request(cells,64,16384,2400),w.identity);}
    void drain(AutoLightSession s,World w){for(int i=0;i<3000&&!s.done();i++)s.tick(w);assertTrue(s.done());}
    @Test void creativeStillCreativeAllowsEmptyInventory(){
        World w=new World();w.consume=false;w.creative=true;w.materials=0;
        var s=session(w,1);drain(s,w);assertEquals(1,w.calls);assertEquals(0,w.materials);assertTrue(s.complete());
    }
    @Test void creativeToSurvivalStopsBeforeAnyFurtherPlacement(){
        World w=new World();w.consume=false;w.creative=true;
        var s=session(w,4);s.tick(w);assertEquals(1,w.calls);
        w.creative=false;s.tick(w);assertEquals("material_privilege_lost",s.reason());
        assertFalse(s.complete());s.tick(w);assertEquals(1,w.calls);
    }
    @Test void creativeLostWhileNavigatingStopsBeforeFirstPlacement(){
        World w=new World();w.consume=false;w.creative=true;w.approach=0;
        var s=session(w,4);s.tick(w);w.creative=false;w.approach=1;
        drain(s,w);assertEquals("material_privilege_lost",s.reason());assertEquals(0,w.calls);
    }
    @Test void caveDarkPlaces(){World w=new World();var s=session(w,1);drain(s,w);assertEquals(1,s.data().get("placed"));}
    @Test void daylightBrightSkips(){World w=new World();w.brightness=15;var s=session(w,4);drain(s,w);assertEquals("no_candidates",s.reason());assertEquals(0,w.calls);}
    @Test void nightDarkPlaces(){World w=new World();w.brightness=4;var s=session(w,1);drain(s,w);assertEquals(1,w.calls);}
    @Test void existingTorchSkipped(){World w=new World();w.torches.add(BlockPos.ZERO);var s=session(w,1);drain(s,w);assertEquals(0,w.calls);}
    @Test void firstTorchIlluminatesRemaining(){World w=new World();w.illuminate=true;var s=session(w,16);drain(s,w);assertEquals(1,w.calls);assertEquals("completed",s.reason());}
    @Test void permissionDeniedNeverCallsPlacement(){World w=new World();w.permit=false;var s=session(w,2);drain(s,w);assertEquals(0,w.calls);assertFalse(s.complete());assertEquals(2,s.data().get("denied"));}
    @Test void interactionSuccessWithoutTorchNotCountedOrRefunded(){World w=new World();w.accept=false;var s=session(w,1);drain(s,w);assertEquals(0,s.data().get("placed"));assertEquals(63,w.materials);assertFalse(s.complete());}
    @Test void occupiedNeverOverwritten(){World w=new World();w.empty=false;var s=session(w,1);drain(s,w);assertEquals(0,w.calls);}
    @Test void missingSupportNeverPlaces(){World w=new World();w.support=false;var s=session(w,1);drain(s,w);assertEquals(0,w.calls);}
    @Test void noMaterialDistinct(){World w=new World();w.materials=0;var s=session(w,1);drain(s,w);assertEquals("materials_exhausted",s.reason());assertEquals(0,w.calls);}
    @Test void materialRunsOutAfterPartial(){World w=new World();w.materials=1;var s=session(w,4);drain(s,w);assertEquals(1,s.data().get("placed"));assertEquals("materials_exhausted",s.reason());}
    @Test void cancelledStopsForever(){World w=new World();var s=session(w,4);s.stop("cancelled");s.tick(w);assertEquals(0,w.calls);assertFalse(s.complete());}
    @Test void sameDimensionNewWorldStops(){World w=new World();var s=session(w,4);w.identity=new Object();drain(s,w);assertEquals("world_changed",s.reason());assertEquals(0,w.calls);}
    @Test void unloadedIncomplete(){World w=new World();w.loaded=false;var s=session(w,4);drain(s,w);assertEquals("incomplete",s.reason());assertEquals(0,w.calls);}
    @Test void scanBudget(){World w=new World();w.brightness=15;var s=new AutoLightSession(request(200,64,10,2400),w.identity);drain(s,w);assertEquals("scan_budget",s.reason());assertEquals(10,s.data().get("scanned"));}
    @Test void scansOnly64PerTick(){World w=new World();w.brightness=15;var s=session(w,200);s.tick(w);assertEquals(64,s.data().get("scanned"));assertFalse(s.done());}
    @Test void placementBudget(){World w=new World();var s=new AutoLightSession(request(10,2,100,2400),w.identity);drain(s,w);assertEquals("placement_budget",s.reason());assertEquals(2,w.calls);}
    @Test void timeBudgetIncludesWaiting(){World w=new World();w.approach=0;var s=new AutoLightSession(request(10,2,100,2),w.identity);drain(s,w);assertEquals("time_budget",s.reason());assertEquals(0,w.calls);}
    @Test void brightnessRecheckedAfterNavigation(){World w=new World();w.approach=0;var s=session(w,1);s.tick(w);w.brightness=14;w.approach=1;drain(s,w);assertEquals(0,w.calls);}
    @Test void supportRecheckedAfterNavigation(){World w=new World();w.approach=0;var s=session(w,1);s.tick(w);w.support=false;w.approach=1;drain(s,w);assertEquals(0,w.calls);}
    @Test void unreachableIsIncomplete(){World w=new World();w.approach=-1;var s=session(w,1);drain(s,w);assertEquals("incomplete",s.reason());}
}
