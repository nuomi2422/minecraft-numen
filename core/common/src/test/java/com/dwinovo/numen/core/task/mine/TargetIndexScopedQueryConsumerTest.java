package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes complete production query/collect methods plus original SearchGeometry.
 * Section/index/world storage is supplied; actual MC chunk lifecycle and scan cost are not proven. */
class TargetIndexScopedQueryConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        Path main=root.resolve("core/common/src/main/java/com/dwinovo/numen/core/scan");
        String source=Files.readString(main.resolve("TargetIndex.java"));
        String methods="";
        for(String prefix:List.of("    public static Result query(","    private static void collect(")){
            int at=0;
            while((at=source.indexOf(prefix,at))>=0){
                int end=source.indexOf("\n    }",at);
                assertTrue(end>at);methods+=source.substring(at,end+6)+"\n";at=end+6;
            }
        }
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/ScopedIndex.java",BOUNDARIES.replace("QUERY_METHODS",methods));
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/SearchGeometry.java",Files.readString(main.resolve("SearchGeometry.java")).replace("package com.dwinovo.numen.core.scan;","package fixture;"));
        ByteArrayOutputStream diagnostics=new ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new)),"SETUP: "+diagnostics);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.ScopedIndex");
    }
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String s)throws Exception{
        boolean supported=Arrays.stream(fixture.getDeclaredMethods()).anyMatch(m->m.getName().equals("query")&&m.getParameterCount()==7);
        assertTrue(supported,"requested scoped query capability is absent; not an existing unscoped API violation");
        try{return (Map<String,Object>)fixture.getMethod("run",String.class).invoke(null,s);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    @Test void excludedNearOreCannotTriggerEarlyStopBeforeFarAcceptedOre()throws Exception{
        var r=run("far");assertEquals(List.of("16,0,0"),r.get("hits"));assertEquals(true,r.get("complete"));
    }
    @Test void saturatedQuotaCountsAcceptedPositionsNotRawHits()throws Exception{
        var r=run("saturated");assertEquals(List.of("1,0,0"),r.get("hits"));assertEquals(2,r.get("reads"));
    }
    @Test void rejectedWholeSectionDoesNotPreventNextSectionFromFillingQuota()throws Exception{
        var r=run("section");assertEquals(List.of("0,16,0"),r.get("hits"));
    }
    @Test void filteredEmptyWithExhaustedBuildBudgetIsIncomplete()throws Exception{
        var r=run("budget");assertEquals(List.of(),r.get("hits"));assertEquals(false,r.get("complete"));assertEquals(0,r.get("builds"));
    }
    @Test void fullLoadedEmptyIsDifferentFromBudgetUnknown()throws Exception{
        var r=run("empty");assertEquals(List.of(),r.get("hits"));assertEquals(true,r.get("complete"));
    }
    @Test void defaultTrueAndLaterUnfilteredReadKeepOriginalSharedEntries()throws Exception{
        var r=run("compat");assertEquals(r.get("legacy"),r.get("all"));assertEquals(r.get("legacy"),r.get("after"));
        assertEquals(List.of(),r.get("hits"));
    }
    @Test void sparseNegativeSectionCoordinatesRemainCorrect()throws Exception{
        var r=run("negative");assertEquals(List.of("-1,-1,-1"),r.get("hits"));
    }
    @Test void predicateFailurePropagatesInsteadOfEmptyComplete()throws Exception{
        assertThrows(IllegalStateException.class,()->run("throw"));
    }
    @Test void nullPredicateIsRejectedEvenWithoutIndex()throws Exception{
        assertThrows(NullPointerException.class,()->run("null"));
    }
    static final String BOUNDARIES="""
        package fixture;
        import java.util.*;import java.util.function.Predicate;
        public class ScopedIndex {
            record BlockPos(int x,int y,int z){int getX(){return x;}int getY(){return y;}int getZ(){return z;}
                double distSqr(BlockPos p){return (x-p.x)*(x-p.x)+(y-p.y)*(y-p.y)+(z-p.z)*(z-p.z);}
                public String toString(){return x+","+y+","+z;}}
            static class SectionPos {
                static int sectionToBlockCoord(int n){return n*16;}static int blockToSectionCoord(int n){return n>>4;}
                static long asLong(int x,int y,int z){return ((long)(x&0x3fffff)<<42)|((long)(z&0x3fffff)<<20)|(y&0xfffff);}
            }
            static class Block {}
            record BlockState(Block block){Block getBlock(){return block;}}
            static class LevelChunkSection {
                final Block block;int reads;LevelChunkSection(Block b){block=b;}
                LevelChunkSection getStates(){return this;}BlockState get(int x,int y,int z){reads++;return new BlockState(block);}
            }
            record LevelChunk(LevelChunkSection[] sections){LevelChunkSection[] getSections(){return sections;}}
            static class ServerLevel {
                Map<String,LevelChunk> chunks=new HashMap<>();int minSection=0,sectionCount=1;
                Object dimension(){return "world";}int getMinSection(){return minSection;}int getSectionsCount(){return sectionCount;}
                ServerLevel getChunkSource(){return this;}LevelChunk getChunkNow(int x,int z){return chunks.get(x+","+z);}
            }
            static final short[] SATURATED={-1};
            static class SectionEntry {int version=1;Map<Block,short[]> hits=new HashMap<>();}
            static class LevelIndex {int version=1;Map<Long,SectionEntry> sections=new HashMap<>();}
            static Map<Object,LevelIndex> INDEXES=new HashMap<>();static int builds;
            public record Result(List<BlockPos> hits,boolean complete){}
            static boolean triviallyEmpty(LevelChunkSection s,LevelIndex i){return s==null;}
            static SectionEntry build(LevelChunkSection s,LevelIndex i){builds++;SectionEntry e=new SectionEntry();e.hits.put(s.block,SATURATED);return e;}
        QUERY_METHODS
            static List<String> positions(Result r){return r.hits().stream().map(Object::toString).toList();}
            static void section(ServerLevel level,LevelIndex idx,Block ore,int cx,int sy,int cz,boolean saturated){
                LevelChunk chunk=level.chunks.computeIfAbsent(cx+","+cz,k->new LevelChunk(new LevelChunkSection[level.sectionCount]));
                chunk.sections()[sy-level.minSection]=new LevelChunkSection(ore);
                SectionEntry e=new SectionEntry();e.hits.put(ore,saturated?SATURATED:new short[]{0,(short)0xfff});
                idx.sections.put(SectionPos.asLong(cx,sy,cz),e);
            }
            static Result scoped(ServerLevel l,BlockPos c,List<Block> t,int n,int radius,int budget,Predicate<BlockPos> p)throws Exception{
                try{return (Result)ScopedIndex.class.getDeclaredMethod("query",ServerLevel.class,BlockPos.class,Collection.class,int.class,int.class,int.class,Predicate.class).invoke(null,l,c,t,n,radius,budget,p);}
                catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
            }
            public static Map<String,Object> run(String scenario)throws Exception{
                INDEXES.clear();builds=0;ServerLevel level=new ServerLevel();LevelIndex idx=new LevelIndex();INDEXES.put(level.dimension(),idx);
                Block ore=new Block();BlockPos center=new BlockPos(0,0,0);int radius=0,budget=5;
                Predicate<BlockPos> accepted=p->p.x()==1;
                switch(scenario){
                    case "far" -> {section(level,idx,ore,0,0,0,true);section(level,idx,ore,1,0,0,true);radius=1;accepted=p->p.x()>=16;}
                    case "saturated" -> section(level,idx,ore,0,0,0,true);
                    case "section" -> {level.sectionCount=2;section(level,idx,ore,0,0,0,true);section(level,idx,ore,0,1,0,true);accepted=p->p.y()>=16;}
                    case "budget" -> {section(level,idx,ore,0,0,0,true);idx.sections.clear();budget=0;accepted=p->false;}
                    case "empty" -> {section(level,idx,ore,0,0,0,true);accepted=p->false;}
                    case "compat" -> {section(level,idx,ore,0,0,0,false);accepted=p->false;}
                    case "negative" -> {level.minSection=-1;center=new BlockPos(-1,-1,-1);section(level,idx,ore,-1,-1,-1,false);accepted=p->p.x()==-1;}
                    case "throw" -> {section(level,idx,ore,0,0,0,true);accepted=p->{throw new IllegalStateException("bad scope");};}
                    case "null" -> {INDEXES.clear();accepted=null;}
                    default -> throw new IllegalArgumentException(scenario);
                }
                List<String> legacy=scenario.equals("compat")?positions(query(level,center,List.of(ore),1,radius,budget)):List.of();
                List<String> all=scenario.equals("compat")?positions(scoped(level,center,List.of(ore),1,radius,budget,p->true)):List.of();
                Result r=scoped(level,center,List.of(ore),1,radius,budget,accepted);
                List<String> after=scenario.equals("compat")?positions(query(level,center,List.of(ore),1,radius,budget)):List.of();
                int reads=level.chunks.values().stream().flatMap(c->Arrays.stream(c.sections())).filter(Objects::nonNull).mapToInt(s->s.reads).sum();
                return Map.of("hits",positions(r),"complete",r.complete(),"reads",reads,"builds",builds,"legacy",legacy,"all",all,"after",after);
            }
        }
        """;
}
