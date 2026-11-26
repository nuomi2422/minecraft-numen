package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.llm.LlmActivity;
import com.dwinovo.numen.agent.tool.ToolCallFingerprint;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TimerRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded repeat supervision for one current RUNNING task identity.
 *
 * <p>This is the existing H3 watcher, not a second scheduler: it only observes
 * tool calls and may inject one system reminder through the shared notification
 * owner. It never dispatches, pauses, resumes or fails a body/task.</p>
 *
 * <p>The legacy length-only production hook cannot prove equal arguments and is
 * deliberately fail-closed. The raw-argument overload below is the integration
 * seam; until a producer supplies raw JSON this watcher stays silent rather than
 * reviving the old {@code tool + args_chars} false positives. Completed executor
 * responses also supply exact assistant-visible text (never hidden reasoning)
 * once per request token for the zero-tool repeat signal.</p>
 */
final class RddRepeatQueryWatcher {
    static final int MIN_COUNT = 3;
    static final int WINDOW_TICKS = 600;
    static final int MIN_SEQUENCE_ROUNDS = 2;
    static final int MAX_SEQUENCE_ACTIONS = 3;
    private static final int MAX_ACTIONS = 12;
    private static final int MAX_CALL_IDS = 64;
    private static final int MAX_COMPANIONS = 64;

    private static final Map<UUID, Session> STATES = new ConcurrentHashMap<>();
    private static volatile Path offFlag;
    private static volatile Notifier notifier;

    private static final class Session {
        final String identity;
        final ArrayDeque<Action> actions = new ArrayDeque<>();
        final Map<String, Hit> hits = new HashMap<>();
        final Set<String> callIds = new LinkedHashSet<>();
        final Set<Long> responseTokens = new LinkedHashSet<>();
        long lastTick;
        boolean notified;
        boolean visibleNotified;
        String visibleHash;
        int visibleCount;
        long visibleFirstTick;

        Session(String identity) { this.identity = identity; }
    }

    private record Action(String fingerprint, long tick) {}
    private record Hit(int count, long firstTick) {}
    record Repeat(String identity, String kind, String tool, int argsChars,
                          int count, int sequenceLength, String actionHash) {}

    /** Sampled task/activity context. Ineligible observations discard old state. */
    record Context(String identity, boolean eligible) {
        static final Context INELIGIBLE = new Context(null, false);
    }

    interface Notifier {
        boolean notifyRepeat(UUID companionId, Repeat repeat);
    }

    private RddRepeatQueryWatcher() {}

    static void install(Path configDir) {
        offFlag = configDir == null ? null : configDir.resolve("redundant-query-off.flag");
        notifier = (companionId, repeat) -> {
            if (!RddSupervisionNotifications.nudgeIfRunning(companionId, repeat.identity(), repeatMessage(repeat))) {
                return false;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId.toString());
            data.put("taskIdentity", repeat.identity());
            data.put("kind", repeat.kind());
            data.put("tool", repeat.tool());
            data.put("argsChars", repeat.argsChars());
            data.put("actionHash", repeat.actionHash());
            data.put("count", repeat.count());
            data.put("sequenceLength", repeat.sequenceLength());
            data.put("windowTicks", WINDOW_TICKS);
            RddMonitor.publish("redundant_query_runtime", data);
            return true;
        };
        LlmActivity.installSettledListener((companionId, token, phase, finish, toolCalls, visibleText) -> {
            if (toolCalls != 0) {
                clearVisible(companionId);
                return;
            }
            if (token <= 0L || phase == null || !phase.startsWith("execution") || !"stop".equals(finish)) return;
            UUID id;
            try { id = UUID.fromString(companionId); }
            catch (RuntimeException invalid) { return; }
            long tick = currentGameTick(id);
            if (tick < 0) return;
            observeVisible(id, token, phase, finish, toolCalls, visibleText, tick);
        });
        clearAll();
    }

    static void installNotifier(Notifier n) {
        notifier = n;
    }

