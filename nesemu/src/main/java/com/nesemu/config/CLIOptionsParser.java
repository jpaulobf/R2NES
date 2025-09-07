package com.nesemu.config;

import java.util.Locale;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/** Parses CLI arguments into an AppOptions struct. */
public final class CLIOptionsParser {

    /**
     * Private constructor (static class).
     */
    private CLIOptionsParser() {
    }

    /**
     * Parses CLI args.
     * @param args
     * @return
     */
    public static AppOptions parse(String[] args) {
        AppOptions o = new AppOptions();
        if (args == null)
            return o;
        for (String a : args) {
            if (a.equalsIgnoreCase("--gui")) {
                o.gui = true;
                o.guiCliSpecified = true;
            } else if (a.equalsIgnoreCase("--no-gui")) {
                o.gui = false;
                o.guiCliSpecified = true;
            } else if (a.startsWith("--gui=")) {
                String v = a.substring(6).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) {
                    o.gui = true;
                    o.guiCliSpecified = true;
                } else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) {
                    o.gui = false;
                    o.guiCliSpecified = true;
                } else
                    Log.warn(GENERAL, "Valor inválido em --gui= (usar true|false)");
            } else if (a.startsWith("--frames=")) {
                o.frames = Integer.parseInt(a.substring(9));
            } else if (a.equalsIgnoreCase("--dump-nt")) {
                o.dumpNt = true;
            } else if (a.equalsIgnoreCase("--until-vblank")) {
                o.untilVblank = true;
            } else if (a.startsWith("--dump-pattern=")) {
                try {
                    o.dumpPattern = Integer.parseInt(a.substring(15), 16);
                } catch (NumberFormatException e) {
                    Log.error(GENERAL, "Valor de tile inválido em --dump-pattern (usar hex)");
                }
            } else if (a.startsWith("--dump-patterns=")) {
                o.dumpPatternsList = a.substring(16);
            } else if (a.equalsIgnoreCase("--header")) {
                o.showHeader = true;
            } else if (a.equalsIgnoreCase("--chr-log")) {
                o.chrLog = true;
            } else if (a.startsWith("--tile-matrix=")) {
                o.tileMatrixMode = a.substring(14).trim();
            } else if (a.startsWith("--trace-cpu=")) {
                try {
                    o.traceInstrCount = Long.parseLong(a.substring(12));
                } catch (NumberFormatException e) {
                    Log.error(CPU, "Valor inválido para --trace-cpu (usar número de instruções)");
                }
            } else if (a.equalsIgnoreCase("--trace-nmi")) {
                o.traceNmi = true;
            } else if (a.equalsIgnoreCase("--log-ppu-reg")) {
                o.logPpuReg = true;
            } else if (a.startsWith("--break-at=")) {
                try {
                    o.breakAtPc = Integer.parseInt(a.substring(11), 16);
                } catch (NumberFormatException e) {
                    Log.error(CPU, "PC inválido em --break-at (hex)");
                }
            } else if (a.startsWith("--break-read=")) {
                String spec = a.substring(13);
                String[] parts = spec.split(",");
                try {
                    o.breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        o.breakReadCount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    Log.error(CPU, "Formato --break-read=ADDR[,count] inválido");
                }
            } else if (a.startsWith("--dbg-bg-sample=")) {
                try {
                    o.dbgBgSample = Integer.parseInt(a.substring(17));
                } catch (NumberFormatException e) {
                    Log.error(PPU, "Valor inválido em --dbg-bg-sample (usar número)");
                }
            } else if (a.equalsIgnoreCase("--dbg-bg-all")) {
                o.dbgBgAll = true;
            } else if (a.equalsIgnoreCase("--init-scroll")) {
                o.initScroll = true;
            } else if (a.equalsIgnoreCase("--timing-simple")) {
                o.timingSimple = true;
            } else if (a.startsWith("--timing-mode=")) {
                String v = a.substring(14).trim().toLowerCase(Locale.ROOT);
                if (v.equals("simple") || v.equals("interleaved"))
                    o.timingModeOpt = v;
                else
                    Log.warn(GENERAL, "Valor inválido em --timing-mode= (usar simple|interleaved)");
            } else if (a.startsWith("--fast-forward-key=")) {
                o.fastForwardKeyCli = a.substring(19).trim().toLowerCase(Locale.ROOT);
                if (o.fastForwardKeyCli.isEmpty())
                    o.fastForwardKeyCli = null;
            } else if (a.startsWith("--fast-forward-max-fps=")) {
                try {
                    o.fastForwardMaxFpsCli = Integer.parseInt(a.substring(24).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --fast-forward-max-fps= (usar número inteiro)");
                }
            } else if (a.startsWith("--left-column-mode=")) {
                o.leftColumnModeOpt = a.substring(19).trim().toLowerCase(Locale.ROOT);
            } else if (a.startsWith("--spin-watch=")) {
                try {
                    o.spinWatchThreshold = Long.parseLong(a.substring(13).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --spin-watch= (usar número)");
                }
            } else if (a.startsWith("--spin-dump-bytes=")) {
                try {
                    o.spinDumpBytes = Integer.parseInt(a.substring(18).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --spin-dump-bytes= (usar número)");
                }
            } else if (a.equalsIgnoreCase("--force-sprite0-hit")) {
                o.forceSprite0Hit = true;
            } else if (a.equalsIgnoreCase("--log-mmc1")) {
                o.mmc1LogLimit = 128;
            } else if (a.startsWith("--log-mmc1=")) {
                try {
                    o.mmc1LogLimit = Integer.parseInt(a.substring(12).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --log-mmc1= (usar número)");
                }
            } else if (a.startsWith("--log-attr")) {
                if (a.contains("=")) {
                    try {
                        o.logAttrLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --log-attr= (usar número)");
                    }
                } else {
                    o.logAttrLimit = 200;
                }
            } else if (a.startsWith("--log-nt")) {
                if (a.contains("=")) {
                    try {
                        o.logNtLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --log-nt= (usar número)");
                    }
                } else {
                    o.logNtLimit = 200;
                }
            } else if (a.equalsIgnoreCase("--force-bg")) {
                o.forceBg = true;
            } else if (a.equalsIgnoreCase("--bg-col-stats")) {
                o.bgColStats = true;
            } else if (a.equalsIgnoreCase("--hud")) {
                o.hud = true;
            } else if (a.equalsIgnoreCase("--test-bands")) {
                o.testPattern = "h";
            } else if (a.equalsIgnoreCase("--test-bands-h")) {
                o.testPattern = "h";
            } else if (a.equalsIgnoreCase("--test-bands-v")) {
                o.testPattern = "v";
            } else if (a.equalsIgnoreCase("--test-checker") || a.equalsIgnoreCase("--test-xadrez")) {
                o.testPattern = "checker";
            } else if (a.startsWith("--nt-baseline=")) {
                try {
                    int eq = a.indexOf('=');
                    if (eq >= 0 && eq + 1 < a.length()) {
                        o.ntBaseline = Integer.parseInt(a.substring(eq + 1), 16) & 0xFF;
                    } else {
                        throw new NumberFormatException("empty");
                    }
                } catch (NumberFormatException e) {
                    Log.error(PPU, "Valor inválido em --nt-baseline= (usar hex)");
                }
            } else if (a.startsWith("--pipe-log")) {
                int eq = a.indexOf('=');
                if (eq >= 0 && eq + 1 < a.length()) {
                    try {
                        o.pipeLogLimit = Integer.parseInt(a.substring(eq + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --pipe-log= (usar número)");
                    }
                } else {
                    o.pipeLogLimit = 400;
                }
            } else if (a.equalsIgnoreCase("--quiet") || a.equalsIgnoreCase("--no-debug")) {
                o.quiet = true;
            } else if (a.equalsIgnoreCase("--verbose")) {
                o.verboseFlag = Boolean.TRUE;
            } else if (a.startsWith("--log-level=")) {
                o.logLevelOpt = a.substring(12).trim();
            } else if (a.startsWith("--log-cats=")) {
                o.logCatsOpt = a.substring(11).trim();
            } else if (a.equalsIgnoreCase("--log-ts")) {
                o.logTimestamps = true;
            } else if (a.startsWith("--log-palette")) {
                if (a.contains("=")) {
                    try {
                        o.paletteLogLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.warn(PPU, "Valor inválido em --log-palette= (usar número)");
                    }
                } else {
                    o.paletteLogLimit = 256;
                }
            } else if (a.startsWith("--reset-key=")) {
                o.resetKeyToken = a.substring(12).trim();
            } else if (a.equalsIgnoreCase("--unlimited-sprites")) {
                o.unlimitedSprites = Boolean.TRUE;
            } else if (a.startsWith("--unlimited-sprites=")) {
                String v = a.substring(20).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                    o.unlimitedSprites = Boolean.TRUE;
                else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                    o.unlimitedSprites = Boolean.FALSE;
                else
                    Log.warn(PPU, "Valor inválido em --unlimited-sprites= (usar true|false)");
            } else if (a.startsWith("--sprite-y=")) {
                String v = a.substring(11).trim().toLowerCase(Locale.ROOT);
                if (v.equals("hardware") || v.equals("test"))
                    o.spriteYMode = v;
                else
                    Log.warn(PPU, "Valor inválido em --sprite-y= (usar hardware|test)");
            } else if (a.startsWith("--pacer=")) {
                String v = a.substring(8).trim().toLowerCase(Locale.ROOT);
                if (v.equals("legacy") || v.equals("hr"))
                    o.pacerModeOpt = v;
                else
                    Log.warn(GENERAL, "Valor inválido em --pacer= (usar legacy|hr)");
            } else if (a.equalsIgnoreCase("--buffer-strategy")) {
                o.bufferStrategyOpt = Boolean.TRUE;
            } else if (a.startsWith("--buffer-strategy=")) {
                int eq = a.indexOf('=');
                String v = (eq >= 0 && eq + 1 < a.length()) ? a.substring(eq + 1) : "";
                v = v.trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                    o.bufferStrategyOpt = Boolean.TRUE;
                else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                    o.bufferStrategyOpt = Boolean.FALSE;
                else
                    Log.warn(GENERAL, "Valor inválido em --buffer-strategy= (usar true|false)");
            } else if (a.equalsIgnoreCase("--borderless-fullscreen")) {
                o.borderlessFullscreen = Boolean.TRUE;
            } else if (a.equalsIgnoreCase("--no-borderless-fullscreen")) {
                o.borderlessFullscreen = Boolean.FALSE;
            } else if (a.startsWith("--borderless-fullscreen=")) {
                String v = a.substring(a.indexOf('=') + 1).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                    o.borderlessFullscreen = Boolean.TRUE;
                else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                    o.borderlessFullscreen = Boolean.FALSE;
                else
                    Log.warn(GENERAL, "Valor inválido em --borderless-fullscreen= (usar true|false)");
            } else if (!a.startsWith("--")) {
                o.romPath = a;
            }
        }
        return o;
    }
}
