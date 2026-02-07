package com.nesemu.headless;

import com.nesemu.app.EmulatorContext;
import com.nesemu.config.AppOptions;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;
import java.nio.file.Path;

/**
 * Handles the headless (no-GUI) execution mode.
 * Runs the emulator for a specific number of frames or until a condition is met,
 * then dumps diagnostic information.
 */
public class HeadlessLauncher {

    private final EmulatorContext context;
    private final AppOptions options;

    public HeadlessLauncher(EmulatorContext context, AppOptions options) {
        this.context = context;
        this.options = options;
    }

    public void launch() {
        long start = System.nanoTime();
        
        if (options.untilVblank) {
            runUntilVblank();
        } else if (options.traceInstrCount > 0) {
            runTraceInstructions();
        } else {
            runFrames();
        }
        
        printStats(start);
        dumpDebugInfo();
    }

    private void runUntilVblank() {
        long executed = 0;
        long maxInstr = (options.traceInstrCount > 0) ? options.traceInstrCount : 1_000_000;
        long startCpuCycles = context.emulator.getCpu().getTotalCycles();
        
        // Manually step CPU and PPU to detect VBlank edge precisely
        while (!context.emulator.getPpu().isInVBlank() && executed < maxInstr) {
            stepInstruction();
            executed++;
        }
        
        if (context.emulator.getPpu().isInVBlank()) {
            Log.info(PPU, "UNTIL-VBLANK atingido instr=%d cpuCycles~%d frame=%d scan=%d cyc=%d status=%02X",
                    executed, (context.emulator.getCpu().getTotalCycles() - startCpuCycles),
                    context.emulator.getPpu().getFrame(),
                    context.emulator.getPpu().getScanline(), context.emulator.getPpu().getCycle(),
                    context.emulator.getPpu().getStatusRegister());
        } else {
            Log.warn(PPU, "UNTIL-VBLANK limite instr (%d) sem vblank scan=%d cyc=%d frame=%d",
                    maxInstr, context.emulator.getPpu().getScanline(), context.emulator.getPpu().getCycle(),
                    context.emulator.getPpu().getFrame());
        }
        
        // Run remaining requested frames normally
        for (int i = 0; i < options.frames; i++) {
            context.emulator.stepFrame();
        }
    }

    private void runTraceInstructions() {
        long executed = 0;
        while (executed < options.traceInstrCount) {
            int pc = context.emulator.getCpu().getPC();
            int opcode = context.emulator.getBus().read(pc);
            Log.trace(CPU, "TRACE PC=%04X OP=%02X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d",
                    pc, opcode, context.emulator.getCpu().getA(), context.emulator.getCpu().getX(), 
                    context.emulator.getCpu().getY(), context.emulator.getCpu().getStatusByte(), 
                    context.emulator.getCpu().getSP(), context.emulator.getCpu().getTotalCycles());
            
            stepInstruction();
            executed++;
            
            if (options.breakAtPc != null && context.emulator.getCpu().getPC() == (options.breakAtPc & 0xFFFF)) {
                Log.info(CPU, "BREAK PC=%04X após %d instruções", options.breakAtPc, executed);
                break;
            }
            if (options.breakReadAddr >= 0 && context.emulator.getBus().isWatchTriggered()) {
                Log.info(BUS, "BREAK leitura %04X atingida count=%d após %d instr",
                        options.breakReadAddr, options.breakReadCount, executed);
                break;
            }
            if (options.traceNmi && executed % 5000 == 0) {
                Log.debug(CPU, "trace progress instr=%d", executed);
            }
        }
        // Run remaining requested frames normally
        for (int i = 0; i < options.frames; i++) {
            context.emulator.stepFrame();
        }
    }

    private void runFrames() {
        for (int i = 0; i < options.frames; i++) {
            context.emulator.stepFrame();
        }
    }

    private void stepInstruction() {
        long before = context.emulator.getCpu().getTotalCycles();
        context.emulator.getCpu().stepInstruction();
        long after = context.emulator.getCpu().getTotalCycles();
        long cpuSpent = after - before;
        for (long c = 0; c < cpuSpent * 3; c++) {
            context.emulator.getPpu().clock();
        }
    }

    private void printStats(long startNs) {
        long elapsedNs = System.nanoTime() - startNs;
        double fpsSim = options.frames / (elapsedNs / 1_000_000_000.0);
        Log.info(GENERAL, "Frames simulados: %d (%.2f fps)", options.frames, fpsSim);
    }

    private void dumpDebugInfo() {
        if (options.pipeLogLimit > 0) {
            Log.info(PPU, "--- PIPELINE LOG ---");
            Log.info(PPU, "%s", context.emulator.getPpu().consumePipelineLog());
        }
        if (options.dbgBgSample > 0) {
            context.emulator.getPpu().dumpFirstBackgroundSamples(Math.min(options.dbgBgSample, 50));
        }
        Log.info(PPU, "--- Tile index matrix (hex of first pixel per tile) ---");
        context.emulator.getPpu().printTileIndexMatrix();
        context.emulator.getPpu().printBackgroundIndexHistogram();
        if (options.bgColStats) {
            context.emulator.getPpu().printBackgroundColumnStats();
        }
        Path out = Path.of("background.ppm");
        context.emulator.getPpu().dumpBackgroundToPpm(out);
        Log.info(PPU, "PPM gerado: %s", out.toAbsolutePath());
        if (options.dumpNt) {
            context.emulator.getPpu().printNameTableTileIds(0);
        }
        if (options.dumpPattern != null) {
            context.emulator.getPpu().dumpPatternTile(options.dumpPattern);
        }
        if (options.dumpPatternsList != null) {
            for (String part : options.dumpPatternsList.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                try {
                    int t = Integer.parseInt(part, 16);
                    context.emulator.getPpu().dumpPatternTile(t);
                } catch (NumberFormatException e) {
                    Log.error(PPU, "Tile inválido em lista: %s", part);
                }
            }
        }
    }
}