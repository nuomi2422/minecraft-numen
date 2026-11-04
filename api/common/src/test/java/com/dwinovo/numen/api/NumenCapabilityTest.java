package com.dwinovo.numen.api;

import com.dwinovo.numen.api.observation.WorldObservationService;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 039 U1：跨插件能力的取用口（按接口登记 / 按接口取 / 找不到就是 null）。
 *
 * <p>注意：能力表是**进程级静态**的，所以每个用例用**自己独有的接口类型**，
 * 否则用例之间会互相污染（第一次写这版就踩了）。
 */
class NumenCapabilityTest {

    private interface NeverRegistered { String v(); }
    private interface Basic { String greet(); }
    private interface Replaceable { String who(); }
    private interface Typed { String t(); }
    private interface IsolatedA { String a(); }
    private interface IsolatedB { String b(); }

    private static NumenApi api() {
        AtomicReference<NumenApi> ref = new AtomicReference<>();
        NumenPlugins.register(ref::set);
        assertNotNull(ref.get(), "插件登记必须拿到 API");
        return ref.get();
    }

    @Test
    void unregisteredCapabilityIsNullNotAnException() {
        assertNull(NumenPlugins.capability(NeverRegistered.class),
                "没登记就该返回 null，让调用方显式降级");
    }

    @Test
    void registerThenLookupByInterface() {
        NumenApi api = api();
        Basic g = () -> "hello";
        assertNull(api.capability(Basic.class, g), "第一次登记没有旧实现");
        assertSame(g, api.capability(Basic.class));
        assertSame(g, NumenPlugins.capability(Basic.class), "静态查找与实例查找同源");
    }

    @Test
    void secondRegistrationReturnsThePreviousImplementation() {
        NumenApi api = api();
        Replaceable first = () -> "one";
        Replaceable second = () -> "two";
        assertNull(api.capability(Replaceable.class, first));
        assertSame(first, api.capability(Replaceable.class, second), "覆盖时要能把旧的交还给登记方");
        assertSame(second, api.capability(Replaceable.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wrongTypeIsRejectedInsteadOfSilentlyStored() {
        NumenApi api = api();
        Object notTyped = new Object();
        // 走 raw 调用绕过编译期泛型，才能真的进到运行期类型检查
        assertThrows(IllegalArgumentException.class,
                () -> api.capability((Class) Typed.class, notTyped));
        assertNull(NumenPlugins.capability(Typed.class), "被拒的实现不许留在表里");
    }

    @Test
    void differentInterfacesAreIsolated() {
        NumenApi api = api();
        IsolatedA a = () -> "a";
        IsolatedB b = () -> "b";
        api.capability(IsolatedA.class, a);
        api.capability(IsolatedB.class, b);
        assertEquals("a", api.capability(IsolatedA.class).a());
        assertEquals("b", api.capability(IsolatedB.class).b());
    }

    @Test
    void observationServiceIsJustAnotherCapability() {
        NumenApi api = api();
        WorldObservationService svc = request -> WorldObservationService.Response.ok(
                request.topic(), "snap", 1L, WorldObservationService.Coverage.FULL, java.util.List.of());
        api.capability(WorldObservationService.class, svc);
        assertSame(svc, NumenPlugins.capability(WorldObservationService.class));
        Map<String, String> sel = Map.of();
        assertEquals(WorldObservationService.Status.OK,
                NumenPlugins.capability(WorldObservationService.class)
                        .read(new WorldObservationService.Request("t", "minecraft:overworld", "environment", sel, 500, 10, 50))
                        .status());
    }
}
