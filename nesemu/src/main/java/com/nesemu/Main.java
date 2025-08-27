package com.nesemu;

import java.nio.file.*;

import com.nesemu.emulator.NesEmulator;
import com.nesemu.gui.NesWindow;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;

/**
 * Simple headless runner: loads a .nes ROM, executes a number of frames and
 * dumps
 * background information for inspection (ASCII tile matrix + PPM of palette
 * indices).
 * Usage: java -cp nesemu.jar com.nesemu.Main path/to/game.nes [frames]
 */
public class Main {
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
                    System.err.println("Valor de tile inválido em --dump-pattern (usar hex)");
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
                    System.err.println("Valor inválido para --trace-cpu (usar número de instruções)");
                }
            } else if (a.equalsIgnoreCase("--trace-nmi"))
                traceNmi = true;
            else if (a.equalsIgnoreCase("--log-ppu-reg"))
                logPpuReg = true;
            else if (a.startsWith("--break-at=")) {
                try {
                    breakAtPc = Integer.parseInt(a.substring(11), 16);
                } catch (NumberFormatException e) {
                    System.err.println("PC inválido em --break-at (hex)");
                }
            } else if (a.startsWith("--break-read=")) {
                String spec = a.substring(13);
                String[] parts = spec.split(",");
                try {
                    breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        breakReadCount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    System.err.println("Formato --break-read=ADDR[,count] inválido");
                }
            } else if (a.startsWith("--dbg-bg-sample=")) {
                try {
                    dbgBgSample = Integer.parseInt(a.substring(17));
                } catch (NumberFormatException e) {
                    System.err.println("Valor inválido em --dbg-bg-sample (usar número)");
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
                        System.err.println("Valor inválido em --log-attr= (usar número)");
                    }
                } else {
                    logAttrLimit = 200; // default
                }
            } else if (a.startsWith("--log-nt")) {
                if (a.contains("=")) {
                    try {
                        logNtLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        System.err.println("Valor inválido em --log-nt= (usar número)");
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
                    System.err.println("Valor inválido em --nt-baseline= (usar hex)");
                }
            } else if (!a.startsWith("--"))
                romPath = a;
        }
        if (romPath == null)
            romPath = "D:\\Desenvolvimento\\Games\\Nesemu\\nesemu\\roms\\donkeykong.nes";
        Path p = Path.of(romPath);
        if (!Files.exists(p)) {
            System.err.println("ROM não encontrada: " + romPath);
            return;
        }
        System.out.println("Carregando ROM: " + p.toAbsolutePath());
        INesRom rom = RomLoader.load(p);
        NesEmulator emu = new NesEmulator(rom);
        if (tileMatrixMode != null) {
            emu.getPpu().setTileMatrixMode(tileMatrixMode);
            System.out.println("Tile matrix mode: " + tileMatrixMode);
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
            System.out.println("[FORCE-BG] Forçando bit 3 (background) em PPUMASK");
        }
        if (testPattern != null) {
            emu.getPpu().setTestPatternMode(testPattern);
            System.out.println("[TEST-PATTERN] modo=" + testPattern);
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
            System.out.println("[INIT-SCROLL] Scroll e VRAM inicializados (nametable 0, pattern $1000)");
        }
        if (showHeader) {
            var h = rom.getHeader();
            System.out.printf("Header: PRG=%d x16KB (%d bytes) CHR=%d x8KB (%d bytes) Mapper=%d Mirroring=%s%n",
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
            System.out.printf("Watch de leitura armado em %04X count=%d\n", breakReadAddr, breakReadCount);
        }
        if (traceNmi) {
            emu.getPpu().setNmiCallback(() -> {
                int pc = emu.getCpu().getPC();
                System.out.printf("[NMI FIRING] frame=%d PC=%04X cycles=%d\n", emu.getFrame(), pc,
                        emu.getCpu().getTotalCycles());
            });
        }
        // Optionally force background enable early (most games set this quickly anyway)
        emu.getBus().cpuWrite(0x2001, 0x08);
        if (gui) {
            NesWindow window = new NesWindow("NESemu - " + p.getFileName(), 3);
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
                    int boxH = 44;
                    g2.setColor(new java.awt.Color(0, 0, 0, 160));
                    g2.fillRect(0, 0, 210, boxH);
                    g2.setColor(java.awt.Color.WHITE);
                    g2.drawString(l1, pad, 12);
                    g2.drawString(l2, pad, 24);
                    g2.drawString(l3, pad, 36);
                });
            }
            System.out.println("Iniciando loop de render em modo GUI (Ctrl+C para sair)...");
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
                    System.out.printf(
                            "[UNTIL-VBLANK] vblank atingido após %d instruções (CPU cycles ~%d) frame=%d scan=%d cyc=%d status=%02X%n",
                            executed, (emu.getCpu().getTotalCycles() - startCpuCycles), emu.getPpu().getFrame(),
                            emu.getPpu().getScanline(), emu.getPpu().getCycle(), emu.getPpu().getStatusRegister());
                } else {
                    System.out.printf(
                            "[UNTIL-VBLANK] limite de instruções (%d) atingido sem vblank. scan=%d cyc=%d frame=%d%n",
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
                    System.out.printf("TRACE PC=%04X OP=%02X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d\n",
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
                        System.out.printf("[BREAK] PC atingiu %04X após %d instruções\n", breakAtPc, executed);
                        break;
                    }
                    if (breakReadAddr >= 0 && emu.getBus().isWatchTriggered()) {
                        System.out.printf("[BREAK] Leitura monitorada %04X atingida (count=%d) após %d instruções\n",
                                breakReadAddr, breakReadCount, executed);
                        break;
                    }
                    if (traceNmi && executed % 5000 == 0) {
                        System.out.println("-- trace progress instr=" + executed);
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
            System.out.printf("Frames simulados: %d (%.2f fps simulado)\n", frames, fpsSim);
            if (dbgBgSample > 0) {
                emu.getPpu().dumpFirstBackgroundSamples(Math.min(dbgBgSample, 50));
            }
            // Dump ASCII matrix (first pixel per tile)
            System.out.println("--- Tile index matrix (hex of first pixel per tile) ---");
            emu.getPpu().printTileIndexMatrix();
            emu.getPpu().printBackgroundIndexHistogram();
            if (bgColStats) {
                emu.getPpu().printBackgroundColumnStats();
            }
            // Dump PPM (palette index grayscale)
            Path out = Path.of("background.ppm");
            emu.getPpu().dumpBackgroundToPpm(out);
            System.out.println("PPM gerado: " + out.toAbsolutePath());
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
                        System.err.println("Tile inválido em lista: " + part);
                    }
                }
            }
        }
    }
}