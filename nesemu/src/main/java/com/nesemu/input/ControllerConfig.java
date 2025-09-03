package com.nesemu.input;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 
 * Mapping of logical buttons to a set of key tokens (strings from config). 
 */
public class ControllerConfig {
    
    // Map of button to set of tokens
    private final Map<ControllerButton, Set<String>> map = new EnumMap<>(ControllerButton.class);

    /**
     * Add a mapping from button to token.
     * @param btn
     * @param token
     */
    public void add(ControllerButton btn, String token) {
        map.computeIfAbsent(btn, k -> new HashSet<>()).add(token);
    }

    /**
     * Get set of tokens mapped to given button (empty if none).
     * @param btn
     * @return
     */
    public Set<String> getTokens(ControllerButton btn) {
        return map.containsKey(btn) ? Collections.unmodifiableSet(map.get(btn)) : Collections.emptySet();
    }

    /**
     * Get full mapping as unmodifiable map.
     * @return
     */
    public Map<ControllerButton, Set<String>> asMap() {
        return Collections.unmodifiableMap(map);
    }
}
