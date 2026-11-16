package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddEarlyCompletionProposal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-17 早完成建议职责拆分后的最少真实消费控：
 * 用反射构造非空 latest / 塞私有 map（测试内清理，生产不加 setForTest/clock），
 * 覆盖匹配 UNVERIFIED、每类身份/代次不符 UNKNOWN 且不带旧 id、TTL 过期 UNKNOWN，
 * 以及 Detector facade 与 owner 的接线等价。publish 正向/重复抑制/失败顺序需 MC 平台，
 * 当前无原消费可复用，明确未覆盖。
 */
class RddEarlyProposalPublisherTest {

    private static final String CHAIN = "chain-1";
    private static final long REV = 7L;
    private static final long STATE = 3L;

    private static RddEarlyCompletionProposal.Proposal proposal(long sampledAt) {
        return new RddEarlyCompletionProposal.Proposal(
                List.of("sub-1"), REV, CHAIN, STATE, Map.of("minecraft:diamond", 1), sampledAt);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> latestMap() throws Exception {
        Field f = RddEarlyProposalPublisher.class.getDeclaredField("EARLY_PROPOSAL_LATEST");
        f.setAccessible(true);
        return (Map<UUID, Object>) f.get(null);
    }

    private static Object latest(Object p, String world, String session) throws Exception {
        Class<?> c = Class.forName("com.dwinovo.numen.plugins.rdd.RddEarlyProposalPublisher$EarlyLatest");
        Constructor<?> ctor = c.getDeclaredConstructor(
                RddEarlyCompletionProposal.Proposal.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(p, world, session);
    }

    @Test
    void matchingIdentityIsUnverified() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            latestMap().put(id, latest(proposal(System.currentTimeMillis()), "w1", "s1"));
            String out = RddEarlyProposalPublisher.notice(id, "w1", "s1", CHAIN, REV, STATE);
            assertTrue(out.contains("status=\"UNVERIFIED\""), out);
            assertTrue(out.contains(CHAIN), out);
        } finally {
            latestMap().remove(id);
        }
    }

    @Test
    void identityOrGenerationMismatchIsUnknownWithoutOldIds() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            latestMap().put(id, latest(proposal(System.currentTimeMillis()), "w1", "s1"));
            String[] outs = {
                    RddEarlyProposalPublisher.notice(id, "w2", "s1", CHAIN, REV, STATE),
                    RddEarlyProposalPublisher.notice(id, "w1", "s2", CHAIN, REV, STATE),
                    RddEarlyProposalPublisher.notice(id, "w1", "s1", "chain-2", REV, STATE),
                    RddEarlyProposalPublisher.notice(id, "w1", "s1", CHAIN, REV + 1, STATE),
                    RddEarlyProposalPublisher.notice(id, "w1", "s1", CHAIN, REV, STATE + 1),
                    RddEarlyProposalPublisher.notice(id, null, null, null, -1L, -1L)
            };
            for (String out : outs) {
                assertTrue(out.contains("status=\"UNKNOWN\""), out);
                assertFalse(out.contains(CHAIN), "UNKNOWN must not carry old ids: " + out);
            }
        } finally {
            latestMap().remove(id);
        }
    }

    @Test
    void expiredProposalIsUnknownEvenWhenIdentityMatches() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            latestMap().put(id, latest(
                    proposal(System.currentTimeMillis() - RddEarlyCompletionProposal.STALE_MS - 1000),
                    "w1", "s1"));
            String out = RddEarlyProposalPublisher.notice(id, "w1", "s1", CHAIN, REV, STATE);
            assertTrue(out.contains("status=\"UNKNOWN\""), out);
        } finally {
            latestMap().remove(id);
        }
    }

    @Test
    void unknownCompanionHasNoNotice() {
        assertEquals("", RddEarlyProposalPublisher.notice(UUID.randomUUID(), "w", "s", "c", 1, 1));
        assertEquals("", RddEarlyProposalPublisher.notice(null, "w", "s", "c", 1, 1));
    }

    @Test
    void detectorFacadeDelegatesToPublisher() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            latestMap().put(id, latest(proposal(System.currentTimeMillis()), "w1", "s1"));
            assertEquals(
                    RddEarlyProposalPublisher.notice(id, "w1", "s1", CHAIN, REV, STATE),
                    RddDetector.earlyProposalNotice(id, "w1", "s1", CHAIN, REV, STATE));
        } finally {
            latestMap().remove(id);
        }
    }
}