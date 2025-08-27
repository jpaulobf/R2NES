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
        long traceInstrCount = 0;
        boolean traceNmi = false;
        boolean logPpuReg = false;
        Integer breakAtPc = null;
        int breakReadAddr = -1;
        int breakReadCount = 1;
        for (String a : args) {
            if (a.equalsIgnoreCase("--gui"))
                gui = true;
            else if (a.startsWith("--frames="))
                frames = Integer.parseInt(a.substring(9));
            else if (a.equalsIgnoreCase("--dump-nt"))
                dumpNt = true;
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
            else if (a.startsWith("--trace-cpu=")) {
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
            } else if (!a.startsWith("--"))
                romPath = a;
        }
        if (romPath == null)
            romPath = "roms/donkeykong.nes";
        Path p = Path.of(romPath);
        if (!Files.exists(p)) {
            System.err.println("ROM não encontrada: " + romPath);
            return;
        }
        System.out.println("Carregando ROM: " + p.toAbsolutePath());
        INesRom rom = RomLoader.load(p);
        NesEmulator emu = new NesEmulator(rom);
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
            System.out.println("Iniciando loop de render em modo GUI (Ctrl+C para sair)...");
            window.startRenderLoop(() -> {
                emu.stepFrame();
            }, 60); // target 60 fps
        } else {
            long start = System.nanoTime();
            if (traceInstrCount > 0) {
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
            // Dump ASCII matrix (first pixel per tile)
            System.out.println("--- Tile index matrix (hex of first pixel per tile) ---");
            emu.getPpu().printTileIndexMatrix();
            emu.getPpu().printBackgroundIndexHistogram();
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