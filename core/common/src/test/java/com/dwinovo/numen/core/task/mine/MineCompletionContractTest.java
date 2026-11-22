package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact published description contrasted with real Mine/base/Slot partial settlement.
 * No model adoption, transport, MC pickup or world-effect claim. */
class MineCompletionContractTest {
    @TempDir static Path temp;
    static URLClassLoader descriptionLoader;
    @BeforeAll static void setup() throws Exception {
        MineSettlementConsumerTest.temp=temp.resolve("settlement");
        Files.createDirectories(MineSettlementConsumerTest.temp);
        MineSettlementConsumerTest.compileConsumer();
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        String source=Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/tools/work/AutoMineTool.java"));
        String method=MineHarvestRecoveryConsumerTest.method(source,"    public String description() {");
        Path src=temp.resolve("description-src"),out=temp.resolve("description-classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/Description.java","package fixture; public class Description {"+method+"}");
        ByteArrayOutputStream diagnostics=new ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new)),"SETUP: "+diagnostics);
        descriptionLoader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());
    }
    @AfterAll static void close() throws Exception {
        if(descriptionLoader!=null)descriptionLoader.close();
        MineSettlementConsumerTest.close();
    }
    @Test void descriptionDoesNotPromiseQuantityForAPartialSuccessfulTerminal() throws Exception {
        var partial=MineSettlementConsumerTest.run("partial");
        assertEquals("SUCCESS",partial.get("state"));assertEquals(1,partial.get("gathered"));
        assertEquals(true,partial.get("partial"));assertEquals(3,partial.get("shortfall"));assertEquals(1,partial.get("outbox"));
        Class<?> description=descriptionLoader.loadClass("fixture.Description");
        String text=(String)description.getMethod("description").invoke(description.getConstructor().newInstance());
        assertFalse(text.contains("requested count is complete"),"legal 1/4 SUCCESS cannot be advertised as quantity complete");
        assertTrue(text.contains("partial"),"the actual tool catalog must disclose partial collection");
        assertTrue(text.contains("gathered")&&text.contains("requested"),"explain how to distinguish completion from a shortfall");
    }
}
