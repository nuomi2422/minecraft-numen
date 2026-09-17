package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * GUI tool implementations — the business half of {@code InspectGuiTool} and
 * {@code CloseGuiTool}: read the open container menu and close it.
 */
public final class GuiOps {

    public String inspectGui(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null) {
            return TaskResult.fail("no GUI open.").toJson();
        }
        // With no block menu open, containerMenu IS your own InventoryMenu — which carries the 2x2
        // crafting grid. Surface it so the model can craft small recipes without a table.
        boolean ownInventory = menu == self.inventoryMenu;
        StringBuilder container = new StringBuilder();
        StringBuilder mine = new StringBuilder();
        // Crafting grid (if any). Detect generically: a slot backed by a CraftingContainer IS a grid
        // cell (vanilla 2x2/3x3 AND modded NxM), the ResultSlot IS the output. We lay the cells out in
        // 2D with their click-able slot numbers so the model can drop the recipe ascii straight onto it
        // — no "row-major + stride + gaps" arithmetic, which is exactly where it kept misplacing.
        int gridW = 0, gridH = 0, resultIndex = -1;
        Slot[] gridCells = null;   // indexed by position-in-container (row-major)
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean playerSide = slot.container == self.getInventory();
            ItemStack it = slot.getItem();
            if (slot instanceof ResultSlot) {
                resultIndex = slot.index;
                continue;   // shown as part of the crafting-grid section, not the generic dump
            }
            if (slot.container instanceof CraftingContainer cc) {
                if (gridCells == null) {
                    gridW = cc.getWidth();
                    gridH = cc.getHeight();
                    gridCells = new Slot[gridW * gridH];
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < gridCells.length) {
                    gridCells[pos] = slot;
                }
                continue;
            }
            // Output-only = a non-empty machine slot that won't take its own item back (result slot).
            boolean output = !playerSide && !it.isEmpty() && !slot.mayPlace(it);
            String line = "  " + i + ": " + describe(it) + (output ? " [output]" : "") + "\n";
            if (playerSide) {
                if (!it.isEmpty()) {
                    mine.append(line);   // only your filled slots — the items you can move in
                }
            } else {
                container.append(line);  // all container slots, empty included (placement targets)
            }
        }
        // Data slots = the menu's OTHER synced channel, parallel to the item slots: the ints a real
        // screen reads to draw progress / fuel / energy bars. Read them generically (no per-menu
        // special-casing) — meaning is GUI-specific, the model/skill interprets (e.g. a furnace's are
        // [litTime, litDuration, cookProgress, cookTotal], so cook% = cookProgress/cookTotal).
        String dataLine = "";
        List<DataSlot> data = ((com.dwinovo.numen.mixin.MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
        if (!data.isEmpty()) {
            StringBuilder d = new StringBuilder("data values (machine state — progress/fuel/energy/…, "
                    + "meaning is GUI-specific): [");
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) d.append(", ");
                d.append(data.get(i).get());
            }
            dataLine = d.append("]\n").toString();
        }

        // Render the crafting grid as a 2D map of click-able slot numbers, so the recipe ascii from
        // lookup_recipe overlays cell-for-cell (a smaller recipe goes in the TOP-LEFT — same as here).
        String gridSection = "";
        if (gridCells != null) {
            StringBuilder g = new StringBuilder("crafting grid " + gridW + "x" + gridH
                    + " — put each recipe ingredient into the slot at the SAME position (a recipe "
                    + "smaller than the grid goes in the top-left); take the result from slot "
                    + resultIndex + ":\n");
            for (int r = 0; r < gridH; r++) {
                g.append("  ");
                for (int c = 0; c < gridW; c++) {
                    Slot cell = gridCells[r * gridW + c];
                    ItemStack it = cell == null ? ItemStack.EMPTY : cell.getItem();
                    int idx = cell == null ? -1 : cell.index;
                    g.append("slot ").append(idx).append("=").append(describe(it));
                    if (c < gridW - 1) {
                        g.append("  |  ");
                    }
                }
                g.append("\n");
            }
            gridSection = g.toString();
        }

        String header = ownInventory
                ? "GUI: InventoryMenu (YOUR own inventory — includes the 2x2 crafting grid below)\n"
                : "GUI: " + menu.getClass().getSimpleName() + "\n";
        return TaskResult.ok(header
                + gridSection
                + "container slots:\n" + (container.length() == 0 ? "  (none)\n" : container)
                + "your inventory (non-empty):\n" + (mine.length() == 0 ? "  (empty)\n" : mine)
                + "cursor: " + describe(menu.getCarried()) + "\n"
                + dataLine
                + "tip: transfer {from} (no `to`) routes a whole stack to the other section; add `to`"
                + " + `count` for an exact move into a specific slot.").toJson();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }

    public String closeGui(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            // The InventoryMenu (your own 2x2 grid + inventory) is always open — nothing to close.
            // If you left items in the 2x2 crafting grid, transfer them back out.
            return TaskResult.ok("no block GUI was open (your own inventory menu is always available).").toJson();
        }
        self.closeContainer();
        return TaskResult.ok("closed the GUI.").toJson();
    }

    /**
     * 按下一个菜单按钮/选项——这是与"槽位"并列的另一类 GUI 交互。
     *
     * <p>打开的菜单里有两类可交互对象：<b>槽位</b>（物品，{@code transfer} 管）和
     * <b>按钮/选项</b>（真实屏幕上画成可点控件的动作）。原版把后者统一走
     * {@link AbstractContainerMenu#clickMenuButton}：附魔台的三档附魔、村民交易的选择、
     * 织布机图案、切石机配方、信标能力。在补上这个工具之前，模型能打开并读懂这些菜单，
     * 但<b>按不下任何东西</b>——所以凡是要靠菜单动作达成的目标（例如
     * {@code story/enchant_item} 成就）都会走到死路。
     *
     * <p>这是自主跑真机上由智能体自己提出的能力缺口：它诊断出
     * {@code interact_at} 只对准世界方块、{@code transfer} 只操作槽位，缺"菜单按钮"这一类，
     * 并请求了 {@code click_gui}。
     */
    public String clickMenuButton(NumenPlayer self, int index) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            return TaskResult.fail("no station GUI is open (only your own inventory menu, which has no "
                    + "buttons). Open the station first — e.g. interact_at on the enchanting table — "
                    + "then press its option.").toJson();
        }
        String name = menu.getClass().getSimpleName();
        boolean accepted;
        try {
            accepted = menu.clickMenuButton(self, index);
        } catch (RuntimeException ex) {
            return TaskResult.fail("menu button " + index + " on " + name + " threw: " + ex).toJson();
        }
        if (!accepted) {
            // 让拒绝可解释：不吞掉，也不假装成功。
            return TaskResult.fail("menu button " + index + " was refused by " + name
                    + ". Usual causes: the index is out of range; the option costs something you "
                    + "don't have yet (the enchanting table needs lapis lazuli in its slot AND enough "
                    + "XP levels); or the offer isn't ready. Re-read the menu with inspect_gui and fix "
                    + "the inputs, then press again.").toJson();
        }
        return TaskResult.ok("pressed menu button " + index + " on " + name,
                Map.of("button", index, "menu", name)).toJson();
    }
}
