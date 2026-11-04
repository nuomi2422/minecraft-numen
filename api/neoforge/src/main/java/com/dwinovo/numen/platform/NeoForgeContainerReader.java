package com.dwinovo.numen.platform;

import com.dwinovo.numen.api.storage.ContainerReader;
import com.dwinovo.numen.api.storage.ItemHandlerTally;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NeoForge 的 {@link ContainerReader} 实现（037/T8b）。
 *
 * <p>读法与 {@link NeoForgeBlockCapabilityReader} 一致：{@code null} 上下位 + 六个面都探，
 * 因为有些机器只在侧面暴露 handler。区别是这里返回<b>结构化数量</b>，不再渲染成文本。
 *
 * <p><b>按内容去重</b>：同一个箱子按 7 个面各返回一个新的包装对象，按身份去重不成立
 * （实机把 9 个铁锭读成 ×63）——去重与合并交给 {@link ItemHandlerTally#merge}。
 */
public final class NeoForgeContainerReader implements ContainerReader {

    @Override
    public Result read(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return Result.unknown("missing level or position");
        }
        try {
            List<List<ItemHandlerTally.Slot>> views = new ArrayList<>(7);
            IItemHandler direct = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (direct != null) {
                views.add(snapshot(direct));
            }
            for (Direction side : Direction.values()) {
                IItemHandler perSide = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
                if (perSide != null) {
                    views.add(snapshot(perSide));
                }
            }
            if (views.isEmpty()) {
                return Result.unknown("block at " + pos + " exposes no item storage");
            }
            Map<String, Integer> counts = ItemHandlerTally.merge(views);
            return Result.known(counts);
        } catch (RuntimeException ex) {
            // 契约：实现不抛异常。modded 方块实体的读取经常在别人的代码里炸。
            return Result.unknown("container read failed: " + ex);
        }
    }

    private static List<ItemHandlerTally.Slot> snapshot(IItemHandler handler) {
        List<ItemHandlerTally.Slot> slots = new ArrayList<>(handler.getSlots());
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            slots.add(new ItemHandlerTally.Slot(id, stack.getCount()));
        }
        return slots;
    }
}
