/**
 * Source-only guard for the original ExpBridge diagnostic, not a Java behavior test.
 * Keep retrieval noise behind the existing named logger without changing its payload.
 * Run: node --test plugins/experience/src/test/ExperienceStoreRetrievalBridge.test.mjs
 * Does not compile Java, load SLF4J, or touch a running game.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('original ExpBridge diagnostic uses DEBUG with unchanged payload', () => {
    const source = readFileSync(new URL(
        '../main/java/com/dwinovo/numen/plugins/experience/ExperienceStoreRetrievalBridge.java',
        import.meta.url), 'utf8').replaceAll('\r\n', '\n');
    const diagnostic = `org.slf4j.LoggerFactory.getLogger("ExpBridge").debug(
                        "[EXP-BRIDGE] id={} pub={} retracted={} published={} retrievable={} trial={}",
                        e.id(),
                        e.publication(),
                        e.retracted(),
                        e.publication() == com.dwinovo.numen.experience.api.PublicationState.PUBLISHED,
                        e.publication() != com.dwinovo.numen.experience.api.PublicationState.REVOKED,
                        e.publication() == com.dwinovo.numen.experience.api.PublicationState.TRIAL);`;
    assert.ok(source.includes(diagnostic), 'original named logger, DEBUG level, format and arguments must stay wired');
    assert.equal((source.match(/LoggerFactory\.getLogger\("ExpBridge"\)/g) ?? []).length, 1);
});
