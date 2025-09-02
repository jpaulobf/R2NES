package com.nesemu.input;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Mapping of logical buttons to a set of key tokens (strings from config). */
public class ControllerConfig {
    private final Map<ControllerButton, Set<String>> map = new EnumMap<>(ControllerButton.class);

    public void add(ControllerButton btn, String token) {
        map.computeIfAbsent(btn, k -> new HashSet<>()).add(token);
    }

    public Set<String> getTokens(ControllerButton btn) {
        return map.containsKey(btn) ? Collections.unmodifiableSet(map.get(btn)) : Collections.emptySet();
    }

    public Map<ControllerButton, Set<String>> asMap() {
        return Collections.unmodifiableMap(map);
    }
}
