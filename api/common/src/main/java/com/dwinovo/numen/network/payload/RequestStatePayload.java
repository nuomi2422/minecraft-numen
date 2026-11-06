package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: "show me this companion's backpack." Other players' full
 * inventory isn't synced to clients (only equipment is), so the read-only Items
 * tab fetches it on demand. Answered with one {@link NumenStatePayload}.
 *
 * <p>Only the owner of a LOADED companion gets the contents; otherwise the reply
 * is {@code loaded=false} (asleep / not yours — no inventory oracle).
 */
public record RequestStatePayload(UUID uuid) implements CustomPacketPayload {

    /** The 36 main backpack slots (hotbar + storage); equipment is already client-synced. */
    public static final int MAIN_SLOTS = 36;

    public static final Type<RequestStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestStatePayload> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, RequestStatePayload::uuid,
                    RequestStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(RequestStatePayload p, ServerPlayer player) {
        NumenPlayer numen = NumenPlayer.findByUuid(player.level().getServer(), p.uuid());
        if (numen == null || !numen.isOwnedByPlayer(player.getUUID())) {
            Services.NETWORK.sendToPlayer(player, absent(p.uuid()));
            return;
        }
        Services.NETWORK.sendToPlayer(player, snapshot(numen));
    }

    /** 身体不在(睡在未加载区块 / 不是你的):没有内容可给。 */
    public static NumenStatePayload absent(java.util.UUID uuid) {
        return new NumenStatePayload(uuid, false, List.of(), List.of(), 0, 0f,
                0, ItemStack.EMPTY, List.of(), "", -1);
    }

    /**
     * 这具身体此刻带着什么。应答面板的请求和背包变化时的主动推送共用这一份——两条路给出
     * 不同的快照就等于两个真源。
     */
    public static NumenStatePayload snapshot(NumenPlayer numen) {
        var sample = com.dwinovo.numen.entity.CompanionBodySample.capture(numen);
        List<ItemStack> items = sample.main();
        // The 2×2 crafting menu (vanilla InventoryMenu layout): slot 0 = result, slots 1-4 = grid.
        // Packed as [grid0, grid1, grid2, grid3, result] for the Items tab to mirror.
        List<ItemStack> craft = new ArrayList<>(5);
        for (int i = 1; i <= 4; i++) craft.add(numen.inventoryMenu.getSlot(i).getItem().copy());
        craft.add(numen.inventoryMenu.getSlot(0).getItem().copy());
        // Full components/effects are copied by the shared host frame, never rebuilt from skinny SELF DTOs.
        String vehicleType = sample.vehicleType();
        if (!vehicleType.isEmpty()) vehicleType = net.minecraft.resources.ResourceLocation.parse(vehicleType).getPath();
        return new NumenStatePayload(numen.getUUID(), true, items, craft, sample.food(), sample.saturation(),
                sample.selected(), sample.slot(40), sample.effects(), vehicleType, sample.vehicleId());
    }
}
