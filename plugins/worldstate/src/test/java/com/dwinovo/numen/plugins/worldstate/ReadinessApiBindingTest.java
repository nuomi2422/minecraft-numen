package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.observation.Readiness;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计 D：12 组合测试用的是**手抄的字符串**，API 改名/加值都不会红。
 *
 * <p>这条测试把两端钉在一起——它同时 import api 与 core，只能在 plugins/worldstate 这一层写。
 * API 枚举加值 / 改名 → 这里先红。
 */
class ReadinessApiBindingTest {

    @Test
    void apiEnumSizesArePinned() {
        assertEquals(4, WorldObservationService.Status.values().length,
                "Status 加了值就要回来更新映射表（ReadinessApiMappingTest）");
        assertEquals(3, WorldObservationService.Coverage.values().length,
                "Coverage 加了值就要回来更新映射表");
    }

    @Test
    void everyStatusNameMapsToSomethingRecognised() {
        for (WorldObservationService.Status s : WorldObservationService.Status.values()) {
            Readiness.Transport t = Readiness.Transport.fromApiName(s.name());
            if (s == WorldObservationService.Status.ERROR) {
                // ERROR 本来就该映射成 ERROR——这条是"正名"，不是 fail-closed
                assertEquals(Readiness.Transport.ERROR, t);
                continue;
            }
            assertNotEquals(Readiness.Transport.ERROR, t,
                    "Status." + s.name() + " 落进了 fail-closed 的 ERROR 分支——映射表漏了它");
        }
    }

    @Test
    void everyCoverageNameMapsPerTheDocumentedTable() {
        Map<String, Coverage> expected = new LinkedHashMap<>();
        expected.put("FULL", Coverage.COMPLETE);
        expected.put("PARTIAL", Coverage.PARTIAL);
        expected.put("UNKNOWN", Coverage.UNLOADED);
        for (WorldObservationService.Coverage c : WorldObservationService.Coverage.values()) {
            Coverage mapped = Readiness.coverageFromApiName(c.name());
            assertTrue(expected.containsKey(c.name()),
                    "Coverage." + c.name() + " 不在映射表里——先补表再改代码");
            assertEquals(expected.get(c.name()), mapped, "Coverage." + c.name() + " 映射不符");
        }
    }

    @Test
    void unknownNamesAreFailClosed() {
        assertEquals(Readiness.Transport.ERROR, Readiness.Transport.fromApiName("WHATEVER"));
        assertEquals(Coverage.UNLOADED, Readiness.coverageFromApiName("WHATEVER"));
    }
}
