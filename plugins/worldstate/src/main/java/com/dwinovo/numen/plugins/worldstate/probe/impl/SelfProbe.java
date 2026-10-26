package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同伴自身：血量/饥饿/护甲/装备/死亡数/坐标/模式。
 *
 * <p>不是世界控制变量，但对实验前后的「她还是不是同一个人」有用；死亡数是
 * 学习/实验最常被忽略的一列。
 */
public final class SelfProbe implements WorldProbe {

    @Override public String id() { return "self"; }

    @Override public String title() { return "Companion self state"; }

    @Override public String description() {
        return "Read the selected companion's own state: HP, food, saturation, armor, equipment, "
                + "death count, XP, position, dimension, game mode and sleeping flag.";
    }

    @Override public List<String> params() { return List.of("companion"); }

    @Override public List<String> returns() {
        return List.of("name", "mode", "hp", "food", "saturation", "armor", "equipment",
                "deaths", "xp_level", "x", "y", "z", "sleeping");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        ServerPlayer p = ctx.target();
        if (p == null) {
            return ProbeResult.failed("NOT_FOUND", "companion not found on this server");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName().getString());
        out.put("uuid", p.getStringUUID());
        out.put("mode", p.gameMode.getGameModeForPlayer().getName());
        out.put("spectator", p.isSpectator());
        out.put("sleeping", p.isSleeping());
        out.put("hp", p.getHealth());
        out.put("max_hp", p.getMaxHealth());
        out.put("food", p.getFoodData().getFoodLevel());
        out.put("saturation", p.getFoodData().getSaturationLevel());
        out.put("armor", p.getArmorValue());

        Map<String, Object> equipment = new LinkedHashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = p.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            row.put("count", stack.getCount());
            equipment.put(slot.getName(), row);
        }
        out.put("equipment", equipment);
        out.put("deaths", p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)));
        out.put("xp_level", p.experienceLevel);
        out.put("total_xp", p.totalExperience);
        out.put("x", p.getX());
        out.put("y", p.getY());
        out.put("z", p.getZ());
        out.put("dimension", p.level().dimension().location().toString());
        return ProbeResult.complete(out);
    }
}
