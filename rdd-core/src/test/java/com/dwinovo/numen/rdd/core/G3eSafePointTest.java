package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3e 契约（IC75-r1 §15.8 安全点）：<b>结构变更必须出示一张「身体停着」的凭据</b>。
 *
 * <p>这组断言钉住四件在实机上很难复现的事：
 * ①凭据一次性（同 epoch、同用途也只能用一次）；
 * ②旧 epoch 一律作废（换人设/死亡复活后旧凭据不许复活）；
 * ③过期不会自动放行结构变更（宁可重新签，不许拿过期凭据改计划）；
 * ④冻结只作用于「新动作」（凭据在途时不许 beginAttempt / 换计划），且被拒的调用不改状态。
 */
class G3eSafePointTest {

    private static Subtask sub(String id) {
        return Subtask.hardCoded(id, "do " + id, Map.of("type", "true"));
    }

    /** 已展开的一级：可以 startCurrent（用于验冻结）。 */
    private static TaskChain expandedChain() {
        PrimaryGoal primary = new PrimaryGoal("p1", "get wood",
                List.of(sub("p1-0"), sub("p1-1")),
                List.of(new AssetRequirement("minecraft:oak_log", 4)), false);
        return new TaskChain(new Goal("g3e", "G3e safe point", List.of(primary)));
    }

    /** 未展开的一级：可以走 expandCurrentPrimary（用于验带凭据的结构变更）。 */
    private static TaskChain unexpandedChain() {
        PrimaryGoal primary = new PrimaryGoal("p1", "get wood", List.of(), List.of(), true);
        return new TaskChain(new Goal("g3e", "G3e safe point", List.of(primary)));
    }

    @Test
    void issuedTokenIsAcceptedOnceThenConsumed() { // S90
        TaskChain c = unexpandedChain();
        SafePoint token = c.issueSafePoint(SafePoint.Purpose.EXPAND_PRIMARY, 60_000);

        assertEquals(SafePoint.OK, c.checkSafePoint(token, SafePoint.Purpose.EXPAND_PRIMARY).code());
        assertTrue(c.consumeSafePoint(token), "第一次消费必须成功");
        assertFalse(c.consumeSafePoint(token), "第二次必须失败：一次性");
        assertEquals(SafePoint.ALREADY_CONSUMED,
                c.checkSafePoint(token, SafePoint.Purpose.EXPAND_PRIMARY).code());
        assertNull(c.outstandingSafePoint(), "消费后不该再冻结");
    }

    @Test
    void tokenFromAnotherOwnerEpochIsStale() { // S91
        TaskChain c = expandedChain();
        SafePoint token = c.issueSafePoint(SafePoint.Purpose.REPLACE_PLAN, 60_000);
        long before = c.stateVersion();

        assertEquals(1L, c.bumpOwnerEpoch(), "换代 = +1");
        assertEquals(SafePoint.STALE_EPOCH, c.checkSafePoint(token, SafePoint.Purpose.REPLACE_PLAN).code());
        assertFalse(c.consumeSafePoint(token));
        assertEquals(before, c.stateVersion(), "被拒的凭据不许改状态");
        assertNull(c.outstandingSafePoint(), "换代必须当场解冻");
    }

    @Test
    void purposeMismatchIsRejected() { // S92
        TaskChain c = unexpandedChain();
        SafePoint token = c.issueSafePoint(SafePoint.Purpose.REPLACE_PLAN, 60_000);
        assertEquals(SafePoint.WRONG_PURPOSE, c.checkSafePoint(token, SafePoint.Purpose.PATCH_NODE).code());
        assertEquals(SafePoint.OK, c.checkSafePoint(token, SafePoint.Purpose.REPLACE_PLAN).code(),
                "用途不匹配的拒绝不该顺手把凭据弄坏");
    }

    @Test
    void expiredTokenIsRejectedAndDoesNotUnlockStructuralChange() throws Exception { // S93
        TaskChain c = unexpandedChain();
        SafePoint token = c.issueSafePoint(SafePoint.Purpose.EXPAND_PRIMARY, 1);
        Thread.sleep(15);
        assertEquals(SafePoint.EXPIRED, c.checkSafePoint(token, SafePoint.Purpose.EXPAND_PRIMARY).code());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> c.expandCurrentPrimary(List.of(sub("p1-9")), token));
        assertTrue(e.getMessage().contains("EXPIRED"), e.getMessage());

