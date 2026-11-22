package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes actual collect, not implemented range support. MC section storage is a boundary.
 * Caller post-filter is deliberately hypothetical: it demonstrates why a future scope must
 * precede collection truncation, not that the current unscoped API violated its own contract. */
class TargetIndexRangeConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        Path main=root.resolve("core/common/src/main/java/com/dwinovo/numen/core/scan");
        String source=Files.readString(main.resolve("TargetIndex.java")),collect="";
        int at=0;
        while((at=source.indexOf("    private static void collect(",at))>=0){
            int end=source.indexOf("\n    }",at);assertTrue(end>at);
            collect+=source.substring(at,end+6)+"\n";at=end+6;
        }
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/Index.java",BOUNDARIES.replace("COLLECT_METHOD",collect));
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/SearchGeometry.java",Files.readString(main.resolve("SearchGeometry.java")).replace("package com.dwinovo.numen.core.scan;","package fixture;"));
        ByteArrayOutputStream diagnostics=new ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new)),"SETUP: "+diagnostics);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.Index");
    }
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String scenario)throws Exception{return (Map<String,Object>)fixture.getMethod("run",String.class).invoke(null,scenario);}
    @Test void sparsePackedPositionsPreserveNegativeSectionCoordinates()throws Exception{
        var r=run("sparse");assertEquals(List.of("-16,-16,-16","-1,-1,-1"),r.get("positions"));
    }
    @Test void saturatedCollectionStopsAtItsExistingUnfilteredQuota()throws Exception{
        var r=run("saturated");assertEquals(List.of("0,0,0"),r.get("positions"));assertEquals(1,r.get("reads"));
    }
    @Test void hypotheticalPostFilterCanLoseAnAvailableLaterMatch()throws Exception{
        var r=run("postFilter");assertEquals(List.of("0,0,0"),r.get("positions"));assertEquals(0,r.get("afterFilter"));
        assertEquals(true,r.get("laterMatchExists"),"the supplied section contains x=1 but collect ended at x=0");
    }
    @Test void rejectedCandidatesMustNotBeUsedToClaimScopedSearchCanStop()throws Exception{
        var r=run("bound");assertEquals(true,r.get("rawCanStop"));assertEquals(false,r.get("filteredCanStop"));
    }
    static final String BOUNDARIES="""
        package fixture;
        import java.util.*;import java.util.function.Predicate;
        public class Index {
            record BlockPos(int x,int y,int z){public String toString(){return x+","+y+","+z;}}
            static class SectionPos {static int sectionToBlockCoord(int n){return n*16;}}
            static class Block {}
            record BlockState(Block block){Block getBlock(){return block;}}
            static class LevelChunkSection {
                final Block block;int reads;
                LevelChunkSection(Block b){block=b;} LevelChunkSection getStates(){return this;}
                BlockState get(int x,int y,int z){reads++;return new BlockState(block);}
            }
            static final short[] SATURATED={-1};
            static class SectionEntry {Map<Block,short[]> hits=new HashMap<>();}
        COLLECT_METHOD
            public static Map<String,Object> run(String scenario){
                Block ore=new Block();SectionEntry e=new SectionEntry();LevelChunkSection section=new LevelChunkSection(ore);
                List<BlockPos> out=new ArrayList<>();
                if(scenario.equals("sparse")){
                    e.hits.put(ore,new short[]{0,(short)0xfff});collect(e,section,-1,-1,-1,List.of(ore),1,out);
                }else{
                    e.hits.put(ore,SATURATED);collect(e,section,0,0,0,List.of(ore),1,out);
                }
                int collectionReads=section.reads;
                boolean laterMatch=section.get(1,0,0).getBlock()==ore;
                int accepted=(int)out.stream().filter(p->p.x()==1).count();
                SearchGeometry.NearestBound raw=new SearchGeometry.NearestBound(1),filtered=new SearchGeometry.NearestBound(1);
                for(BlockPos p:out){raw.offer(0);if(p.x()==1)filtered.offer(0);}
                return Map.of("positions",out.stream().map(Object::toString).toList(),"reads",collectionReads,
                    "afterFilter",accepted,"laterMatchExists",laterMatch,
                    "rawCanStop",SearchGeometry.canStop(0,raw),"filteredCanStop",SearchGeometry.canStop(0,filtered));
            }
        }
        """;
}
