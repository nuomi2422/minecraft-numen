package com.dwinovo.numen.agent.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07: real 429 retry path emits throttled events and eventually succeeds. */
class HttpLlmTransportRetryTest {
    @Test void two429ThenSuccessReportsThrottleEvents() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat", ex -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                byte[] body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Retry-After", "1");
                ex.sendResponseHeaders(429, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            List<String> seen = new ArrayList<>();
            HttpLlmTransport transport = new HttpLlmTransport(null, Map.of());
            JsonObject body = new JsonObject();
            body.addProperty("model", "test");
            var future = transport.postSse("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat",
                    "k", body, chunk -> {}, null, Map.of(), new HttpLlmTransport.RetryListener() {
                        @Override public void onRetry(int code, int attempt, long delayMs, long nextAtMillis) {
                            seen.add("retry:" + code + ":" + attempt);
                        }
                        @Override public void onGiveUp(int code, int attempts) {
                            seen.add("giveup:" + code + ":" + attempts);
                        }
                    });
            long t0 = System.nanoTime();
            future.get(15, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(3, calls.get());
            assertEquals(2, seen.stream().filter(x -> x.startsWith("retry:429")).count(), seen.toString());
            assertTrue(elapsedMs <= 300_000L, "总耗时不超 5 分钟封顶");
            assertFalse(seen.stream().anyMatch(s -> s.startsWith("giveup")), seen.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test void always429GivesUpWithAvailabilityEvent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat", ex -> {
            byte[] body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Retry-After", "1");
            ex.sendResponseHeaders(429, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            List<String> seen = new ArrayList<>();
            HttpLlmTransport transport = new HttpLlmTransport(null, Map.of());
            JsonObject body = new JsonObject();
            body.addProperty("model", "test");
            var future = transport.postSse("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat",
                    "k", body, chunk -> {}, null, Map.of(), new HttpLlmTransport.RetryListener() {
                        @Override public void onRetry(int code, int attempt, long delayMs, long nextAtMillis) {
                            seen.add("retry:" + code);
                        }
                        @Override public void onGiveUp(int code, int attempts) {
                            seen.add("giveup:" + code + ":" + attempts);
                        }
                    });
            assertThrows(Exception.class, () -> future.get(30, TimeUnit.SECONDS));
            assertTrue(seen.stream().anyMatch(x -> x.startsWith("giveup:429")), seen.toString());
        } finally {
            server.stop(0);
        }
    }
}
