package com.dwinovo.numen.core.task.combat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level contract: fleeing may take one eligible melee hit without changing escape input. */
class AttackCompanionTaskFleeTest {

    private static String source;

    @BeforeAll
    static void readProductionSource() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "cannot locate production source tree");
        source = Files.readString(root.resolve(
                "core/common/src/main/java/com/dwinovo/numen/core/task/combat/AttackCompanionTask.java"));
    }

    @Test
    void onTickPassesDisengageAsFleeingToWeaponTick() {
        String onTick = method(source, "protected TaskState onTick()");
        assertTrue(onTick.contains(
                "boolean fleeing = move.action() == AttackPlan.Action.DISENGAGE;"));
        assertTrue(onTick.contains("tickWeapon(field, fleeing);"));
        assertFalse(onTick.contains("tickWeapon(field);"), "old unconditional weapon tick");
    }

    @Test
    void weaponTickKeepsTheExistingEligibilityChecksAndAddsNoPursuit() {
        String weapon = method(source, "private void tickWeapon(Battlefield field, boolean fleeing)");
        assertTrue(weapon.contains("for (var f : field.foes())"), "only use the surveyed field");
        assertTrue(weapon.contains("if (f.armed() || f.distance() >= best)"));
        assertTrue(weapon.contains("f.distance() <= Swing.reachTo("));
        assertTrue(weapon.contains("victim instanceof LivingEntity hurt && hurt.hurtTime > 0"));
        assertTrue(weapon.contains("Swing.mayStrike(weaponChanged,"));
        assertTrue(weapon.contains("player.getAttackStrengthScale(0.0f)"));
        for (String forbidden : new String[] {
                "PlayerNav", "nav.", "Menace.", "stepToward", "InputDriver.halt",
                "target =", "shootAt(", "Loadout.forTarget(player, target)"
        }) {
            assertFalse(weapon.contains(forbidden), "weapon tick must not pursue or navigate: " + forbidden);
        }
    }

    @Test
    void fleeingAttackPreservesSprintAndMovementWithoutTurning() {
        String weapon = method(source, "private void tickWeapon(Battlefield field, boolean fleeing)");
        String fleeing = block(weapon, "if (fleeing)");
        assertFalse(fleeing.contains("InputDriver.lookAt"), "turning changes travel direction");
        assertFalse(fleeing.contains("setSprinting(false)"), "escape must not cancel sprint");
        assertFalse(fleeing.contains("InputDriver.halt"));
        assertOrdered(fleeing,
                "float escapeXxa = player.xxa;",
                "float escapeZza = player.zza;",
                "boolean escapeSprinting = player.isSprinting();",
                "player.attack(victim);",
                "player.xxa = escapeXxa;",
                "player.zza = escapeZza;",
                "player.setSprinting(escapeSprinting);");
    }

    @Test
    void ordinaryAttackStillTurnsAndCancelsSprintForCriticalHits() {
        String weapon = method(source, "private void tickWeapon(Battlefield field, boolean fleeing)");
        String ordinary = block(weapon, "else");
        assertOrdered(ordinary,
                "InputDriver.lookAt(player, victim.getEyePosition());",
                "player.setSprinting(false);",
                "player.attack(victim);");
    }

    private static void assertOrdered(String body, String... statements) {
        int previous = -1;
        for (String statement : statements) {
            int at = body.indexOf(statement);
            assertTrue(at > previous, "missing or out of order: " + statement + " in " + body);
            previous = at;
        }
    }

    private static String method(String source, String signature) {
        String code = codeOnly(source);
        int declaration = code.indexOf(signature);
        assertTrue(declaration >= 0, "missing method: " + signature);
        return normalize(body(code, declaration));
    }

    private static String block(String code, String marker) {
        int markerAt = code.indexOf(marker);
        assertTrue(markerAt >= 0, "missing block: " + marker);
        return normalize(body(code, markerAt));
    }

    private static String body(String code, int from) {
        int open = code.indexOf('{', from);
        assertTrue(open >= 0, "missing opening brace");
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            if (code.charAt(i) == '}' && --depth == 0) return code.substring(open + 1, i);
        }
        throw new AssertionError("unclosed block");
    }

    private static String normalize(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    /** Comments and literals cannot satisfy a production-code pin. */
    private static String codeOnly(String source) {
        char[] code = source.toCharArray();
        java.util.Arrays.fill(code, ' ');
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (source.startsWith("/*", i)) {
                i += 2;
                while (i < source.length() && !source.startsWith("*/", i)) i++;
                i = Math.min(source.length(), i + 2);
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i++);
                while (i < source.length()) {
                    char current = source.charAt(i++);
                    if (current == '\\' && i < source.length()) i++;
                    else if (current == quote) break;
                }
            } else {
                code[i] = source.charAt(i);
                i++;
            }
        }
        return new String(code);
    }
}
