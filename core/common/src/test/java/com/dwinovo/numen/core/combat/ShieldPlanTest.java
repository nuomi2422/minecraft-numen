package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.combat.ShieldPlan.Decision;
import com.dwinovo.numen.core.combat.ShieldPlan.Mode;
import com.dwinovo.numen.core.combat.ShieldPlan.Threat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动举盾纯策略：useItem 互锁、五刻提前量、真实威胁选择和爆炸辅助边界。 */
class ShieldPlanTest {

    private static Threat threat(int id, double distance, double radius,
                                 boolean canRaise, boolean canHold) {
        return new Threat(id, distance * distance, radius * radius, canRaise, canHold);
    }

    private static ShieldPlan.Plan decide(boolean usable, boolean otherItem, boolean raised,
                                          List<Threat> threats, int preferred) {
        return ShieldPlan.decide(Mode.MELEE, usable, otherItem, raised, threats, preferred);
    }

    @Test
    void preemptionReleasesShieldBeforeHandingBodyToAnotherTask() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "core/common/src/main/java/com/dwinovo/numen/core/task/combat/AttackCompanionTask.java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "source root missing");
        String source = Files.readString(root.resolve(
                "core/common/src/main/java/com/dwinovo/numen/core/task/combat/AttackCompanionTask.java"));
        int stop = source.indexOf("public void stop(NumenPlayer companion, StopReason why)");
        assertTrue(stop >= 0, "attack task must release active item use on preemption");
        assertTrue(source.indexOf("releaseShield();", stop) >= 0);
        assertTrue(source.indexOf("releaseShield();", stop) < source.indexOf("super.stop(companion, why);", stop));
    }

    /** 切弓的当刻必须释放副手盾，不能让 shootAt 永远撞在 isUsingItem 上。 */
    @Test
    void bowSwitchReleasesExistingShieldBeforeDrawing() {
        assertEquals(new ShieldPlan.Plan(Decision.RELEASE, ShieldPlan.NO_TARGET),
                ShieldPlan.decide(Mode.BOW, true, false, true, List.of(), ShieldPlan.NO_TARGET));
        assertEquals(Decision.PROCEED,
                ShieldPlan.decide(Mode.BOW, true, false, false, List.of(), ShieldPlan.NO_TARGET).decision());
    }

    /** 非弓路径上，吃饭/拉弓等其它 item 使用分支仍然优先，不打断玩家动作。 */
    @Test
    void anotherItemInUseStillWins() {
        assertEquals(Decision.WAIT,
                decide(true, true, false, List.of(threat(1, 1.0, 2.0, true, true)), 1).decision());
    }

    @Test
    void nearRealThreatRaisesUsableShield() {
        assertEquals(new ShieldPlan.Plan(Decision.RAISE, 7),
                decide(true, false, false, List.of(threat(7, 1.5, 2.0, true, true)), 7));
    }

    @Test
    void noThreatReleasesAnExistingShield() {
        assertEquals(Decision.RELEASE, decide(true, false, true, List.of(), ShieldPlan.NO_TARGET).decision());
    }

    /** 副手被占用或背包无盾：不偷换玩家物品，也不新举。 */
    @Test
    void noUsableShieldMeansCarryOn() {
        assertEquals(Decision.PROCEED,
                decide(false, false, false, List.of(threat(1, 1.0, 2.0, true, true)), 1).decision());
    }

    /** 斧破盾冷却期间，已有盾不再有效；放下它，继续原有移动/撤退。 */
    @Test
    void cooldownReleasesAnAlreadyRaisedShield() {
        assertEquals(Decision.RELEASE,
                decide(false, false, true, List.of(threat(1, 1.0, 2.0, true, true)), 1).decision());
    }

    /** 五刻 × 实测 0.25 格/刻 = 1.25 格提前量，3 格点火线变为 4.25 格。 */
    @Test
    void creeperGetsFiveTickActualApproachLead() {
        double radius = ShieldPlan.triggerRadius(3.0, 0.25);
        assertEquals(4.25, radius, 1.0e-9);
        Threat creeper = threat(1, 4.2, radius, true, true);
        assertEquals(Decision.RAISE, decide(true, false, false, List.of(creeper), 1).decision());
        assertEquals(Decision.PROCEED,
                decide(true, false, false, List.of(threat(1, 4.3, radius, true, true)), 1).decision());
    }

    /** 静止爬行者没有虚构位移，仍必须覆盖到原版三点火线本身。 */
    @Test
    void stationaryCreeperStillCoversIgnitionLine() {
        double radius = ShieldPlan.triggerRadius(3.0, 0.0);
        assertEquals(3.0, radius, 1.0e-9);
        assertEquals(Decision.RAISE,
                decide(true, false, false, List.of(threat(1, 3.0, radius, true, true)), 1).decision());
        assertEquals(Decision.PROCEED,
                decide(true, false, false, List.of(threat(1, 3.01, radius, true, true)), 1).decision());
    }

    /** 已点火爆炸不新举盾；不能把辅助格挡放到撤退前面。 */
    @Test
    void armedExplosiveNeverStartsANewBlock() {
        Threat armed = threat(1, 3.0, 6.0, false, true);
        assertEquals(Decision.PROCEED, decide(true, false, false, List.of(armed), 1).decision());
    }

    /** 已有盾可朝向爆炸辅助，但决策仍是 HOLD，不改变现有撤退/走位所有权。 */
    @Test
    void armedExplosiveOnlyAssistsAnExistingBlock() {
        Threat armed = threat(1, 3.0, 6.0, false, true);
        assertEquals(new ShieldPlan.Plan(Decision.HOLD, 1),
                decide(true, false, true, List.of(armed), 1));
    }

    /** 多敌按距触发圈的 urgency 选，不是只盯当前攻击目标或盲选最近中心。 */
    @Test
    void mostUrgentThreatWinsEvenWhenItIsFartherAway() {
        Threat creeper = threat(1, 4.0, 4.25, true, true);
        Threat melee = threat(2, 2.1, 2.2, true, true);
        assertEquals(1, decide(true, false, false, List.of(melee, creeper), 2).targetId());
    }

    @Test
    void attackTargetOnlyBreaksAnUrgencyTie() {
        Threat first = threat(1, 2.0, 3.0, true, true);
        Threat second = threat(2, 2.0, 3.0, true, true);
        assertEquals(2, decide(true, false, false, List.of(first, second), 2).targetId());
        assertEquals(1, decide(true, false, false, List.of(first, second), ShieldPlan.NO_TARGET).targetId());
    }

    /** 攻击/移动切换后旧威胁离开触发圈，格挡方向立刻改选仍在圈内的真实威胁。 */
    @Test
    void targetSwitchKeepsBlockingTheNextActualThreat() {
        Threat oldTarget = threat(1, 1.0, 2.0, true, true);
        Threat nextTarget = threat(2, 1.5, 2.0, true, true);
        assertEquals(1, decide(true, false, true, List.of(oldTarget, nextTarget), 1).targetId());
        Threat gone = threat(1, 5.0, 2.0, true, true);
        assertEquals(2, decide(true, false, true, List.of(gone, nextTarget), 1).targetId());
    }

    /** 圈外、零半径和非有限输入一律 fail-closed，不凭脏快照举盾。 */
    @Test
    void invalidOrOutOfRangeCandidatesFailClosed() {
        assertEquals(ShieldPlan.NO_TARGET,
                decide(true, false, false, List.of(
                        threat(1, 3.0, 2.0, true, true),
                        new Threat(2, 1.0, 0.0, true, true),
                        new Threat(3, Double.NaN, 4.0, true, true)), ShieldPlan.NO_TARGET).targetId());
    }

    private static final double EPS = 1.0e-6;

    private static ShieldPlan.MovementInput preserve(float xxa, float zza,
                                                       float oldYaw, float newYaw) {
        return ShieldPlan.preserveWorldDirection(
                new ShieldPlan.MovementInput(xxa, zza), oldYaw, newYaw);
    }

    private static double[] world(ShieldPlan.MovementInput input, float yaw) {
        double radians = Math.toRadians(yaw);
        return new double[]{
                input.xxa() * Math.cos(radians) - input.zza() * Math.sin(radians),
                input.xxa() * Math.sin(radians) + input.zza() * Math.cos(radians)};
    }

    private static void assertSameWorld(ShieldPlan.MovementInput before, float oldYaw,
                                        ShieldPlan.MovementInput after, float newYaw) {
        double[] oldWorld = world(before, oldYaw);
        double[] newWorld = world(after, newYaw);
        assertEquals(oldWorld[0], newWorld[0], EPS);
        assertEquals(oldWorld[1], newWorld[1], EPS);
    }

    /** 从朝南前进转成朝东举盾：输入改为横移，世界移动仍向南 z+。 */
    @Test
    void facingThreatKeepsSouthwardForwardMovement() {
        ShieldPlan.MovementInput before = new ShieldPlan.MovementInput(0.0f, 1.0f);
        ShieldPlan.MovementInput after = preserve(0.0f, 1.0f, 0.0f, 90.0f);
        assertEquals(1.0f, after.xxa(), EPS);
        assertEquals(0.0f, after.zza(), EPS);
        assertSameWorld(before, 0.0f, after, 90.0f);
    }

    /** 反向旋转：从朝西前进转成朝南举盾，输入改为后退，世界移动仍向西 x-。 */
    @Test
    void reverseYawChangeKeepsWestwardForwardMovement() {
        ShieldPlan.MovementInput before = new ShieldPlan.MovementInput(0.0f, 1.0f);
        ShieldPlan.MovementInput after = preserve(0.0f, 1.0f, 90.0f, 0.0f);
        assertEquals(-1.0f, after.xxa(), EPS);
        assertEquals(0.0f, after.zza(), EPS);
        assertSameWorld(before, 90.0f, after, 0.0f);
    }

    @Test
    void rightStrafeAndBackpedalKeepTheirWorldDirections() {
        ShieldPlan.MovementInput right = new ShieldPlan.MovementInput(-1.0f, 0.0f);
        ShieldPlan.MovementInput turnedRight = preserve(-1.0f, 0.0f, 0.0f, 90.0f);
        assertEquals(0.0f, turnedRight.xxa(), EPS);
        assertEquals(1.0f, turnedRight.zza(), EPS);
        assertSameWorld(right, 0.0f, turnedRight, 90.0f);

        ShieldPlan.MovementInput back = new ShieldPlan.MovementInput(0.0f, -1.0f);
        ShieldPlan.MovementInput turnedBack = preserve(0.0f, -1.0f, 90.0f, 0.0f);
        assertEquals(1.0f, turnedBack.xxa(), EPS);
        assertEquals(0.0f, turnedBack.zza(), EPS);
        assertSameWorld(back, 90.0f, turnedBack, 0.0f);
    }

    @Test
    void zeroMovementStaysZeroAcrossYawChange() {
        ShieldPlan.MovementInput stationary = new ShieldPlan.MovementInput(0.0f, 0.0f);
        assertEquals(stationary, preserve(0.0f, 0.0f, 37.0f, -122.0f));
    }
}
