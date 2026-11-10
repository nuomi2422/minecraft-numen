package com.dwinovo.numen.api;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.command.ChatCommands;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * The public entry point for feeding messages INTO a companion from outside
 * the chat GUI — Discord bridges, QQ bots, stream-chat relays, MCP servers,
 * anything.
 *
 * <h2>Deliberately unspecialized</h2>
 * This is the abstract "start()" on the base class: numen-api defines one
 * verb — <em>enqueue a string for a companion</em> — and every integration
 * decides for itself what that string is. Provenance tags, rate limiting,
 * translation, permission checks: all caller-side. The message lands in the
 * same owner-prompt queue the chat GUI uses and is spliced into the
 * conversation at the next protocol-valid point, exactly as if the owner had
 * typed it.
 *
 * <h2>Outbound is not here — and never will be</h2>
 * Replies leave the companion through tools, not callbacks: register a
 * {@code NumenTool} (e.g. {@code send_qq_message}) via
 * {@code ToolRegistry.register} and the brain calls it when it has something
 * to say. Inbound = message queue, outbound = tool call. The tool-call queue
 * itself is likewise sealed: its entries are the products of an LLM decision,
 * each bound to a {@code tool_call_id} in the conversation protocol —
 * injecting one from outside would be operating the hands without the brain.
 *
 * <h2>Client-side API</h2>
 * Companions are driven by their owner's game client (the owner's API key
 * pays for the tokens), so this must be called in the owner's client process.
 * Safe from any thread — the enqueue itself is marshalled onto the client
 * main thread. Companion UUIDs come from the entity
 * ({@code entity.getUUID()}).
 */
public final class NumenGateway {

    private static final ThreadLocal<String> CURRENT_COMMAND_ID = new ThreadLocal<>();

    /** Correlation id of the client command currently executing on this thread, or null. */
    public static String currentCommandId() { return CURRENT_COMMAND_ID.get(); }

    private NumenGateway() {}

    /** Run a slash command and return its correlation id; null means it was not a command. */
    private static String dispatchCommand(EntityAgentLoop loop, String message) {
        if (!ChatCommands.isCommand(message)) return null;
        String id = UUID.randomUUID().toString();
        // R-06：嵌套命令不能吃掉外层 id；保存并在 finally 恢复。
        String previous = CURRENT_COMMAND_ID.get();
        CURRENT_COMMAND_ID.set(id);
        try {
            ChatCommands.runIfCommand(loop, message, reply ->
                    com.dwinovo.numen.Constants.LOG.info("[numen-gw] 命令执行回话[{}]: {}", id, reply));
            return id;
        } finally {
            if (previous == null) CURRENT_COMMAND_ID.remove();
            else CURRENT_COMMAND_ID.set(previous);
        }
    }


    /**
     * Queue {@code message} for {@code companion}, verbatim. If the companion
     * is idle this starts a turn immediately; if it is mid-task the message is
     * seen by the model at the next tool-batch boundary (queued messages merge
     * into one user message).
     *
     * <p>The agent loop is created on first contact: any companion on the
     * client's roster is addressable immediately after login — no need to have
     * opened its chat panel first. (Loops used to be panel-created only, which
     * made every quick-key/bridge message before the first panel visit vanish
     * with "not online".)
     *
     * <p>返回的是<b>实际发生了什么</b>,不是对"她忙不忙"的推断——桥接方判"送没送到"
     * 就照这个判,自己另算一遍必然会跟真正的闸门跑偏。游戏内的聊天回显只记事不记状态,
     * 所以这几个值今天没有任何界面在读;它们是给外部集成的汇报,该说实话。
     *
     * @param companion the companion entity's UUID
     * @param message   delivered exactly as given — formatting is the caller's business
     */
    /** R-08: production scheduling seam; tests pass their own instance explicitly (no mutable global hook). */
    private static final EnqueueScheduling DEFAULT_SCHEDULING = new MinecraftEnqueueScheduling();

    /** 事务内身份上下文：只在同一同步调用栈里有效（finally 恢复），不用来跨异步回传。 */
    private static final ThreadLocal<WorldContextStamp.Token> CURRENT_IDENTITY = new ThreadLocal<>();

    public static WorldContextStamp.Token currentIdentityToken() { return CURRENT_IDENTITY.get(); }

