package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.experience.api.PublicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 C0: committed != stored; IO failure must roll back and be visible; retry idempotent. */
class ExperienceStorePersistFailureTest {

    @TempDir Path dir;

    private static ExperienceEntry entry(String title, String description) {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title(title).description(description)
                .mechanism("m").build();
    }

    @Test
    void ioFailureRollsBackAndThrowsInsteadOfReturningStored() throws Exception {
        Path file = dir.resolve("exp.jsonl");
        ExperienceStore s = ExperienceStore.at(file);
        ExperienceEntry first = s.learn(entry("写盘失败要回滚", "第一版"));
        assertEquals(1, s.all().size());

        Files.delete(file);
        Files.createDirectory(file); // move(temp -> file) 必抛 IOException

        ExperienceEntry e2 = entry("第二条不该假成功", "第二版");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> s.learn(e2));
        assertTrue(ex.getMessage().contains("NOT stored"), ex.getMessage());
        assertEquals(List.of(first.id()), s.all().stream().map(ExperienceEntry::id).toList(),
                "失败后 mirror 必须回滚，不能保留第二条");

        Files.delete(file); // 移除目录，恢复正常路径
        ExperienceEntry retried = s.learn(entry("第二条不该假成功", "第二版"));
        assertNotNull(retried);
        assertEquals(2, s.all().size());
        assertEquals(2, ExperienceStore.at(file).all().size(), "重试后落盘条数必须正确");
    }

    @Test
    void retryAfterFailureOfSameCanonicalIdWritesOnlyOnce() throws Exception {
        Path file = dir.resolve("exp2.jsonl");
        ExperienceStore s = ExperienceStore.at(file);
        ExperienceEntry first = s.learn(entry("同一命题", "甲"));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IllegalStateException.class, () -> s.learn(entry("同一命题", "乙")));
        Files.delete(file);

        ExperienceEntry retried = s.learn(entry("同一命题", "乙"));
        assertEquals(first.id(), retried.id(), "同 canonical id 重试必须归并");
        assertEquals(1, ExperienceStore.at(file).all().size(), "重试不得产生重复条目");
    }

    private static void poison(Path file) throws Exception {
        Files.delete(file);
        Files.createDirectory(file);
    }

    private static void heal(Path file) throws Exception {
        Files.delete(file);
    }

    @Test
    void retractFailureRollsBackAndIsVisible() throws Exception {
        Path file = dir.resolve("retract.jsonl");
        ExperienceStore s = ExperienceStore.at(file);
        ExperienceEntry e = s.learn(entry("撤回也要真落盘", "甲"));
        poison(file);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.retract(e.id(), "证据不足"));
        assertTrue(ex.getMessage().contains("retract") && ex.getMessage().contains("NOT stored"), ex.getMessage());
        assertFalse(s.all().get(0).retracted(), "失败后内存必须回滚成未撤回");
        heal(file);
        assertTrue(s.retract(e.id(), "证据不足").retracted(), "恢复后重试成功");
        assertTrue(ExperienceStore.at(file).all().get(0).retracted(), "重试后确实落盘");
    }

    @Test
    void publishFailureRollsBackAndIsVisible() throws Exception {
        Path file = dir.resolve("publish.jsonl");
        ExperienceStore s = ExperienceStore.at(file);
        ExperienceEntry e = s.learn(entry("发布也要真落盘", "甲"));
        PublicationState original = e.publication();
        PublicationState target = PublicationState.SUSPENDED;
        assertTrue(original.canTransitionTo(target), "测试前提：原状态可迁到目标");
        poison(file);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.publish(e.id(), target, "test"));
        assertTrue(ex.getMessage().contains("publish") && ex.getMessage().contains("NOT stored"), ex.getMessage());
        assertEquals(original, s.all().get(0).publication(), "失败后发布状态必须回滚");
        heal(file);
        assertTrue(s.publish(e.id(), target, "test").ok());
        assertEquals(target, ExperienceStore.at(file).all().get(0).publication(), "重试后确实落盘");
    }
}
