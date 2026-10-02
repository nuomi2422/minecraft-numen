package com.dwinovo.numen.event;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.event.EventTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.monitor.MonitoringJournal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>世界事件的唯一入口。</b>常驻任务链、任务收尾、维度穿越、以及第三方内容包,
 * 全都往这里写——一个类,一个方法。
 *
 * <h2>为什么收成一个口</h2>
 * 多开一条发事件的路,就多一套"带不带时间戳""主人离线怎么办""攒不攒",而它们
 * 必然分叉:同样是"主人下线时任务做完了",一条路直接丢、另一条能留六条。
 * 一个问题只能有一个答案,所以只有这一个入口。
 *
 * <h2>两件事这里一定做</h2>
 * <ol>
 *   <li><b>盖时间戳</b>——每条事件都带游戏内日期与时刻。模型能自己判断哪些信息
 *       过期了(死前捡的铁矿在死亡地点掉了),我们就不必替它清箱;</li>
 *   <li><b>主人离线不丢</b>——进 {@link EventOutbox} 跟着存档落盘,主人登录时补发。
 *       每一种事件都享受这条,没有例外。</li>
 *   <li><b>埋点不许比给同伴看的少</b>——{@code attrs} 里的东西（{@code task_finished}
 *       的 {@code status} 就在里面）<b>两条路都得走</b>。B10 实机查出来的断裂就是这条：
 *       {@code compose} 把 {@code status} 拼进了同伴读的 XML，{@code emit} 的埋点却
 *       只发三个键，于是 {@code events.jsonl} 里几千条任务收尾<b>没有一条能回答
 *       「这次到底成没成」</b>。少写的那一半不会有人来报错，只会让人以为那条判据本来就没有。</li>
 * </ol>
 *
 * <h2>urgent</h2>
 * {@code true} = <em>她不知道这件事,正在做的事就是错的</em>。到了客户端队列,
 * urgent 会立刻带走队列里攒的一切并开一轮;非 urgent 攒着,等够数、够久、
 * 或者主人说话时搭车。发事件的人有权判断——判断错了主人会觉得同伴很吵,
 * 那是内容包自己的名声。
 *
 * <p>服务端专用。
 */
public final class NumenEvents {

    /** 事件词汇表。新种类往这里加,别自己拼 XML。 */
    public enum Kind {
        /**
         * 异步任务收尾。
         *
         * <p>{@code status} <b>五种</b>，不是四种：{@code done} / {@code failed} /
         * {@code timeout} / {@code stopped}（{@code CompanionBrain.shipResults} 的 switch，
         * 第四个是 default 兜底）＋ {@code interrupted}（{@code TaskSlot.dropNoResult}：
         * 任务因她死亡而中断，走的是另一条路）。
         * <p>⚠️ 旧注释只写了四种，{@code interrupted} 是 2026-10-03 实机在
         * {@code events.jsonl} 里查出来的——它偏偏是最该被统计的那一种（死亡）。
         */
        TASK_FINISHED("task_finished"),
        /** 身体自理:饿了吃、快淹死了浮上来、被打了还手。 */
        BODY_LOG("body_log"),
        /** 同伴自己跨了维度。 */
        DIMENSION_CHANGE("dimension_change"),
        /** 她死了又复活了(在客户端合成——身体那会儿已经不在了)。 */
        DEATH("death"),
        /** 她自己定的表到点了(见 {@code TimerRegistry})。提醒而已,不代表那件事完成了。 */
        TIMER("timer"),
        /** 她从床上醒了。{@code sleep} 到躺下就返回,醒来这一刻只有这条事件说得出。 */
        WOKE("woke"),
        /** 饿了 —— 她不会自己吃,得主人给或者叫她去弄。 */
        HUNGRY("hungry"),
        /** 主人挨打了(只报实体攻击)。急不急按主人血线分档,见 {@code ownerHurt}。 */
        OWNER_HURT("owner_hurt");

        private final String kind;

        Kind(String kind) {
            this.kind = kind;
        }

        public String kindName() {
            return kind;
        }
    }

    private NumenEvents() {}

    /**
     * 她饿了。<b>急</b> —— 她不会自己吃,主人不知道就没人管,饱食归零会开始掉血。
     * 去抖在 {@code NumenPlayer.pollGotHungry}:一轮饥饿只发一条。
     */
    public static void gotHungry(NumenPlayer companion, int foodLevel) {
        emit(companion, Kind.HUNGRY, null,
                "you are hungry (" + foodLevel + "/20) and you do not eat on your own — "
                        + "call eat with something from your inventory, or go get food",
                true);
    }

