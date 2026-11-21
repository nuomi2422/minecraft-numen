package com.dwinovo.numen.acx.core;

import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxLifecycleState;

/**
 * AC 名字 → 定义的目录。执行器靠它解析「步骤 block 指向另一个 AC」这种递归调用。
 *
 * <p>抽成接口而不是直接传 {@code Map}，是为了让加载器（{@code AcxLoader}）能提供
 * 带分级 / 隔离语义的实现，而不是被执行器绑死成一张扁平表。</p>
 */
@FunctionalInterface
public interface AcxCatalog {

    Optional<AcxDefinition> find(String name);

    /** Version may be absent in older checkpoints; the original fingerprint is always required. */
    default Optional<AcxDefinition> findBound(String name, String version, String fingerprint) {
        return find(name).filter(def -> matches(def, version, fingerprint));
    }

    /**
     * Production lookup: the live library owns published names. Keep the startup catalog only
     * for loader entries outside the library, so an offline version cannot reappear from startup.
     */
    static AcxCatalog withLibrary(FileAcxLibrary library, AcxCatalog startup) {
        return new AcxCatalog() {
            @Override
            public Optional<AcxDefinition> find(String name) {
                if (!library.hasAnyVersion(name)) return startup.find(name);
                return library.isOnline(name) ? Optional.of(library.active(name)) : Optional.empty();
            }

            @Override
            public Optional<AcxDefinition> findBound(String name, String version, String fingerprint) {
                if (!library.hasAnyVersion(name)) return startup.findBound(name, version, fingerprint);
                return library.lifecycle(name).entrySet().stream()
                        .filter(entry -> entry.getValue() == AcxLifecycleState.STABLE)
                        .map(Map.Entry::getKey)
                        .filter(key -> version == null || version.equals(key))
                        .map(key -> library.version(name, key))
                        .filter(def -> matches(def, version, fingerprint))
                        .findFirst();
            }
        };
    }

    private static boolean matches(AcxDefinition def, String version, String fingerprint) {
        return (version == null || version.equals(def.version())) && fingerprint.equals(AcxFingerprint.of(def));
    }

    static AcxCatalog empty() {
        return name -> Optional.empty();
    }

    static AcxCatalog of(Map<String, AcxDefinition> defs) {
        return name -> Optional.ofNullable(defs.get(name));
    }
}