    static boolean isExternalCall(String toolCallId) {
        return toolCallId != null && toolCallId.startsWith("mcp-");
    }

    /**
     * Legacy production entry. Length is not an action fingerprint; accepting it
     * would recreate the false-positive bug this change is meant to remove.
     */
    @Deprecated
    static boolean observe(UUID companionId, String tool, int argsChars, long gameTick) {
        return false;
    }

    /** Raw-argument production seam; call id deduplicates multi-layer reports. */
    static boolean observe(UUID companionId, String toolCallId, String tool, String argsJson, long gameTick) {
        if (companionId == null || disabled() || isExternalCall(toolCallId)) return false;
        Context context = currentContext(companionId);
        Repeat repeat = record(companionId, toolCallId, tool, argsJson, gameTick, context);
        if (repeat == null || !RddPlugin.nudgeAllowed(companionId)) return false;
        // Owner pause, task replacement or fresh activity may have landed after sampling.
        Context latest = currentContext(companionId);
        if (!latest.eligible() || !latest.identity().equals(repeat.identity())) return false;
        return deliver(companionId, repeat);
    }

    /** Deterministic pure-state seam used by tests and future wired producers. */
    static boolean observe(UUID companionId, String toolCallId, String tool, String argsJson,
                           long gameTick, Context context) {
        if (companionId == null || context == null || disabled() || isExternalCall(toolCallId)) return false;
        Repeat repeat = record(companionId, toolCallId, tool, argsJson, gameTick, context);
        return repeat != null && deliver(companionId, repeat);
    }

    /** Production visible-response entry, called once by LlmActivity after token settlement. */
    private static boolean observeVisible(UUID companionId, long token, String phase, String finish,
                                          int toolCalls, String visibleText, long gameTick) {
        if (companionId == null || disabled()) return false;
        Context context = currentContext(companionId);
        Repeat repeat = recordVisible(companionId, token, phase, finish, toolCalls, visibleText, gameTick, context);
        if (repeat == null || !RddPlugin.nudgeAllowed(companionId)) return false;
        Context latest = currentContext(companionId);
        if (!latest.eligible() || !latest.identity().equals(repeat.identity())) return false;
        return deliver(companionId, repeat);
    }

    /** Deterministic seam: exact visible text, zero tools, one completed request token. */
    static boolean observeVisible(UUID companionId, long token, String phase, String finish,
                                  int toolCalls, String visibleText, long gameTick, Context context) {
        if (companionId == null || context == null || disabled()) return false;
        Repeat repeat = recordVisible(companionId, token, phase, finish, toolCalls, visibleText, gameTick, context);
        return repeat != null && deliver(companionId, repeat);
    }

    private static synchronized Repeat recordVisible(UUID companionId, long token, String phase,
            String finish, int toolCalls, String visibleText, long gameTick, Context context) {
        if (toolCalls != 0) {
            Session existing = STATES.get(companionId);
            if (existing != null) resetVisible(existing);
            if (!context.eligible()) STATES.remove(companionId);
            return null;
        }
        if (token <= 0L || phase == null || !phase.startsWith("execution")
                || !"stop".equals(finish)) {
            if (!context.eligible()) STATES.remove(companionId);
            return null;
        }
        String hash = ToolCallFingerprint.hashText(visibleText);
        if (hash == null) {
            Session existing = STATES.get(companionId);
            if (existing != null) resetVisible(existing);
            if (!context.eligible()) STATES.remove(companionId);
            return null;
        }
        Session session = STATES.get(companionId);
        if (!context.eligible() || context.identity() == null) {
            STATES.remove(companionId);
            return null;
        }
        if (session == null || !session.identity.equals(context.identity()) || gameTick < session.lastTick) {
            if (session == null && STATES.size() >= MAX_COMPANIONS) STATES.keySet().iterator().remove();
            session = new Session(context.identity());
            STATES.put(companionId, session);
        }
        session.lastTick = gameTick;
        if (!session.responseTokens.add(token)) return null;
        if (session.responseTokens.size() > MAX_CALL_IDS) session.responseTokens.iterator().remove();
        prune(session, gameTick);
        if (!hash.equals(session.visibleHash) || gameTick - session.visibleFirstTick > WINDOW_TICKS) {
            session.visibleHash = hash;
            session.visibleCount = 0;
            session.visibleFirstTick = gameTick;
            session.visibleNotified = false;
        }
        session.visibleCount++;
        if (session.visibleNotified || session.visibleCount < MIN_COUNT) return null;
        return new Repeat(session.identity, "same_visible_text", "visible_response", visibleText.length(),
                session.visibleCount, 1, hash);
    }

