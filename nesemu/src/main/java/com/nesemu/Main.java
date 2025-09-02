package com.nesemu;

import java.nio.file.*;
import java.util.EnumSet;
import java.util.Locale;

import com.nesemu.bus.Bus;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;
import com.nesemu.gui.NesWindow;
import com.nesemu.input.InputConfig;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;
import com.nesemu.io.NesController;
import com.nesemu.ppu.Ppu2C02;

/**
 * Simple headless runner: loads a .nes ROM, executes a number of frames and
 * dumps background info.
 */
public class Main {
    private static NesController controllerPad1;
    private static NesController controllerPad2;

    public static void main(String[] args) throws Exception {
        boolean gui = false;
        String romPath = null;
        int frames = 60;
        boolean dumpNt = false;
        Integer dumpPattern = null; // tile opcional para dump
        String dumpPatternsList = null; // lista separada por vírgula (hex)
        boolean showHeader = false;
        boolean chrLog = false;
        String tileMatrixMode = null; // first|center|nonzero
        long traceInstrCount = 0;
        boolean traceNmi = false;
        boolean logPpuReg = false;
        Integer breakAtPc = null;
        int breakReadAddr = -1;
        int breakReadCount = 1;
        boolean untilVblank = false; // novo modo: roda até primeiro vblank
        int dbgBgSample = 0; // N amostras de pixels de background para debug
        boolean dbgBgAll = false; // logar todos (até limite)
        boolean initScroll = false; // inicializar scroll/addr manualmente
        boolean timingSimple = false; // modo simples de mapeamento ciclo->pixel
        int logAttrLimit = 0; // ativa logging de attribute table writes
        int logNtLimit = 0; // logging de writes de nametable (tiles)
        Integer ntBaseline = null; // filtrar valor repetitivo
        boolean forceBg = false; // força bit 3 do PPUMASK
        boolean bgColStats = false; // imprime estatísticas de colunas
        boolean hud = false; // exibe HUD na GUI
        String testPattern = null; // modos: h, v, checker
        int pipeLogLimit = 0; // ativa log do pipeline de fetch de background
        boolean quiet = false; // desabilita logs verbosos
        Boolean verboseFlag = null; // se usuário força verbose
        String logLevelOpt = null; // --log-level=TRACE|DEBUG|INFO|WARN|ERROR
        String logCatsOpt = null; // --log-cats=CPU,PPU,... or ALL
        boolean logTimestamps = false; // --log-ts
    String resetKeyToken = null; // configurable reset key (from ini)
        for (String a : args) {
            if (a.equalsIgnoreCase("--gui"))
                gui = true;
            else if (a.startsWith("--frames="))
                frames = Integer.parseInt(a.substring(9));
            else if (a.equalsIgnoreCase("--dump-nt"))
                dumpNt = true;
            else if (a.equalsIgnoreCase("--until-vblank"))
                untilVblank = true;
            else if (a.startsWith("--dump-pattern=")) {
                try {
                    dumpPattern = Integer.parseInt(a.substring(15), 16);
                } catch (NumberFormatException e) {
                    Log.error(GENERAL, "Valor de tile inválido em --dump-pattern (usar hex)");
                }
            } else if (a.startsWith("--dump-patterns=")) {
                dumpPatternsList = a.substring(16);
            } else if (a.equalsIgnoreCase("--header"))
                showHeader = true;
            else if (a.equalsIgnoreCase("--chr-log"))
                chrLog = true;
            else if (a.startsWith("--tile-matrix=")) {
                tileMatrixMode = a.substring(14).trim();
            } else if (a.startsWith("--trace-cpu=")) {
                try {
                    traceInstrCount = Long.parseLong(a.substring(12));
                } catch (NumberFormatException e) {
                    Log.error(CPU, "Valor inválido para --trace-cpu (usar número de instruções)");
                }
            } else if (a.equalsIgnoreCase("--trace-nmi"))
                traceNmi = true;
            else if (a.equalsIgnoreCase("--log-ppu-reg"))
                logPpuReg = true;
            else if (a.startsWith("--break-at=")) {
                try {
                    breakAtPc = Integer.parseInt(a.substring(11), 16);
                } catch (NumberFormatException e) {
                    Log.error(CPU, "PC inválido em --break-at (hex)");
                }
            } else if (a.startsWith("--break-read=")) {
                String spec = a.substring(13);
                String[] parts = spec.split(",");
                try {
                    breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        breakReadCount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    Log.error(CPU, "Formato --break-read=ADDR[,count] inválido");
                }
            } else if (a.startsWith("--dbg-bg-sample=")) {
                try {
                    dbgBgSample = Integer.parseInt(a.substring(17));
                } catch (NumberFormatException e) {
                    Log.error(PPU, "Valor inválido em --dbg-bg-sample (usar número)");
                }
            } else if (a.equalsIgnoreCase("--dbg-bg-all")) {
                dbgBgAll = true;
            } else if (a.equalsIgnoreCase("--init-scroll")) {
                initScroll = true;
            } else if (a.equalsIgnoreCase("--timing-simple")) {
                timingSimple = true;
            } else if (a.startsWith("--log-attr")) {
                if (a.contains("=")) {
                    try {
                        logAttrLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --log-attr= (usar número)");
                    }
                } else {
                    logAttrLimit = 200; // default
                }
            } else if (a.startsWith("--log-nt")) {
                if (a.contains("=")) {
                    try {
                        logNtLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --log-nt= (usar número)");
                    }
                } else {
                    logNtLimit = 200;
                }
            } else if (a.equalsIgnoreCase("--force-bg")) {
                forceBg = true;
            } else if (a.equalsIgnoreCase("--bg-col-stats")) {
                bgColStats = true;
            } else if (a.equalsIgnoreCase("--hud")) {
                hud = true;
            } else if (a.equalsIgnoreCase("--test-bands")) { // retrocompatível -> horizontal
                testPattern = "h";
            } else if (a.equalsIgnoreCase("--test-bands-h")) {
                testPattern = "h";
            } else if (a.equalsIgnoreCase("--test-bands-v")) {
                testPattern = "v";
            } else if (a.equalsIgnoreCase("--test-checker") || a.equalsIgnoreCase("--test-xadrez")) {
                testPattern = "checker";
            } else if (a.startsWith("--nt-baseline=")) {
                try {
                    int eq = a.indexOf('=');
                    if (eq >= 0 && eq + 1 < a.length()) {
                        ntBaseline = Integer.parseInt(a.substring(eq + 1), 16) & 0xFF;
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
                        pipeLogLimit = Integer.parseInt(a.substring(eq + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --pipe-log= (usar número)");
                    }
                } else {
                    pipeLogLimit = 400; // default se não informado
                }
            } else if (a.equalsIgnoreCase("--quiet")) {
                quiet = true;
            } else if (a.equalsIgnoreCase("--no-debug")) {
                quiet = true;
            } else if (a.equalsIgnoreCase("--verbose")) {
                verboseFlag = Boolean.TRUE;
            } else if (a.startsWith("--log-level=")) {
                logLevelOpt = a.substring(12).trim();
            } else if (a.startsWith("--log-cats=")) {
                logCatsOpt = a.substring(11).trim();
            } else if (a.equalsIgnoreCase("--log-ts")) {
                logTimestamps = true;
            } else if (!a.startsWith("--"))
                romPath = a;
        }
        if (romPath == null)
            romPath = "D:\\Desenvolvimento\\Games\\Nesemu\\nesemu\\roms\\donkeykong.nes";
        Path p = Path.of(romPath);
        if (!Files.exists(p)) {
            Log.error(ROM, "ROM não encontrada: %s", romPath);
            return;
        }
        Log.info(ROM, "Carregando ROM: %s", p.toAbsolutePath());
        INesRom rom = RomLoader.load(p);
        NesEmulator emu = new NesEmulator(rom);
        // Load configuration file for controller + global option fallbacks
        InputConfig inputCfg;
        try {
            var inputPath = Path.of("emulator.ini");
            if (Files.exists(inputPath))
                inputCfg = InputConfig.load(inputPath);
            else {
                var devPath = Path.of("src/main/java/com/nesemu/config/emulator.ini");
                if (Files.exists(devPath))
                    inputCfg = InputConfig.load(devPath);
                else
                    inputCfg = new InputConfig();
            }
            // Apply fallback options (only where CLI not specified)
            if (inputCfg.hasOption("quiet") && !quiet)
                quiet = Boolean.parseBoolean(inputCfg.getOption("quiet"));
            if (!gui && inputCfg.hasOption("gui"))
                gui = Boolean.parseBoolean(inputCfg.getOption("gui"));
            if (verboseFlag == null && inputCfg.hasOption("verbose"))
                verboseFlag = Boolean.parseBoolean(inputCfg.getOption("verbose"));
            if (!hud && inputCfg.hasOption("hud"))
                hud = Boolean.parseBoolean(inputCfg.getOption("hud"));
            if (logLevelOpt == null && inputCfg.hasOption("log-level"))
                logLevelOpt = inputCfg.getOption("log-level");
            if (logCatsOpt == null && inputCfg.hasOption("log-cats"))
                logCatsOpt = inputCfg.getOption("log-cats");
            if (!logTimestamps && inputCfg.hasOption("log-ts"))
                logTimestamps = Boolean.parseBoolean(inputCfg.getOption("log-ts"));
            if (tileMatrixMode == null && inputCfg.hasOption("tile-matrix"))
                tileMatrixMode = inputCfg.getOption("tile-matrix");
            if (!chrLog && inputCfg.hasOption("chr-log"))
                chrLog = Boolean.parseBoolean(inputCfg.getOption("chr-log"));
            if (!dumpNt && inputCfg.hasOption("dump-nt"))
                dumpNt = Boolean.parseBoolean(inputCfg.getOption("dump-nt"));
            if (dumpPattern == null && inputCfg.hasOption("dump-pattern"))
                try {
                    dumpPattern = Integer.parseInt(inputCfg.getOption("dump-pattern"), 16);
                } catch (Exception ignore) {
                }
            if (dumpPatternsList == null && inputCfg.hasOption("dump-patterns"))
                dumpPatternsList = inputCfg.getOption("dump-patterns");
            if (!traceNmi && inputCfg.hasOption("trace-nmi"))
                traceNmi = Boolean.parseBoolean(inputCfg.getOption("trace-nmi"));
            if (!logPpuReg && inputCfg.hasOption("log-ppu-reg"))
                logPpuReg = Boolean.parseBoolean(inputCfg.getOption("log-ppu-reg"));
            if (breakAtPc == null && inputCfg.hasOption("break-at"))
                try {
                    breakAtPc = Integer.parseInt(inputCfg.getOption("break-at"), 16);
                } catch (Exception ignore) {
                }
            if (breakReadAddr < 0 && inputCfg.hasOption("break-read"))
                try {
                    var spec = inputCfg.getOption("break-read");
                    var parts = spec.split(",");
                    breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        breakReadCount = Integer.parseInt(parts[1]);
                } catch (Exception ignore) {
                }
            if (!untilVblank && inputCfg.hasOption("until-vblank"))
                untilVblank = Boolean.parseBoolean(inputCfg.getOption("until-vblank"));
            if (dbgBgSample == 0 && inputCfg.hasOption("dbg-bg-sample"))
                try {
                    dbgBgSample = Integer.parseInt(inputCfg.getOption("dbg-bg-sample"));
                } catch (Exception ignore) {
                }
            if (!dbgBgAll && inputCfg.hasOption("dbg-bg-all"))
                dbgBgAll = Boolean.parseBoolean(inputCfg.getOption("dbg-bg-all"));
            if (!initScroll && inputCfg.hasOption("init-scroll"))
                initScroll = Boolean.parseBoolean(inputCfg.getOption("init-scroll"));
            if (!timingSimple && inputCfg.hasOption("timing-simple"))
                timingSimple = Boolean.parseBoolean(inputCfg.getOption("timing-simple"));
            if (logAttrLimit == 0 && inputCfg.hasOption("log-attr"))
                try {
                    logAttrLimit = Integer.parseInt(inputCfg.getOption("log-attr"));
                } catch (Exception ignore) {
                }
            if (logNtLimit == 0 && inputCfg.hasOption("log-nt"))
                try {
                    logNtLimit = Integer.parseInt(inputCfg.getOption("log-nt"));
                } catch (Exception ignore) {
                }
            if (ntBaseline == null && inputCfg.hasOption("nt-baseline"))
                try {
                    ntBaseline = Integer.parseInt(inputCfg.getOption("nt-baseline"), 16) & 0xFF;
                } catch (Exception ignore) {
                }
            if (!forceBg && inputCfg.hasOption("force-bg"))
                forceBg = Boolean.parseBoolean(inputCfg.getOption("force-bg"));
            if (!bgColStats && inputCfg.hasOption("bg-col-stats"))
                bgColStats = Boolean.parseBoolean(inputCfg.getOption("bg-col-stats"));
            if (testPattern == null && inputCfg.hasOption("test-pattern"))
                testPattern = inputCfg.getOption("test-pattern");
            if (pipeLogLimit == 0 && inputCfg.hasOption("pipe-log"))
                try {
                    pipeLogLimit = Integer.parseInt(inputCfg.getOption("pipe-log"));
                } catch (Exception ignore) {
                }
            if (frames == 60 && inputCfg.hasOption("frames"))
                try {
                    frames = Integer.parseInt(inputCfg.getOption("frames"));
                } catch (Exception ignore) {
                }
            if (inputCfg.hasOption("reset")) {
                resetKeyToken = inputCfg.getOption("reset");
            }
            // Controllers
            var pad1 = new NesController(inputCfg.getController(0));
            var pad2 = new NesController(inputCfg.getController(1));
            emu.getBus().attachControllers(pad1, pad2);
            controllerPad1 = pad1;
            controllerPad2 = pad2;
        } catch (Exception ex) {
            Log.warn(CONTROLLER, "Falha ao carregar configuração de input: %s", ex.getMessage());
        }
        // Aplicar política de verbosidade
        if (quiet) {
            Ppu2C02.setVerboseLogging(false);
            Bus.setGlobalVerbose(false);
        } else if (verboseFlag != null && verboseFlag) {
            Ppu2C02.setVerboseLogging(true);
            Bus.setGlobalVerbose(true);
        }
        // Configurar nível de log se fornecido
        if (logLevelOpt != null) {
            try {
                Log.setLevel(Log.Level.valueOf(logLevelOpt.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                Log.warn(GENERAL, "Nível de log inválido: %s (usar TRACE|DEBUG|INFO|WARN|ERROR)", logLevelOpt);
            }
        } else if (verboseFlag != null && verboseFlag && !quiet) {
            Log.setLevel(Log.Level.DEBUG);
        }
        // Configurar categorias
        if (logCatsOpt != null) {
            if (logCatsOpt.equalsIgnoreCase("ALL")) {
                Log.setCategories(EnumSet.allOf(Log.Cat.class));
            } else {
                EnumSet<Log.Cat> set = EnumSet.noneOf(Log.Cat.class);
                for (String c : logCatsOpt.split(",")) {
                    c = c.trim();
                    if (c.isEmpty())
                        continue;
                    try {
                        set.add(Log.Cat.valueOf(c.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        Log.warn(GENERAL, "Categoria de log inválida ignorada: %s", c);
                    }
                }
                if (set.isEmpty()) {
                    Log.warn(GENERAL, "Nenhuma categoria válida em --log-cats, mantendo padrão.");
                } else {
                    Log.setCategories(set);
                }
            }
        }
        if (logTimestamps) {
            Log.setTimestamps(true);
        }
        if (tileMatrixMode != null) {
            emu.getPpu().setTileMatrixMode(tileMatrixMode);
            Log.info(PPU, "Tile matrix mode: %s", tileMatrixMode);
        }
        // Se solicitou pipe-log e nenhum modo de tile matrix foi especificado, usar
        // 'center'
        if (pipeLogLimit > 0 && tileMatrixMode == null) {
            emu.getPpu().setTileMatrixMode("center");
            Log.debug(PPU, "PIPE-LOG ajustando tileMatrixMode=center");
        }
        if (pipeLogLimit > 0) {
            emu.getPpu().enablePipelineLog(pipeLogLimit);
            Log.info(PPU, "PIPE-LOG habilitado limite=%d", pipeLogLimit);
        }
        if (dbgBgSample > 0) {
            if (dbgBgAll)
                emu.getPpu().enableBackgroundSampleDebugAll(dbgBgSample);
            else
                emu.getPpu().enableBackgroundSampleDebug(dbgBgSample);
        }
        if (timingSimple) {
            emu.getPpu().setSimpleTiming(true);
        }
        if (forceBg) {
            emu.getPpu().setForceBackgroundEnable(true);
            Log.info(PPU, "FORCE-BG bit3 PPUMASK");
        }
        if (testPattern != null) {
            emu.getPpu().setTestPatternMode(testPattern);
            Log.info(PPU, "TEST-PATTERN modo=%s", testPattern);
        }
        if (logAttrLimit > 0) {
            emu.getPpu().enableAttributeRuntimeLog(logAttrLimit);
        }
        if (logNtLimit > 0) {
            emu.getPpu().enableNametableRuntimeLog(logNtLimit, ntBaseline == null ? -1 : ntBaseline);
        }
        if (initScroll) {
            // Configura scroll e VRAM address iniciais (coarse/fine = 0, nametable 0)
            emu.getBus().cpuWrite(0x2000, 0x10); // background pattern table = $1000
            emu.getBus().cpuWrite(0x2005, 0x00); // X scroll
            emu.getBus().cpuWrite(0x2005, 0x00); // Y scroll
            emu.getBus().cpuWrite(0x2006, 0x20); // high byte (0x2000)
            emu.getBus().cpuWrite(0x2006, 0x00); // low byte
            Log.debug(PPU, "INIT-SCROLL VRAM inicializada nametable0 pattern $1000");
        }
        if (showHeader) {
            var h = rom.getHeader();
            Log.info(ROM, "Header: PRG=%d x16KB (%d bytes) CHR=%d x8KB (%d bytes) Mapper=%d Mirroring=%s",
                    h.getPrgRomPages(), h.getPrgRomPages() * 16384, h.getChrRomPages(), h.getChrRomPages() * 8192,
                    h.getMapper(),
                    h.isVerticalMirroring() ? "VERTICAL" : "HORIZONTAL");
        }
        if (chrLog) {
            emu.getBus().getMapper0().enableChrLogging(256);
        }
        if (logPpuReg) {
            emu.getBus().enablePpuRegLogging(800);
        }
        if (breakReadAddr >= 0) {
            emu.getBus().setWatchReadAddress(breakReadAddr, breakReadCount);
            Log.info(BUS, "Watch leitura %04X count=%d", breakReadAddr, breakReadCount);
        }
        if (traceNmi) {
            emu.getPpu().setNmiCallback(() -> {
                int pc = emu.getCpu().getPC();
                Log.debug(PPU, "NMI frame=%d PC=%04X cycles=%d", emu.getFrame(), pc,
                        emu.getCpu().getTotalCycles());
            });
        }
        // Optionally force background enable early (most games set this quickly anyway)
        emu.getBus().cpuWrite(0x2001, 0x08);
        if (gui) {
            NesWindow window = new NesWindow("NESemu - " + p.getFileName(), 3);
            if (controllerPad1 != null) {
                final String resetTok = resetKeyToken == null ? null : resetKeyToken.toLowerCase(Locale.ROOT).trim();
                window.installControllerKeyListener(controllerPad1, controllerPad2, resetTok, () -> {
                    Log.info(GENERAL, "RESET key pressed (%s)", resetTok);
                    emu.reset();
                });
            }
            window.show(emu.getPpu().getFrameBuffer());
            if (hud) {
                var ppu = emu.getPpu();
                window.setOverlay(g2 -> {
                    int pad = 4;
                    String l1 = String.format("Frame:%d FPS:%.1f", ppu.getFrame(), window.getLastFps());
                    String l2 = String.format("Scan:%d Cyc:%d VRAM:%04X", ppu.getScanline(), ppu.getCycle(),
                            ppu.getVramAddress() & 0x3FFF);
                    String l3 = String.format("MASK:%02X STAT:%02X fineX:%d", ppu.getMaskRegister(),
                            ppu.getStatusRegister(), ppu.getFineX());
                    String btns = controllerPad1 != null ? controllerPad1.pressedButtonsString() : "-";
                    String l4 = "Pad1: " + btns;
                    int boxH = 56;
                    g2.setColor(new java.awt.Color(0, 0, 0, 160));
                    g2.fillRect(0, 0, 260, boxH);
                    g2.setColor(java.awt.Color.WHITE);
                    g2.drawString(l1, pad, 12);
                    g2.drawString(l2, pad, 24);
                    g2.drawString(l3, pad, 36);
                    g2.drawString(l4, pad, 48);
                });
            }
            Log.info(GENERAL, "Iniciando GUI (Ctrl+C para sair)");
            window.startRenderLoop(() -> {
                emu.stepFrame();
            }, 60); // target 60 fps
        } else {
            long start = System.nanoTime();
            if (untilVblank) {
                long executed = 0;
                long maxInstr = (traceInstrCount > 0) ? traceInstrCount : 1_000_000; // guarda de segurança
                long startCpuCycles = emu.getCpu().getTotalCycles();
                while (!emu.getPpu().isInVBlank() && executed < maxInstr) {
                    long before = emu.getCpu().getTotalCycles();
                    emu.getCpu().stepInstruction();
                    long after = emu.getCpu().getTotalCycles();
                    long cpuSpent = after - before;
                    for (long c = 0; c < cpuSpent * 3; c++) {
                        emu.getPpu().clock();
                    }
                    executed++;
                }
                if (emu.getPpu().isInVBlank()) {
                    Log.info(PPU,
                            "UNTIL-VBLANK atingido instr=%d cpuCycles~%d frame=%d scan=%d cyc=%d status=%02X",
                            executed, (emu.getCpu().getTotalCycles() - startCpuCycles), emu.getPpu().getFrame(),
                            emu.getPpu().getScanline(), emu.getPpu().getCycle(), emu.getPpu().getStatusRegister());
                } else {
                    Log.warn(PPU,
                            "UNTIL-VBLANK limite instr (%d) sem vblank scan=%d cyc=%d frame=%d",
                            maxInstr, emu.getPpu().getScanline(), emu.getPpu().getCycle(), emu.getPpu().getFrame());
                }
                // Depois roda frames solicitados (se frames>0)
                for (int i = 0; i < frames; i++)
                    emu.stepFrame();
            } else if (traceInstrCount > 0) {
                // Trace N instruções ignorando frames, depois continua frames restantes se
                // definido
                long executed = 0;
                while (executed < traceInstrCount) {
                    int pc = emu.getCpu().getPC();
                    int opcode = emu.getBus().read(pc);
                    Log.trace(CPU, "TRACE PC=%04X OP=%02X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d",
                            pc, opcode, emu.getCpu().getA(), emu.getCpu().getX(), emu.getCpu().getY(),
                            emu.getCpu().getStatusByte(), emu.getCpu().getSP(), emu.getCpu().getTotalCycles());
                    // Executa instrução enquanto alimenta PPU com 3 ciclos por ciclo de CPU gasto.
                    long before = emu.getCpu().getTotalCycles();
                    emu.getCpu().stepInstruction();
                    long after = emu.getCpu().getTotalCycles();
                    long cpuSpent = after - before;
                    for (long c = 0; c < cpuSpent * 3; c++) {
                        emu.getPpu().clock();
                    }
                    executed++;
                    if (breakAtPc != null && emu.getCpu().getPC() == (breakAtPc & 0xFFFF)) {
                        Log.info(CPU, "BREAK PC=%04X após %d instruções", breakAtPc, executed);
                        break;
                    }
                    if (breakReadAddr >= 0 && emu.getBus().isWatchTriggered()) {
                        Log.info(BUS, "BREAK leitura %04X atingida count=%d após %d instr",
                                breakReadAddr, breakReadCount, executed);
                        break;
                    }
                    if (traceNmi && executed % 5000 == 0) {
                        Log.debug(CPU, "trace progress instr=%d", executed);
                    }
                }
                // Depois roda frames solicitados (se frames>0)
                for (int i = 0; i < frames; i++)
                    emu.stepFrame();
            } else {
                for (int i = 0; i < frames; i++) {
                    emu.stepFrame();
                }
            }
            long elapsedNs = System.nanoTime() - start;
            double fpsSim = frames / (elapsedNs / 1_000_000_000.0);
            Log.info(GENERAL, "Frames simulados: %d (%.2f fps)", frames, fpsSim);
            if (pipeLogLimit > 0) {
                Log.info(PPU, "--- PIPELINE LOG ---");
                Log.info(PPU, "%s", emu.getPpu().consumePipelineLog());
            }
            if (dbgBgSample > 0) {
                emu.getPpu().dumpFirstBackgroundSamples(Math.min(dbgBgSample, 50));
            }
            // Dump ASCII matrix (first pixel per tile)
            Log.info(PPU, "--- Tile index matrix (hex of first pixel per tile) ---");
            emu.getPpu().printTileIndexMatrix();
            emu.getPpu().printBackgroundIndexHistogram();
            if (bgColStats) {
                emu.getPpu().printBackgroundColumnStats();
            }
            // Dump PPM (palette index grayscale)
            Path out = Path.of("background.ppm");
            emu.getPpu().dumpBackgroundToPpm(out);
            Log.info(PPU, "PPM gerado: %s", out.toAbsolutePath());
            if (dumpNt) {
                emu.getPpu().printNameTableTileIds(0);
            }
            if (dumpPattern != null) {
                emu.getPpu().dumpPatternTile(dumpPattern);
            }
            if (dumpPatternsList != null) {
                for (String part : dumpPatternsList.split(",")) {
                    part = part.trim();
                    if (part.isEmpty())
                        continue;
                    try {
                        int t = Integer.parseInt(part, 16);
                        emu.getPpu().dumpPatternTile(t);
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Tile inválido em lista: %s", part);
                    }
                }
            }
        }
    }
}