    /** 2026-11-10 R-06：不可变回执。accepted != effective：commandId 只代表本地受理，不代表 stop 已生效。 */
    public record EnqueueReceipt(
            Delivery delivery,
            String commandId,
            UUID companionUuid,
            IdentityStatus identityStatus,
            String worldId,
            String serverSession,
            long epoch) {
        public enum IdentityStatus { OK, NO_IDENTITY, CONNECTION_CHANGED, TARGET_CHANGED,
            ATTEMPTED_BUT_INVALID, NOT_APPLICABLE }

        public static EnqueueReceipt rejected(UUID companion) {
            return new EnqueueReceipt(Delivery.REJECTED, null, companion, IdentityStatus.NOT_APPLICABLE, null, null, 0L);
        }

        public static EnqueueReceipt handedOff(UUID companion) {
            return new EnqueueReceipt(Delivery.HANDED_OFF, null, companion, IdentityStatus.NOT_APPLICABLE, null, null, 0L);
        }
    }

    /** 一次真实 dispatch 的结果。 */
    record Executed(Delivery delivery, String commandId) {}

    /** 唯一真实 dispatch 的抽象（生产走 AgentLoop；夹具可数调用次数）。 */
    interface EnqueueDispatch { Executed dispatch(UUID companion, String message); }

    private static final EnqueueDispatch DEFAULT_DISPATCH = NumenGateway::execute;

    /** 旧接口：语义不变（联机照旧执行；跨线程 HANDED_OFF）。 */
    public static Delivery enqueue(UUID companion, String message) {
        return enqueueLegacy(companion, message, DEFAULT_SCHEDULING, DEFAULT_DISPATCH, false);
    }

    static Delivery enqueueLegacy(UUID companion, String message,
                                  EnqueueScheduling scheduling, EnqueueDispatch dispatch, boolean assumeKnown) {
        if (companion == null || message == null || message.isBlank()) return Delivery.REJECTED;
        if (!assumeKnown) {
            boolean known = AgentLoopRegistry.get(companion).isPresent()
                    || com.dwinovo.numen.client.agent.NumenRoster.instance().name(companion) != null;
            if (!known) return Delivery.REJECTED;
        }
        if (!scheduling.onClientThread()) {
            scheduling.executeOnClient(() -> dispatch.dispatch(companion, message));
            return Delivery.HANDED_OFF;
        }
        return dispatch.dispatch(companion, message).delivery();
    }

    /** 新回执入口：client 捕绑定 -> server 采样 -> client 复核执行 -> 异步 post 复核。 */
    public static java.util.concurrent.CompletableFuture<EnqueueReceipt> enqueueReceiptAsync(
            UUID companion, String message) {
        return enqueueReceiptAsync(companion, message, DEFAULT_SCHEDULING, DEFAULT_DISPATCH, false);
    }

