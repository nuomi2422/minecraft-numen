package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.IToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4b 契约（IC09 + IC-75 §15.5）：预留真的接在调用点上——<b>请求在飞时额度已被占住，
 * 落地后按厂商回报的实际用量扣账，一次都没发出去就释放不记账。</b>
 *
 * <p>为什么值得用真 HTTP server 测：这三件事只有在「请求正在飞」的那一刻才看得见。
 * 用桩替掉传输层就只能测到「调了 reserve」，测不到「并发第二路看见的用量里有它」。
 */
class G4bReservationWiringTest {

    @TempDir
    Path configDir;

    private HttpServer server;
    private final List<JsonObject> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        BudgetPolicy.resetForTest();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        BudgetPolicy.resetForTest();
    }

    /** 写一份预算策略（角色限额 = limit 段原文）并安装。 */
    private void installPolicy(String body) throws Exception {
        Files.writeString(configDir.resolve("budget-policy.json"), """
                {
                  "version": 7,
                  "mode": "shadow",
                  "windowMinutes": 60,
                  "adviceCooldownMinutes": 5,
                  %s
                }
                """.formatted(body), StandardCharsets.UTF_8);
        BudgetPolicy.install(configDir);
        assertEquals(7, BudgetPolicy.policyForTest().version(), "策略没装上，后面的断言就没有意义");
    }

    private static IToolSpec tool() {
        return new IToolSpec() {
            public String name() { return "plan"; }
            public String description() { return "count"; }
            public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
        };
    }

    /**
     * 一个只在被放行后才回包的服务端 —— 让「请求在飞」这一段变成可断言的窗口。
     *
     * <p>只挡住<b>第一条</b>请求：本类有一条判据要「两路同时在飞」，两路都挡住就成死锁。
     * 必须用线程池 executor —— 默认的 HttpServer executor 是串行处理，第二路会排在第一路后面。
     */
    private void startBlockingServer(CountDownLatch arrived, CountDownLatch releaseIt) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "g4b-test-http");
            t.setDaemon(true);
            return t;
        }));
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            int n = seq.incrementAndGet();
            received.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            if (n == 1) {
                arrived.countDown();
                try {
                    releaseIt.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            String output = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4,\"total_tokens\":15}}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private NumenLlmClient client() {
        return NumenLlmClient.forEndpoint(new LlmEndpoint("openai", "local-test-model", "test-secret",
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "auto"));
    }

    private static List<Map<String, ?>> eventsOfType(List<LlmObservationEvent> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).map(LlmObservationEvent::data).toList();
    }

    private record LlmObservationEvent(String type, Map<String, ?> data) {}

    private static LlmObservation observing(List<LlmObservationEvent> sink, String phase) {
        return new LlmObservation("numen", "companion-1", phase,
                (type, data) -> sink.add(new LlmObservationEvent(type, data)));
    }

    @Test
    void reservationIsVisibleWhileTheRequestIsInFlightAndChargedWithActualTokens() throws Exception { // S85
        installPolicy("\"roles\": { \"execution\": { \"tokensPerWindow\": 1000000 } }");
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch releaseIt = new CountDownLatch(1);
        startBlockingServer(arrived, releaseIt);

        List<LlmObservationEvent> events = new CopyOnWriteArrayList<>();
        CompletableFuture<NumenLlmClient.ChatResult> future = client().chatStreaming(
                List.of(new ConvoState.Msg.User("在吗")), List.of(tool()), "system", null,
                observing(events, "execution"));

        assertTrue(arrived.await(10, TimeUnit.SECONDS), "请求没到服务端");
        assertEquals(1, BudgetPolicy.activeReservations("execution"),
                "请求在飞的时候额度必须已经占住，否则并发的第二路照样看得到「还没用」");
        assertEquals(0, BudgetPolicy.windowUsage("execution").calls(), "预留不产生真实调用记录");
        assertEquals(1, BudgetPolicy.windowUsageWithReservations("execution").calls());
        List<Map<String, ?>> reserves = eventsOfType(events, "budget_reserve");
        assertEquals(1, reserves.size(), "granted 的预留必须有一条事件：" + events);
        assertEquals("granted", reserves.get(0).get("status"));
        assertEquals("execution", reserves.get(0).get("role"));
        assertEquals("execution", BudgetPolicy.roleOf("execution"));

        releaseIt.countDown();
        var result = future.get(10, TimeUnit.SECONDS);
        assertEquals("好", result.turn().content());

        assertEquals(0, BudgetPolicy.activeReservations("execution"), "扣账后预留必须消失");
        assertEquals(1, BudgetPolicy.windowUsage("execution").calls());
        assertEquals(15L, BudgetPolicy.windowUsage("execution").tokens(), "扣账用的是厂商回报的实际值，不是估算");

        List<Map<String, ?>> settles = eventsOfType(events, "budget_settle");
        assertEquals(1, settles.size(), "扣账必须留下一条事件：" + events);
        assertEquals("charged", settles.get(0).get("status"));
        assertEquals(15L, settles.get(0).get("actualTokens"));
        assertTrue((Long) settles.get(0).get("estimatedTokens") > 0L, "估算要能被看见，才解释得了「占了多少」");
        assertEquals(1L, events.stream().filter(e -> e.type().equals("llm_usage")).count(),
                "llm_usage 照旧一条：接线不许改变既有埋点");
    }

    @Test
    void noLimitMeansNoReservationAndNoNewEvents() throws Exception { // S87
        installPolicy("\"roles\": { \"execution\": { \"tokensPerWindow\": 0, \"callsPerWindow\": 0 } }");
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch releaseIt = new CountDownLatch(1);
        releaseIt.countDown();
        startBlockingServer(arrived, releaseIt);

        List<LlmObservationEvent> events = new CopyOnWriteArrayList<>();
        client().chatStreaming(List.of(new ConvoState.Msg.User("在吗")), List.of(), "system", null,
                observing(events, "execution")).get(10, TimeUnit.SECONDS);

        assertEquals(0, BudgetPolicy.activeReservations("execution"));
        assertTrue(eventsOfType(events, "budget_reserve").isEmpty(), "没配限额就不该有预留事件");
        assertTrue(eventsOfType(events, "budget_settle").isEmpty(), "没配限额就不该有扣账事件");
        assertEquals(1, BudgetPolicy.windowUsage("execution").calls(), "既有计量照旧：直接记一笔");
        assertEquals(15L, BudgetPolicy.windowUsage("execution").tokens());
    }

    @Test
    void expiredReservationStillRecordsTheCallInsteadOfLosingIt() throws Exception { // S85 续：过期不吞账
        installPolicy("\"roles\": { \"execution\": { \"tokensPerWindow\": 1000000 } }");
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch releaseIt = new CountDownLatch(1);
        startBlockingServer(arrived, releaseIt);

        List<LlmObservationEvent> events = new CopyOnWriteArrayList<>();
        CompletableFuture<NumenLlmClient.ChatResult> future = client().chatStreaming(
                List.of(new ConvoState.Msg.User("在吗")), List.of(), "system", null,
                observing(events, "execution"));
        assertTrue(arrived.await(10, TimeUnit.SECONDS));

        // 时钟往前推过 TTL（5 分钟）：预留按 TTL 失效，扣账将失败 —— 但这一笔调用真的发生了，
        // 不能因为没有预留就丢掉这次用量。
        long before = System.currentTimeMillis();
        BudgetPolicy.setClockForTest(() -> before + BudgetPolicy.DEFAULT_RESERVATION_TTL_MS + 60_000L);
        releaseIt.countDown();
        future.get(10, TimeUnit.SECONDS);

        assertEquals(0, BudgetPolicy.activeReservations("execution"));
        assertEquals(1, BudgetPolicy.windowUsage("execution").calls(), "过期预留不许吃掉这笔真实调用");
        assertEquals(15L, BudgetPolicy.windowUsage("execution").tokens());
        assertEquals("charged_after_expiry",
                eventsOfType(events, "budget_settle").get(0).get("status"),
                "退路要写明白：过期后直接记账，状态可核");
    }

    @Test
    void reserveIsAllOrNothingAcrossTwoConcurrentRequests() throws Exception { // IC09 的调用点版本
        // 窗口只够一笔：第一笔发出后（预留占住），第二笔的 reserve 必须失败。
        installPolicy("\"roles\": { \"execution\": { \"callsPerWindow\": 1 } }");
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch releaseIt = new CountDownLatch(1);
        startBlockingServer(arrived, releaseIt);

        List<LlmObservationEvent> events = new CopyOnWriteArrayList<>();
        CompletableFuture<NumenLlmClient.ChatResult> first = client().chatStreaming(
                List.of(new ConvoState.Msg.User("第一次")), List.of(), "system", null,
                observing(events, "execution"));
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        assertEquals(1, BudgetPolicy.activeReservations("execution"));

        // 第二路：预留占不到（额度已被在飞的那笔吃掉）→ 事件里必须看得见 rejected。
        client().chatStreaming(List.of(new ConvoState.Msg.User("第二次")), List.of(), "system", null,
                observing(events, "execution")).get(10, TimeUnit.SECONDS);

        List<Map<String, ?>> reserves = eventsOfType(events, "budget_reserve");
        assertEquals(2, reserves.size(), "两条请求都要留下预留痕迹：" + events);
        assertEquals("granted", reserves.get(0).get("status"));
        assertEquals("rejected", reserves.get(1).get("status"),
                "额度已被在飞的那笔占住 —— 这正是旧 record 记账看不到的并发超支（IC09）");
        assertNull(reserves.get(1).get("reservationId"), "没占到的没有预留号");

        releaseIt.countDown();
        first.get(10, TimeUnit.SECONDS);
    }
}
