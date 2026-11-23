package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attempt06: an empty completed reply paused the loop; queued supervision could not wake it.
 * Run through managed api:common TestOnly with this exact class selector.
 * Production consumers, pause policies and event queue are compiled mechanically below.
 * A policy-only test misses the absent call in enqueueSystemEvent; no MC loop is constructed.
 * UI, environment formatting, budget-policy answers and outbound request I/O are boundary doubles.
 * This proves consumer scheduling and guard preservation, not HTTP, Minecraft or deployment.
 */
class SystemPromptWakeConsumerTest {
    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("api/common/src"))) {
            root = root.getParent();
            assertNotNull(root, "repository root");
        }
        Map<String, String> sources = new LinkedHashMap<>();
        for (String relative : List.of(
                "api/common/src/main/java/com/dwinovo/numen/event/EventQueue.java",
                "api/common/src/main/java/com/dwinovo/numen/event/EventTypes.java",
                "api/common/src/client/java/com/dwinovo/numen/client/agent/AgentTurnPause.java",
                "api/common/src/client/java/com/dwinovo/numen/client/agent/OwnerStopLatch.java")) {
            String text = Files.readString(root.resolve(relative));
            String pkg = text.substring(text.indexOf("package ") + 8, text.indexOf(';')).trim();
            sources.put(pkg + "." + Path.of(relative).getFileName().toString().replace(".java", ""), text);
        }
        String loop = Files.readString(root.resolve(
                "api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java"));
        List<String> consumers = List.of(
                "public boolean submitSystemPrompt(", "private boolean enqueueSystemEvent(",
                "private void enqueueBothQueues(", "public void pushEvent(",
                "private void maybeDrain(", "public void clientTick(",
                "private boolean paused(", "private boolean drainInbox(",
                "private static boolean isControlEntry(", "private void failTurnKeepQueue(",
                "private static String truncate(");
        StringBuilder methods = new StringBuilder();
        for (String signature : consumers) methods.append(method(loop, signature)).append('\n');

        // These are exact contiguous production spans, not rewritten guard/pause algorithms.
        // Binding lookup and request construction are outside this scheduling fixture.
        String start = method(loop, "private boolean tryStartTurn(");
        String startGuards = between(start, "        publishBrainGate();", "        // No hard cap");
        String budget = between(start, "        var budgetOver = BudgetPolicy.checkBlock(",
                "        // 上下文预算闸");
        String context = between(start, "        String contextProblem = contextBudgetProblem();",
                "        // Auto-compaction gate:");
        String goal = method(loop, "private void judgeGoal(");
        String goalBudget = between(goal, "        String contextProblem = contextBudgetProblem();", "        var delivery =");
        String response = method(loop, "private void handleResponse(");
        String retryBudget = between(response, "                String retryContextProblem = contextBudgetProblem();", "                var retryTools =");
        String responseGuard = between(response, "        // Owner interrupted this turn",
                "        if (err != null) {");
        String emptyResponse = between(response, "        AssistantTurn turn = res.turn();",
                "        // True context size");
        methods.append(goal).append('\n');
        methods.append("private boolean tryStartTurn() {\n").append(startGuards)
                .append("long requestTokens = 1L;\n").append(budget).append(context)
                .append("requests++; awaitingLlmResponse = true; return true;\n}\n")
                .append("void goalBudgetGate() {\n").append(goalBudget).append("requests++;\n}\n")
                .append("void retryBudgetGate() {\nvar vt2=new RetryVoice();\n").append(retryBudget).append("requests++;\n}\n")
                .append("void emptyReply() {\n").append(responseGuard)
                .append(emptyResponse).append("\n}\n");

        sources.put("com.dwinovo.numen.Constants", """
                package com.dwinovo.numen;
                public class Constants {
                  public static final Log LOG=new Log();
                  public static class Log {
                    public void info(String s,Object... a){}
                    public void warn(String s,Object... a){}
                    public void debug(String s,Object... a){}
                  }
                }
                """);
        sources.put("net.minecraft.client.Minecraft", """
                package net.minecraft.client;
                public class Minecraft {
                  public static boolean connected=true;
                  public static Minecraft getInstance(){return new Minecraft();}
                  public Object getConnection(){return connected?this:null;}
                  public void execute(Runnable r){r.run();}
                }
                """);
        sources.put("com.dwinovo.numen.api.NumenApi", """
                package com.dwinovo.numen.api;
                public interface NumenApi {String SLEEP_INTERRUPT="sleep_interrupt";}
                """);
        sources.put("com.dwinovo.numen.client.data.ClientPrefs", """
                package com.dwinovo.numen.client.data;
                public class ClientPrefs {public static int initiativeLevel(){return 10;}}
                """);
        sources.put("com.dwinovo.numen.client.chat.ChatLines", """
                package com.dwinovo.numen.client.chat;
                public class ChatLines {public static void notice(String a,String b){}}
                """);
        sources.put("com.dwinovo.numen.client.hud.SpeechBubbles", """
                package com.dwinovo.numen.client.hud;
                public class SpeechBubbles {public static void clear(Object id){}}
                """);
        sources.put("com.dwinovo.numen.client.ui.NumenToasts", """
                package com.dwinovo.numen.client.ui;
                public class NumenToasts {public enum Severity {ERROR}}
                """);
        sources.put("com.dwinovo.numen.client.hud.NumenHudToasts", """
                package com.dwinovo.numen.client.hud;
                public class NumenHudToasts {public static void push(Object severity,String text){}}
                """);
        sources.put("com.dwinovo.numen.monitor.MonitoringJournal", """
                package com.dwinovo.numen.monitor;
                public class MonitoringJournal {
                  public static MonitoringJournal get(){return new MonitoringJournal();}
                  public void publish(String stream,String type,Object data){}
                }
                """);
        sources.put("net.minecraft.client.player.AbstractClientPlayer", """
                package net.minecraft.client.player;
                public class AbstractClientPlayer {public Object level(){return null;}}
                """);
        sources.put("com.dwinovo.numen.api.NumenPlugins", """
                package com.dwinovo.numen.api;
                public class NumenPlugins { public static class DeliveredContext {} }
                """);
        sources.put("com.dwinovo.numen.agent.goal.GoalPrompts", """
                package com.dwinovo.numen.agent.goal;
                public class GoalPrompts {
                  public static String evaluatorQuery(Object goal,String facts,String since){return facts;}
                  public static String evaluatorSystem(){return "system";}
                }
                """);
        sources.put("com.dwinovo.numen.client.agent.WakeFixture", """
                package com.dwinovo.numen.client.agent;
                import java.util.*;
                import com.dwinovo.numen.Constants;
                import com.dwinovo.numen.event.*;
                import net.minecraft.client.player.AbstractClientPlayer;
                import net.minecraft.client.Minecraft;
                public class WakeFixture {
                  UUID entityUuid=UUID.randomUUID();
                  AgentTurnPause turnPause=AgentTurnPause.NONE;
                  boolean awaitingLlmResponse,compacting,dead,external,turnRetried=true,ownerSpokeThisTurn;
                  int requests,turnGeneration,gen,externalTapDropped;
                  long budgetRetryAtMs,contextBudgetNoticeAtMs;
                  static final long CONTEXT_BUDGET_NOTICE_COOLDOWN_MS=1000;
                  static final int EXTERNAL_TAP_CAP=64;
                  String lastTurnError,budgetBlockedRole;
                  String pendingRuntimeStateXml="candidate",pendingDeliveryTaskId="task",pendingRuntimeOrigin="goal_judging";
                  Object pendingRuntimeStatePlan=new Object();
                  List<Object> pendingDeliveredRefs=new ArrayList<>(List.of("ref"));
                  int pendingRuntimeAttempt=1; boolean denyContext,retryingTurn; int voiceFinished;
                  // 标清边界桩:只为让抽取的 drainInbox 编译/运行。本测试走 owner=false 路径,
                  // 不断言也不代签锚点行为(anchor 语义由 OwnerRequestContinuityTest 负责)。
                  OwnerRequestAnchor ownerRequestAnchor=OwnerRequestAnchor.none();
                  static class OwnerRequestAnchor {
                    static OwnerRequestAnchor none(){return new OwnerRequestAnchor();}
                    static OwnerRequestAnchor adopt(OwnerRequestAnchor previous,List<EventQueue.Entry> taken){return previous;}
                    boolean present(){return false;}
                    String text(){return "";}
                    String xml(){return "";}
                  }
                  class RetryVoice {Runnable finish(){return () -> voiceFinished++;}}
                  Result res=new Result(new AssistantTurn("",List.of(),null));
                  EventQueue queue=new EventQueue(EventQueue.Journal.NONE);
                  ArrayDeque<EventQueue.Entry> externalTap=new ArrayDeque<>();
                  ConvoState convo=new ConvoState();
                  Dispatcher dispatcher=new Dispatcher();
                  Presenter presenter=new Presenter();
                  WorkBlocks workBlocks=new WorkBlocks();
                  static class Dispatcher {boolean busy;boolean busy(){return busy;}void tick(){}}
                  static class Presenter {void tick(){}void clearPartial(){}void finishStreamLine(){}String speakerName(){return "test";} }
                  static class WorkBlocks {String formatXml(Object level){return "";}}
                  record AssistantTurn(String content,List<Object> calls,Object extra) {
                    boolean hasToolCalls(){return !calls.isEmpty();}
                  }
                  record Result(AssistantTurn turn){}
                  static class ConvoState {
                    static class Msg {static class User extends Msg {User(){} User(String s){}}}
                    List<Msg> messages=new ArrayList<>();List<String> texts=new ArrayList<>();
                    List<Msg> snapshot(){return messages;}
                    Msg lastMessage(){return messages.isEmpty()?null:messages.get(messages.size()-1);}
                    void addUser(String s){texts.add(s);messages.add(new Msg.User());}
                    void addAssistant(AssistantTurn turn){messages.add(new Msg());}
                    void resetTurnCount(){}
                  }
                  static class BudgetPolicy {
                    static boolean blocked;static int checks;
                    record Used(){long tokens(){return 0;}long calls(){return 0;}long millis(){return 0;}}
                    record Limit(){long tokensPerWindow(){return 0;}long callsPerWindow(){return 0;}long minutesPerWindow(){return 0;}}
                    record Block(){
                      String role(){return "execution";} List<String> exceeded(){return List.of("calls");}
                      Used used(){return new Used();} Limit limit(){return new Limit();}
                      long projectedTokens(){return 1;}String mode(){return "enforce";}
                    }
                    static Block checkBlock(String role,long tokens){checks++;return blocked?new Block():null;}
                    static long millisUntilWindowReset(String role){return 60000;}
                    static long secondsUntilWindowReset(String role){return 60;}
                    static boolean notifyBlock(Block b){return false;}
                  }
                  boolean isExternallyDriven(){return external;}
                  void publishBrainGate(){}
                  void maybeNudgeAfterBudgetWindow(){}
                  void interruptForSleep(String taskId){throw new AssertionError("not a sleep control");}
                  void performClear(){throw new AssertionError("not a clear control");}
                  void startCompaction(boolean automatic){throw new AssertionError("not a compact control");}
                  AbstractClientPlayer resolveEntity(){return null;}
                  Object goal=new Object(); boolean goalJudging,throwBuild; int booked; String mode="none";
                  java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.api.NumenPlugins.DeliveredContext> goalPending;
                  static class Observation {final String id="goal-request";}
                  String runtimeStateXml(boolean publish,String origin,int attempt){if(throwBuild)throw new IllegalStateException("build failed");return "facts";}
                  Object captureDeliveryToken(){return new Object();}
                  Observation observeExecutionRequest(String phase,Object token,java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.api.NumenPlugins.DeliveredContext> pending){goalPending=pending;return new Observation();}
                  void publishPendingRuntimeState(Observation obs,Object token,java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.api.NumenPlugins.DeliveredContext> pending){pending.set(new com.dwinovo.numen.api.NumenPlugins.DeliveredContext());}
                  String sinceGoalForJudge(){return "";}
                  class Client {java.util.concurrent.CompletableFuture<Object> chatStreaming(List<?> m,List<?> t,String s,Object chunk,Observation o){
                    requests++; if(mode.equals("dispatch")||mode.equals("retry")){dispatch(o.id); if(mode.equals("retry"))dispatch(o.id);} else if(mode.equals("wrong"))dispatch("other");
                    return new java.util.concurrent.CompletableFuture<>();}}
                  Client client(){return new Client();}
                  void dispatch(String id){if("goal-request".equals(id)&&goalPending.getAndSet(null)!=null)booked++;}
                  void finishJudging(int gen,Object target,Object result,Throwable error){}
                  public static Map<String,Object> runGoal(String mode){WakeFixture f=new WakeFixture();f.mode=mode;f.denyContext=mode.equals("denied");f.throwBuild=mode.equals("build-fail");
                    try{f.judgeGoal();}catch(IllegalStateException expected){} return Map.of("requests",f.requests,"booked",f.booked,"refs",f.pendingDeliveredRefs.size());}
                  String contextBudgetProblem(){return denyContext ? "DENIED" : null;}
                  public static Map<String,Object> runRetryBudget(boolean denied) {
                    WakeFixture f=new WakeFixture(); f.denyContext=denied; f.awaitingLlmResponse=true; f.retryingTurn=true; f.retryBudgetGate();
                    return Map.of("requests",f.requests,"refs",f.pendingDeliveredRefs.size(),"voiceFinished",f.voiceFinished,
                        "awaiting",f.awaitingLlmResponse,"retrying",f.retryingTurn);
                  }
                  public static Map<String,Object> runGoalBudget(boolean denied) {
                    WakeFixture f=new WakeFixture(); f.denyContext=denied; f.goalBudgetGate();
                    return Map.of("requests",f.requests,"refs",f.pendingDeliveredRefs.size(),
                        "planCleared",f.pendingRuntimeStatePlan==null,"xmlCleared",f.pendingRuntimeStateXml==null);
                  }
                  String describeWindowReset(String role){return "later";}
                  METHODS
                  public static Map<String,Object> run(String scenario) {
                    BudgetPolicy.blocked=false;BudgetPolicy.checks=0;
                    Minecraft.connected=!scenario.equals("disconnected");
                    WakeFixture f=new WakeFixture();
                    f.convo.addUser("original request");
                    f.awaitingLlmResponse=true;
                    f.emptyReply();
                    String afterEmpty=f.turnPause.name();
                    if(!afterEmpty.equals(scenario.equals("disconnected")?"BLOCKED":"RECOVERABLE_FAILURE")||f.awaitingLlmResponse||!f.queue.isEmpty())
                      throw new AssertionError("empty completed response must finish and pause with empty queue");
                    boolean accepted=true;
                    switch(scenario) {
                      case "owner" -> f.turnPause=OwnerStopLatch.afterOwnerStop(f.turnPause);
                      case "blocked" -> f.turnPause=AgentTurnPause.BLOCKED;
                      case "llm" -> f.awaitingLlmResponse=true;
                      case "body" -> f.dispatcher.busy=true;
                      case "compact" -> f.compacting=true;
                      case "dead" -> f.dead=true;
                      case "external" -> f.external=true;
                      case "budget" -> BudgetPolicy.blocked=true;
                      case "ordinary" -> f.turnPause=AgentTurnPause.NONE;
                    }
                    if(scenario.equals("alarm")) f.pushEvent(EventTypes.EVENT,"danger",1L,true);
                    else if(scenario.equals("blank")) accepted=f.submitSystemPrompt(" ");
                    else accepted=f.submitSystemPrompt("supervision");
                    String queuedType=f.externalTap.isEmpty()?"":f.externalTap.peekLast().type();
                    int beforeTick=f.requests;
                    f.clientTick();f.clientTick();
                    int whileGuarded=f.requests;
                    int queuedWhileGuarded=f.queue.size();
                    int repeatedRequests=0,repeatedQueued=0;
                    if(scenario.equals("duplicates")) {
                      for(int i=0;i<3;i++) f.submitSystemPrompt("supervision again");
                      f.clientTick();f.clientTick();
                      repeatedRequests=f.requests;repeatedQueued=f.queue.size();
                      f.awaitingLlmResponse=false;f.clientTick();f.clientTick();
                    }
                    String whilePause=f.turnPause.name();
                    boolean owner=f.ownerSpokeThisTurn;
                    int budgetChecks=BudgetPolicy.checks;
                    if(List.of("llm","body","compact","dead","external").contains(scenario)) {
                      f.awaitingLlmResponse=false;f.dispatcher.busy=false;
                      f.compacting=false;f.dead=false;f.external=false;
                      f.clientTick();f.clientTick();
                    }
                    return Map.ofEntries(
                      Map.entry("afterEmpty",afterEmpty),Map.entry("accepted",accepted),
                      Map.entry("queuedType",queuedType),Map.entry("beforeTick",beforeTick),
                      Map.entry("whileGuarded",whileGuarded),Map.entry("requests",f.requests),
                      Map.entry("pause",whilePause),Map.entry("owner",owner),
                      Map.entry("budgetChecks",budgetChecks),Map.entry("retryAt",f.budgetRetryAtMs),
                      Map.entry("turnRetried",f.turnRetried),Map.entry("queuedWhileGuarded",queuedWhileGuarded),
                      Map.entry("repeatedRequests",repeatedRequests),Map.entry("repeatedQueued",repeatedQueued));
                  }
                }
                """.replace("METHODS", methods.toString()));
        List<String> args = new ArrayList<>(List.of("-d", build.toString()));
        for (var entry : sources.entrySet()) {
            Path file = build.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                args.toArray(String[]::new)), "mechanically extracted consumers must compile");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.client.agent.WakeFixture");
    }

    @AfterAll static void close() throws Exception {if (loader != null) loader.close();}

    @SuppressWarnings("unchecked")
    static Map<String, Object> run(String scenario) throws Exception {
        return (Map<String, Object>) fixture.getMethod("run", String.class).invoke(null, scenario);
    }

    @Test void realGoalMethodOnlyBooksAfterMatchingDispatch() throws Exception {
        for (String scenario : List.of("none", "wrong", "build-fail", "denied", "dispatch", "retry")) {
            @SuppressWarnings("unchecked") var result=(Map<String,Object>) fixture.getMethod("runGoal",String.class).invoke(null,scenario);
            assertEquals(scenario.equals("denied") || scenario.equals("build-fail") ? 0 : 1,result.get("requests"),scenario);
            assertEquals(scenario.equals("dispatch") || scenario.equals("retry") ? 1 : 0,result.get("booked"),scenario);
            if (scenario.equals("denied")) assertEquals(0,result.get("refs"));
        }
    }

    @Test void retryBudgetDenialDoesNotDispatchAndClearsState() throws Exception {
        @SuppressWarnings("unchecked") var denied=(Map<String,Object>) fixture.getMethod("runRetryBudget",boolean.class).invoke(null,true);
        @SuppressWarnings("unchecked") var ok=(Map<String,Object>) fixture.getMethod("runRetryBudget",boolean.class).invoke(null,false);
        assertEquals(0,denied.get("requests")); assertEquals(0,denied.get("refs"));
        assertEquals(1,denied.get("voiceFinished")); assertEquals(false,denied.get("awaiting")); assertEquals(false,denied.get("retrying"));
        assertEquals(1,ok.get("requests")); assertEquals(1,ok.get("refs"));
    }

    @Test void goalBudgetUsesProductionGuardAndClearsDeniedCandidate() throws Exception {
        @SuppressWarnings("unchecked") var denied = (Map<String,Object>) fixture.getMethod("runGoalBudget", boolean.class).invoke(null, true);
        @SuppressWarnings("unchecked") var ok = (Map<String,Object>) fixture.getMethod("runGoalBudget", boolean.class).invoke(null, false);
        assertEquals(0, denied.get("requests"));
        assertEquals(0, denied.get("refs"));
        assertEquals(true, denied.get("planCleared"));
        assertEquals(true, denied.get("xmlCleared"));
        assertEquals(1, ok.get("requests"));
        assertEquals(1, ok.get("refs"));
    }

    @Test void systemSupervisionResumesEmptyTurnFailure() throws Exception {
        var r = run("system");
        assertEquals("RECOVERABLE_FAILURE", r.get("afterEmpty"));
        assertEquals(true, r.get("accepted"));
        assertEquals("event", r.get("queuedType"));
        assertEquals(1, r.get("requests"), "one new request by the next available tick");
        assertEquals("NONE", r.get("pause"));
        assertEquals(false, r.get("owner"), "system input must retain EVENT identity");
        assertEquals(false, r.get("turnRetried"), "wake starts with a fresh retry allowance");
    }

    @Test void existingUrgentAlarmIsTheRecoveryControl() throws Exception {
        var r = run("alarm");
        assertEquals(1, r.get("requests"));
        assertEquals("NONE", r.get("pause"));
        assertEquals(false, r.get("owner"));
    }

    @Test void ownerStopAndConfigurationBlockStayLatched() throws Exception {
        for (String scenario : List.of("owner", "blocked")) {
            var r = run(scenario);
            assertEquals(0, r.get("requests"), scenario);
            assertEquals(scenario.equals("owner") ? "OWNER_INTERRUPT" : "BLOCKED", r.get("pause"));
        }
    }

    @Test void busyAndPausedConsumersWaitThenStartExactlyOnce() throws Exception {
        for (String scenario : List.of("llm", "body", "compact", "dead", "external")) {
            var r = run(scenario);
            assertEquals(0, r.get("whileGuarded"), scenario);
            assertEquals(1, r.get("queuedWhileGuarded"), scenario + " must keep the event buffered");
            assertEquals(1, r.get("requests"), scenario + " must consume after its guard clears");
        }
    }

    @Test void disconnectedResponseRemainsBlockedWhenSystemInputArrives() throws Exception {
        var r = run("disconnected");
        assertEquals("BLOCKED", r.get("afterEmpty"));
        assertEquals("BLOCKED", r.get("pause"));
        assertEquals(0, r.get("requests"));
    }

    @Test void repeatedUrgentSystemEventsNeverStartConcurrentRequests() throws Exception {
        var r = run("duplicates");
        assertEquals(1, r.get("whileGuarded"));
        assertEquals(1, r.get("repeatedRequests"), "new events cannot overlap the in-flight request");
        assertEquals(3, r.get("repeatedQueued"), "busy input stays buffered");
        assertEquals(2, r.get("requests"), "one subsequent request consumes the queued batch");
        assertEquals(false, r.get("owner"));
    }

    @Test void recoveryStillUsesTheOriginalBudgetGuardWithoutOwnerExemption() throws Exception {
        var r = run("budget");
        assertEquals(0, r.get("requests"));
        assertEquals("NONE", r.get("pause"), "recoverable failure clears before testing budget");
        assertEquals(false, r.get("owner"));
        assertTrue((int) r.get("budgetChecks") > 0, "must reach actual production budget guard");
        assertTrue((long) r.get("retryAt") > 0, "existing budget retry remains armed");
    }

    @Test void blankInputCannotWakeAndOrdinaryInputStartsOnlyOnce() throws Exception {
        var blank = run("blank");
        assertEquals(false, blank.get("accepted"));
        assertEquals("RECOVERABLE_FAILURE", blank.get("pause"));
        assertEquals(0, blank.get("requests"));
        var ordinary = run("ordinary");
        assertEquals(1, ordinary.get("requests"));
        assertEquals(false, ordinary.get("owner"));
    }

    static String between(String source, String from, String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "missing production start: " + from);
        int end = source.indexOf(until, start + from.length());
        assertTrue(end > start, "missing production end: " + until);
        return source.substring(start, end);
    }

    /** Source slices fail closed when signatures drift; method bodies remain verbatim. */
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing production method: " + signature);
        int open = source.indexOf('{', start), depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unterminated production method: " + signature);
    }
}