    static java.util.concurrent.CompletableFuture<EnqueueReceipt> enqueueReceiptAsync(
            UUID companion, String message, EnqueueScheduling scheduling,
            EnqueueDispatch dispatch, boolean assumeKnown) {
        java.util.concurrent.CompletableFuture<EnqueueReceipt> done = new java.util.concurrent.CompletableFuture<>();
        if (companion == null || message == null || message.isBlank()) {
            done.complete(EnqueueReceipt.rejected(companion));
            return done;
        }
        try {
            scheduling.executeOnClient(() -> {
                try {
                    if (!assumeKnown) {
                        boolean known = AgentLoopRegistry.get(companion).isPresent()
                                || com.dwinovo.numen.client.agent.NumenRoster.instance().name(companion) != null;
                        if (!known) { done.complete(EnqueueReceipt.rejected(companion)); return; }
                    }
                    EnqueueScheduling.ClientBinding first = scheduling.captureClient(companion);
                    if (first == null || !first.ready()) {
                        done.complete(statusOf(new Executed(Delivery.REJECTED, null), companion,
                                EnqueueReceipt.IdentityStatus.NO_IDENTITY, null));
                        return;
                    }
                    scheduling.sampleServerAsync(companion).whenComplete((server, sampleError) -> {
                        if (sampleError != null || server == null) {
                            done.complete(statusOf(new Executed(Delivery.REJECTED, null), companion,
                                    EnqueueReceipt.IdentityStatus.NO_IDENTITY, null));
                            return;
                        }
                        scheduling.executeOnClient(() -> {
                            try {
                                EnqueueScheduling.ClientBinding second = scheduling.captureClient(companion);
                                EnqueueReceipt gate = precheck(companion, first, second, server);
                                if (gate != null) { done.complete(gate); return; }
                                WorldContextStamp.Token token = tokenOf(server);
                                Executed[] holder = new Executed[1];
                                withIdentityToken(token, () -> holder[0] = dispatch.dispatch(companion, message));
                                scheduling.sampleServerAsync(companion).whenComplete((post, postError) -> {
                                    try {
                                        done.complete(evaluate(companion, server, postError == null ? post : null, holder[0]));
                                    } catch (Throwable t) {
                                        done.completeExceptionally(t);
                                    }
                                });
                            } catch (Throwable t) {
                                done.completeExceptionally(t);
                            }
                        });
                    });
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            done.completeExceptionally(t);
        }
        return done;
    }

    /** 执行前门：client 绑定两次采样一致且 ready；server 身份/实体齐全；否则不执行。 */
    private static EnqueueReceipt precheck(UUID companion, EnqueueScheduling.ClientBinding first,
                                           EnqueueScheduling.ClientBinding second, EnqueueScheduling.ServerSample server) {
        if (first == null || !first.ready() || second == null || !second.ready() || !first.sameAs(second)) {
            return statusOf(new Executed(Delivery.REJECTED, null), companion,
                    EnqueueReceipt.IdentityStatus.CONNECTION_CHANGED, server);
        }
        if (server == null || !server.hasIdentity()) {
            return statusOf(new Executed(Delivery.REJECTED, null), companion,
                    EnqueueReceipt.IdentityStatus.NO_IDENTITY, server);
        }
        if (server.entityRef() == null) {
            return statusOf(new Executed(Delivery.REJECTED, null), companion,
                    EnqueueReceipt.IdentityStatus.TARGET_CHANGED, server);
        }
        return null;
    }

    /** 执行后判定：post 缺失/不一致只能算已尝试但归属失效。 */
    static EnqueueReceipt evaluate(UUID companion, EnqueueScheduling.ServerSample before,
                                   EnqueueScheduling.ServerSample after, Executed executed) {
        if (before == null || !before.hasIdentity() || before.entityRef() == null) {
            return statusOf(executed, companion, EnqueueReceipt.IdentityStatus.NO_IDENTITY, before);
        }
        if (after == null || !after.hasIdentity()) {
            return statusOf(executed, companion, EnqueueReceipt.IdentityStatus.ATTEMPTED_BUT_INVALID, before);
        }
        if (!before.sameAs(after)) {
            return statusOf(executed, companion, EnqueueReceipt.IdentityStatus.ATTEMPTED_BUT_INVALID, after);
        }
        return statusOf(executed, companion, EnqueueReceipt.IdentityStatus.OK, after);
    }

    /** 同步事务上下文：嵌套时保存/恢复外层 token，异常路径也恢复（不用于异步回传）。 */
    static void withIdentityToken(WorldContextStamp.Token token, Runnable action) {
        WorldContextStamp.Token previous = CURRENT_IDENTITY.get();
        CURRENT_IDENTITY.set(token);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT_IDENTITY.remove();
            else CURRENT_IDENTITY.set(previous);
        }
    }

    private static EnqueueReceipt statusOf(Executed executed, UUID companion,
                                           EnqueueReceipt.IdentityStatus status, EnqueueScheduling.ServerSample sample) {
        String world = sample == null ? null : sample.worldId();
        String session = sample == null ? null : sample.serverSession();
        long epoch = sample == null ? 0L : sample.epoch();
        return new EnqueueReceipt(executed.delivery(), executed.commandId(), companion, status, world, session, epoch);
    }

    private static WorldContextStamp.Token tokenOf(EnqueueScheduling.ServerSample sample) {
        if (sample == null || !sample.hasIdentity()) return new WorldContextStamp.Token(null, 0L);
        return new WorldContextStamp.Token(java.util.Map.of(
                "world_id", sample.worldId(), "server_session", sample.serverSession()), sample.epoch());
    }

    /** 唯一执行点：dispatch 或 prompt；命令路径显式带 token 发布。 */
    private static Executed execute(UUID companion, String message) {
        EntityAgentLoop loop = AgentLoopRegistry.getOrCreate(companion);
        String commandId = dispatchCommand(loop, message);
        if (commandId != null) {
            String cmd = message.trim();
            int sp = cmd.indexOf(' ');
            String name = sp < 0 ? cmd : cmd.substring(0, sp);
            com.dwinovo.numen.monitor.MonitoringJournal.get().publish("ai", "command_dispatch", java.util.Map.of(
                    "companion_id", companion.toString(), "command", name, "delivery", "SEEN",
                    "correlation_id", commandId), CURRENT_IDENTITY.get());
            return new Executed(Delivery.SEEN, commandId);
        }
        boolean pressed = loop.submitPrompt(message);
        if (loop.isExternallyDriven()) return new Executed(Delivery.TO_EXTERNAL_BRAIN, null);
        return new Executed(pressed ? Delivery.QUEUED : Delivery.SEEN, null);
    }
    /**
     * G4c S107b：把一条<b>系统事件</b>交给同伴（「目标还在，接着干」这类），<b>不冒充主人</b>。
     *
     * <p>与 {@link #enqueue} 的唯一区别：入口走 {@code submitSystemPrompt}，进的是「世界事件」队列，
     * 于是「主人回合豁免预算」那条判断不会把它算成人说的话 —— 否则「卡死 → 重推」这个循环
     * 可以无限绕过预算（2026-10-21 实机暴露）。
     *
     * <p>其它语义与 {@code enqueue} 一致：跨线程自动 marshal、斜杠命令照旧本地执行、驾驶期间照旧报
     * {@link Delivery#TO_EXTERNAL_BRAIN}。
     */
    public static Delivery enqueueSystem(UUID companion, String message) {
        if (companion == null || message == null || message.isBlank()) return Delivery.REJECTED;
        boolean known = AgentLoopRegistry.get(companion).isPresent()
                || com.dwinovo.numen.client.agent.NumenRoster.instance().name(companion) != null;
        if (!known) return Delivery.REJECTED;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> {
                EntityAgentLoop l = AgentLoopRegistry.getOrCreate(companion);
                if (!dispatchIfCommand(l, message)) {
                    l.submitSystemPrompt(message);
                }
            });
            return Delivery.HANDED_OFF;
        }
        EntityAgentLoop loop = AgentLoopRegistry.getOrCreate(companion);
        String commandId = dispatchCommand(loop, message);
        if (commandId != null) {
            String cmd = message.trim();
            int sp = cmd.indexOf(' ');
            String name = sp < 0 ? cmd : cmd.substring(0, sp);
            com.dwinovo.numen.monitor.MonitoringJournal.get().publish("ai", "command_dispatch", java.util.Map.of(
                    "companion_id", companion.toString(), "command", name, "delivery", "SEEN",
                    "correlation_id", commandId));
            return Delivery.SEEN;
        }
        boolean pressed = loop.submitSystemPrompt(message);
        if (loop.isExternallyDriven()) return Delivery.TO_EXTERNAL_BRAIN;
        return pressed ? Delivery.QUEUED : Delivery.SEEN;
    }

