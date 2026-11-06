import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

// Offline wiring pins only: no Minecraft loading, compilation, deployment, or game.
const source = readFileSync(new URL('../main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java', import.meta.url), 'utf8');
const region = (from, to) => {
    const start = source.indexOf(from);
    assert.ok(start >= 0, from);
    const end = source.indexOf(to, start);
    assert.ok(end > start, to);
    return source.slice(start, end);
};
const registration = region('        numen.contributeState("rdd", "runtime"', '\n    /** Model-requested');
const renderer = region('    private static String renderStateContext(', '\n    /**');
const assets = region('private static String withAssets(', '\n    /** Stage-A');
const base = region('    private static String renderBasePlaybook(', '\n    /** Mandatory');
const count = (text, value) => text.split(value).length - 1;
const has = (text, value) => assert.ok(text.includes(value), value);

test('stable runtime mandatory and only base workflow optional at priority 5', () => {
    has(registration, 'contributeState("rdd", "runtime", true, 100,');
    has(registration, 'contributeState("rdd", "base_playbook", false, 5, RddPlugin::renderBasePlaybook)');
    assert.equal(count(registration, 'contributeState('), 2);
    assert.ok(!assets.includes('RddBasePlaybook'));
    assert.ok(!assets.includes('merged.append(playbook)'));
    assert.equal(count(source, 'RddBasePlaybook.block('), 1);
});

test('all six renderer branches still return mandatory withAssets', () => {
    assert.equal(count(renderer, 'return '), 6);
    assert.equal(count(renderer, 'return withAssets('), 6);
    for (const branch of ['sideContext != null', 'DECOMPOSING.contains', 'runtime == null', 'PrimaryGoalStatus.COMPLETED', 'current == null']) has(renderer, branch);
});

test('mine survival rules remain generated and appended once in mandatory assets', () => {
    assert.equal(count(assets, 'RddMinePlaybook.block(both)'), 1);
    assert.equal(count(assets, 'merged.append(minePlaybook)'), 1);
    assert.ok(!registration.includes('mine_playbook'));
    const mine = readFileSync(new URL('../main/java/com/dwinovo/numen/plugins/rdd/RddMinePlaybook.java', import.meta.url), 'utf8');
    for (const rule of ['没有水桶就别往岩浆层去', '不垂直往下挖', '听到怪先处理或往上撤']) has(mine, rule);
});

test('home, death, gear, goals and environment remain mandatory; base is task-string only', () => {
    for (const required of ['carryHint(companionId)', 'RddEnvBlock.build(companionId)', 'wayHomeHint(companionId)', 'RddGoalHint.block(', 'merged.append(goalHint)', 'merged.append(bedHint)', 'merged.append(homeBlock)', 'RddDeathSpotFacts.blockFor(companionId)', 'merged.append(dangerBlock)', 'merged.append(needsBlock)']) has(assets, required);
    assert.doesNotMatch(base, /withAssets|renderStateContext|RddEnvBlock|RddObservation|System.currentTimeMillis/);
    has(base, 'RddBasePlaybook.block(goal + " " + step)');
    has(base, 'current == null ? ""');
    has(base, 'rt == null || rt.chain() == null');
});

test('monitor String joins base once; callback returns only mandatory text and renders once', () => {
    assert.equal(count(registration, 'renderStateContext(uuid)'), 1);
    assert.equal(count(registration, 'renderBasePlaybook(uuid)'), 1);
    has(registration, 'String context = basePlaybook.isEmpty() ? requiredContext');
    has(registration, String.raw`: requiredContext + "\n" + basePlaybook;`);
    has(registration, 'data.put("context", context)');
    has(registration, 'RddMonitor.publish("numen_context", data)');
    has(registration, 'return requiredContext;');
    assert.ok(!registration.includes('return context;'));
});
