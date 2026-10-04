package com.dwinovo.numen.client.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令层的解析与补全。这几条都不碰同伴({@code loop} 传 null),因为它们本来就不该碰——
 * 认不认识一条命令、怎么拆参数,跟她在干什么无关。
 */
class ChatCommandsTest {

    @Test
    void onlyASlashMakesItACommand() {
        assertTrue(ChatCommands.isCommand("/skills"));
        assertTrue(ChatCommands.isCommand("   /skills  "), "两边的空白不该影响判断");
        assertFalse(ChatCommands.isCommand("skills"));
        assertFalse(ChatCommands.isCommand("你好 /skills"), "斜杠得在开头");
        assertTrue(ChatCommands.isCommand("/"),
                "光一个斜杠也算 —— 主人打了斜杠就是要用命令,该给他看清单,"
                        + "而不是把裸斜杠当聊天发给她");
        assertFalse(ChatCommands.isCommand(null));
    }

    @Test
    void parseSplitsTheNameFromTheRest() {
        ChatCommands.Parsed p = ChatCommands.parse("/build 在河边盖个木屋");
        assertEquals("build", p.name());
        assertEquals("在河边盖个木屋", p.args());
    }

    @Test
    void aBareCommandHasEmptyArgsNotNull() {
        assertEquals("", ChatCommands.parse("/skills").args());
        assertEquals("", ChatCommands.parse("/skills   ").args());
    }

    @Test
    void argumentsKeepTheirOwnInnerSpacing() {
        assertEquals("盖 一间  木屋", ChatCommands.parse("/build   盖 一间  木屋 ").args());
    }

    /**
     * 不认得的 {@code /}命令：<b>转给服务器，但绝不当成聊天发出去</b>。
     *
     * <p>2026-10-04 改了机制（原来直接报错「没有这条命令」）：本类在客户端拦下所有
     * {@code /} 输入，不回落就等于把 {@code /give}、{@code /time set}、{@code /tp}
     * 这些原版命令全挡在门外 —— 实测确认从游戏外没有任何别的入口能布置夹具。
     *
     * <p><b>本测试守的不变式没变</b>：打错命令不能变成一句话发给模型。
     * 现在多了一条：它会被当作命令转给服务器，由服务器决定认不认。
     * 单测里没有客户端，所以「转出去」这一步是 no-op，回话必须仍然点名那条命令。
     */
    @Test
    void unknownCommandIsRefusedRatherThanSentAsChat() {
        String reply = ChatCommands.dispatch(null, "/nosuchthing");
        assertNotNull(reply);
        assertTrue(reply.contains("/nosuchthing"), reply);
        // 仍然必须点名那条命令：不能变成「好的」然后把整句当聊天发出去。
        // 无论它是"没有这条命令"还是"已按原版命令转给服务器"，都必须让人看得出发生了什么。
    }

    /**
     * 反证：<b>非 {@code /} 开头的输入绝不会被当成命令</b>。
     *
     * <p>这条与上面那条是一对：上面钉「不认识的 /命令不变成聊天」，
     * 这条钉「不是命令的东西不许被当成命令发出去」——
     * 两边都守住，才不会出现「打错一句话就去执行了」。
     */
    @Test
    void ordinarySpeechIsNeverTreatedAsACommand() {
        assertNull(ChatCommands.dispatchIfCommand(null, "把田里的麦子收了"),
                "普通中文不是命令，必须返回 null 让它走「主人说话」那条路");
        assertNull(ChatCommands.dispatchIfCommand(null, "/"),
                "只有一个斜杠不算命令名");
    }


    @Test
    void theBuiltinListingShowsUpWhenYouTypeJustASlash() {
        List<Completion> rows = ChatCommands.complete(null, "/");
        assertTrue(rows.stream().anyMatch(r -> r.label().equals("/skills")), rows.toString());
    }

    @Test
    void completionFiltersByPrefixAndIsCaseBlind() {
        assertFalse(ChatCommands.complete(null, "/SKI").isEmpty());
        assertTrue(ChatCommands.complete(null, "/zzz").isEmpty());
    }

    @Test
    void aTrailingSpaceMeansTheNameIsDoneAndArgsBegin() {
        // "/skills" 还在打名字 → 列命令;"/skills " 名字打完了 → 交给这条命令补参数
        // (它不吃参数,所以是空)。右边那个空格是唯一的信号,不能被 strip 掉。
        assertFalse(ChatCommands.complete(null, "/skills").isEmpty());
        assertTrue(ChatCommands.complete(null, "/skills ").isEmpty());
    }

    @Test
    void completionForACommandWithoutArgumentsDoesNotAppendASpace() {
        Completion skills = ChatCommands.complete(null, "/skills").get(0);
        assertEquals("/skills", skills.insert(), "不吃参数的命令补完就该能直接回车");
    }

    @Test
    void nonCommandTextHasNoCompletions() {
        assertTrue(ChatCommands.complete(null, "你好").isEmpty());
        assertTrue(ChatCommands.complete(null, "").isEmpty());
    }

    @Test
    void recentlyUsedCommandsFloatToTheTop() {
        // 打一个 / 直接回车会落在第一条上,所以第一条必须是主人刚用过的那条,
        // 而不是碰巧排在前面的那条 —— 否则手滑就执行了别的命令。
        ChatCommands.remember("skills");
        assertEquals("/skills", ChatCommands.complete(null, "/").get(0).label());
        ChatCommands.remember("compact");
        assertEquals("/compact", ChatCommands.complete(null, "/").get(0).label());
        ChatCommands.remember("skills");
        assertEquals("/skills", ChatCommands.complete(null, "/").get(0).label());
    }

    @Test
    void rememberingTheSameCommandTwiceDoesNotDuplicateIt() {
        ChatCommands.remember("skills");
        ChatCommands.remember("skills");
        List<Completion> rows = ChatCommands.complete(null, "/");
        assertEquals(1, rows.stream().filter(r -> r.label().equals("/skills")).count());
    }

    @Test
    void blankNamesAreNotRemembered() {
        ChatCommands.remember(null);
        ChatCommands.remember("  ");
        assertFalse(ChatCommands.complete(null, "/").isEmpty());
    }
}
