package com.nesemu.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple logging utility with global level and category filtering.
 * Usage: Log.info(Log.Cat.CPU, "Message %d", 123);
 */
public final class Log {

    /**
     * Private ctor - static methods only.
     */
    private Log() {
    }

    /**
     * Logging levels (increasing severity).
     */
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    /**
     * Logging categories (for filtering).
     */
    public enum Cat {
        CPU, PPU, APU, BUS, DMA, CONTROLLER, ROM, TEST, GENERAL
    }

    // Global configuration
    private static volatile Level globalLevel = Level.INFO;
    private static final EnumSet<Cat> enabledCats = EnumSet.allOf(Cat.class);
    private static final AtomicBoolean timestamps = new AtomicBoolean(false);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Set global logging level. Messages below this level are ignored.
     * 
     * @param lvl
     */
    public static void setLevel(Level lvl) {
        if (lvl != null)
            globalLevel = lvl;
    }

    /**
     * Enable or disable a logging category.
     * 
     * @param c
     * @param enable
     */
    public static void enableCategory(Cat c, boolean enable) {
        if (enable)
            enabledCats.add(c);
        else
            enabledCats.remove(c);
    }

    /**
     * Enable or disable timestamps in log output.
     * 
     * @param on
     */
    public static void setTimestamps(boolean on) {
        timestamps.set(on);
    }

    /** Replace enabled category set (thread-unsafe coarse reconfiguration). */
    public static void setCategories(Collection<Cat> cats) {
        enabledCats.clear();
        if (cats != null && !cats.isEmpty()) {
            enabledCats.addAll(cats);
        }
    }

    /**
     * True if messages at this level and category are enabled.
     * 
     * @param lvl
     * @param cat
     * @return
     */
    public static boolean isEnabled(Level lvl, Cat cat) {
        return lvl.ordinal() >= globalLevel.ordinal() && enabledCats.contains(cat);
    }

    /**
     * Log a message if level/category enabled.
     * 
     * @param lvl
     * @param cat
     * @param fmt
     * @param args
     */
    private static void log(Level lvl, Cat cat, String fmt, Object... args) {
        if (!isEnabled(lvl, cat))
            return;
        StringBuilder sb = new StringBuilder();
        if (timestamps.get())
            sb.append(LocalDateTime.now().format(TS_FMT)).append(' ');
        sb.append('[').append(lvl.name()).append(']').append('[').append(cat.name()).append("] ");
        sb.append(String.format(Locale.ROOT, fmt, args));
        System.out.println(sb.toString());
    }

    /**
     * Log at trace level.
     * 
     * @param c
     * @param f
     * @param a
     */
    public static void trace(Cat c, String f, Object... a) {
        log(Level.TRACE, c, f, a);
    }

    /**
     * Log at debug level.
     * 
     * @param c
     * @param f
     * @param a
     */
    public static void debug(Cat c, String f, Object... a) {
        log(Level.DEBUG, c, f, a);
    }

    /**
     * Log at info level.
     * 
     * @param c
     * @param f
     * @param a
     */
    public static void info(Cat c, String f, Object... a) {
        log(Level.INFO, c, f, a);
    }

    /**
     * Log at warn level.
     * 
     * @param c
     * @param f
     * @param a
     */
    public static void warn(Cat c, String f, Object... a) {
        log(Level.WARN, c, f, a);
    }

    /**
     * Log at error level.
     * 
     * @param c
     * @param f
     * @param a
     */
    public static void error(Cat c, String f, Object... a) {
        log(Level.ERROR, c, f, a);
    }
}