    /**
     * 是命令就跑掉并返回 true;不是命令返回 false(调用方照旧当聊天喂)。
     * 命令回话经 {code ChatCommands.dispatch} 走,和 GUI 手打同一条路。
     *
     * <p>★ 判据是 {@link ChatCommands#isCommand},<b>不是"回话是不是 null"</b>。命令成功时
     * 可以不吭声({@code /clear}),拿回话当判据的话它会被当成聊天再发一遍——
     * 2026-10-22 实机就是这么漏的(清空生效 + 一条 {@code <query>/clear</query>} 请求)。
     */
    private static boolean dispatchIfCommand(EntityAgentLoop loop, String message) {
        return ChatCommands.runIfCommand(loop, message, reply ->
                com.dwinovo.numen.Constants.LOG.info("[numen-gw] 命令执行回话: {}", reply));
    }

    /**
     * 接管同伴头像的画法——装了会改外观的插件之后,原版皮肤那张脸就不是她真实的样子了。
     *
     * <p>契约与注意事项见 {@link CompanionPortrait}(尤其是"每帧都会调用,所以必须便宜"
     * 那条)。注册后引擎在所有画头像的地方都会问你,你只在能答时答,其余返回 null 让它回退。
     *
     * <p>客户端调用,通常在你的模组构造期。
     */
    public static void registerPortrait(CompanionPortrait provider) {
        com.dwinovo.numen.client.skin.CompanionFace.register(provider);
    }
}
