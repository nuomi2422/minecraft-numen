package com.dwinovo.numen.core.task.interact;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact production act/button/Interaction entity-attack paths; Minecraft is a counter boundary. */
class InteractGolemSafetyConsumerTest {
 static String method(String text,String start,String end){int a=text.indexOf(start),b=text.indexOf(end,a);assertTrue(a>=0&&b>a,"missing source seam "+start);return text.substring(a,b).replace("@Override","");}
 @Test void golemLeftMustNotAttackWhileEnemyAndRightStillWork() throws Exception {
  Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();assertNotNull(root);
  String task=Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/interact/InteractEntityCompanionTask.java"));
  String interaction=Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/act/Interaction.java"));
  String act=method(task,"    protected TaskState act() {","    /**\n     * Bounded recovery");
  String button=method(task,"    private Interaction.Button button() {","    private boolean withinReach()");
  String factory=method(interaction,"    public static Interaction forHit(","    public String failReason()").replace("NumenPlayer", "Fixture.NumenPlayer").replace("BlockHitResult", "Fixture.BlockHitResult").replace("EntityHitResult", "Fixture.EntityHitResult").replace("HitResult hit", "Fixture.HitResult hit").replace("Entity e =", "Fixture.Entity e =");
  String attackFactory=method(interaction,"    public static Interaction attackEntity(NumenPlayer p, Entity target, Timing timing)","    /** Right-click a block").replace("NumenPlayer", "Fixture.NumenPlayer").replace("Entity target", "Fixture.Entity target");
  String tick=method(interaction,"    public Status tick() {","    // ---- ATTACK + block");
  String discrete=method(interaction,"    private Status discrete() {","    private boolean fireAttackEntity()");
  String attack=method(interaction,"    private boolean fireAttackEntity() {","    private boolean fireUseBlock()").replace("InputDriver.","Fixture.InputDriver.").replace("entity instanceof net.minecraft.world.entity.LivingEntity living", "entity instanceof Fixture.LivingEntity living").replace("com.dwinovo.numen.core.combat.Swing.","Fixture.Swing.");
  act=act.replace("com.dwinovo.numen.core.act.PressReceipt.before", "PressReceipt.before")
         .replace("net.minecraft.world.entity.animal.IronGolem", "IronGolem");
  String code=HEADER+act+button+"}\n"+IHEADER+factory+attackFactory+tick+discrete+attack+"}\n";
  Path dir=Files.createTempDirectory("golem-consumer-");Path file=dir.resolve("Fixture.java");Files.writeString(file,code);
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",dir.toString(),file.toString()),"SETUP ERROR");
  try(var loader=new URLClassLoader(new URL[]{dir.toUri().toURL()},null)){
   var c=loader.loadClass("Fixture");var m=c.getMethod("run",String.class,String.class);
   assertAll(
    () -> assertEquals("FAILED:0:0",m.invoke(null,"golem","left"),"golem left must refuse before attack"),
    () -> assertEquals("SUCCESS:1:0",m.invoke(null,"enemy","left"),"enemy left remains available"),
    () -> assertEquals("SUCCESS:0:1",m.invoke(null,"golem","right"),"golem right remains available"),
    () -> assertEquals("FAILED:0:0",m.invoke(null,"changed","left"),"target changed before action is rechecked")
   );
  }
 }
 static final String HEADER="""
import java.util.*;
public class Fixture {
 enum TaskState {RUNNING,SUCCESS,FAILED} enum MouseButton{LEFT,RIGHT} enum FailureType{UNKNOWN,TARGET_LOST}
 static final double REACH=3; static class Entity {boolean isAlive(){return true;} Object getEyePosition(){return this;}} static class LivingEntity extends Entity {int hurtTime;} static class IronGolem extends LivingEntity {} static class Enemy extends LivingEntity {} static class Swing {static boolean mayStrike(boolean a,boolean b,float c){return true;}}
 static class HitResult {enum Type{ENTITY,BLOCK,MISS} Type getType(){return Type.ENTITY;}} static class EntityHitResult extends HitResult {Entity e;EntityHitResult(Entity e){this.e=e;} Entity getEntity(){return e;}} static class BlockHitResult extends HitResult {Object getBlockPos(){return null;}}
 static class ItemStack {Object getItem(){return null;}} static class NumenPlayer {int attacks,uses; Entity target;Object getVehicle(){return null;} NumenPlayer level(){return this;}long getGameTime(){return 0;}ItemStack getMainHandItem(){return new ItemStack();}ItemStack getOffhandItem(){return new ItemStack();} Object getInventory(){return null;} void holdInHand(int i){} void setSprinting(boolean b){} void attack(Entity e){attacks++;} void swing(InteractionHand h){} float getAttackStrengthScale(float f){return 1;}}
 static class InputDriver {static void lookAt(NumenPlayer p,Object o){} static void halt(NumenPlayer p){}}
 static class PressReceipt {static Object before(Object a,Object b){return null;}} static class PlayerInv {static int findSlot(Object a,Object b){return 0;}} static class InteractAtTaskRecord {static String bodyBoundReason(Object a){return null;}}
 static class Record {MouseButton button;Object item;int holdTicks;} Record r=new Record(); NumenPlayer player=new NumenPlayer(); Entity entity;Interaction interaction;long holdUntil=-1;boolean acted;String successMsg; Object receipt;
 void fail(String s,FailureType t){} String targetName(){return "target";} String describeDone(){return "done";}String settle(){return "";}
 public static String run(String target,String key){Fixture f=new Fixture();f.entity=target.equals("golem")||target.equals("changed")?new IronGolem():new Enemy();f.player.target=f.entity;f.r.button=key.equals("left")?MouseButton.LEFT:MouseButton.RIGHT;TaskState result=f.act();return result+":"+f.player.attacks+":"+f.player.uses;}
""";
 static final String IHEADER="""
enum InteractionHand {MAIN_HAND}
class Interaction {
 static final int CONTINUOUS=1000000, RIGHT_CLICK_DELAY=4;
 enum Status {RUNNING,DONE,FAILED} enum Button {ATTACK,USE}
 static class Timing {int limit=1,interval=1;static Timing once(){return new Timing();}static Timing repeat(int a,int b){return new Timing();}static Timing hold(){return new Timing();}static Timing hold(int x){return new Timing();}}
 Fixture.NumenPlayer player; Fixture.Entity entity; Button button;Object block,presetHit;int cooldown,fires;boolean hardFail,itemFallthrough;Timing timing=new Timing();
 Interaction(Fixture.NumenPlayer p,Button b,Object k,Fixture.Entity e,InteractionHand h,Timing t){player=p;button=b;block=k;entity=e;timing=t;}
 static Interaction attackBlock(Fixture.NumenPlayer p,Object b){return null;}static Interaction useInAir(Fixture.NumenPlayer p,InteractionHand h,Timing t){return null;}
 static Fixture.HitResult nativeRaytrace(Fixture.NumenPlayer p,double reach){return new Fixture.EntityHitResult(p.target);}
 Status breakBlock(){return Status.DONE;}Status useAir(){return Status.DONE;}boolean fireUseEntity(){player.uses++;return true;}boolean fireUseBlock(){return true;} void stop(){} String failReason(){return "";}
""";
}
