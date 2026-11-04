package com.dwinovo.numen.plugins.worldstate.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R1：把 `world_state` **公布出去的 schema 形状**钉住。
 *
 * <p>为什么断言"形状"而不是"字段存在"：字段存在只证明我写了这行，形状才决定模型看到什么。
 * 尤其 `required`——它现在是 `[action, topic]`，等 R3 把 topic 改成 optional 时这条会红，
 * **那是故意的**：强制一次有意识的修改，而不是让它悄悄漂。
 */
class WorldStateSchemaTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root() {
        return WorldStateSchema.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props() {
        return (Map<String, Object>) root().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required() {
        return (List<String>) root().get("required");
    }

    @Test
    void requiredIsExactlyActionAndTopic() {
        assertEquals(Set.of("action", "topic"), Set.copyOf(required()),
                "当前契约：action 与 topic 必填；R3 把 topic 改 optional 时这条应红");
        // 审计 nit：Set 比较会吞掉重复项，加一条长度断言兜住
        assertEquals(required().size(), Set.copyOf(required()).size(), "required 里不该有重复项");
    }

    @Test
    void containerIsPublishedButOptional() {
        Map<String, Object> container = (Map<String, Object>) props().get("container");
        assertTrue(container != null, "container 必须进 properties（042 R1 的核心）");
        assertFalse(required().contains("container"), "container 不该是必填——environment 等主题用不到它");
        String desc = String.valueOf(container.get("description"));
        assertTrue(desc.contains("block:"), "说明要写清 block:x,y,z 的形式，实际：" + desc);
        assertTrue(desc.contains("player:"), "说明要写清 player:<uuid> 的形式，实际：" + desc);
    }

    @Test
    void optionalFieldsAreNotRequired() {
        for (String f : List.of("container", "after", "limit", "topics", "dimension",
                "x1", "y1", "z1", "x2", "y2", "z2", "entity_type", "category", "block", "samples")) {
            assertTrue(props().containsKey(f), "properties 缺字段：" + f);
            assertFalse(required().contains(f), "字段不该必填：" + f);
        }
    }

    @Test
    void afterDescriptionDoesNotClaimRevision() {
        Map<String, Object> after = (Map<String, Object>) props().get("after");
        String desc = String.valueOf(after.get("description"));
        assertFalse(desc.toLowerCase().contains("revision"),
                "R3-2 要拆 revision/streamSeq，说明先写死 revision 会骗人：" + desc);
    }

    @Test
    void actionEnumContainsChanges() {
        Map<String, Object> action = (Map<String, Object>) props().get("action");
        List<String> values = (List<String>) action.get("enum");
        assertTrue(values.contains("changes"));
        assertTrue(values.contains("read"));
        assertTrue(values.contains("refresh"));
    }

    @Test
    void topicEnumContainsContainers() {
        Map<String, Object> topic = (Map<String, Object>) props().get("topic");
        List<String> values = (List<String>) topic.get("enum");
        assertEquals(Set.of("environment", "entities", "blocks", "containers"), Set.copyOf(values));
    }

    @Test
    void additionalPropertiesStaysClosed() {
        assertEquals(Boolean.FALSE, root().get("additionalProperties"),
                "多余字段保持拒绝（042 R1 的反例之一）");
    }
}
