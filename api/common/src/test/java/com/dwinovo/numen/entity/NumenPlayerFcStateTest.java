package com.dwinovo.numen.entity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 2026-10-29：用 CompoundTag / NumenPlayer（MC 类），运行期需要 MC 注册表 → 与 InventoryBlockTest 同属 mc 组。
@Tag("mc")
class NumenPlayerFcStateTest {

    @Test
    void oldCompanionDataKeepsFcEnabled() {
        assertTrue(NumenPlayer.fcEnabledFromSaveData(new CompoundTag()));
    }

    @Test
    void explicitFcSwitchRoundTripsBothStates() {
        CompoundTag data = new CompoundTag();
        data.putBoolean("NumenFcEnabled", false);
        assertFalse(NumenPlayer.fcEnabledFromSaveData(data));

        data.putBoolean("NumenFcEnabled", true);
        assertTrue(NumenPlayer.fcEnabledFromSaveData(data));
    }
}
