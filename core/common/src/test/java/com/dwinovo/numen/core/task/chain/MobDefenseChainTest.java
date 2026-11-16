package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDefenseChainTest {

    @Test
    void reflexFightHasAStrictBoundedOwnershipWindow() {
        long started = 4_000L;
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS - 1));
        assertTrue(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS));
    }

    @Test
    void tickScansOnceBeforeFightButStartLogKeepsFreshObservation() throws Exception {
        var resource = MobDefenseChain.class.getResourceAsStream("MobDefenseChain.class");
        assertTrue(resource != null);
        byte[] bytes;
        try (resource) { bytes = resource.readAllBytes(); }
        var reader = new org.objectweb.asm.ClassReader(bytes);
        int[] scans = new int[3];
        reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                int slot = name.equals("tick") ? 0 : name.equals("canRun") ? 1 : name.equals("begin") ? 2 : -1;
                if (slot < 0) return null;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String descriptor, boolean isInterface) {
                        if (method.equals("dangersNear") && owner.endsWith("/MobDefenseChain")) scans[slot]++;
                    }
                };
            }
        }, 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{1, 1, 1}, scans);
    }

    @Test
    void unstartedOrFutureFightNeverTimesOut() {
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(Long.MIN_VALUE, 10_000L));
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(10_000L, 9_999L));
    }
}
