package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entire production MineBlockTaskRecord against MC position/block and base-record boundaries.
 * Tests target scope only, not a route/terrain boundary, index binding or live world identity. */
class MineTargetScopeRecordConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;static Class<?> record,pos;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();assertNotNull(root);
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        add(src,args,"com/dwinovo/numen/core/task/mine/MineBlockTaskRecord.java",Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/mine/MineBlockTaskRecord.java")));
        add(src,args,"com/dwinovo/numen/task/TaskRecord.java","package com.dwinovo.numen.task; public abstract class TaskRecord {protected TaskRecord(String t,String c,long d){} public abstract String describe();}");
        add(src,args,"net/minecraft/world/level/block/Block.java","package net.minecraft.world.level.block; public class Block {}");
        add(src,args,"net/minecraft/core/BlockPos.java","""
            package net.minecraft.core;
            public class BlockPos {
                protected int x,y,z;public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
                public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
                public static class MutableBlockPos extends BlockPos {public MutableBlockPos(int x,int y,int z){super(x,y,z);}public void set(int x,int y,int z){this.x=x;this.y=y;this.z=z;}}
            }
            """);
        ByteArrayOutputStream errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,args.toArray(String[]::new)),"SETUP: "+errors);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        record=loader.loadClass("com.dwinovo.numen.core.task.mine.MineBlockTaskRecord");pos=loader.loadClass("net.minecraft.core.BlockPos");
    }
    static void add(Path p,List<String>a,String n,String s)throws Exception{MineHarvestRecoveryConsumerTest.add(p,a,n,s);}
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    static Object p(int x,int y,int z)throws Exception{return pos.getConstructor(int.class,int.class,int.class).newInstance(x,y,z);}
    static Constructor<?> scoped(){
        var ctor=Arrays.stream(record.getConstructors()).filter(c->c.getParameterCount()==7).findFirst();
        assertTrue(ctor.isPresent(),"target-scope record capability is absent, not a defect in the unscoped constructor");return ctor.orElseThrow();
    }
    static Object make(Object a,Object b)throws Exception{return scoped().newInstance("call",1000L,Set.of(),4,"ore",a,b);}
    static boolean contains(Object r,int x,int y,int z)throws Exception{return (boolean)record.getMethod("containsTarget",pos).invoke(r,p(x,y,z));}
    @Test void legacyConstructorRemainsUnbounded()throws Exception{
        Object r=record.getConstructor(String.class,long.class,Set.class,int.class,String.class).newInstance("call",1000L,Set.of(),4,"ore");
        assertNotNull(r);assertEquals("挖 ore 0/4",record.getMethod("describe").invoke(r));
    }
    @Test void reversedCornersNormalizeAndBothBoundaryCellsAreIncluded()throws Exception{
        Object r=make(p(3,70,4),p(-2,60,-5));assertTrue(contains(r,-2,60,-5));assertTrue(contains(r,3,70,4));
        assertFalse(contains(r,-3,60,-5));assertFalse(contains(r,3,71,4));assertFalse(contains(r,3,70,5));
    }
    @Test void mutableCallerCornersCannotRetargetAnExistingScope()throws Exception{
        Class<?> mutable=loader.loadClass("net.minecraft.core.BlockPos$MutableBlockPos");Object a=mutable.getConstructor(int.class,int.class,int.class).newInstance(0,64,0);
        Object r=make(a,p(2,66,2));mutable.getMethod("set",int.class,int.class,int.class).invoke(a,100,100,100);
        assertTrue(contains(r,0,64,0));assertFalse(contains(r,100,100,100));
    }
    @Test void singleCornerIsRejectedInsteadOfSilentlyUnbounded()throws Exception{
        Constructor<?> ctor=scoped();var failure=assertThrows(InvocationTargetException.class,()->ctor.newInstance("call",1000L,Set.of(),4,"ore",p(0,0,0),null));
        assertInstanceOf(IllegalArgumentException.class,failure.getCause());
    }
    @Test void targetYRestrictionDoesNotRequireTheBodyToStandInsideIt()throws Exception{
        Object r=make(p(0,66,0),p(0,66,0));assertTrue(contains(r,0,66,0));
        assertFalse(contains(r,0,64,0),"body feet are not ore candidates; task must not use this as its stance rule");
    }
    @Test void explicitAbsentScopeKeepsUnlimitedTargetCandidates()throws Exception{
        Object r=make(null,null);assertTrue(contains(r,Integer.MIN_VALUE,-64,Integer.MAX_VALUE));
    }
}
