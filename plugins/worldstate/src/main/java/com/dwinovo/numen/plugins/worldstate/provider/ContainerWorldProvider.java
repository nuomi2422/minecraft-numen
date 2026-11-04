package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.api.storage.ContainerReader;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.ContainerPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读一个方块容器的内容（039 U3，第一个 {@code containers} 采集器）。
 *
 * <p>走公开的 {@link ContainerReader}（037/T8b 定的窄能力），不碰内部工具、不解析人读文本。
 *
 * <p><b>"读不到"绝不落成空快照</b>：未知就抛 {@link ProviderException}，让上层是 ERROR/PENDING，
 * 而不是一条"看起来空"的观测。已知为空则返回空表 + COMPLETE —— 两者在下游必须分得开。
 */
public final class ContainerWorldProvider implements WorldProvider {

    @Override public Topic topic() { return Topic.CONTAINERS; }

    @Override public String id() { return "containers"; }

    @Override public int dataVersion() { return 1; }

    @Override
    public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
        McCollectionContext mc = McCollectionContexts.require(ctx);
        String target = request.target();
        if (target != null && target.startsWith("player:")) {
            return collectPlayerInventory(mc, request, target);
        }
        if (target == null || !target.startsWith("block:")) {
            throw new ProviderException(FailReason.INVALID_REQUEST,
                    "containers supports block:x,y,z or player:<uuid>, got: " + target);
        }
        BlockPos pos = parsePos(target.substring("block:".length()));
        if (pos == null) {
            throw new ProviderException(FailReason.INVALID_REQUEST, "bad position in " + target);
        }
        ServerLevel level = mc.levelOf(request.dimension());
        if (level == null) {
            throw new ProviderException(FailReason.DIMENSION_MISSING,
                    "dimension not loaded: " + request.dimension());
        }
        if (!level.hasChunkAt(pos)) {
            throw new ProviderException(FailReason.CHUNK_UNLOADED, "chunk not loaded at " + target);
        }
        ContainerReader.Result read;
        try {
            read = Services.CONTAINERS.read(level, pos);
        } catch (RuntimeException | LinkageError ex) {
            throw new ProviderException(FailReason.PROVIDER_ERROR, "container read threw: " + ex);
        }
        if (read == null || !read.known()) {
            throw new ProviderException(FailReason.PROVIDER_ERROR,
                    read == null ? "no reader" : "container unreadable: " + read.reason());
        }
        int slots = 0;
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) slots = container.getContainerSize();
        } catch (RuntimeException ignored) {
            // 槽位只是附加信息，取不到就记 0，不影响"里面有什么"
        }
        ContainerPayload payload = new ContainerPayload(request.dimension(), target, slots, read.items());
        return ProviderResult.of(payload, Coverage.COMPLETE);
    }

    /**
     * 玩家背包当成一个容器来读（039 U3 的前置）：36 格主背包 + 4 格护甲 + 1 格副手。
     *
     * <p>读的是**服务端线程上的真实背包**，不是缓存计数；空背包返回空表 + COMPLETE
     * （"已知为空"），玩家不存在则是 CHUNK/DIMENSION 之外的明确失败。
     */
    private ProviderResult collectPlayerInventory(McCollectionContext mc, ObservationRequest request,
                                                  String target) {
        String raw = target.substring("player:".length()).trim();
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(FailReason.INVALID_REQUEST, "bad player uuid: " + raw);
        }
        net.minecraft.server.MinecraftServer server = mc.server();
        if (server == null) {
            throw new ProviderException(FailReason.PROVIDER_ERROR, "no server");
        }
        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null) {
            throw new ProviderException(FailReason.PROVIDER_ERROR, "player not online: " + id);
        }
        Map<String, Integer> items = new java.util.LinkedHashMap<>();
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int slots = inv.getContainerSize();
        for (int i = 0; i < slots; i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            items.merge(itemId, stack.getCount(), Integer::sum);
        }
        ContainerPayload payload = new ContainerPayload(request.dimension(), target, slots, items);
        return ProviderResult.of(payload, Coverage.COMPLETE);
    }

    private static BlockPos parsePos(String raw) {
        String[] parts = raw == null ? new String[0] : raw.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
