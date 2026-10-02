package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 把 {@code ExperienceDraft.ENTRY_KEYS} 与<b>真实的</b> {@code ExperienceEntry.toJson()} 绑在一起。
 *
 * <p><b>为什么必须读源文件而不是各写一份列表</b>（Codex 审核 P1-1 之后才补的）：
 * {@code plugins/learner} <b>刻意不依赖</b> {@code experience-core} —— 两个 mod
 * 各带一份同名类会出 split-package / JPMS 问题，所以两边没有编译期绑定。
 * 只靠自己抄的 {@code ENTRY_KEYS} 跟自己抄的草稿做断言 = <b>自己验自己</b>，
 * 那边改键名这边不会红。</p>
 *
 * <p>所以这里直接读 {@code ExperienceEntry.java} 的源，把 {@code toJson()} 方法体里
 * {@code addProperty("x", …)} / {@code add("x", …)} 的键抠出来，做<b>集合相等</b>断言
 * （相等，不是包含 —— 包含会让多出来的键永远查不出来）。
 * 抠不出来（方法体改名/重构）时<b>失败</b>，不跳过 —— 跳过等于这道门形同虚设。</p>
 */
class ExperienceDraftKeysBindToRealEntryTest {

    private static final Path ENTRY_SOURCE = Paths.get(
            "..", "..", "experience-core", "src", "main", "java",
            "com", "dwinovo", "numen", "experience", "api", "ExperienceEntry.java");

    /** 从源码里抠 {@code toJson()} 的方法体。 */
    private static String toJsonBody(String src) {
        int sig = src.indexOf("public JsonObject toJson()");
        if (sig < 0) {
            fail("ExperienceEntry 里找不到 public JsonObject toJson() —— 源文件结构变了，"
                    + "这道门必须跟着改，不能悄悄放过");
        }
        int open = src.indexOf('{', sig);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, i);
                }
            }
        }
        return fail("toJson() 的方法体括号不配平 —— 抠不出来，这道门失效了");
    }

    /** 抠出 {@code addProperty("k"} 与 {@code add("k"} 的全部键。 */
    private static Set<String> keysInToJson() throws IOException {
        Path abs = ENTRY_SOURCE.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            return fail("找不到 ExperienceEntry 源文件：" + abs
                    + " —— 说明测试的工作目录假设变了，必须修，不能跳过");
        }
        String src = Files.readString(abs, StandardCharsets.UTF_8);
        String body = toJsonBody(src);
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("add(?:Property)?\\(\\s*\"([a-z_]+)\"").matcher(body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertTrue(keys.size() >= 10, "只抠到 " + keys.size() + " 个键，正则多半失效了：" + keys);
        return keys;
    }

    @Test
    void entryKeysMatchTheRealExperienceEntryToJson() throws Exception {
        Set<String> real = keysInToJson();
        // toJson() 额外写 v / id —— 那是记录层的序列化头，不属于「草稿能填的字段」
        real.remove("v");
        real.remove("id");

        List<String> declared = new ArrayList<>(ExperienceDraft.ENTRY_KEYS);
        List<String> actual = new ArrayList<>(real);

        assertEquals(actual.size(), declared.size(),
                "键数量不一致 —— 经验库加了字段而学习者没跟（或反之）。"
                        + "\n  真实 toJson(): " + actual + "\n  ENTRY_KEYS   : " + declared);
        for (String k : actual) {
            assertTrue(declared.contains(k),
                    "经验库的 toJson() 有键 " + k + "，ENTRY_KEYS 里没有 ⇒ "
                            + "学习者产的草稿可能漏填它，且没人会发现");
        }
        for (String k : declared) {
            assertTrue(actual.contains(k),
                    "ENTRY_KEYS 声明了 " + k + "，但 ExperienceEntry.toJson() 并不写它 ⇒ 写进去也读不出来");
        }
    }

    /** 反向：映射承诺会写的键，必须都是真实存在的键。 */
    @Test
    void writtenKeysAreAllRealEntryKeys() throws Exception {
        for (String k : ExperienceDraft.WRITTEN_KEYS) {
            assertTrue(ExperienceDraft.ENTRY_KEYS.contains(k),
                    "WRITTEN_KEYS 里的 " + k + " 不在 ENTRY_KEYS 里，两处自相矛盾");
        }
    }
}