        SafePoint fresh = c.issueSafePoint(SafePoint.Purpose.EXPAND_PRIMARY, 60_000);
        assertTrue(c.consumeSafePoint(fresh), "过期后重新签发的新凭据可用（过期不会自动放行）");
    }

    @Test
    void outstandingTokenFreezesNewAttemptsButNotStatuses() { // S94
        TaskChain c = expandedChain();
        c.issueSafePoint(SafePoint.Purpose.EXPAND_PRIMARY, 60_000);
        long version = c.stateVersion();

        IllegalStateException e = assertThrows(IllegalStateException.class, c::startCurrent);
        assertTrue(e.getMessage().contains("SAFE_POINT_PENDING"), e.getMessage());
        assertEquals(version, c.stateVersion(), "被冻结的启动不许改 stateVersion");
        assertEquals(0, c.attempts("p1-0"), "被冻结的启动不许 +attempts");
        // 换计划的旧签名入口同样被冻住：不许绕过冻结
        assertThrows(IllegalStateException.class, () -> c.replaceCurrentSubtasks(List.of(sub("p1-0"))));
    }

    @Test
    void consumeUnfreezesTheChainAndNewAttemptsProceed() { // S95
        TaskChain c = unexpandedChain();
        SafePoint token = c.issueSafePoint(SafePoint.Purpose.EXPAND_PRIMARY, 60_000);
        c.expandCurrentPrimary(List.of(sub("p1-0"), sub("p1-1")), token);
        assertNull(c.outstandingSafePoint(), "结构变更成功后凭据应当已消费");

        c.startCurrent();      // 解冻后新动作可以正常开
        assertEquals(1, c.attempts("p1-0"));
    }

    @Test
    void revokeUnfreezesAndLeavesTokenConsumed() { // S96
        TaskChain c = unexpandedChain();
        SafePoint token = c.issueSafePoint(SafePoint.Purpose.REPLACE_PLAN, 60_000);
        c.revokeSafePoint(token);
        assertNull(c.outstandingSafePoint());
        assertEquals(SafePoint.ALREADY_CONSUMED, c.checkSafePoint(token, SafePoint.Purpose.REPLACE_PLAN).code(),
                "作废与消费同一个出口：不给失效造第二种名字");

        assertDoesNotThrow(() -> c.issueSafePoint(SafePoint.Purpose.REPLACE_PLAN, 60_000),
                "作废之后可以重新签发");
    }

    @Test
    void secondIssueWhileOutstandingIsRefused() {
        TaskChain c = unexpandedChain();
        c.issueSafePoint(SafePoint.Purpose.REPLACE_PLAN, 60_000);
        assertThrows(IllegalStateException.class,
                () -> c.issueSafePoint(SafePoint.Purpose.EXPAND_PRIMARY, 60_000),
                "一次只允许一个结构变更在途，否则两张凭据可能都消费在一次变更上");
    }

    @Test
    void snapshotExposesSafePointAndEpoch() {
        TaskChain c = expandedChain();
        assertEquals(0L, c.ownerEpoch());
        Map<String, Object> before = c.snapshot();
        assertEquals(Boolean.FALSE, ((Map<?, ?>) before.get("safePoint")).get("outstanding"));

        c.issueSafePoint(SafePoint.Purpose.REPLACE_PLAN, 60_000);
        Map<String, Object> after = c.snapshot();
        Map<?, ?> safePoint = (Map<?, ?>) after.get("safePoint");
        assertEquals(Boolean.TRUE, safePoint.get("outstanding"), "快照必须能说清「现在为什么不能换计划」");
        assertEquals("REPLACE_PLAN", safePoint.get("purpose"));
        assertTrue(((Number) safePoint.get("expiresInMs")).longValue() > 0);

        c.bumpOwnerEpoch();
        assertEquals(1L, c.snapshot().get("ownerEpoch"));
    }

    @Test
    void ownerEpochSurvivesJsonRoundTrip() {
        TaskChain c = expandedChain();
        c.bumpOwnerEpoch();
        c.bumpOwnerEpoch();
        TaskChain restored = TaskChain.fromJson(c.toJson());
        assertEquals(2L, restored.ownerEpoch(), "代次必须活过重启，否则旧凭据会复活");
        assertNull(restored.outstandingSafePoint(), "凭据本身不落盘：重启后必须重签");
    }
}
