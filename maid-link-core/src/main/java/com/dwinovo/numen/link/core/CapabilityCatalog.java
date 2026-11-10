package com.dwinovo.numen.link.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/** Immutable capability manifest, supplied by trusted adapters, never by model output.
 * No built-in claim that MSC supports Numen: each tested cross-host edge must be advertised.
 */
public final class CapabilityCatalog {
    private final Map<String, Provider> providers;
    public CapabilityCatalog(Collection<Provider> descriptors) {
        var copy = new LinkedHashMap<String, Provider>();
        for (Provider p : descriptors) {
            if (copy.putIfAbsent(p.id(), p) != null) throw new IllegalArgumentException("duplicate provider");
        }
        providers = Map.copyOf(copy);
    }
    public Provider provider(String id) { return providers.get(id); }
    public Check validate(Binding binding, Slot slot, Choice choice, Set<String> available) {
        if (!binding.bodies().containsKey(slot.host())) return new Check(Code.UNBOUND, "target-body-missing");
        if (!choice.enabled()) return Check.yes();
        Provider p = providers.get(choice.provider());
        if (p == null || p.capability() != slot.capability()) return new Check(Code.UNSUPPORTED, "provider-capability");
        if (!p.supports().contains(new Support(slot.host(), choice.mode())))
            return new Check(Code.UNSUPPORTED, "host-mode-not-advertised");
        if (!binding.bodies().keySet().containsAll(p.requiredBodies()))
            return new Check(Code.UNBOUND, "required-body-missing");
        if (!available.containsAll(p.dependencies())) return new Check(Code.UNAVAILABLE, "dependency-missing");
        return Check.yes();
    }
}
