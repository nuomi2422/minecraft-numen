package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import com.dwinovo.numen.rdd.policy.ReservationLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1c 接线契约：预留进入执行上下文（「已预留」可见）与账本生命周期。
 *
 * <p>纯逻辑（不碰 MC）：渲染块 + 账本的 retainOnly/available 口径。
 */
class G1cReservationWiringTest {

    private static PrimaryGoal goal() {
        return new PrimaryGoal("primary-g1c", "下矿准备", List.of(
                Subtask.hardCoded("s1", "火把", Map.of("asset_key", "minecraft:torch", "minimum", 32)),
                Subtask.hardCoded("s2", "面包", Map.of("asset_key", "minecraft:bread", "minimum", 4))),
                List.of(new AssetRequirement("minecraft:torch", 32)));   // waitFor → CRITICAL
    }

    @Test
    void needsBlockShowsReservedForCriticalRows() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        Map<String, Integer> held = Map.of("minecraft:torch", 22, "minecraft:bread", 1);

        // 未带预留：旧行为不受影响
        String plain = RddFactContext.renderNeedsBlock(needs, held);
        assertTrue(plain.contains("[关键] minecraft:torch ×32（现有 22）"), plain);
        assertFalse(plain.contains("已预留"), "没有预留时不许凭空出现预留文案：" + plain);

        // 带预留：只给有预留的行追加
        String reserved = RddFactContext.renderNeedsBlock(needs, held,
                Map.of("minecraft:torch", 22));
        assertTrue(reserved.contains("[关键] minecraft:torch ×32（现有 22）（已预留 22）"), reserved);
        assertFalse(reserved.contains("minecraft:bread ×4（现有 1）（已预留"), "无预留的行不许带后缀：" + reserved);
    }

    @Test
    void reservationSurvivesTicksButDiesWithThePrimary() {
        UUID companion = UUID.randomUUID();
        ReservationLedger ledger = RddFactContext.ledger(companion);
        Map<String, Integer> held = Map.of("minecraft:torch", 22);

        // tick 每 30 秒跑一次：同股同量必须幂等（不涨条目）
        assertTrue(ledger.reserve("primary-g1c", "minecraft:torch", 22, 0, 0, held).ok());
        assertTrue(ledger.reserve("primary-g1c", "minecraft:torch", 22, 0, 0, held).ok());
        assertEquals(1, ledger.reservations().size(), "重复 tick 不得堆条目");

        // 换 primary：上一级的承诺释放
        assertEquals(1, ledger.retainOnly("primary-other"));
        assertTrue(ledger.reservations().isEmpty());
        assertEquals(22, ledger.available(held, "primary-other").get("minecraft:torch"),
                "释放后资源重新可用");
    }

    @Test
    void criticalOnlyGetsReservedByPolicy() {
        // 策略层：只有 CRITICAL 需求会被 ensureReservations 申请（NORMAL 按需分配，D11）
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        var torch = needs.stream().filter(n -> "minecraft:torch".equals(n.key())).findFirst().orElseThrow();
        var bread = needs.stream().filter(n -> "minecraft:bread".equals(n.key())).findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.CRITICAL, torch.importance());
        assertEquals(RequirementManifest.Importance.NORMAL, bread.importance());
    }
}
