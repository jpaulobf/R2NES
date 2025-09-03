package com.nesemu.debbuger;

import com.nesemu.bus.Bus;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.cpu.CPU;
import com.nesemu.cpu.interfaces.NesCPU;
import com.nesemu.mapper.Mapper0;
import com.nesemu.mapper.Mapper2;
import com.nesemu.mapper.Mapper3;
import com.nesemu.mapper.Mapper5;
import com.nesemu.ppu.PPU;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;

import java.nio.file.Path;
import java.util.Locale;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * Runner genérico para ROMs de teste estilo blargg:
 * Protocolo: $6000 = 0 em execução; $6000 = 1 PASS; $6000 > 1 FAIL.
 * Mensagem ASCII iniciando em $6004 terminada por 0.
 * Uso: java -cp target/... com.nesemu.tools.GenericTestRunner rom.nes
 * [maxInstr] [maxCycles] [--debug]
 */
public class GenericTestRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            Log.error(TEST, "Usage: GenericTestRunner <romFile> [maxInstr] [maxCycles]");
            return;
        }
        Path romPath = Path.of(args[0]);
        long maxInstr = args.length >= 2 ? Long.parseLong(args[1]) : 5_000_000L;
        long maxCycles = args.length >= 3 ? Long.parseLong(args[2]) : 50_000_000L;
        boolean debug = false;
        for (String a : args)
            if ("--debug".equalsIgnoreCase(a))
                debug = true;

        INesRom rom = RomLoader.load(romPath);
        int mapperNum = rom.getHeader().getMapper();
        // Build minimal system selecting mapper
        Mapper0 mapper0 = null;
        Mapper3 mapper3 = null;
        Mapper2 mapper2 = null;
        Mapper5 mapper5 = null;
        switch (mapperNum) {
            case 0:
                mapper0 = new Mapper0(rom);
                break;
            case 2:
                mapper2 = new Mapper2(rom);
                break;
            case 3:
                mapper3 = new Mapper3(rom);
                break;
            case 5:
                mapper5 = new Mapper5(rom);
                break;
            default:
                Log.warn(TEST, "Mapper %d not explicitly supported; using NROM fallback.", mapperNum);
                mapper0 = new Mapper0(rom);
        }
        PPU ppu = new PPU();
        ppu.reset();
        Bus bus = new Bus();
        bus.attachPPU(ppu);
        if (mapper0 != null) {
            bus.attachMapper(mapper0, rom);
        } else if (mapper2 != null) {
            bus.attachMapper(mapper2, rom);
        } else if (mapper3 != null) {
            bus.attachMapper(mapper3, rom);
        } else if (mapper5 != null) {
            bus.attachMapper(mapper5, rom);
        }
        NesBus baseBus = bus;
        NesBus cpuBus = baseBus;
        DebugTap tap = null;
        if (debug) {
            tap = new DebugTap(baseBus, 50_000, ppu); // capture first 50k ops
            cpuBus = tap;
        }
        NesCPU cpu = new CPU(cpuBus);
        // Allow PPU->CPU NMI wiring
        bus.attachCPU(cpu);

        // Normal reset vector start (não força estado custom)
        long instrCount = 0;
        long lastReport = 0;
        boolean vblankLogged = false;
        long lastScanReport = 0;
        long nextInstrMilestone = 10_000;
        long timingReportInterval = 5_000; // report every 5k instructions for timing sanity
        // Early instruction trace (first N) capturing PC/opcode/total cycles
        final int traceLimit = debug ? 512 : 0;
        int traceCount = 0;
        StringBuilder earlyTrace = new StringBuilder();
        // Instrumentação do laço inicial em E596-E599
        boolean initLoopSeen = false; // já entrou no laço
        long initLoopIterations = 0; // contagem de iterações (DEY)
        boolean initLoopExitReported = false;
        boolean initLoopStuckReported = false;
        while (true) {
            if (debug && !vblankLogged && ppu.isInVBlank()) {
                Log.debug(TEST, "VBLANK_REACHED instr=%d cycles=%d scanline=%d ppuCycle=%d status2002=%02X",
                        instrCount, cpu.getTotalCycles(), ppu.getScanline(), ppu.getCycle(), bus.read(0x2002));
                vblankLogged = true;
            }
            if (debug && instrCount - lastScanReport >= 100_000) {
                lastScanReport = instrCount;
                Log.debug(TEST, "DBG instr=%d cpuCycles=%d scan=%d ppuCycle=%d vblank=%s status2002=%02X",
                        instrCount, cpu.getTotalCycles(), ppu.getScanline(), ppu.getCycle(), ppu.isInVBlank(),
                        bus.read(0x2002));
            }
            if (debug && instrCount >= nextInstrMilestone) {
                Log.info(TEST, "MILESTONE instr=%d cpuCycles=%d scan=%d ppuCycle=%d vblank=%s status2002=%02X",
                        instrCount, cpu.getTotalCycles(), ppu.getScanline(), ppu.getCycle(), ppu.isInVBlank(),
                        bus.read(0x2002));
                nextInstrMilestone += 10_000;
            }
            if (instrCount >= maxInstr || cpu.getTotalCycles() >= maxCycles) {
                Log.warn(TEST,
                        "TIMEOUT instr=%d cycles=%d status=%02X msg=%s finalScan=%d finalPpuCycle=%d vblank=%s",
                        instrCount, cpu.getTotalCycles(), cpuBus.read(0x6000), readMsg(cpuBus), ppu.getScanline(),
                        ppu.getCycle(), ppu.isInVBlank());
                long actualPpuCycles = computePpuCycles(ppu.getScanline(), ppu.getCycle());
                long expectedPpuCycles = cpu.getTotalCycles() * 3L;
                Log.info(TEST,
                        "TIMING_SUMMARY cpuCycles=%d expectedPpuCycles=%d actualPpuCycles=%d ratio=%.4f scan=%d cyc=%d",
                        cpu.getTotalCycles(), expectedPpuCycles, actualPpuCycles,
                        (expectedPpuCycles > 0 ? (double) actualPpuCycles / expectedPpuCycles : 0.0), ppu.getScanline(),
                        ppu.getCycle());
                if (traceCount > 0 && traceCount < traceLimit) {
                    Log.info(TEST, "--- Early Instruction Trace (partial %d/%d) ---%n%s", traceCount, traceLimit,
                            earlyTrace.toString());
                }
                if (tap != null)
                    tap.dumpSummary();
                break;
            }
            int status = cpuBus.read(0x6000) & 0xFF;
            if (status != 0) {
                String msg = readMsg(cpuBus);
                if (status == 1) {
                    Log.info(TEST, "PASS instr=%d cycles=%d msg=%s", instrCount, cpu.getTotalCycles(), msg);
                } else {
                    Log.error(TEST, "FAIL code=%02X instr=%d cycles=%d msg=%s", status, instrCount,
                            cpu.getTotalCycles(), msg);
                }
                if (traceCount > 0 && traceCount < traceLimit) {
                    Log.info(TEST, "--- Early Instruction Trace (partial %d/%d) ---%n%s", traceCount, traceLimit,
                            earlyTrace.toString());
                }
                if (tap != null)
                    tap.dumpSummary();
                break;
            }
            // Execute one instruction with per-cycle PPU stepping for accurate vblank
            // polling
            long beforeCycles = cpu.getTotalCycles();
            long cycStart = beforeCycles;
            do {
                cpu.clock();
                // Advance PPU three cycles per CPU cycle
                for (int c = 0; c < 3; c++)
                    ppu.clock();
            } while (!cpu.isInstructionBoundary());
            long consumed = cpu.getTotalCycles() - cycStart;
            if (consumed == 0) {
                Log.error(TEST, "ASSERT_FAIL consumed==0 pc=%04X lastOpcode=%02X totalCycles=%d",
                        cpu.getPC(), cpu.getLastOpcodeByte(), cpu.getTotalCycles());
                break;
            }
            if (debug) {
                int lastPC = cpu.getLastInstrPC();
                // Extra diagnostics for BIT $2002 / BPL loop
                if (lastPC == 0xE59E) { // BIT $2002
                    int neg = cpu.isNegative() ? 1 : 0;
                    int statVal = bus.read(0x2002); // peek (will clear vblank) for log context
                    Log.debug(TEST, "BIT_PPUSTATUS pc=%04X N=%d read2002=%02X cycles=%d", lastPC, neg,
                            statVal, cpu.getTotalCycles());
                } else if (lastPC == 0xE5A1) { // BPL
                    Log.debug(TEST, "BPL_LOOP pc=%04X N=%d willBranch=%s cycles=%d Y=%02X", lastPC,
                            cpu.isNegative() ? 1 : 0, (!cpu.isNegative()) + "", cpu.getTotalCycles(), cpu.getY());
                }
                // Detecta DEY no endereço E596 como marcador de uma iteração do laço
                if (lastPC == 0xE596) {
                    initLoopSeen = true;
                    initLoopIterations++;
                    if (!initLoopStuckReported && initLoopIterations > 400) {
                        Log.warn(TEST,
                                "INIT_LOOP_STUCK iter=%d Y=%02X A=%02X X=%02X flags[C=%d Z=%d N=%d] cycles=%d",
                                initLoopIterations, cpu.getY(), cpu.getA(), cpu.getX(), cpu.isCarry() ? 1 : 0,
                                cpu.isZero() ? 1 : 0, cpu.isNegative() ? 1 : 0, cpu.getTotalCycles());
                        initLoopStuckReported = true;
                    }
                } else {
                    // Se saiu da faixa E596-E599 depois de ter estado lá, reporta saída uma vez
                    if (!initLoopExitReported && initLoopSeen && (lastPC < 0xE596 || lastPC > 0xE599)) {
                        Log.info(TEST,
                                "INIT_LOOP_EXIT iter=%d lastPC=%04X Y=%02X A=%02X X=%02X flags[C=%d Z=%d N=%d] cycles=%d",
                                initLoopIterations, lastPC, cpu.getY(), cpu.getA(), cpu.getX(), cpu.isCarry() ? 1 : 0,
                                cpu.isZero() ? 1 : 0, cpu.isNegative() ? 1 : 0, cpu.getTotalCycles());
                        initLoopExitReported = true;
                    }
                }
            }
            if (traceCount < traceLimit) {
                // After step: last instruction context available
                earlyTrace.append(String.format(Locale.ROOT, "%03d PC=%04X OPC=%02X cyc=%d+%d total=%d\n",
                        traceCount,
                        cpu.getLastInstrPC(),
                        cpu.getLastOpcodeByte(),
                        cpu.getLastBaseCycles(),
                        cpu.getLastExtraCycles(),
                        cpu.getTotalCycles()));
                traceCount++;
                if (traceCount == traceLimit) {
                    Log.info(TEST, "--- Early Instruction Trace (first %d) ---%n%s", traceLimit,
                            earlyTrace.toString());
                }
            }
            if (debug && instrCount > 0 && instrCount % timingReportInterval == 0) {
                long actualPpuCycles = computePpuCycles(ppu.getScanline(), ppu.getCycle());
                long expectedPpuCycles = cpu.getTotalCycles() * 3L;
                Log.debug(TEST,
                        "TIMING_CHECK instr=%d cpuCycles=%d expectedPpu=%d actualPpu=%d ratio=%.4f scan=%d cyc=%d vblank=%s",
                        instrCount, cpu.getTotalCycles(), expectedPpuCycles, actualPpuCycles,
                        (expectedPpuCycles > 0 ? (double) actualPpuCycles / expectedPpuCycles : 0.0), ppu.getScanline(),
                        ppu.getCycle(), ppu.isInVBlank());
            }
            instrCount++;
            if (tap != null && tap.detectedTightLoop()) {
                Log.warn(TEST, "LOOP_DETECTED pc=%04X instr=%d cycles=%d status=%02X msg=%s", cpu.getPC(),
                        instrCount, cpu.getTotalCycles(), cpuBus.read(0x6000) & 0xFF, readMsg(cpuBus));
                if (traceCount > 0 && traceCount < traceLimit) {
                    Log.info(TEST, "--- Early Instruction Trace (partial %d/%d) ---%n%s", traceCount, traceLimit,
                            earlyTrace.toString());
                }
                tap.dumpSummary();
                break;
            }
            if (instrCount - lastReport >= 500_000) {
                lastReport = instrCount;
                Log.debug(TEST, ". instr=%d cycles=%d status=0", instrCount, cpu.getTotalCycles());
            }
        }
    }

    private static String readMsg(NesBus bus) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            int b = bus.read(0x6004 + i) & 0xFF;
            if (b == 0)
                break;
            if (b >= 32 && b < 127)
                sb.append((char) b);
            else
                sb.append('.');
        }
        return sb.toString();
    }

    // Approximate total progressed PPU cycles since reset based on scanline &
    // cycle.
    // Each scanline has 341 cycles; scanline sequence wraps after 261 to -1.
    private static long computePpuCycles(int scanline, int cycle) {
        // Map scanline -1 to 261 for linearization
        int normalizedScan = (scanline < 0) ? 261 : scanline;
        return (long) normalizedScan * 341L + (long) cycle;
    }

    // Debug tap wrapper capturing bus operations
    private static class DebugTap implements NesBus {
        private final NesBus inner;
        private final int maxOps;
        private long opCount = 0;
        private final int[] addrFreq = new int[0x10000];
        private int lastAddr = -1;
        private int repeatCount = 0;
        private boolean loopDetected = false;
        private final StringBuilder firstOps = new StringBuilder();
        private final PPU ppu;
        private int statusReadLogCount = 0;

        DebugTap(NesBus inner, int maxOps, PPU ppu) {
            this.inner = inner;
            this.maxOps = maxOps;
            this.ppu = ppu;
        }

        @Override
        public int read(int address) {
            int v = inner.read(address);
            record(address & 0xFFFF, true, v);
            return v;
        }

        @Override
        public void write(int address, int value) {
            inner.write(address, value);
            record(address & 0xFFFF, false, value & 0xFF);
        }

        private void record(int addr, boolean read, int val) {
            opCount++;
            addrFreq[addr]++;
            if (opCount <= maxOps) {
                firstOps.append(String.format(Locale.ROOT, "%s %04X %02X\n", read ? "R" : "W", addr, val));
                if (!read && addr == 0x2000) {
                    firstOps.append(
                            String.format(Locale.ROOT, "; PPUCTRL WRITE val=%02X (NMI=%d)\n", val, (val >> 7) & 1));
                }
            }
            if (read && addr == 0x2002 && statusReadLogCount < 200) {
                // Log first 200 status reads with PPU timing context
                firstOps.append(String.format(Locale.ROOT, "; PPUSTAT read #%d scan=%d cyc=%d vblank=%s val=%02X\n",
                        statusReadLogCount + 1, ppu.getScanline(), ppu.getCycle(), ppu.isInVBlank(), val & 0xFF));
                statusReadLogCount++;
            }
            if (addr == lastAddr) {
                repeatCount++;
                if (repeatCount > 100000)
                    loopDetected = true; // heurística simples
            } else {
                lastAddr = addr;
                repeatCount = 0;
            }
        }

        boolean detectedTightLoop() {
            return loopDetected;
        }

        void dumpSummary() {
            Log.info(TEST, "--- DebugTap Summary ---");
            Log.info(TEST, "Total bus ops: %d", opCount);
            Log.info(TEST, "Top addresses (non-zero freq):");
            for (int i = 0; i < addrFreq.length; i++) {
                if (addrFreq[i] > 0 && addrFreq[i] >= 1000) {
                    Log.debug(TEST, "%04X = %d", i, addrFreq[i]);
                }
            }
            Log.info(TEST, "First ops sample:\n%s", firstOps.toString());
        }
    }
}
