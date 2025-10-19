package com.nesemu.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.GENERAL;

/**
 * Persists lightweight user preferences such as the default and last-used ROM
 * directories.
 * Data is stored in $HOME/.R2NES/r2nes.config (platform agnostic).
 */
public final class UserConfig {

    private static final String KEY_DEFAULT_ROM_DIR = "defaultRomDir";
    private static final String KEY_LAST_ROM_DIR = "lastRomDir";

    private final Path configDir;
    private final Path configFile;
    private Path defaultRomDir;
    private Path lastRomDir;

    private UserConfig(Path configDir, Path configFile) {
        this.configDir = configDir;
        this.configFile = configFile;
    }

    /**
     * Load the persisted configuration from disk. Any issues are logged and a
     * default (empty) config is returned.
     */
    public static UserConfig load() {
        Path userHome = Path.of(System.getProperty("user.home", "."));
        Path dir = userHome.resolve(".R2NES");
        Path file = dir.resolve("r2nes.config");
        UserConfig cfg = new UserConfig(dir, file);
        cfg.readFromDisk();
        return cfg;
    }

    /**
     * Return the configured default ROM directory, if any.
     */
    public Path getDefaultRomDirectory() {
        return defaultRomDir;
    }

    /**
     * Assign a new default ROM directory. The directory must exist and be a folder.
     */
    public void setDefaultRomDirectory(Path dir) {
        this.defaultRomDir = normalizeDirectory(dir);
    }

    /**
     * Remove the default ROM directory entry (fall back to last-used when resolving
     * preferences).
     */
    public void clearDefaultRomDirectory() {
        this.defaultRomDir = null;
    }

    /**
     * Return whether a default ROM directory is currently configured.
     */
    public boolean hasDefaultRomDirectory() {
        return defaultRomDir != null;
    }

    /**
     * Return the last directory where a ROM was opened, if any.
     */
    public Path getLastRomDirectory() {
        return lastRomDir;
    }

    /**
     * Update the last-used ROM directory (only accepted if it exists and is a
     * directory).
     */
    public void setLastRomDirectory(Path dir) {
        this.lastRomDir = normalizeDirectory(dir);
    }

    /**
     * Resolve the preferred directory for the ROM chooser: default directory when
     * present, otherwise the last-used one.
     */
    public Path resolvePreferredRomDirectory() {
        if (defaultRomDir != null)
            return defaultRomDir;
        return lastRomDir;
    }

    /**
     * Persist the current configuration to disk. Any errors are logged and false is
     * returned.
     */
    public boolean save() {
        Properties props = new Properties();
        if (defaultRomDir != null)
            props.setProperty(KEY_DEFAULT_ROM_DIR, defaultRomDir.toString());
        if (lastRomDir != null)
            props.setProperty(KEY_LAST_ROM_DIR, lastRomDir.toString());
        try {
            Files.createDirectories(configDir);
            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                props.store(writer, "R2NES user configuration");
            }
            return true;
        } catch (IOException ex) {
            Log.warn(GENERAL, "Falha ao salvar configuração do usuário (%s): %s", configFile, ex.getMessage());
            return false;
        }
    }

    private void readFromDisk() {
        if (!Files.exists(configFile))
            return;
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ex) {
            Log.warn(GENERAL, "Falha ao ler configuração do usuário (%s): %s", configFile, ex.getMessage());
            return;
        }
        setDefaultRomDirectorySafe(props.getProperty(KEY_DEFAULT_ROM_DIR));
        setLastRomDirectorySafe(props.getProperty(KEY_LAST_ROM_DIR));
    }

    private void setDefaultRomDirectorySafe(String raw) {
        Path dir = toDirectory(raw);
        if (dir != null)
            this.defaultRomDir = dir;
    }

    private void setLastRomDirectorySafe(String raw) {
        Path dir = toDirectory(raw);
        if (dir != null)
            this.lastRomDir = dir;
    }

    private Path normalizeDirectory(Path dir) {
        if (dir == null)
            return null;
        if (!Files.isDirectory(dir))
            return null;
        return dir.toAbsolutePath().normalize();
    }

    private Path toDirectory(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        try {
            Path dir = Path.of(raw.trim());
            return normalizeDirectory(dir);
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "UserConfig{" +
                "defaultRomDir=" + Objects.toString(defaultRomDir) +
                ", lastRomDir=" + Objects.toString(lastRomDir) +
                '}';
    }
}
