package com.nesemu.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for emulator.ini controller mapping. */
public class InputConfig {
    private final List<ControllerConfig> controllers = new ArrayList<>();

    public ControllerConfig getController(int index) {
        while (controllers.size() <= index)
            controllers.add(new ControllerConfig());
        return controllers.get(index);
    }

    public static InputConfig load(Path path) throws IOException {
        InputConfig cfg = new InputConfig();
        if (!Files.exists(path))
            return cfg; // empty default
        Pattern p = Pattern.compile("dp-(\\d+)-(up|down|left|right|select|start|a|b)");
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
                Matcher m = p.matcher(key);
                if (!m.matches())
                    continue;
                int pad = Integer.parseInt(m.group(1));
                String btnName = m.group(2).toLowerCase(Locale.ROOT);
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
                            cc.add(btn, tok.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return cfg;
    }
}
