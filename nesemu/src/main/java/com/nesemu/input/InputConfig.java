package com.nesemu.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for emulator.ini controller mapping.
 */
public class InputConfig {

    // 1-based controller list
    private final List<ControllerConfig> controllers = new ArrayList<>();
    private final Map<String, String> options = new HashMap<>();

    /**
     * Get (and create if needed) controller config for given 0-based index.
     * 
     * @param index
     * @return
     */
    public ControllerConfig getController(int index) {
        while (controllers.size() <= index)
            controllers.add(new ControllerConfig());
        return controllers.get(index);
    }

    /**
     * Get number of configured controllers.
     * 
     * @param key
     * @return
     */
    public String getOption(String key) {
        if (key == null)
            return null;
        return options.get(key.toLowerCase(Locale.ROOT));
    }

    /**
     * Check if given option is present.
     * 
     * @param key
     * @return
     */
    public boolean hasOption(String key) {
        if (key == null)
            return false;
        return options.containsKey(key.toLowerCase(Locale.ROOT));
    }

    /**
     * Load input config from given path (if file missing, return empty config).
     * 
     * @param path
     * @return
     * @throws IOException
     */
    public static InputConfig load(Path path) throws IOException {
        InputConfig cfg = new InputConfig();
        if (!Files.exists(path))
            return cfg; // empty default
        // Accept normal mappings and optional '-turbo' suffix for A/B (e.g.,
        // dp-01-a-turbo)
        Pattern p = Pattern.compile("dp-(\\d+)-(up|down|left|right|select|start|a|b)(-turbo)?");
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                int eq = line.indexOf('=');
                if (eq < 0)
                    continue; // ignore malformed
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                Matcher m = p.matcher(key.toLowerCase(Locale.ROOT));
                if (m.matches()) {
                    int pad = Integer.parseInt(m.group(1));
                    String btnName = m.group(2).toLowerCase(Locale.ROOT);
                    boolean turbo = m.group(3) != null; // '-turbo' suffix present
                    ControllerButton btn = switch (btnName) {
                        case "up" -> ControllerButton.UP;
                        case "down" -> ControllerButton.DOWN;
                        case "left" -> ControllerButton.LEFT;
                        case "right" -> ControllerButton.RIGHT;
                        case "select" -> ControllerButton.SELECT;
                        case "start" -> ControllerButton.START;
                        case "a" -> ControllerButton.A;
                        case "b" -> ControllerButton.B;
                        default -> null;
                    };
                    if (btn == null)
                        continue;
                    ControllerConfig cc = cfg.getController(pad - 1); // config is 1-based
                    if (!val.isEmpty()) {
                        String[] toks = val.split("/");
                        for (String t : toks) {
                            String tok = t.trim();
                            if (!tok.isEmpty())
                                if (turbo && (btn == ControllerButton.A || btn == ControllerButton.B)) {
                                    cc.addTurbo(btn, tok.toLowerCase(Locale.ROOT));
                                } else {
                                    cc.add(btn, tok.toLowerCase(Locale.ROOT));
                                }
                        }
                    }
                } else {
                    // generic option (store lowercase key)
                    cfg.options.put(key.toLowerCase(Locale.ROOT), val);
                }
            }
        }
        return cfg;
    }
}
