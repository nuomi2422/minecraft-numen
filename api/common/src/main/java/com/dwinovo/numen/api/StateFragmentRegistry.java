package com.dwinovo.numen.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Small state-only registry, independently testable without Minecraft (2026-11-06).
 * Register at setup; enumerate once per request; each callback is evaluated once.
 * Duplicate explicit owner/id is rejected before mutation; the first producer and
 * its requiredness stay intact. Callers must handle rejected registrations explicitly.
 * Why not last-wins: replacing an earlier safety producer silently loses facts.
 * No lifecycle, world-reading, cache, TTL or budget policy belongs here.
 */
final class StateFragmentRegistry {
    private record Key(String owner, String id) {}
    private record Producer(String owner, String id, boolean mandatory, int priority,
                            Function<UUID, String> callback) {}
    private final List<Producer> producers = new ArrayList<>();
    private final Set<Key> names = new HashSet<>();

    synchronized void addLegacy(Function<UUID, String> callback) {
        if (callback != null) producers.add(new Producer("legacy", UUID.randomUUID().toString(),
                true, 0, callback));
    }

    synchronized void add(String owner, String id, boolean mandatory, int priority,
                          Function<UUID, String> callback) {
        StateFragment.requireName(owner, "owner");
        StateFragment.requireName(id, "id");
        Objects.requireNonNull(callback, "callback");
        if (!names.add(new Key(owner, id))) {
            throw new IllegalArgumentException("Duplicate state fragment: " + owner + "/" + id);
        }
        producers.add(new Producer(owner, id, mandatory, priority, callback));
    }

    List<StateFragment> entries(UUID companion, Consumer<Throwable> failureSink) {
        List<Producer> snapshot;
        synchronized (this) { snapshot = List.copyOf(producers); }
        List<StateFragment> result = new ArrayList<>();
        for (Producer producer : snapshot) {
            try {
                String text = producer.callback().apply(companion);
                if (text != null && !text.isBlank()) result.add(new StateFragment(producer.owner(),
                        producer.id(), text, producer.mandatory(), producer.priority()));
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath death) throw death;
                failureSink.accept(failure);
            }
        }
        return List.copyOf(result);
    }
}