    /** 身体自理日记——常驻任务链的叙事出口。永远不急。 */
    public static void body(NumenPlayer companion, String text) {
        emit(companion, Kind.BODY_LOG, null, text, false);
    }

    /**
     * 主人挨打了。急不急按血线分档:安全区只是消息(攒着搭车,她下次开口自然带一句);
     * 跌进危险区(与饥饿同一条"原版跑不动"的线)才是急件。这条事件<b>不碰身体</b>——
     * 去不去救永远是她的决定。检测与去抖在 {@code OwnerHurtWatch}。
     */
    public static void ownerHurt(NumenPlayer companion, String attacker,
                                 float hp, float maxHp, double distance, boolean urgent) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("by", attacker);
        attrs.put("owner_hp", Math.round(hp) + "/" + Math.round(maxHp));
        attrs.put("distance", String.valueOf(Math.round(distance)));
        String text = urgent
                ? "your owner is in DANGER: " + attacker + " has them down to " + Math.round(hp)
                        + "/" + Math.round(maxHp) + " HP, about " + Math.round(distance)
                        + " blocks from you — decide now whether to go help"
                : "your owner just took a hit from " + attacker + " (" + Math.round(hp) + "/"
                        + Math.round(maxHp) + " HP, about " + Math.round(distance)
                        + " blocks from you) — they can likely handle it; your call";
        emit(companion, Kind.OWNER_HURT, attrs, text, urgent);
    }

    /**
     * 异步任务收尾。{@code status} ∈ done / failed / timeout / stopped / interrupted。
     *
     * <p><b>急不急只问「是不是主人自己叫停的」</b>：{@code !"stopped".equals(status)}。
     * 所以 {@code interrupted}（她死了，任务被丢掉）是急件。
     * <p>⚠️ 这是 B10 之后<b>刻意不动</b>的一条：死亡导致的中断该不该立刻吵她，
     * 属于「改变同伴行为」的调参，<b>零实机证据前不许顺手改</b>。这里只把事实写准。
     */
    public static void taskFinished(NumenPlayer companion, String taskId, String tool,
                                    String status, String message) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", taskId);
        attrs.put("task", tool);
        attrs.put("status", status);
        emit(companion, Kind.TASK_FINISHED, attrs, message, !"stopped".equals(status));
    }

    /** {@code data} 里真正落盘的 attrs 个数。哪怕是 0 也报——「本来就没有」和「没写进去」必须分得开。 */
    public static final String ATTRS_WRITTEN = "attrs_written";

    /** 被丢掉的 attrs 键（{@code 键(原因)} 逗号分隔）。一个都没丢时<b>不放这个键</b>。 */
    public static final String ATTRS_SKIPPED = "attrs_skipped";

    /**
     * 观测行 {@code data} 这一层该有什么。<b>抽出来是为了能测</b>——{@link #emit} 第一行就
     * 要 {@code MinecraftServer}，单测里一行都跑不到；而「字段有没有被丢掉」的现场恰好只有这一层。
     *
     * <p><b>为什么 attrs 必须进来</b>：{@link #taskFinished} 算好的 {@code status}
     * （done / failed / timeout / stopped / interrupted）原本只进了同伴读的 XML，
     * 埋点整块丢掉 ⇒ 实机 {@code events.jsonl} 里 3000+ 条 {@code task_finished}
     * <b>没有一条带 {@code status}</b>。判成败的真判据只有它：{@code urgent} 里真失败和
     * 真成功混在一起（实测 urgent=true 的 3009 条里既有 {@code collected 0 all items}
     * 也有 {@code built 1/1 block(s)}），{@code message} 是自由文本，两个都不能当判据。
     *
     * <p><b>为什么不套一层 {@code attrs={}}</b>：{@code data} 本来就嵌在信封里，
     * 平铺撞不到信封键（{@code schema_version} 等都在 {@code data} 外面），
     * 而 XML 里 attrs 就是平铺的属性——保持同一个形状，读的人不用多跳一层。
     *
     * <p><b>包私有而不是 public</b>：现在唯一的调用者是 {@link #emit}。
     * 没有消费者就往外开可见性，是 {@code ArtifactSink} 那种「接口在、没人用」的反面教材。
     *
     * @param attrs 事件属性；{@code null} 表示这条事件本来就没有属性（饿了 / 身体日记）
     */
    static Map<String, Object> journalData(UUID companionId, Map<String, String> attrs,
                                           String text, boolean urgent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("urgent", urgent);
        data.put("companion_id", companionId == null ? "" : companionId.toString());
        data.put("message", text == null ? "" : text);
        int written = 0;
        List<String> skipped = new ArrayList<>();
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isBlank()) {
                    // 键都没有的东西没法在日志里指认，只能记「有一个无名键被丢了」这件事本身
                    skipped.add("(无名键)");
                    continue;
                }
                if (e.getValue() == null) {
                    // gson 会静默跳过 null 值 ⇒ 不显式挑出来就是「下游以为那就是全部」
                    skipped.add(key + "(值为空)");
                    continue;
                }
                if (data.containsKey(key)) {
                    // attrs 是调用方给的，而固定键只有三个；撞了就保住固定键并说明白。
                    // 不做「谁后到谁赢」——那会让同一条事件的字段含义随调用方改动而漂移。
                    skipped.add(key + "(与固定键同名)");
                    continue;
                }
                data.put(key, e.getValue());
                written++;
            }
        }
        data.put(ATTRS_WRITTEN, written);
        if (!skipped.isEmpty()) {
            data.put(ATTRS_SKIPPED, String.join(",", skipped));
        }
        return data;
    }

    /**
     * 发一条世界事件。主人在线直接送达,离线进出箱等他回来。
     *
     * @param urgent 她不知道就会做错事 → 立刻开一轮;否则攒着搭车
     */
    public static void emit(NumenPlayer companion, Kind kind, Map<String, String> attrs,
                            String text, boolean urgent) {
        if (companion == null) {
            return;
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        String xml = compose(server, kind, attrs, text);
        long now = System.currentTimeMillis();
        MonitoringJournal.get().publish("events", kind.kindName(),
                journalData(companion.getUUID(), attrs, text, urgent));
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner != null) {
            Services.NETWORK.sendToPlayer(owner, new NumenEventPayload(
                    companion.getUUID(), EventTypes.EVENT, xml, now, urgent));
            Constants.LOG.info("[numen-event] {} kind={}{} → 客户端", companion.getUUID(),
                    kind.kind, urgent ? " URGENT" : "");
            return;
        }
        // 主人不在:留着。他下线期间她照样在干活,回来该知道发生了什么。
        EventOutbox outbox = EventOutbox.get(server);
        outbox.put(companion.getUUID(), EventTypes.EVENT, xml, now, urgent);
        Constants.LOG.info("[numen-event] {} kind={}{} → 暂存(主人离线,已攒 {} 条)",
                companion.getUUID(), kind.kind, urgent ? " URGENT" : "",
                outbox.peek(companion.getUUID()).size());
    }

    /** 组装 XML,盖上游戏内时间戳。 */
    private static String compose(MinecraftServer server, Kind kind, Map<String, String> attrs, String text) {
        return compose(server.overworld().getDayTime(), kind, attrs, text);
    }

    /**
     * 造一条 {@code <event>} —— <b>唯一的构造口</b>,{@code day} / {@code t} 由它统一盖上。
     *
     * <p>收 {@code dayTime} 而不是 {@code MinecraftServer},所以客户端也能用同一条路
     * (死亡事件在客户端合成:那会儿身体已经不在了)。两侧共用这一个构造口,
     * 才不会出现"最该有时间的那条事件恰好没盖上时间"。
     */
    public static String compose(long dayTime, Kind kind, Map<String, String> attrs, String text) {
        StringBuilder sb = new StringBuilder("<event kind=\"").append(kind.kind).append('"');
        sb.append(" day=\"").append(dayTime / 24000L).append('"');
        sb.append(" t=\"").append(clockOf(dayTime)).append('"');
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                sb.append(' ').append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
            }
        }
        return sb.append('>').append(escape(text)).append("</event>").toString();
    }

    /** 游戏内时刻 HH:mm。原版 0 刻 = 早上 6 点。 */
    static String clockOf(long dayTime) {
        long inDay = Math.floorMod(dayTime, 24000L);
        long minutes = (inDay * 60L / 1000L + 6L * 60L) % (24L * 60L);
        return String.format("%02d:%02d", minutes / 60L, minutes % 60L);
    }

    /** XML 属性/正文转义——事件正文里可能有实体名、物品名,是玩家能控制的输入。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
