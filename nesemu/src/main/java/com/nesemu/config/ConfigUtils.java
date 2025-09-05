package com.nesemu.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.nesemu.input.InputConfig;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * Utility helpers for loading emulator configuration from emulator.ini and
 * extracting commonly used options.
 */
public final class ConfigUtils {
    private ConfigUtils() {
    }

    /**
     * Loads InputConfig from emulator.ini (cwd) or dev path fallback.
     * Never throws; returns an empty InputConfig on failure and logs a warning.
     */
    public static InputConfig loadInputConfig() {
        try {
            Path inputPath = Path.of("emulator.ini");
            if (Files.exists(inputPath))
                return InputConfig.load(inputPath);
            Path devPath = Path.of("src/main/java/com/nesemu/config/emulator.ini");
            if (Files.exists(devPath))
                return InputConfig.load(devPath);
        } catch (Exception ex) {
            Log.warn(CONTROLLER, "Falha ao carregar configuração de input: %s", ex.getMessage());
        }
        return new InputConfig();
    }

    /**
     * Parses pause-emulation option into a set of key tokens (lowercase).
     */
    public static Set<String> getPauseKeyTokens(InputConfig cfg) {
        Set<String> out = new HashSet<>();
        if (cfg == null)
            return out;
        String pauseOptRaw = cfg.getOption("pause-emulation");
        if (pauseOptRaw == null || pauseOptRaw.isBlank())
            return out;
        for (String t : pauseOptRaw.split("/")) {
            if (t == null)
                continue;
            t = t.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty())
                out.add(t);
        }
        return out;
    }
}
