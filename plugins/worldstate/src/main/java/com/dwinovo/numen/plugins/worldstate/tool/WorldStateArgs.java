package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.google.gson.JsonObject;

/**
 * `world_state` 的**参数解析与报错**（042 R1-3/R1-5，纯函数、无 MC 依赖）。
 *
 * <p>为什么抽出来：onServerCall 需要 NumenPlayer（离线起不来），于是"schema 公布的样子"
 * 和"实际解析出的请求/报错"之间一直没人测。这里把那段逻辑挪成纯函数，
 * 测试可以拿 JSON 直接走一遍**公布 schema 允许的输入**，断言它落到正确的请求或明确的报错。
 *
 * <p>它不校验"模型会不会按 schema 传"——那要靠 schema 本身；这里校验的是
 * **传进来之后我们是否理解得对、错的时候说的是不是人话**。
 */
public final class WorldStateArgs {

    private WorldStateArgs() {}

    /** 解析结果：要么是请求，要么是一句能直接回给模型的错误。 */
    public record Outcome(ObservationRequest request, String error) {
        public static Outcome ok(ObservationRequest request) {
            return new Outcome(request, null);
        }

        public static Outcome fail(String error) {
            return new Outcome(null, error);
        }

        public boolean ok() {
            return request != null;
        }
    }

    /** action=read|refresh 的解析（topic 已在调用方解析成枚举）。 */
    public static Outcome forReadRefresh(Topic topic, JsonObject args, String dimension) {
        if (topic == null) {
            return Outcome.fail("world_state: topic is required for action=read|refresh"
                    + " (environment|entities|blocks|containers|self; facilities is defined but not wired yet)");
        }
        ObservationRequest request = buildRequest(args, topic, dimension);
        if (request == null) {
            return Outcome.fail(missingFieldMessage(topic));
        }
        return Outcome.ok(request);
    }

    /** 缺字段的明确报错（042 R1-3：非法 selector / 缺字段要能一眼看出来）。 */
    public static String missingFieldMessage(Topic topic) {
        return switch (topic) {
            case SELF -> "world_state: self needs target=player:<uuid> (got empty or invalid)";
            case CONTAINERS -> "world_state: containers needs container=block:x,y,z or player:<uuid> (got empty)";
            case ENTITIES, BLOCKS -> "world_state: " + topic.id()
                    + " needs a box: x1,y1,z1,x2,y2,z2 (got empty or invalid)";
            default -> "world_state: invalid parameters for topic " + topic.id();
        };
    }

    /** 与工具处理器同口径的请求构造；返回 null 表示这个主题的参数不完整。 */
    public static ObservationRequest buildRequest(JsonObject args, Topic topic, String dimension) {
        return switch (topic) {
            case SELF -> {
                try { yield new ObservationRequest(Topic.SELF, dimension, optionalString(args, "target"), "", null); }
                catch (RuntimeException e) { yield null; }
            }
            case ENVIRONMENT -> ObservationRequest.environment(dimension);
            case ENTITIES -> {
                Bounds box = boundsFrom(args);
                if (box == null) yield null;
                yield ObservationRequest.entities(dimension, box,
                        optionalString(args, "entity_type"), optionalString(args, "category"));
            }
            case BLOCKS -> {
                Bounds box = boundsFrom(args);
                if (box == null) yield null;
                int samples = 0;
                if (args.has("samples") && !args.get("samples").isJsonNull()) {
                    try {
                        samples = Math.max(0, Math.min(32, args.get("samples").getAsInt()));
                    } catch (RuntimeException ignored) {
                        samples = 0;
                    }
                }
                yield ObservationRequest.blocks(dimension, box, optionalString(args, "block"), samples);
            }
            case CONTAINERS -> {
                String target = optionalString(args, "container");
                if (target == null || target.isBlank()) yield null;
                try {
                    yield ObservationRequest.containers(dimension, target);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    /** 2026-11-06: SELF alone is caller-bound; do not silently change legacy four-topic permissions. */
    public static void requireOwnSelf(ObservationRequest request, String companionId, String dimension) {
        if (request.topic() != Topic.SELF) return;
        if (companionId == null || !request.target().equals("player:" + companionId)
                || !request.dimension().equals(dimension))
            throw new IllegalArgumentException("DENIED: self requires current companion and dimension");
    }

    /** x1..z2 六个数都在就构成范围；缺一个就 null（不猜默认值）。 */
    static Bounds boundsFrom(JsonObject args) {
        Integer x1 = optionalInt(args, "x1");
        Integer y1 = optionalInt(args, "y1");
        Integer z1 = optionalInt(args, "z1");
        Integer x2 = optionalInt(args, "x2");
        Integer y2 = optionalInt(args, "y2");
        Integer z2 = optionalInt(args, "z2");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            return null;
        }
        return new Bounds(x1, y1, z1, x2, y2, z2);
    }

    private static String optionalString(JsonObject args, String name) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) return null;
        String v = args.get(name).getAsString();
        return v == null || v.isBlank() ? null : v;
    }

    private static Integer optionalInt(JsonObject args, String name) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) return null;
        try {
            return args.get(name).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
