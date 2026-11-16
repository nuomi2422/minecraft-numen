package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.carrier.CarrierAlarms;
import com.dwinovo.numen.api.carrier.CarrierChain;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production projection; no alternate alarm evaluator, world or cache. */
class RddCarryProjectionTest {
    static CarrierChain.Facts facts(String hp,long at,String coverage) {
        return CarrierChain.factsOf(Map.of("hp",hp,"bed","0"),"hp="+hp,at,"body",coverage);
    }
    static CarrierChain.Result result(List<String> carry,int stopped) {
        return new CarrierChain.Result(carry,"",stopped,List.of(),List.of());
    }
    static CarrierAlarms.Hit alarm(String rule,String priority) {
        return new CarrierAlarms.Hit(rule,1,priority,"facts<&>","advice<&>");
    }
    @Test void emptyChannelsDoNotReadRuleCountAndNoEmptyTagsAreInvented() {
        var reads=new AtomicInteger();
        var output=RddCarryProjection.render(facts("20",0,"UNKNOWN"),result(List.of(),-1),List.of(),reads::incrementAndGet);
        assertEquals("",output.xml());assertEquals("",output.fingerprint());assertEquals(0,reads.get());
    }
    @Test void carryPreservesOrderFourItemBoundEscapingAndShortCircuitRead() {
        var reads=new AtomicInteger();
        var output=RddCarryProjection.render(facts("20",0,"COMPLETE"),
                result(List.of("a<&>","b","c","d","excluded"),0),List.of(),()->{reads.incrementAndGet();return 4;});
        String xml=output.xml();
        assertTrue(xml.contains("<item>a&lt;&amp;&gt;</item><item>b</item><item>c</item><item>d</item>"));
        assertFalse(xml.contains("excluded"));assertFalse(xml.contains("<alarms"));
        assertTrue(xml.contains("第 1 级不成立，后续 3 项未求值"));assertEquals(1,reads.get());
        assertTrue(xml.contains("一般不要仅为携带建议偏离当前任务"));
        assertTrue(xml.contains("未知或过期提示不作为自动抢占依据"));
    }
    @Test void alarmOnlyPreservesOrderIdentityUnknownTimeAndBounds() {
        var hits=List.of(alarm("night","P1"),new CarrierAlarms.Hit("low_hp",3,"P0","x".repeat(121),"y".repeat(201)));
        String xml=RddCarryProjection.render(facts("2",0,"UNKNOWN"),result(List.of(),-1),hits,
                ()->{throw new AssertionError("no carry short circuit");}).xml();
        assertFalse(xml.contains("<carry>"));assertTrue(xml.indexOf("rule=\"night\"")<xml.indexOf("rule=\"low_hp\""));
        assertTrue(xml.contains("asOf=\"unknown\" age=\"unknown\" source=\"body\" coverage=\"UNKNOWN\""));
        assertTrue(xml.contains("facts&lt;&amp;&gt;"));assertTrue(xml.contains("x".repeat(119)+"…"));
        assertTrue(xml.contains("y".repeat(199)+"…"));assertTrue(xml.contains("已过期或覆盖不足"));
        assertFalse(xml.contains("可以暂停或替换"));
    }
    @Test void freshAndStaleUseExistingResponsePolicyWithoutGrantingAuthority() {
        var hits=List.of(alarm("night","P1"));long now=System.currentTimeMillis();
        String fresh=RddCarryProjection.render(facts("12",now,"COMPLETE"),result(List.of("bed"),-1),hits,()->0).xml();
        assertTrue(fresh.contains("这是现场风险建议，不是自动命令"));assertTrue(fresh.contains("挖三填一"));
        String stale=RddCarryProjection.render(facts("12",now-20000,"COMPLETE"),result(List.of(),-1),hits,()->0).xml();
        assertTrue(stale.contains("stale=\"true\""));assertTrue(stale.contains("已过期或覆盖不足"));
        assertFalse(stale.contains("可以暂停或替换"));
    }
    @Test void fingerprintIgnoresBasisHpButRetainsItemAndAlarmRuleVersionOrder() {
        var hits=List.of(alarm("night","P1"),alarm("low_hp","P0"));
        var a=RddCarryProjection.render(facts("12",0,"COMPLETE"),result(List.of("bed"),-1),hits,()->0);
        var b=RddCarryProjection.render(facts("20",0,"COMPLETE"),result(List.of("bed"),-1),hits,()->0);
        assertNotEquals(a.xml(),b.xml());assertEquals(a.fingerprint(),b.fingerprint());
        assertTrue(a.fingerprint().endsWith("|night@1|low_hp@1"));
    }
    @Test void originalHostDelegatesProjectionAfterEvaluatorsAndKeepsRefreshOwnership() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isRegularFile(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddCarryHint.java")))root=root.getParent();
        assertNotNull(root);
        String source=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddCarryHint.java"));
        int chain=source.indexOf("CarrierChain.evaluate("), alarms=source.indexOf("RddAlarms.evaluate("), projection=source.indexOf("return RddCarryProjection.render(");
        assertTrue(chain>=0&&chain<alarms&&alarms<projection);
        assertTrue(source.contains("() -> CarrierRuleStore.effective().size()"),"retain lazy second rule-count lookup");
        assertTrue(source.indexOf("if (!server.isSameThread())")<source.indexOf("CARRY_HINT_AT.put(companionId, now)"));
        assertTrue(source.contains("private static final long WAKE_COOLDOWN_MS = 30_000L"));
        assertTrue(source.contains("if (body.isAlive() && !stale)"));
    }
}
