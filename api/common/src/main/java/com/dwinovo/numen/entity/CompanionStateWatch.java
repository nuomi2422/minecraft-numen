package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.network.payload.NumenStatePayload;
import com.dwinovo.numen.network.payload.RequestStatePayload;
import com.dwinovo.numen.platform.Services;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.dwinovo.numen.monitor.MonitoringJournal;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴身上的状态一变就推一份给主人的客户端,让 agent 循环不必花一整轮
 * {@code get_self_status} 去重新发现自己带着什么、身上有什么效果。
 *
 * <h2>为什么非推不可</h2>
 * 渲染那块提示词的代码在<b>客户端</b>,而 companion 的 36 格从不同步给客户端——原版只同步
 * 装备槽,背包是每个玩家的私有数据。客户端手上根本没有背包可遍历,而构造请求是同步的,
 * 不能现场等一个网络往返。所以只能由服务端把它推过去,让客户端的缓存始终是新鲜的。
 *
 * <h2>为什么要检测变化</h2>
 * 省的不是提示词 token(那块每次请求都得带,它从不进历史),是<b>包和序列化</b>。不检测就得
 * 每 tick 推一个,20 包/秒/同伴,每包 36 个 {@code ItemStack};而客户端只在发请求那一刻读一次,
 * 她十秒不说话,那 200 个包里 199 个白发。服务器上同伴一多就更明显。
 *
 * <h2>为什么是自己遍历,不挂原版监听器</h2>
 * 原版的 {@code ContainerListener} 确实白送一份算好的 diff,但它要跟着菜单换(广播的只有
 * 当前菜单,开箱子就换了)、要按 {@code Slot.container} 挡掉箱子自己的格子、还要信
 * {@code getContainerSlot()} 的下标口径。一趟 41 格的遍历省掉这一整串前提,代价是每 tick
 * 多读四十来次——在一个 50ms 的 tick 预算里看不见。
 *
 * <h2>只认"什么物品、几个"</h2>
 * 耐久、附魔、其它组件一概不进比较。否则她每挥一镐都算变了。模型读的是"我有什么、几个",
 * 要精确到槽位和附魔时才调 {@code inspect_gui}。
 *
 * <h2>采样段与发包段是两件事(2026-11-06)</h2>
 * 同一个类里现在有两段,顺序固定、状态互不相干:先按 10 tick 节拍做一次 <b>SELF 采样</b>
 * (读身体 + fire {@link CompanionEvent#SELF_SAMPLE_READY}),再走原来的发包段。
 * <ul>
 *   <li><b>为什么采样不看主人在不在</b>:世界观测描述的是身体在世界里的样子,主人下线了
 *       身体还在(钓鱼、走路、被打),这段时间的事实不能空着;而发给主人的包一旦没人收白费。
 *       把两段拆开,离线时只省包、不省事实。</li>
 *   <li><b>为什么先采样再判断 owner</b>:原来 owner 为 null 就在遍历之前 return,
 *       镜像和发包都不推进——这条保持不变(离线期间攒下的变化,主人回来时一次补发)。
 *       采样段放在这个 return <b>之前</b>,所以离线也照采。</li>
 *   <li><b>为什么不自动落盘</b>:SELF 只在内存里(见 WorldStateService),采样只负责把帧交出去。</li>
 * </ul>
 * 采样失败/死亡/换维度/换身体/换服务器一律<b>重置节拍</b>:宁可早采一拍,也不拿旧身份接着数。
 *
 * <h2>效果同理:只认"有哪些、几级"</h2>
 * <b>剩余时间不进比较</b> —— 它每 tick 都在减,拿它当变化判据就是每 tick 推一个包。
 * 时长照发,由客户端按收到的时刻自己往下扣:效果是确定性的倒计时,推算出来的秒数和真值
 * 一致,而重推只发生在"多了一种、少了一种、升了级"这三件真事上。
 */
public final class CompanionStateWatch {

    /** 最小推送间隔(tick)。她连续捡东西时压成一波,模型下一轮才读,晚半秒无感。 */
    private static final int MIN_INTERVAL_TICKS = 10;
    /**
     * SELF 采样间隔(tick)。与 {@link #MIN_INTERVAL_TICKS} 数值相同但是<b>两条独立的节拍</b>:
     * 推送压的是包,采样压的是读身体的次数;主人离线时前者停、后者照走,共用一个字段就会互相拖。
     *
     * <p>10 tick(约 0.5 秒)仍是候选值,未经实机测性能,不声称"够用"。
     */
    private static final int SAMPLE_INTERVAL_TICKS = 10;
    private static final Item[] EMPTY_ITEMS = new Item[0];
    private static final int[] EMPTY_COUNTS = new int[0];

    private static final Map<UUID, CompanionStateWatch> WATCHES = new HashMap<>();

    static {
        // 谁持有谁自己报到:这张表是世界作用域的,不作废会跟着进下一个存档。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(CompanionStateWatch::dropAll);
    }

    /** 每格上次见到的物品与数量。{@link Item} 是注册表单例,比引用就够——不必算哈希,
     *  也就没有"把数量塞进低位、模组把堆叠上限调过 65535 就溢出"这种雷。 */
    private Item[] lastItem = EMPTY_ITEMS;
    private int[] lastCount = EMPTY_COUNTS;
    private int lastSelected = -1;
    /** 上次见到的效果:每条编码成"效果 + 等级",不含剩余时间。 */
    private java.util.Set<String> lastEffects = java.util.Set.of();
    /** 上次见到的载具实体 id;-1 = 没骑。上下船是模型必须实时知道的身体事实。 */
    private int lastVehicleId = -1;
    private long lastSentTick;
    private boolean everSent;
    /** 上次 SELF 采样的服务端 tick;只在采样成功时推进。 */
    private long lastSampleTick;
    /** 有没有成功采过。第一次必须放行,否则"上次 tick 是 0"会跟真实第 0 tick 撞。 */
    private boolean everSampled;
    /**
     * 上次采样时的身体/服务器/维度。弱引用是为了不留住一具已经离开世界的身体
     * (这张表活到关服);维度用串,弱引用对字符串没有意义。
     */
    private WeakReference<NumenPlayer> sampledBody;
    private WeakReference<Object> sampledServer;
    private String sampledDimension = "";

    private CompanionStateWatch() {}

    /** 每服务端 tick、每个同伴调一次(见 {@code CompanionTickDispatcher})。 */
    public static void tick(NumenPlayer companion, long serverTick) {
        WATCHES.computeIfAbsent(companion.getUUID(), ignored -> new CompanionStateWatch())
                .tickOne(companion, serverTick);
    }

    /** 同伴离场(休眠/死亡/关服):丢掉它的镜像。 */
    public static void forget(UUID companionUuid) {
        WATCHES.remove(companionUuid);
    }

    /** 关服 / 换存档:世界作用域的进程内状态一律作废。 */
    public static void dropAll() {
        WATCHES.clear();
    }

    private void tickOne(NumenPlayer companion, long serverTick) {
        // 采样先行:主人离线也照采(世界观测不属于某个客户端)。这一段不推进任何发包状态。
        sampleSelf(companion, serverTick);
        // 主人不在线就直接走,而且<b>不碰镜像</b>:走一趟把变化吃掉的话,他回来时镜像已经
        // 是最新的,再也检测不出那段时间发生过什么,她的背包就一直是他离线前那份。
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            return;
        }
        // 节流先行:没到间隔连遍历都省了。只对"已经推过一次"成立——第一次必须放行,
        // 她一登场就该有一份快照。
        if (everSent && serverTick - lastSentTick < MIN_INTERVAL_TICKS) {
            return;
        }
        // 各趟都要走:短路的话背包变了就不再看别的,镜像会漏掉同一波里的其余变化。
        CompanionBodySample.Frame sample = CompanionBodySample.capture(companion);
        boolean invChanged = changedSince(sample);
        boolean effectsChanged = effectsChangedSince(sample);
        boolean vehicleChanged = vehicleChangedSince(sample);
        if (!invChanged && !effectsChanged && !vehicleChanged) {
            return;
        }
        lastSentTick = serverTick;
        boolean first = !everSent;
        everSent = true;
        NumenStatePayload payload = RequestStatePayload.snapshot(companion);
        Services.NETWORK.sendToPlayer(owner, payload);
        MonitoringJournal.get().publish("state", "companion_state", Map.of(
                "companion_id", companion.getUUID().toString(), "items", usedSlots(payload),
                "effects", payload.effects().size(), "vehicle_id", payload.vehicleId()));
        // 一次推送一行。链路是"服务端推 → 客户端缓存 → 渲染进请求",出问题时得能一眼看出
        // 断在哪一节;只记开始不记结果的日志已经害过我们一次。
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-state] {} → {} 种物品 / {} 格, 主手 {}, 副手 {}, 效果 {}{}{}",
                companion.getName().getString(), kinds(payload), usedSlots(payload),
                slotName(payload.selectedSlot(), payload.items()), name(payload.offhand()),
                payload.effects().size(),
                payload.vehicleId() >= 0 ? ", 骑 " + payload.vehicleType() : "",
                first ? " (首次)" : "");
    }

    /**
     * 自动 SELF 采样段:每 {@link #SAMPLE_INTERVAL_TICKS} tick 读一次身体,并告诉订阅者
     * "这一拍有了一帧新事实"。
     *
     * <p>失败/死亡/换维度/换身体/换服务器一律<b>重置节拍</b>:这几件事会让上一帧不再代表
     * 当前身份,接着按旧节拍数等于继续引用一份过期事实。重置的代价只是早采一拍。
     */
    private void sampleSelf(NumenPlayer companion, long serverTick) {
        if (companion.isRemoved() || companion.isDeadOrDying()) {
            // 尸体不是"这一拍没轮到":身份已经作废,活过来的第一 tick 就要重采。
            resetSampleCadence();
            return;
        }
        Object server = companion.getServer();
        if (server == null) {
            // 不在世界里就没有服务端可读;不是失败,也不能把节拍留给下一具身体接着数。
            resetSampleCadence();
            return;
        }
        String dimension = companion.serverLevel().dimension().location().toString();
        boolean identityChanged = !sameSampleIdentity(companion, server, dimension);
        if (!identityChanged && everSampled && serverTick - lastSampleTick < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        try {
            // 同一 tick 的第二次调用(发包段 / 插件 refresh)会命中 CompanionBodySample 的帧复用,
            // 不会再多读 41 格——这是"采样与消费者共享一帧"的落点。
            CompanionBodySample.capture(companion);
        } catch (RuntimeException failed) {
            // 失败不能记成"这拍采过了":否则要白等 10 tick 才重试。
            resetSampleCadence();
            return;
        }
        lastSampleTick = serverTick;
        everSampled = true;
        sampledBody = new WeakReference<>(companion);
        sampledServer = new WeakReference<>(server);
        sampledDimension = dimension;
        try {
            CompanionEvents.fire(CompanionEvent.SELF_SAMPLE_READY, companion);
        } catch (RuntimeException listenerFailed) {
            // 总线自己已逐个监听者兜底(CompanionEvents.fire);这里再兜一层,
            // 保证"某个监听者写坏"绝不会带走身体这一 tick 的其余工作。
            Constants.LOG.warn("[numen-state] SELF 采样事件派发失败: {}", listenerFailed.toString());
        }
    }

    /** 身体、服务器、维度三者都没换,才算"同一个身份"——任何一项变了都要立刻重采。 */
    private boolean sameSampleIdentity(NumenPlayer companion, Object server, String dimension) {
        return sampledBody != null && sampledBody.get() == companion
                && sampledServer != null && sampledServer.get() == server
                && sampledDimension.equals(dimension);
    }

    /** 节拍与身份一起作废:下一次 tick 必须重采。 */
    private void resetSampleCadence() {
        everSampled = false;
        lastSampleTick = 0L;
        sampledBody = null;
        sampledServer = null;
        sampledDimension = "";
    }

    /** 一趟遍历:顺便把镜像更新到最新。返回"有没有模型在乎的变化"。 */
    private boolean changedSince(CompanionBodySample.Frame sample) {
        int size = 41;
        if (lastItem.length != size) {
            lastItem = new Item[size];   // 全 null,首格全部算变化
            lastCount = new int[size];
        }
        boolean changed = false;
        for (int i = 0; i < size; i++) {
            ItemStack stack = sample.slot(i);
            // 空栈的 getItem() 是 AIR、getCount() 是 0,和"这一格没东西"天然一致。
            if (lastItem[i] != stack.getItem() || lastCount[i] != stack.getCount()) {
                lastItem[i] = stack.getItem();
                lastCount[i] = stack.getCount();
                changed = true;   // 不 break:整趟走完,镜像才是完整的
            }
        }
        // 换手不动任何格子的内容,但块里的 holding 会变——所以选中槽自成一处状态。
        if (sample.selected() != lastSelected) {
            lastSelected = sample.selected();
            changed = true;
        }
        return changed;
    }


    /** 一趟遍历:更新效果镜像,返回"有没有多一种、少一种、或者升了级"。 */
    private boolean effectsChangedSince(CompanionBodySample.Frame sample) {
        java.util.Set<String> now = new java.util.HashSet<>();
        for (var effect : sample.effects()) {
            now.add(effect.getEffect().unwrapKey()
                    .map(key -> key.location().toString()).orElse("unknown")
                    + '@' + effect.getAmplifier());
        }
        if (now.equals(lastEffects)) {
            return false;
        }
        lastEffects = now;
        return true;
    }

    /** 上没上/下没下载具。比实体 id 就够:换了一条船也是一次上下船。 */
    private boolean vehicleChangedSince(CompanionBodySample.Frame sample) {
        int nowId = sample.vehicleId();
        if (nowId == lastVehicleId) {
            return false;
        }
        lastVehicleId = nowId;
        return true;
    }

    private static int kinds(NumenStatePayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty())
                .map(ItemStack::getItem).distinct().count();
    }

    private static int usedSlots(NumenStatePayload p) {
        return (int) p.items().stream().filter(s -> !s.isEmpty()).count();
    }

    private static String slotName(int selected, java.util.List<ItemStack> items) {
        return selected < 0 || selected >= items.size() ? "空手" : name(items.get(selected));
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "空手"
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).getPath();
    }
}
