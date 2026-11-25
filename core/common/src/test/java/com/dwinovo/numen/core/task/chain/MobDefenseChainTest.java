package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDefenseChainTest {

    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> probe;

    /**
     * Full production chain, real Task/Reflex/AttackTaskRecord and Menace proximity members.
     * MC/entity/Attack lifecycle are recording boundary fakes, NOT vanilla emulation.
     * Real survey/AttackPlan run at the fake start/tick observation hook, NOT production onStart.
     * Owner identity is a boundary sentinel only: no real task-slot/selector is exercised.
     * Projectile physics, attack execution and navigation implementation are NOT tested.
     * Compiler failures are SETUP ERROR, never the expected business red.
     * The parent owns executing this existing test class through the approved runner.
     */
    @BeforeAll static void compileConsumer() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("SETUP ERROR: cannot locate candidate source tree");
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        Path src = temp.resolve("src"), out = temp.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", out.toString()));
        for (String name : List.of("Task", "TaskState", "reflex/Reflex")) {
            add(src, args, "com/dwinovo/numen/task/" + name + ".java", Files.readString(root.resolve(
                    "api/common/src/main/java/com/dwinovo/numen/task/" + name + ".java")));
        }
        for (String name : List.of("task/chain/MobDefenseChain", "task/combat/AttackTaskRecord", "task/survival/SurvivalDecisions")) {
            add(src, args, "com/dwinovo/numen/core/" + name + ".java", Files.readString(main.resolve(name + ".java")));
        }
        add(src, args, "com/dwinovo/numen/task/TaskRecord.java", """
                package com.dwinovo.numen.task;
                public abstract class TaskRecord {
                    public final String toolCallId; public final long deadlineGameTime;
                    protected TaskRecord(String tool, String call, long deadline) { toolCallId=call; deadlineGameTime=deadline; }
                    public abstract String describe();
                }
                """);
        add(src, args, "com/dwinovo/numen/task/TaskResult.java", """
                package com.dwinovo.numen.task;
                public record TaskResult(String message) { }
                """);
        add(src, args, "net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public double x=0.5, z=0.5; public float width=0.6f; public boolean alive=true, removed, allied;
                    public boolean isRemoved() { return removed; }
                    public boolean isAlliedTo(Entity e) { return allied; }
                    public Level world;
                    public int id=7; public int getId() { return id; }
                    public EntityType<?> type=EntityType.UNKNOWN;
                    public EntityType<?> getType() { return type; }
                    public float distanceTo(Entity e) { return (float)Math.sqrt(distanceToSqr(e)); }
                    public double getX() { return x; } public double getZ() { return z; }
                    public int getBlockX() { return (int)Math.floor(x); } public int getBlockZ() { return (int)Math.floor(z); }
                    public float getBbWidth() { return width; } public boolean isAlive() { return alive; }
                    public Level level() { return world; }
                    public Box getBoundingBox() { return new Box(); }
                    public double distanceToSqr(Entity e) { return (x-e.x)*(x-e.x)+(z-e.z)*(z-e.z); }
                    // Deliberately no collision/physics: world supplies candidate entities; production filters distance/alive.
                    public static class Box { public Box inflate(double r) { return this; } }
                    public static class Level {
                        public long time=1000; public final java.util.List<Mob> mobs=new java.util.ArrayList<>();
                        public long getGameTime() { return time; }
                        public java.util.List<Mob> getEntitiesOfClass(Class<Mob> type, Box box) { return mobs; }
                    }
                }
                """);
        add(src, args, "net/minecraft/world/entity/EntityType.java", """
                package net.minecraft.world.entity;
                public class EntityType<T> {
                    private final String id;
                    public EntityType(String id) { this.id=id; }
                    public String toString() { return id; }
                    public static final EntityType<?> COW=new EntityType<>("cow");
                    public static final EntityType<?> PIG=new EntityType<>("pig");
                    public static final EntityType<?> SHEEP=new EntityType<>("sheep");
                    public static final EntityType<?> CHICKEN=new EntityType<>("chicken");
                    public static final EntityType<?> COD=new EntityType<>("cod");
                    public static final EntityType<?> SALMON=new EntityType<>("salmon");
                    public static final EntityType<?> RABBIT=new EntityType<>("rabbit");
                    public static final EntityType<?> WOLF=new EntityType<>("wolf");
                    public static final EntityType<?> UNKNOWN=new EntityType<>("unknown");
                }
                """);
        add(src, args, "net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                public class LivingEntity extends Entity {
                    public boolean outmatched; public int tickCount=200, hurtTimestamp=200;
                    public LivingEntity attacker;
                    public LivingEntity getLastHurtByMob() { return attacker; }
                    public int getLastHurtByMobTimestamp() { return hurtTimestamp; }
                }
                """);
        add(src, args, "net/minecraft/world/entity/Mob.java", """
                package net.minecraft.world.entity;
                public class Mob extends LivingEntity {
                    public LivingEntity target; public boolean sight=true;
                    public LivingEntity getTarget() { return target; }
                    public boolean hasLineOfSight(Entity e) { return sight; }
                }
                """);
        add(src, args, "net/minecraft/world/entity/monster/RangedAttackMob.java",
                "package net.minecraft.world.entity.monster; public interface RangedAttackMob { }");
        add(src, args, "net/minecraft/world/entity/monster/Enemy.java",
                "package net.minecraft.world.entity.monster; public interface Enemy { }");
        add(src, args, "net/minecraft/world/entity/monster/Creeper.java", """
                package net.minecraft.world.entity.monster;
                public class Creeper extends net.minecraft.world.entity.Mob implements Enemy {
                    public boolean ignited, powered; public int swell;
                    public int getSwellDir() { return swell; } public boolean isIgnited() { return ignited; }
                    public boolean isPowered() { return powered; }
                }
                """);
        add(src, args, "net/minecraft/world/entity/boss/enderdragon/EndCrystal.java", """
                package net.minecraft.world.entity.boss.enderdragon;
                public class EndCrystal extends net.minecraft.world.entity.Entity { }
                """);
        add(src, args, "com/dwinovo/numen/entity/NumenPlayer.java", """
                package com.dwinovo.numen.entity;
                public class NumenPlayer extends net.minecraft.world.entity.LivingEntity {
                    public boolean enabled=true, paused, shift=true, melee=true, ranged;
                    public double health=20;
                    public final java.util.List<String> events=new java.util.ArrayList<>();
                    public final Object explicitOwner=new Object();
                    public NumenPlayer() { world=new Level(); }
                    public boolean fcEnabled() { return enabled; }
                    public boolean reflexPaused(String id) { return paused; }
                    public net.minecraft.world.entity.LivingEntity getLastHurtByMob() { return attacker; }
                    public void setShiftKeyDown(boolean v) { shift=v; events.add("shift:"+v); }
                }
                """);
        add(src, args, "com/dwinovo/numen/entity/InputDriver.java", """
                package com.dwinovo.numen.entity;
                public class InputDriver { public static void halt(NumenPlayer p) { p.events.add("halt"); } }
                """);
        add(src, args, "com/dwinovo/numen/Constants.java", """
                package com.dwinovo.numen;
                public class Constants {
                    public static final Log LOG=new Log();
                    public static class Log { public void info(String s,Object... args) { } public void warn(String s,Object... args) { } }
                }
                """);
        add(src, args, "com/dwinovo/numen/event/NumenEvents.java", """
                package com.dwinovo.numen.event;
                public class NumenEvents {
                    public static void body(com.dwinovo.numen.entity.NumenPlayer p,String s) { p.events.add("body:"+s); }
                }
                """);
        for (String name : List.of("Battlefield", "AttackPlan")) {
            add(src, args, "com/dwinovo/numen/core/combat/"+name+".java", Files.readString(main.resolve("combat/"+name+".java")));
        }
        add(src, args, "com/dwinovo/numen/core/combat/Loadout.java", """
                package com.dwinovo.numen.core.combat;
                import com.dwinovo.numen.entity.NumenPlayer;
                // Inventory observation only; not the real weapon-selection/inventory algorithm.
                public record Loadout(boolean hasMelee,boolean hasRanged) {
                    public static Loadout forTarget(NumenPlayer p,Object target) { return new Loadout(p.melee,p.ranged); }
                }
                """);
        String attack=Files.readString(main.resolve("task/combat/AttackCompanionTask.java"));
        add(src, args, "com/dwinovo/numen/core/task/combat/AttackCompanionTask.java", """
                package com.dwinovo.numen.core.task.combat;
                import com.dwinovo.numen.entity.NumenPlayer;
                import com.dwinovo.numen.task.*;
                import java.util.*;
                import net.minecraft.world.entity.*;
                import com.dwinovo.numen.core.combat.*;
                // Recording lifecycle boundary, but surveyField and AttackPlan below are real production.
                public class AttackCompanionTask {
                    public static TaskState next=TaskState.RUNNING;
                    private static final double FIELD_RADIUS=12;
                    private static final double HUNT_SAFE_RADIUS=12;   // same value as production; boundary constant only
                    private static final int MAX_RETREAT_FAILURES=3;
                    private int retreatFailures;
                    private List<Mob> hostiles=List.of();
                    private Set<Integer> knownRangedEngagements=Set.of();
                    public static AttackTaskRecord latestRecord;
                    private AttackPlan.Move lastMove;
                    public static AttackPlan.Move lastObserved;
                    private final AttackTaskRecord r;
                    private final NumenPlayer player;
                    private boolean reachable(int id) { return true; }
                    private double reachToTarget() { return 3.3; }
                    private Entity liveEntity(int id) { return player.world.mobs.stream().filter(m->m.id==id).findFirst().orElse(null); }
                    public AttackCompanionTask(NumenPlayer p,AttackTaskRecord r) {
                        player=p; this.r=r; latestRecord=r;
                        p.events.add("construct:"+r.indiscriminate+":"+r.entityIds.size()+":"+r.toolCallId+":"+r.deadlineGameTime);
                    }
                    private void observe(NumenPlayer p) {
                        Battlefield field=surveyField(); lastMove=AttackPlan.decide(field,lastMove); lastObserved=lastMove;
                        p.events.add("survey:"+field.foes().size()); p.events.add("action:"+lastMove.action());
                    }
                    public void start(NumenPlayer p) { p.events.add("start"); observe(p); }
                    public TaskState tick(NumenPlayer p) { p.events.add("fightTick"); observe(p); return next; }
                    public void stop(NumenPlayer p,Task.StopReason why) { p.events.add("stop:"+why); }
                    public TaskResult result(TaskState state) { player.events.add("result:"+state); return new TaskResult("fake:"+state); }
                """ + member(attack,"    private Battlefield surveyField()") + "\n"
                    + member(attack,"    private static boolean containsId(") + "\n"
                    + (attack.contains("    public boolean retreatingFromKnownRangedThreat()")
                        ? member(attack,"    public boolean retreatingFromKnownRangedThreat()") : "") + "\n}\n");
        String menace = Files.readString(main.resolve("combat/Menace.java"));
        StringBuilder proximity = new StringBuilder("""
                package com.dwinovo.numen.core.combat;
                import java.util.*;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.monster.*;
                import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
                import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;
                public final class Menace {
                    // Armor/health is an external observation, NOT a copied outmatched algorithm.
                    public static boolean outmatched(LivingEntity self) { return self.outmatched; }
                    public static double effectiveHealth(LivingEntity self) { return ((com.dwinovo.numen.entity.NumenPlayer)self).health; }
                """);
        for (String constant : List.of("CREEPER_BLAST_RADIUS", "CRYSTAL_BLAST_RADIUS", "MOB_ATTACK_REACH", "CELL_SLACK")) {
            int at = menace.indexOf(" " + constant + " =");
            if (at < 0) throw new IllegalStateException("SETUP ERROR: missing Menace constant " + constant);
            proximity.append(menace, menace.lastIndexOf('\n', at) + 1, menace.indexOf(';', at) + 1).append('\n');
        }
        for (String method : List.of("boolean explodes(", "boolean armed(", "boolean threatens(", "boolean fusing(",
                "boolean harmlessPrey(", "boolean harmlessPreyType(",
                "double dangerRadius(", "double rawDangerRadius(", "double strikeRangeOf(", "double blastSpanOf(",
                "boolean hostile(", "List<Mob> hostilesAround(", "boolean tooClose(")) {
            proximity.append(member(menace, "    public static " + method)).append('\n');
        }
        // On the unmodified red tree these members do not exist and the old chain never calls them.
        // Never fill that gap with a test-side copy of the proposed algorithm.
        if (menace.contains("    public static boolean rangedThreat(")) {
            int at=menace.indexOf(" FLEE_DISTANCE =");
            proximity.append(menace, menace.lastIndexOf('\n',at)+1, menace.indexOf(';',at)+1).append('\n');
            if (menace.contains("    public static boolean rangedWithinEscapeRange("))
                proximity.append(member(menace,"    public static boolean rangedWithinEscapeRange(")).append('\n');
            proximity.append(member(menace,"    public static boolean rangedThreat(")).append('\n');
            proximity.append(member(menace,"    public static List<Mob> combatantsAround(")).append('\n');
        }
        add(src, args, "com/dwinovo/numen/core/combat/Menace.java", proximity.append("}\n").toString());
        String goals = Files.readString(main.resolve("pathing/goals/GoalAvoidEntities.java"));
        add(src, args, "com/dwinovo/numen/core/pathing/goals/GoalAvoidEntities.java",
                "package com.dwinovo.numen.core.pathing.goals; public class GoalAvoidEntities {\n"
                        + member(goals, "    public static boolean clearOf(") + "\n}\n");
        add(src, args, "com/dwinovo/numen/core/task/chain/DefenseBoundaryProbe.java", boundaryProbe());
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("SETUP ERROR: JDK compiler required");
        var diagnostics = new ByteArrayOutputStream();
        if (javac.run(null, diagnostics, diagnostics, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("SETUP ERROR: full production chain fixture did not compile; NOT business red:\n"
                    + diagnostics.toString(StandardCharsets.UTF_8));
        }
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        probe = loader.loadClass("com.dwinovo.numen.core.task.chain.DefenseBoundaryProbe");
    }

    @AfterAll static void closeFixture() throws Exception { if (loader != null) loader.close(); }

    private static void add(Path src, List<String> args, String relative, String source) throws Exception {
        Path path = src.resolve(relative); Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8); args.add(path.toString());
    }

    private static String member(String source, String signature) {
        int start=source.indexOf(signature), end=source.indexOf("\n    }", start);
        if (start < 0 || end < 0 || source.indexOf(signature, start+1) >= 0)
            throw new IllegalStateException("SETUP ERROR: changed production member " + signature);
        return source.substring(start, end + "\n    }".length());
    }

    private static String boundaryProbe() {
        return """
                package com.dwinovo.numen.core.task.chain;
                import java.util.*;
                import com.dwinovo.numen.entity.NumenPlayer;
                import com.dwinovo.numen.task.*;
                import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
                import com.dwinovo.numen.core.task.combat.AttackCompanionTask;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.monster.*;
                // Not a test class or alternate chain: supplies facts and invokes the real chain.
                public class DefenseBoundaryProbe {
                    public static class Hostile extends Mob implements Enemy { }
                    public static class Shooter extends Hostile implements RangedAttackMob { }
                    public static Map<String,Object> run(String scenario) {
                        if (scenario.startsWith("hunt")) return hunt(scenario);
                        NumenPlayer p=new NumenPlayer(); Object owner=p.explicitOwner;
                        Mob m=scenario.equals("explosive") || scenario.equals("budgetHandbackArmed") ? new Creeper() :
                            scenario.equals("neutral") ? new Mob() : scenario.startsWith("remote") ? new Shooter() : new Hostile();
                        m.world=p.world; m.x=p.x+2; m.z=p.z; m.target=p;
                        if(m instanceof Creeper c && !scenario.equals("budgetHandbackArmed")) { c.ignited=true; m.x=p.x+6; }
                        if(scenario.startsWith("remote")) m.x=p.x+20;
                        if(scenario.equals("remote8")) m.x=p.x+8;
                        if(scenario.equals("remote32")) m.x=p.x+32;
                        if(scenario.equals("remoteBeyond")) m.x=p.x+32.01;
                        if(scenario.equals("remoteWall")) m.sight=false;
                        if(scenario.equals("remoteNoOwner")) m.target=null;
                        if(scenario.equals("remoteOtherOwner")) m.target=new LivingEntity();
                        if(scenario.equals("remoteAllied")) m.allied=true;
                        if(scenario.equals("remoteSelfAllied")) p.allied=true;
                        if(scenario.equals("remoteRemoved")) m.removed=true;
                        if(scenario.equals("remoteDead")) m.alive=false;
                        if(scenario.equals("remoteOtherLevel")) m.world=new Entity.Level();
                        if(scenario.startsWith("remoteHit")) {
                            m.sight=false; m.target=null; p.attacker=m;
                            p.hurtTimestamp=p.tickCount-(scenario.equals("remoteHit101") ? 101 :
                                scenario.equals("remoteHit100") ? 100 : scenario.equals("remoteHitFuture") ? -1 : 0);
                        }
                        if(scenario.equals("dead")) m.alive=false;
                        if(scenario.equals("bystander")) m.target=new LivingEntity();
                        if(scenario.equals("remoteRetreatMeleeLostSight")) { m.sight=true; m.target=p; }
                        else if(scenario.startsWith("remoteRetreat")) { p.melee=false; m.sight=false; p.attacker=m; p.hurtTimestamp=p.tickCount-100; }
                        if(scenario.equals("remoteLowHealth")) p.health=6;
                        if(scenario.equals("remoteUnarmed")) p.melee=false;
                        if(scenario.equals("remoteBow")) { p.melee=false; p.ranged=true; }
                        p.world.mobs.add(m);
                        Mob idle=new Shooter(); idle.world=p.world; idle.id=8; idle.x=p.x+10; idle.z=p.z;
                        if(scenario.equals("remoteRetreatBystander")) p.world.mobs.add(idle);
                        p.paused=scenario.equals("paused") || scenario.equals("outmatched");
                        p.outmatched=scenario.equals("outmatched") || scenario.equals("budgetHandback");
                        MobDefenseChain chain=new MobDefenseChain();
                        AttackCompanionTask.next=TaskState.RUNNING;
                        Map<String,Object> r=new LinkedHashMap<>();
                        boolean selected=chain.canRun(p); r.put("selected",selected);
                        if(selected) { r.put("firstState",chain.tick(p).name()); }
                        if(scenario.equals("remoteRetreatBystander") || scenario.equals("remoteRetreatTerminal")) {
                            p.tickCount++; p.world.time++; m.target=null;
                            if(scenario.equals("remoteRetreatBystander")) { m.x=p.x+33; idle.x=p.x+13; }
                            else AttackCompanionTask.latestRecord.unreachable(m.id);
                            r.put("continued",chain.canRun(p));
                            // Direct observation probes survey authorization even after the selector declines the chain.
                            chain.tick(p); r.put("lastSurveyEmpty",p.events.get(p.events.size()-2).equals("survey:0"));
                        } else if(scenario.startsWith("remoteRetreat")) {
                            p.tickCount++; p.world.time++; m.target=null;
                            if(scenario.equals("remoteRetreatMeleeLostSight")) m.sight=false; // shooter breaks LOS
                            r.put("continued",chain.canRun(p)); chain.tick(p);
                            r.put("retreatTicks",Collections.frequency(p.events,"action:DISENGAGE"));
                            if(scenario.equals("remoteRetreatDead")) m.alive=false;
                            else if(scenario.equals("remoteRetreatAlly")) m.allied=true;
                            else if(scenario.equals("remoteRetreatBudget")) p.world.time=1000+MobDefenseChain.MAX_REFLEX_FIGHT_TICKS;
                            else m.x=p.x+32.01;
                            r.put("released",!chain.canRun(p));
                        } else if(scenario.equals("preempt")) {
                            chain.stop(p,Task.StopReason.PREEMPTED);
                            r.put("resumed",chain.canRun(p)); chain.tick(p);
                        } else if(scenario.equals("disable")) {
                            p.enabled=false; r.put("disabled",chain.canRun(p));
                            chain.stop(p,Task.StopReason.REPLACED);
                            p.enabled=true; r.put("resumed",chain.canRun(p)); chain.tick(p);
                        } else if(scenario.equals("budget")) {
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            r.put("sameDanger",chain.canRun(p));
                            p.world.mobs.clear(); chain.canRun(p);
                            p.world.mobs.add(m); r.put("newDanger",chain.canRun(p)); chain.tick(p);
                        } else if(scenario.equals("budgetHandback")) {
                            // Consumer sequence: FC owns 400t, hands the body back, the
                            // mainline actually ticks for the grace window, then the same
                            // still-lethal threat / a new attacker must be re-evaluated.
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            int bodyFreeTicks=0;
                            for(int i=0;i<40;i++) {
                                p.world.time++;
                                // Selector-visible body window only; the real TaskSlot
                                // consumer is NOT exercised by this fixture.
                                if(!chain.canRun(p)) bodyFreeTicks++;
                            }
                            r.put("bodyFreeTicks",bodyFreeTicks);
                            r.put("sameLethalReeval",chain.canRun(p));
                            if(Boolean.TRUE.equals(r.get("sameLethalReeval"))) chain.tick(p);
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("secondBeforeBudget",chain.canRun(p));
                            if(Boolean.TRUE.equals(r.get("sameLethalReeval"))) chain.tick(p);
                            p.world.time++; r.put("secondAtBudget",chain.canRun(p));
                            // A genuinely new attacker arrives while the body is handed back.
                            Mob second=new Hostile(); second.world=p.world; second.id=9;
                            second.x=p.x+2; second.z=p.z; second.target=p;
                            p.world.mobs.add(second);
                            r.put("newAttackerReeval",chain.canRun(p));
                            if(Boolean.TRUE.equals(r.get("newAttackerReeval"))) chain.tick(p);
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("thirdBeforeBudget",chain.canRun(p));
                            if(Boolean.TRUE.equals(r.get("newAttackerReeval"))) chain.tick(p);
                            p.world.time++; r.put("thirdAtBudget",chain.canRun(p));
                            r.put("unresolvedNotified",p.events.stream().anyMatch(e->e.startsWith("body:")));
                        } else if(scenario.equals("budgetHandbackSafe")) {
                            // Same non-lethal danger must not re-arm on every grace window.
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            int bodyFreeTicks=0;
                            for(int i=0;i<40;i++) {
                                p.world.time++;
                                if(!chain.canRun(p)) bodyFreeTicks++;
                            }
                            r.put("bodyFreeTicks",bodyFreeTicks);
                            r.put("sameSafeReeval",chain.canRun(p));
                            r.put("unresolvedNotified",p.events.stream().anyMatch(e->e.startsWith("body:")));
                        } else if(scenario.equals("budgetHandbackHurt")) {
                            // Same threat set, but a fresh hurt is new evidence.
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            for(int i=0;i<40;i++) p.world.time++;
                            p.tickCount++; p.attacker=m; p.hurtTimestamp=p.tickCount;
                            r.put("newHurtReeval",chain.canRun(p));
                        } else if(scenario.equals("budgetHandbackHurtImmediate")) {
                            // A fresh hit must re-evaluate without waiting a fixed grace.
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            p.tickCount++; p.attacker=m; p.hurtTimestamp=p.tickCount;
                            r.put("newHurtImmediate",chain.canRun(p));
                        } else if(scenario.equals("budgetHandbackNewEnemyImmediate")) {
                            // A newly close enemy is new evidence even while handed back.
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            Mob second=new Hostile(); second.world=p.world; second.id=9;
                            second.x=p.x+2; second.z=p.z; second.target=p;
                            p.world.mobs.add(second);
                            r.put("newEnemyImmediate",chain.canRun(p));
                        } else if(scenario.equals("budgetHandbackArmed")) {
                            // Same creeper id, HP20, no fresh hurt: only unarmed->armed changes.
                            // Creeper state is a boundary fact; no explosion physics here.
                            p.world.time+=MobDefenseChain.MAX_REFLEX_FIGHT_TICKS-1;
                            r.put("beforeBudget",chain.canRun(p)); chain.tick(p);
                            p.world.time++; r.put("atBudget",chain.canRun(p));
                            if(m instanceof Creeper c) c.ignited=true;
                            r.put("sameCreeperArmedReeval",chain.canRun(p));
                        } else if(scenario.equals("cancelled")) {
                            AttackCompanionTask.next=TaskState.CANCELLED; chain.tick(p);
                            r.put("resumed",chain.canRun(p)); chain.tick(p);
                        } else if(scenario.equals("gone")) {
                            p.world.mobs.clear(); r.put("gone",chain.canRun(p));
                            chain.stop(p,Task.StopReason.PREEMPTED);
                        }
                        r.put("ownerPreserved",owner==p.explicitOwner);
                        r.put("shift",p.shift); r.put("events",List.copyOf(p.events)); return r;
                    }

                    private static Mob prey(NumenPlayer p, EntityType<?> type, int id) {
                        Mob m=new Mob(); m.type=type; m.world=p.world; m.id=id; m.x=p.x+2; m.z=p.z; return m;
                    }

                    /** Real named-hunt entry: real AttackTaskRecord -> real surveyField -> real AttackPlan. */
                    public static Map<String,Object> hunt(String scenario) {
                        NumenPlayer p=new NumenPlayer();
                        p.health=scenario.equals("huntHealthyCowWolf")?20:7; p.melee=true; p.ranged=false;
                        Mob cow=prey(p, EntityType.COW, 11);
                        p.world.mobs.add(cow);
                        if(scenario.equals("huntCowTargeting")) cow.target=p;
                        if(scenario.equals("huntRabbit")) cow.type=EntityType.RABBIT;
                        if(scenario.equals("huntCowWolf")) p.world.mobs.add(prey(p, EntityType.WOLF, 12));
                        if(scenario.equals("huntCowUnknown")) p.world.mobs.add(prey(p, EntityType.UNKNOWN, 12));
                        if(scenario.equals("huntCowNearbyPreyTargeting")) {
                            Mob other=prey(p, EntityType.COW, 12); other.target=p; p.world.mobs.add(other);
                        }
                        if(scenario.equals("huntCowUnlitCreeper")) {
                            Creeper c=new Creeper(); c.world=p.world; c.id=13; c.x=p.x+4; c.z=p.z; c.swell=0; p.world.mobs.add(c);
                        }
                        if(scenario.equals("huntCowEnemy")) {
                            Hostile h=new Hostile(); h.world=p.world; h.id=14; h.x=p.x+5; h.z=p.z; p.world.mobs.add(h);
                        }
                        if(scenario.equals("huntCowRanged")) {
                            Shooter s=new Shooter(); s.world=p.world; s.id=15; s.x=p.x+6; s.z=p.z; p.world.mobs.add(s);
                        }
                        if(scenario.equals("huntCowDeadEnemy")) {
                            Hostile d=new Hostile(); d.world=p.world; d.id=16; d.x=p.x+4; d.z=p.z; d.alive=false; p.world.mobs.add(d);
                        }
                        if(scenario.equals("huntCowTerminalEnemy")) {
                            Hostile st=new Hostile(); st.world=p.world; st.id=17; st.x=p.x+5; st.z=p.z; p.world.mobs.add(st);
                        }
                        boolean self=scenario.equals("huntSelfDefense");
                        AttackTaskRecord rec=new AttackTaskRecord("hunt-"+scenario, 0L, self?List.of():List.of(cow.id), self);
                        if(scenario.equals("huntCowTerminalEnemy")) rec.unreachable(17);
                        AttackCompanionTask task=new AttackCompanionTask(p, rec);
                        task.start(p);
                        Map<String,Object> r=new LinkedHashMap<>();
                        r.put("action", AttackCompanionTask.lastObserved.action().name());
                        r.put("foeId", AttackCompanionTask.lastObserved.foeId());
                        r.put("events", List.copyOf(p.events));
                        return r;
                    }
                }
                """;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> run(String scenario) throws Exception {
        return (Map<String,Object>) probe.getMethod("run", String.class).invoke(null, scenario);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> runHunt(String scenario) throws Exception {
        return (Map<String,Object>) probe.getMethod("hunt", String.class).invoke(null, scenario);
    }

    private static long events(Map<String,Object> r, String prefix) {
        return ((List<?>) r.get("events")).stream().filter(e -> e.toString().startsWith(prefix)).count();
    }

    @Test void engagedShooterAtEightOrTwentyBlocksStartsTheRealChainFight() throws Exception {
        // Original baseline: 8 blocks fails tooClose; 20 also exceeds the old 12-block scan.
        // This is the behavioral red on that baseline, not a helper-presence/string assertion.
        for (String scenario : List.of("remote8", "remote20", "remote32", "remoteHit", "remoteHit100")) {
            var r=run(scenario); assertEquals(true,r.get("selected"),scenario);
            assertEquals("RUNNING",r.get("firstState")); assertEquals(1,events(r,"construct:true:0:"));
            assertEquals(1,events(r,"start")); assertEquals(true,r.get("ownerPreserved"));
        }
    }

    @Test void retreatNeverPromotesIdleBystanderOrKeepsATerminalShooter() throws Exception {
        for (String scenario : List.of("remoteRetreatBystander", "remoteRetreatTerminal")) {
            var r=run(scenario); assertEquals(true,r.get("selected"),scenario);
            assertEquals(false,r.get("continued"),scenario); assertEquals(true,r.get("lastSurveyEmpty"),scenario);
        }
    }

    @Test void knownRetreatSurvivesLostEngagementButReleasesAtEscapeDeathAllianceOrBudget() throws Exception {
        for (String scenario : List.of("remoteRetreat", "remoteRetreatDead", "remoteRetreatAlly", "remoteRetreatBudget")) {
            var r=run(scenario); assertEquals(true,r.get("selected"),scenario); assertEquals(true,r.get("continued"),scenario);
            assertEquals(2,r.get("retreatTicks")); assertEquals(true,r.get("released"),scenario);
            if(scenario.equals("remoteRetreatBudget")) assertEquals(1,events(r,"result:CANCELLED"));
        }
    }

    /** D2 follow-up: once self-defense retreats from a shooter, one tick of lost LOS must not flip back to chasing. */
    @Test void selfDefenseMeleeRetreatDoesNotChargeBackWhenShooterBreaksLine() throws Exception {
        var r = run("remoteRetreatMeleeLostSight");
        assertEquals(true, r.get("selected"));
        assertEquals(2, r.get("retreatTicks"), "initial DISENGAGE and next tick must both stay retreating");
        assertEquals(true, r.get("continued"), "same known shooter keeps the chain committed");
    }
    @Test void realSurveyAndAttackPlanConsumeDistantCandidateWithoutForcingACharge() throws Exception {
        for (String scenario : List.of("remoteLowHealth", "remoteUnarmed", "remote20", "remoteBow")) {
            var r=run(scenario); assertEquals(true,r.get("selected")); assertEquals(1,events(r,"survey:1"));
            // 2026-11-24 D2: self-defense no longer charges a distant shooter with only melee.
            String action=scenario.equals("remote20") ? "DISENGAGE" : scenario.equals("remoteBow") ? "BOW" : "DISENGAGE";
            assertEquals(1,events(r,"action:"+action),scenario);
        }
    }

    /**
     * D2 death80：反射自卫只有石镐（hasMelee=true、hasRanged=false），骷髅在20格瞄她；
     * 现有 AttackPlan 会 SKIRMISH 冲近。期望反射不主动追远射，改 DISENGAGE；
     * 显式狩猎的独立意图由 reflex/explicit 区分保留（产品后继另测，不在本红例里假装）。
     */
    @Test void reflexDoesNotChargeDistantShooterWithOnlyMeleeTool() throws Exception {
        var r = run("remote20");
        assertEquals(true, r.get("selected"));
        assertEquals(1, events(r, "survey:1"));
        assertEquals(1, events(r, "action:DISENGAGE"),
                "reflex self-defense must not charge a 20-block shooter with only a melee tool");
    }
    @Test void distantShooterNeedsCurrentEngagementAndAValidLivingNonAlliedCandidate() throws Exception {
        for (String scenario : List.of("remoteWall", "remoteNoOwner", "remoteOtherOwner", "remoteBeyond",
                "remoteAllied", "remoteSelfAllied", "remoteRemoved", "remoteDead", "remoteOtherLevel",
                "remoteHit101", "remoteHitFuture")) {
            var r=run(scenario); assertEquals(false,r.get("selected"),scenario); assertEquals(0,events(r,"start"));
        }
    }

    @Test void originalThreatFiltersAndPausedOverrideAreConsumedByCanRunAndTick() throws Exception {
        for (String scenario : List.of("melee", "explosive", "outmatched")) {
            var r=run(scenario); assertEquals(true,r.get("selected"),scenario);
            assertEquals("RUNNING",r.get("firstState")); assertEquals(1,events(r,"construct:true:0:reflex-1000:1729000"));
            assertEquals(1,events(r,"start")); assertEquals(true,r.get("ownerPreserved"));
        }
        for (String scenario : List.of("neutral", "dead", "bystander", "paused")) {
            var r=run(scenario); assertEquals(false,r.get("selected"),scenario); assertEquals(0,events(r,"start"));
        }
    }

    @Test void preemptionForwardsStopAndResumesTheSameFightWithoutReplacingOwner() throws Exception {
        var r=run("preempt"); assertEquals(true,r.get("resumed"));
        assertEquals(1,events(r,"construct:")); assertEquals(1,events(r,"start"));
        assertEquals(1,events(r,"stop:PREEMPTED")); assertEquals(1,events(r,"fightTick"));
        assertEquals(1,events(r,"halt")); assertEquals(false,r.get("shift")); assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void disableForwardsStopAndReenableStartsFreshFight() throws Exception {
        var r=run("disable"); assertEquals(false,r.get("disabled")); assertEquals(true,r.get("resumed"));
        assertEquals(1,events(r,"stop:REPLACED")); assertEquals(2,events(r,"construct:"));
        assertEquals(2,events(r,"start")); assertEquals(1,events(r,"halt")); assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetReleaseBlocksSameDangerAndRearmsOnlyAfterClearObservation() throws Exception {
        var r=run("budget"); assertEquals(true,r.get("beforeBudget")); assertEquals(false,r.get("atBudget"));
        assertEquals(false,r.get("sameDanger")); assertEquals(true,r.get("newDanger"));
        assertEquals(1,events(r,"fightTick")); assertEquals(1,events(r,"stop:PREEMPTED"));
        assertEquals(1,events(r,"result:CANCELLED"));
        assertEquals(2,events(r,"construct:")); assertEquals(2,events(r,"start"));
        assertEquals(1,events(r,"halt")); assertEquals(false,r.get("shift")); assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetHandsBodyBackThenReevaluatesLethalAndNewAttackerAndNotifiesUnresolved() throws Exception {
        var r=run("budgetHandback");
        assertEquals(true,r.get("beforeBudget"));
        assertEquals(false,r.get("atBudget"));
        // Consumer boundary: canRun/tick exposes a body-free selector window.
        // The real TaskSlot/mainline executor is NOT exercised here (fixture boundary).
        assertTrue(((Integer)r.get("bodyFreeTicks")) >= 39);
        // Same threat is still lethal: must re-evaluate, not stay handed back forever.
        assertEquals(true,r.get("sameLethalReeval"));
        // A new attacker is new evidence even before the old danger clears.
        assertEquals(true,r.get("newAttackerReeval"));
        // Repeatedly unable to escape must be explicitly notified, not silently false.
        assertEquals(true,r.get("unresolvedNotified"));
        assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetHandbackSameSafeDangerDoesNotRearmOnEveryGraceWindow() throws Exception {
        var r=run("budgetHandbackSafe");
        assertEquals(true,r.get("beforeBudget")); assertEquals(false,r.get("atBudget"));
        assertTrue(((Integer)r.get("bodyFreeTicks")) >= 39);
        assertEquals(false,r.get("sameSafeReeval"));
        assertEquals(false,r.get("unresolvedNotified"));
        assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetHandbackFreshHurtReevaluatesEvenWhenDangerSetIsUnchanged() throws Exception {
        var r=run("budgetHandbackHurt");
        assertEquals(true,r.get("beforeBudget")); assertEquals(false,r.get("atBudget"));
        assertEquals(true,r.get("newHurtReeval"));
        assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetHandbackFreshHurtImmediateReevaluatesWithoutFixedGrace() throws Exception {
        var r=run("budgetHandbackHurtImmediate");
        assertEquals(true,r.get("beforeBudget")); assertEquals(false,r.get("atBudget"));
        assertEquals(true,r.get("newHurtImmediate"));
        assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetHandbackNewCloseEnemyImmediateReevaluatesWhileOldDangerPersists() throws Exception {
        var r=run("budgetHandbackNewEnemyImmediate");
        assertEquals(true,r.get("beforeBudget")); assertEquals(false,r.get("atBudget"));
        assertEquals(true,r.get("newEnemyImmediate"));
        assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void budgetHandbackSameCreeperUnarmedToArmedReevaluatesWithoutNewIdOrHurt() throws Exception {
        var r=run("budgetHandbackArmed");
        assertEquals(true,r.get("beforeBudget")); assertEquals(false,r.get("atBudget"));
        assertEquals(true,r.get("sameCreeperArmedReeval"));
        assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void cancelledFightEndsAndNextThreatCanStartFreshWithoutReplacingOwner() throws Exception {
        var r=run("cancelled"); assertEquals(true,r.get("resumed"));
        assertEquals(1,events(r,"fightTick")); assertEquals(2,events(r,"construct:"));
        assertEquals(2,events(r,"start")); assertEquals(1,events(r,"body"));
        assertEquals(1,events(r,"halt")); assertEquals(true,r.get("ownerPreserved"));
    }

    @Test void disappearingDangerReturnsBodyImmediately() throws Exception {
        var r=run("gone"); assertEquals(false,r.get("gone"));
        assertEquals(1,events(r,"stop:PREEMPTED")); assertEquals(1,events(r,"halt"));
        assertEquals(false,r.get("shift")); assertEquals(true,r.get("ownerPreserved"));
    }

    @Test
    void reflexFightHasAStrictBoundedOwnershipWindow() {
        long started = 4_000L;
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS - 1));
        assertTrue(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS));
    }

    @Test
    void tickScansOnceBeforeFightButStartLogKeepsFreshObservation() throws Exception {
        // Source wiring regression only; not a simulated Minecraft combat test.
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "must locate candidate source tree");
        String source = java.nio.file.Files.readString(root.resolve(
                "core/common/src/main/java/com/dwinovo/numen/core/task/chain/MobDefenseChain.java"));
        String canRun = between(source, "public boolean canRun(NumenPlayer companion) {", "public TaskState tick(NumenPlayer companion) {");
        String tick = between(source, "public TaskState tick(NumenPlayer companion) {", "private void begin(NumenPlayer companion) {");
        String begin = between(source, "private void begin(NumenPlayer companion) {", "private static final long NO_DEADLINE");
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(canRun, "dangersNear(companion)"));
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(tick, "dangersNear(companion)"));
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(begin, "dangersNear(companion)"));
        assertTrue(tick.contains("if (threat)"));
        assertTrue(tick.contains("if (!threat)"));
        assertTrue(begin.indexOf("fight.start(companion)") < begin.indexOf("dangersNear(companion)"));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, a + start.length());
        assertTrue(a >= 0 && b > a, "source method boundaries must exist");
        return source.substring(a, b);
    }

    private static int occurrences(String source, String needle) {
        return (source.length() - source.replace(needle, "").length()) / needle.length();
    }

    @Test
    void unstartedOrFutureFightNeverTimesOut() {
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(Long.MIN_VALUE, 10_000L));
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(10_000L, 9_999L));
    }

    // ==================== 061 harmless prey: low-HP named hunt through the real adapter ====================

    /**
     * Named hunt of an explicitly classified harmless prey at low health must act, not retreat.
     * EntityType -> Menace.harmlessPrey -> real surveyField -> real AttackPlan; no hand-filled flag.
     * On the pre-exemption baseline this is the business red (DISENGAGE), never a SETUP error.
     */
    @Test void namedHuntOfHarmlessPreyAtLowHealthActs() throws Exception {
        var r=runHunt("huntCow");
        assertEquals("SKIRMISH", r.get("action"), r.toString());
        assertEquals(11, r.get("foeId"), r.toString());
    }

    /** Every unknown / neutral / explosive / engaging / ranged fellow keeps the original 8-HP line. */
    @Test void anyThreatOrUnclassifiedBystanderKeepsTheEightHealthLine() throws Exception {
        for(String scenario : List.of("huntCowWolf", "huntCowUnknown", "huntCowUnlitCreeper",
                "huntCowEnemy", "huntCowRanged", "huntCowTargeting", "huntCowNearbyPreyTargeting")) {
            var r=runHunt(scenario);
            assertEquals("DISENGAGE", r.get("action"), scenario+" -> "+r);
        }
    }

    /**
     * A nearby living Enemy marked terminal (NO_PATH) is still alive and can hit her while she
     * hunts the cow. The original survey removes terminal targets, but the blocker scan must not
     * inherit that skip: terminal is "not worth chasing", not "safe".
     */
    @Test void terminalLivingBystanderStillBlocksTheHarmlessHunt() throws Exception {
        var r=runHunt("huntCowTerminalEnemy");
        assertEquals("DISENGAGE", r.get("action"), r.toString());
    }

    /** A dead bystander is not a live threat; it must not block the harmless hunt. */
    @Test void deadBystanderDoesNotBlockTheHarmlessHunt() throws Exception {
        var r=runHunt("huntCowDeadEnemy");
        assertEquals("SKIRMISH", r.get("action"), r.toString());
    }

    /** Rabbit stays out of the exact prey whitelist (killer rabbit shares the same EntityType). */
    @Test void rabbitTypeIsNotAHarmlessPrey() throws Exception {
        var r=runHunt("huntRabbit");
        assertEquals("DISENGAGE", r.get("action"), r.toString());
    }

    /** Self-defense (indiscriminate) never receives the named-hunt exemption. */
    @Test void selfDefenseNeverGetsThePreyExemption() throws Exception {
        var r=runHunt("huntSelfDefense");
        assertEquals("DISENGAGE", r.get("action"), r.toString());
    }

    /** Unauthorized bystanders must not rewrite the selected target when health is fine. */
    @Test void unauthorizedBystanderDoesNotChangeTargetSelectionWhenHealthy() throws Exception {
        var r=runHunt("huntHealthyCowWolf");
        assertEquals("SKIRMISH", r.get("action"), r.toString());
        assertEquals(11, r.get("foeId"), r.toString());
    }
}
