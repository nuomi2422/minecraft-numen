package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Coverage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R2 / 审计 C：**API 枚举 → core 枚举的映射**必须唯一且被钉死。
 *
 * <p>API 是 `Status{OK,PARTIAL,PENDING,ERROR}` × `Coverage{FULL,PARTIAL,UNKNOWN}` = 12 种组合；
 * core 是 `Transport{OK,PARTIAL,PENDING,ERROR}` × `Coverage{COMPLETE,PARTIAL,UNLOADED}`。
 * 两边各写一套映射，就会出现"PARTIAL 算 OK 还是 ERROR"三种答案——这里把 12 种全钉住。
 */
class ReadinessApiMappingTest {

    private static final Set<String> REQ = Set.of("count");
    private static final Set<String> KNOWN = Set.of("count");

    private static Readiness map(String status, String coverage) {
        return Readiness.assess(
                Readiness.Transport.fromApiName(status),
                Readiness.coverageFromApiName(coverage),
                REQ, KNOWN, Set.of(), Set.of());
    }

    @Test
    void statusNamesMapOneToOne() {
        assertEquals(Readiness.Transport.OK, Readiness.Transport.fromApiName("OK"));
        assertEquals(Readiness.Transport.PARTIAL, Readiness.Transport.fromApiName("PARTIAL"));
        assertEquals(Readiness.Transport.PENDING, Readiness.Transport.fromApiName("PENDING"));
        assertEquals(Readiness.Transport.ERROR, Readiness.Transport.fromApiName("ERROR"));
    }

    @Test
    void unknownStatusIsFailClosed() {
        assertEquals(Readiness.Transport.ERROR, Readiness.Transport.fromApiName("SOMETHING_NEW"));
        assertEquals(Readiness.Transport.ERROR, Readiness.Transport.fromApiName(null));
    }

    @Test
    void coverageNamesMapConservatively() {
        assertEquals(Coverage.COMPLETE, Readiness.coverageFromApiName("FULL"));
        assertEquals(Coverage.PARTIAL, Readiness.coverageFromApiName("PARTIAL"));
        assertEquals(Coverage.UNLOADED, Readiness.coverageFromApiName("UNKNOWN"));
        assertEquals(Coverage.UNLOADED, Readiness.coverageFromApiName("SOMETHING_NEW"));
    }

    @Test
    void twelveCombinationsArePinned() {
        // 顺序：OK / PARTIAL / PENDING / ERROR × FULL / PARTIAL / UNKNOWN
        List<Readiness> expected = List.of(
                Readiness.READY,          // OK + FULL
                Readiness.READY_PARTIAL,  // OK + PARTIAL
                Readiness.NOT_READY_UNKNOWN, // OK + UNKNOWN
                Readiness.READY_PARTIAL,  // PARTIAL + FULL（传输部分成功，最多算部分可读）
                Readiness.READY_PARTIAL,  // PARTIAL + PARTIAL
                Readiness.NOT_READY_UNKNOWN, // PARTIAL + UNKNOWN
                Readiness.NOT_READY_PENDING, // PENDING + 任意
                Readiness.NOT_READY_PENDING,
                Readiness.NOT_READY_PENDING,
                Readiness.NOT_READY_UNKNOWN, // ERROR + 任意
                Readiness.NOT_READY_UNKNOWN,
                Readiness.NOT_READY_UNKNOWN);
        List<String> statuses = List.of("OK", "OK", "OK", "PARTIAL", "PARTIAL", "PARTIAL",
                "PENDING", "PENDING", "PENDING", "ERROR", "ERROR", "ERROR");
        List<String> coverages = List.of("FULL", "PARTIAL", "UNKNOWN", "FULL", "PARTIAL", "UNKNOWN",
                "FULL", "PARTIAL", "UNKNOWN", "FULL", "PARTIAL", "UNKNOWN");
        for (int i = 0; i < 12; i++) {
            assertEquals(expected.get(i), map(statuses.get(i), coverages.get(i)),
                    statuses.get(i) + " + " + coverages.get(i));
        }
    }

    @Test
    void onlyCompletePlusOkCanProveAbsence() {
        int provable = 0;
        for (String s : List.of("OK", "PARTIAL", "PENDING", "ERROR")) {
            for (String c : List.of("FULL", "PARTIAL", "UNKNOWN")) {
                if (map(s, c).canProveAbsence()) provable++;
            }
        }
        assertEquals(1, provable, "12 种组合里只有 OK+FULL 能说'没有'");
    }

    @Test
    void transportPartialNeverClaimsAbsence() {
        assertFalse(map("PARTIAL", "FULL").canProveAbsence(),
                "传输只成功一部分，即使覆盖 FULL 也不能说'没有'");
        assertTrue(map("PARTIAL", "FULL").canReportPresence());
    }
}
