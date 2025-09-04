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
    // Map of button to set of TURBO tokens (only meaningful for A/B)
    private final Map<ControllerButton, Set<String>> turboMap = new EnumMap<>(ControllerButton.class);

    /**
     * Add a mapping from button to token.
     * 
     * @param btn
     * @param token
     */
    public void add(ControllerButton btn, String token) {
        map.computeIfAbsent(btn, k -> new HashSet<>()).add(token);
    }

    /**
     * Add a TURBO mapping from button to token (A/B only semantics enforced by
     * caller).
     * 
     * @param btn
     * @param token
     */
    public void addTurbo(ControllerButton btn, String token) {
        turboMap.computeIfAbsent(btn, k -> new HashSet<>()).add(token);
    }

    /**
     * Get set of tokens mapped to given button (empty if none).
     * 
     * @param btn
     * @return
     */
    public Set<String> getTokens(ControllerButton btn) {
        return map.containsKey(btn) ? Collections.unmodifiableSet(map.get(btn)) : Collections.emptySet();
    }

    /**
     * Get TURBO tokens mapped to given button (empty if none).
     */
    public Set<String> getTurboTokens(ControllerButton btn) {
        return turboMap.containsKey(btn) ? Collections.unmodifiableSet(turboMap.get(btn)) : Collections.emptySet();
    }

    /**
     * Get full mapping as unmodifiable map.
     * 
     * @return
     */
    public Map<ControllerButton, Set<String>> asMap() {
        return Collections.unmodifiableMap(map);
    }
}