    private static long currentGameTick(UUID companionId) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return -1;
            var body = com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, companionId);
            return body == null ? -1 : body.level().getGameTime();
        } catch (RuntimeException unknown) {
            return -1;
        }
    }

    private static synchronized void clearVisible(String companionId) {
        if (isBlank(companionId)) return;
        Session session = STATES.get(companionId);
        if (session != null) resetVisible(session);
    }

    private static void resetVisible(Session session) {
        session.visibleHash = null;
        session.visibleCount = 0;
        session.visibleFirstTick = 0;
        session.visibleNotified = false;
        session.responseTokens.clear();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static synchronized Repeat record(UUID companionId, String toolCallId, String tool,
                                 String argsJson, long gameTick, Context context) {
        if (tool == null || tool.isBlank()) {
            if (!context.eligible()) STATES.remove(companionId);
            return null;
        }
        String action = ToolCallFingerprint.action(tool, argsJson);
        if (action == null) {
            if (!context.eligible()) STATES.remove(companionId);
            return null;
        }
        Session session = STATES.get(companionId);
        if (!context.eligible() || context.identity() == null) {
            STATES.remove(companionId);
            return null;
        }
        if (session == null || !session.identity.equals(context.identity()) || gameTick < session.lastTick) {
            if (session == null && STATES.size() >= MAX_COMPANIONS) {
                STATES.keySet().iterator().remove();
            }
            session = new Session(context.identity());
            STATES.put(companionId, session);
        }
        session.lastTick = gameTick;
        resetVisible(session);
        if (toolCallId != null && !toolCallId.isBlank()) {
            if (!session.callIds.add(toolCallId)) return null;
            if (session.callIds.size() > MAX_CALL_IDS) {
                session.callIds.iterator().remove();
            }
        }
        prune(session, gameTick);
        session.actions.addLast(new Action(action, gameTick));
        while (session.actions.size() > MAX_ACTIONS) session.actions.removeFirst();
        Hit hit = session.hits.get(action);
        session.hits.put(action, new Hit(hit == null ? 1 : hit.count() + 1,
                hit == null ? gameTick : hit.firstTick()));
        if (session.notified) return null;

        List<Action> list = new ArrayList<>(session.actions);
        Sequence sequence = repeatedSuffix(list);
        if (sequence != null) {
            return new Repeat(session.identity, "tool_sequence", tool, argsJson.length(),
                    sequence.rounds(), sequence.length(), actionHash(action));
        }
        Hit current = session.hits.get(action);
        if (current.count() >= MIN_COUNT) {
            return new Repeat(session.identity, "same_action", tool, argsJson.length(),
                    current.count(), 1, actionHash(action));
        }
        return null;
    }

    private static Sequence repeatedSuffix(List<Action> actions) {
        for (int length = Math.min(MAX_SEQUENCE_ACTIONS, actions.size() / MIN_SEQUENCE_ROUNDS); length >= 2; length--) {
            int end = actions.size();
            boolean equal = true;
            for (int i = 0; i < length; i++) {
                if (!actions.get(end - length * 2 + i).fingerprint()
                        .equals(actions.get(end - length + i).fingerprint())) {
                    equal = false;
                    break;
                }
            }
            if (!equal) continue;
            int rounds = MIN_SEQUENCE_ROUNDS;
            while (end - length * (rounds + 1) >= 0) {
                for (int i = 0; i < length; i++) {
                    if (!actions.get(end - length * rounds + i).fingerprint()
                            .equals(actions.get(end - length * (rounds + 1) + i).fingerprint())) {
                        return new Sequence(length, rounds);
                    }
                }
                rounds++;
            }
            return new Sequence(length, rounds);
        }
        return null;
    }

    private record Sequence(int length, int rounds) {}

    private static String actionHash(String action) {
        return action.substring(action.indexOf('|') + 1);
    }

    private static void prune(Session session, long tick) {
        session.actions.removeIf(action -> tick - action.tick() > WINDOW_TICKS);
        session.hits.entrySet().removeIf(entry -> tick - entry.getValue().firstTick() > WINDOW_TICKS);
    }

    private static synchronized boolean deliver(UUID companionId, Repeat repeat) {
        Session session = STATES.get(companionId);
        if (session == null || !session.identity.equals(repeat.identity())) return false;
        Notifier n = notifier;
        if (n == null) return false;
        try {
            if (!n.notifyRepeat(companionId, repeat)) return false;
        } catch (RuntimeException ignored) {
            return false;
        }
        if (repeat.kind().equals("same_visible_text")) session.visibleNotified = true;
        else session.notified = true;
        return true;
    }

    private static String repeatMessage(Repeat repeat) {
        String what = switch (repeat.kind()) {
            case "tool_sequence" -> "你刚重复了同一组 " + repeat.sequenceLength() + " 个工具动作（" + repeat.count() + " 轮）";
            case "same_visible_text" -> "你已连续 " + repeat.count() + " 轮给出完全相同的可见回答，且没有调用任何工具";
            default -> "你刚用相同规范参数第 " + repeat.count() + " 次调用 " + repeat.tool();
        };
        return "[重复监督] " + what + "。这只是重复证据，不等于答案或世界状态已经证明相同。"
                + "\n先看最近一次真实回执和当前目标：已有有效结果就直接接续；确实无进展就换参数、工具或方案，"
                + "或按原契约报告阻塞。不要原样重发，也不要为了确认重复查询。";
    }

    /** Production admission: fresh RUNNING identity and no legitimate busy/wait state. */
    static Context currentContext(UUID companionId) {
        if (companionId == null || disabled() || !RddPlugin.supervisionEnabled()) return Context.INELIGIBLE;
        try {
            RddRuntime runtime = RddPlugin.runtime(companionId);
            if (runtime == null) return Context.INELIGIBLE;
            TaskChain chain = runtime.chain();
            String identity = RddSupervisionNotifications.runningTaskIdentity(chain);
            if (identity == null || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING
                    || chain.isOwnerPause(chain.currentSubtask().id())) return Context.INELIGIBLE;
            if (SideTaskHost.isActive(companionId)) return Context.INELIGIBLE;
            var body = CompanionTickDispatcher.currentTaskFor(companionId);
            if (body != null && !body.getState().isTerminal()) return Context.INELIGIBLE;
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return Context.INELIGIBLE;
            var timers = TimerRegistry.get(server).list(companionId);
            if (timers != null && !timers.isEmpty()) return Context.INELIGIBLE;
            LlmActivity.Snapshot llm = LlmActivity.snapshot(companionId.toString());
            if (llm.inFlight() && !llm.stale()) return Context.INELIGIBLE;
            return new Context(identity, true);
        } catch (RuntimeException unknown) {
            return Context.INELIGIBLE;
        }
    }

    private static boolean disabled() {
        try {
            return offFlag != null && Files.exists(offFlag);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Real observed progress or a legitimate wait breaks the old repeat episode. */
    static synchronized void clear(UUID companionId) {
        if (companionId == null) clearAll();
        else STATES.remove(companionId);
    }

    static synchronized void clearAll() {
        STATES.clear();
    }
}
