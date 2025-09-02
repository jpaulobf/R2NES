package com.nesemu.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal lightweight logger without external deps. */
public final class Log {
    private Log() {
    }

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    public enum Cat {
        CPU, PPU, APU, BUS, DMA, CONTROLLER, ROM, TEST, GENERAL
    }

    private static volatile Level globalLevel = Level.INFO;
    private static final EnumSet<Cat> enabledCats = EnumSet.allOf(Cat.class);
    private static final AtomicBoolean timestamps = new AtomicBoolean(false);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void setLevel(Level lvl) {
        if (lvl != null)
            globalLevel = lvl;
    }

    public static void enableCategory(Cat c, boolean enable) {
        if (enable)
            enabledCats.add(c);
        else
            enabledCats.remove(c);
    }

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

    public static boolean isEnabled(Level lvl, Cat cat) {
        return lvl.ordinal() >= globalLevel.ordinal() && enabledCats.contains(cat);
    }

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

    public static void trace(Cat c, String f, Object... a) {
        log(Level.TRACE, c, f, a);
    }

    public static void debug(Cat c, String f, Object... a) {
        log(Level.DEBUG, c, f, a);
    }

    public static void info(Cat c, String f, Object... a) {
        log(Level.INFO, c, f, a);
    }

    public static void warn(Cat c, String f, Object... a) {
        log(Level.WARN, c, f, a);
    }

    public static void error(Cat c, String f, Object... a) {
        log(Level.ERROR, c, f, a);
    }
}
