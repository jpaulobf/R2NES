package com.nesemu.emulator;

import com.nesemu.cpu.CPU;
import com.nesemu.bus.Bus;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.mapper.Mapper0;
import com.nesemu.mapper.Mapper3;
import com.nesemu.mapper.Mapper2;
import com.nesemu.mapper.Mapper1;
import com.nesemu.mapper.Mapper5;
import com.nesemu.mapper.Mapper4;
import com.nesemu.mapper.Mapper7;
import com.nesemu.mapper.Mapper9;
import com.nesemu.mapper.Mapper;
import com.nesemu.ppu.PPU;
import com.nesemu.apu.APU;
import com.nesemu.apu.interfaces.NesAPU;
import com.nesemu.cpu.Opcode;
import com.nesemu.cpu.AddressingMode;
import com.nesemu.rom.INesRom;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * NES emulator façade. Now builds a proper Bus + Mapper0 + PPU stack.
 * Legacy constructor (iMemory) kept for CPU unit tests; new constructor accepts
 * an iNES ROM.
 */
public class NesEmulator {

    // Core components
    private final CPU cpu;
    private final NesBus bus; // system bus (CPU visible view via iBus)
    private final PPU ppu; // minimal PPU skeleton
    private final NesAPU apu; // APU (2A03) skeleton
    private final Mapper mapper; // current mapper (Mapper0 or Mapper3 for now)
    private Path romPath; // optional original ROM path for deriving .sav
    private Path autoSavePath;
    private boolean autoSaveEnabled = true;
    private long autoSaveIntervalFrames = 600; // ~10s @60fps
    private long lastAutoSaveFrame = 0;

    // Save state constants
    private static final int STATE_MAGIC = 0x4E455353; // 'NESS'
    private static final int STATE_VERSION = 2;

    /**
     * CPU <-> PPU timing mode. SIMPLE mantém padrão antigo (CPU depois 3×PPU).
     * INTERLEAVED reduz defasagem média aplicando 1 PPU antes da CPU e 2 depois.
     * Ambos preservam razão 3:1 de ciclos agregados.
     */
    public enum TimingMode {
        SIMPLE, INTERLEAVED
    }

    private TimingMode timingMode = TimingMode.SIMPLE; // default compat

    // ---- Spin / stall watchdog (optional, enabled via CLI) ----
    // Global lightweight switch to allow JIT to strip most instrumentation when
    // false.
    // Set true automatically when any debug/instrumentation feature is enabled.
    private boolean instrumentationEnabled = false;
    private boolean spinWatchEnabled = false;
    private long spinWatchThreshold = 0; // cycles with same PC before snapshot
    private int spinWatchLastPc = -1;
    private long spinWatchSameCount = 0;
    private int spinWatchLastReportedPc = -1; // avoid duplicate reports for same PC run

    // Simple read address histogram (top small set) during current spin window
    private static final int SPIN_HIST_SIZE = 8;
    private final int[] spinHistAddr = new int[SPIN_HIST_SIZE];
    private final int[] spinHistCount = new int[SPIN_HIST_SIZE];

    // Separate IO/RAM (<0x4020) address histogram
    private static final int SPIN_IO_HIST_SIZE = 8;
    private final int[] spinIoAddr = new int[SPIN_IO_HIST_SIZE];
    private final int[] spinIoCount = new int[SPIN_IO_HIST_SIZE];

    // NMI counter snapshot support
    private long lastNmiCountSnapshot = -1;

    // Hex dump bytes option
    private int spinDumpBytes = 0; // 0 = disabled
    private int spinDumpLastPc = -1;

    /**
     * Legacy path: build minimal stack with no PPU or mapper (for CPU unit tests).
     */
    public NesEmulator() {
        this.bus = new Bus();
        this.ppu = null;
        this.apu = null;
        this.mapper = null;
        this.cpu = new CPU(bus);
    }

    /**
     * New path: build full stack from ROM
     * 
     * @param rom
     */
    public NesEmulator(INesRom rom) {
        int mapperNum = rom.getHeader().getMapper();
        switch (mapperNum) {
            case 0 -> this.mapper = new Mapper0(rom);
            case 2 -> this.mapper = new Mapper2(rom);
            case 1 -> this.mapper = new Mapper1(rom); // MMC1
            case 3 -> this.mapper = new Mapper3(rom);
            case 4 -> this.mapper = new Mapper4(rom); // MMC3 (partial, no IRQ yet)
            case 5 -> this.mapper = new Mapper5(rom); // MMC5 (partial)
            case 7 -> this.mapper = new Mapper7(rom); // AxROM
            case 9 -> this.mapper = new Mapper9(rom); // MMC2 (Punch-Out!!)
            default ->
                throw new IllegalArgumentException(
                        "Unsupported mapper " + mapperNum + " (only 0,1,2,3,4,5,7,9 implemented)");
        }
        this.ppu = new PPU();
        this.ppu.reset();
        this.ppu.attachMapper(this.mapper);
        this.bus = new Bus();
        bus.attachPPU(ppu);
        bus.attachMapper(mapper, rom);
        this.apu = new APU();
        this.apu.reset();
        bus.attachAPU(this.apu);
        this.cpu = new CPU(bus);
        this.ppu.attachCPU(this.cpu);
        if (this.mapper != null) {
            this.mapper.setIrqCallback(this.cpu::irq);
        }
    }

    /** Alternative constructor with ROM path (enables automatic .sav naming). */
    public NesEmulator(INesRom rom, Path romFilePath) {
        this(rom);
        this.romPath = romFilePath;
        deriveAutoSavePath();
        tryAutoLoad();
    }

    /**
     * Alternative constructor with ROM + explicit save directory.
     * If saveDir != null, the autosave path is placed inside saveDir (creating it)
     * and PRG RAM is auto-loaded from there (if existing). No lookup is attempted
     * in the ROM directory in this path.
     */
    public NesEmulator(INesRom rom, Path romFilePath, Path saveDir) {
        this(rom);
        this.romPath = romFilePath;
        if (saveDir != null) {
            setSaveDirectory(saveDir); // sets autoSavePath + attempts load from saveDir
        } else {
            deriveAutoSavePath();
            tryAutoLoad();
        }
    }

    /**
     * Dump a warning-level snapshot of CPU + PPU + Mapper1 (if present) state.
     * @param reason
     */
    public void dumpWarnSnapshot(String reason) {
        if (!instrumentationEnabled)
            return; // no-op when instrumentation off
        StringBuilder sb = new StringBuilder();
        int pc = cpu.getPC() & 0xFFFF;
        sb.append(String.format("[SNAP] PC=%04X A=%02X X=%02X Y=%02X SP=%02X P=%02X CYC=%d NMIH=%d lastVec=%04X",
                pc, cpu.getA() & 0xFF, cpu.getX() & 0xFF, cpu.getY() & 0xFF,
                cpu.getSP() & 0xFF, cpu.getStatusByte() & 0xFF, cpu.getTotalCycles(),
                cpu.getNmiHandlerCount(), cpu.getLastNmiHandlerVector()));
        if (ppu != null) {
            sb.append(String.format(" Frame=%d SL=%d CYC=%d STAT=%02X CTRL=%02X MASK=%02X NMI=%d lastNmiFrame=%d",
                    ppu.getFrame(), ppu.getScanline(), ppu.getCycle(),
                    ppu.getStatusRegister() & 0xFF, ppu.getCtrl() & 0xFF, ppu.getMaskRegister() & 0xFF,
                    ppu.getNmiCount(), ppu.getLastNmiFrame()));
            sb.append(String.format(" SRS=%d", ppu.getStatusReadCountFrame()));
            int[] recent = ppu.getStatusReadRecent();
            sb.append(" RS8=");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02X", recent[i] & 0xFF));
                if (i < 7)
                    sb.append('-');
            }
            long s0f = ppu.getSprite0HitSetFrame();
            if (s0f >= 0) {
                sb.append(String.format(" S0@F%d/%d/%d", s0f, ppu.getSprite0HitSetScanline(),
                        ppu.getSprite0HitSetCycle()));
            }
        }
        if (mapper instanceof com.nesemu.mapper.Mapper1 m1) {
            sb.append(String.format(" MMC1:CTRL=%02X PRG=%02X CHR0=%02X CHR1=%02X",
                    m1.getControl(), m1.getPrgBank(), m1.getChrBank0(), m1.getChrBank1()));
        }
        if (spinWatchEnabled) {
            // Include partial histograms (top entries only)
            sb.append(" HREADS[");
            for (int i = 0; i < SPIN_HIST_SIZE; i++) {
                if (spinHistCount[i] == 0)
                    break;
                sb.append(String.format("%04X:%d", spinHistAddr[i], spinHistCount[i]));
                if (i + 1 < SPIN_HIST_SIZE && spinHistCount[i + 1] > 0)
                    sb.append(',');
            }
            sb.append(']');
        }
        // Append short disassembly (up to 8 instructions) for context
        if (instrumentationEnabled) {
            sb.append(" DISS[");
            sb.append(disassembleAround(pc, 8));
            sb.append(']');
        }
        if (reason != null && !reason.isEmpty()) {
            sb.append(" REASON=").append(reason);
        }
        com.nesemu.util.Log.warn(com.nesemu.util.Log.Cat.CPU, sb.toString());
    }

    /**
     * Enable spin/stall watchdog with given threshold (in CPU cycles).
     * @param threshold
     */
    public void enableSpinWatch(long threshold) {
        if (threshold <= 0)
            return;
        instrumentationEnabled = true;
        this.spinWatchEnabled = true;
        this.spinWatchThreshold = threshold;
        this.spinWatchLastPc = -1;
        this.spinWatchSameCount = 0;
        this.spinWatchLastReportedPc = -1;
        for (int i = 0; i < SPIN_HIST_SIZE; i++) {
            spinHistAddr[i] = 0;
            spinHistCount[i] = 0;
        }
        for (int i = 0; i < SPIN_IO_HIST_SIZE; i++) {
            spinIoAddr[i] = 0;
            spinIoCount[i] = 0;
        }
        if (this.bus instanceof Bus b) {
            b.setSpinReadRecorder(this::spinWatchRecordRead);
        }
    }

    /**
     * Set number of bytes to dump as hex after PC on each spin report (0 = disable).
     * @param bytes
     */
    public void setSpinDumpBytes(int bytes) {
        this.spinDumpBytes = Math.max(0, Math.min(64, bytes));
        if (this.spinDumpBytes > 0)
            instrumentationEnabled = true;
    }

    /**
     * Check for spin condition and dump snapshot if detected.
     */
    private void spinWatchTick() {
        if (!spinWatchEnabled)
            return;
        int pc = cpu.getPC() & 0xFFFF;
        if (pc == spinWatchLastPc) {
            spinWatchSameCount++;
        } else {
            spinWatchLastPc = pc;
            spinWatchSameCount = 1;
        }
        if (spinWatchSameCount == spinWatchThreshold && pc != spinWatchLastReportedPc) {
            spinWatchLastReportedPc = pc;
            // Snapshot CPU + PPU + Mapper1 (if present)
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(
                    "[SPIN] PC=%04X sameCycles=%d A=%02X X=%02X Y=%02X SP=%02X P=%02X NMIH=%d lastVec=%04X ",
                    pc, spinWatchSameCount, cpu.getA() & 0xFF, cpu.getX() & 0xFF, cpu.getY() & 0xFF,
                    cpu.getSP() & 0xFF, cpu.getStatusByte() & 0xFF,
                    cpu.getNmiHandlerCount(), cpu.getLastNmiHandlerVector()));
            if (ppu != null) {
                sb.append(String.format("Frame=%d SL=%d CYC=%d ", ppu.getFrame(), ppu.getScanline(), ppu.getCycle()));
                long nmis = ppu.getNmiCount();
                if (lastNmiCountSnapshot >= 0) {
                    sb.append(String.format("NMI=%d(+%d) ", nmis, (nmis - lastNmiCountSnapshot)));
                } else {
                    sb.append(String.format("NMI=%d ", nmis));
                }
                lastNmiCountSnapshot = nmis;
            }
            if (mapper instanceof com.nesemu.mapper.Mapper1 m1) {
                sb.append(String.format("MMC1:CTRL=%02X PRG=%02X CHR0=%02X CHR1=%02X ",
                        m1.getControl(), m1.getPrgBank(), m1.getChrBank0(), m1.getChrBank1()));
            }
            // Append histogram
            sb.append("READS[");
            for (int i = 0; i < SPIN_HIST_SIZE; i++) {
                if (spinHistCount[i] == 0)
                    break;
                sb.append(String.format("%04X:%d", spinHistAddr[i], spinHistCount[i]));
                if (i + 1 < SPIN_HIST_SIZE && spinHistCount[i + 1] > 0)
                    sb.append(',');
            }
            sb.append(']');
            // IO subset
            boolean anyIo = false;
            for (int i = 0; i < SPIN_IO_HIST_SIZE; i++)
                if (spinIoCount[i] > 0) {
                    anyIo = true;
                    break;
                }
            if (anyIo) {
                sb.append(" IO[");
                for (int i = 0; i < SPIN_IO_HIST_SIZE; i++) {
                    if (spinIoCount[i] == 0)
                        break;
                    sb.append(String.format("%04X:%d", spinIoAddr[i], spinIoCount[i]));
                    if (i + 1 < SPIN_IO_HIST_SIZE && spinIoCount[i + 1] > 0)
                        sb.append(',');
                }
                sb.append(']');
            }
            if (spinDumpBytes > 0 && spinDumpLastPc != pc) {
                spinDumpLastPc = pc;
                sb.append(" CODE=");
                for (int i = 0; i < spinDumpBytes; i++) {
                    int addr = (pc + i) & 0xFFFF;
                    int val = bus.read(addr) & 0xFF; // safe read
                    sb.append(String.format("%02X", val));
                }
            }
            sb.append(" DISS[").append(disassembleAround(pc, 6)).append(']');
            com.nesemu.util.Log.warn(com.nesemu.util.Log.Cat.CPU, sb.toString());
        }
    }

    /**
     * Record a read access for spin histogram (called from Bus).
     * @param address
     */
    public void spinWatchRecordRead(int address) {
        if (!spinWatchEnabled)
            return;
        address &= 0xFFFF;
        // find or insert
        int slot = -1;
        for (int i = 0; i < SPIN_HIST_SIZE; i++) {
            if (spinHistCount[i] == 0) {
                if (slot < 0)
                    slot = i;
                continue;
            }
            if (spinHistAddr[i] == address) {
                spinHistCount[i]++;
                return;
            }
        }
        if (slot >= 0) {
            spinHistAddr[slot] = address;
            spinHistCount[slot] = 1;
            return;
        }
        // simple replacement: replace smallest
        int minIdx = 0;
        for (int i = 1; i < SPIN_HIST_SIZE; i++)
            if (spinHistCount[i] < spinHistCount[minIdx])
                minIdx = i;
        spinHistAddr[minIdx] = address;
        spinHistCount[minIdx] = 1;
        // IO classification (RAM/PPU/APU) < 0x4020
        if (address < 0x4020) {
            int slot2 = -1;
            for (int i = 0; i < SPIN_IO_HIST_SIZE; i++) {
                if (spinIoCount[i] == 0) {
                    if (slot2 < 0)
                        slot2 = i;
                    continue;
                }
                if (spinIoAddr[i] == address) {
                    spinIoCount[i]++;
                    return;
                }
            }
            if (slot2 >= 0) {
                spinIoAddr[slot2] = address;
                spinIoCount[slot2] = 1;
                return;
            }
            int min2 = 0;
            for (int i = 1; i < SPIN_IO_HIST_SIZE; i++)
                if (spinIoCount[i] < spinIoCount[min2])
                    min2 = i;
            spinIoAddr[min2] = address;
            spinIoCount[min2] = 1;
        }
    }

    /**
     * Disassemble up to 'count' instructions starting at startPc.
     * @param startPc
     * @param count
     * @return
     */
    private String disassembleAround(int startPc, int count) {
        if (!instrumentationEnabled) {
            // Minimal fast path: just return current opcode byte hex without decoding
            // chain.
            int opcodeByte = bus.read(startPc & 0xFFFF) & 0xFF;
            return String.format("%04X:%02X", startPc & 0xFFFF, opcodeByte);
        }
        StringBuilder sb = new StringBuilder();
        int pc = startPc & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int opcodeByte = bus.read(pc) & 0xFF;
            Opcode op = Opcode.fromByte(opcodeByte);
            AddressingMode mode = AddressingMode.getAddressingMode(opcodeByte);
            int len = 1;
            String operandStr = "";
            if (op == null) {
                sb.append(String.format("%04X:DB %02X", pc, opcodeByte));
                pc = (pc + 1) & 0xFFFF;
            } else {
                switch (mode) {
                    case IMMEDIATE -> {
                        int v = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        operandStr = String.format("#$%02X", v);
                        len = 2;
                    }
                    case ZERO_PAGE -> {
                        int a = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        operandStr = String.format("$%02X", a);
                        len = 2;
                    }
                    case ZERO_PAGE_X -> {
                        int a = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        operandStr = String.format("$%02X,X", a);
                        len = 2;
                    }
                    case ZERO_PAGE_Y -> {
                        int a = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        operandStr = String.format("$%02X,Y", a);
                        len = 2;
                    }
                    case ABSOLUTE -> {
                        int lo = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        int hi = bus.read((pc + 2) & 0xFFFF) & 0xFF;
                        int a = (hi << 8) | lo;
                        operandStr = String.format("$%04X", a);
                        len = 3;
                    }
                    case ABSOLUTE_X -> {
                        int lo = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        int hi = bus.read((pc + 2) & 0xFFFF) & 0xFF;
                        int a = (hi << 8) | lo;
                        operandStr = String.format("$%04X,X", a);
                        len = 3;
                    }
                    case ABSOLUTE_Y -> {
                        int lo = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        int hi = bus.read((pc + 2) & 0xFFFF) & 0xFF;
                        int a = (hi << 8) | lo;
                        operandStr = String.format("$%04X,Y", a);
                        len = 3;
                    }
                    case INDIRECT -> {
                        int lo = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        int hi = bus.read((pc + 2) & 0xFFFF) & 0xFF;
                        int a = (hi << 8) | lo;
                        operandStr = String.format("($%04X)", a);
                        len = 3;
                    }
                    case INDIRECT_X -> {
                        int zp = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        operandStr = String.format("($%02X,X)", zp);
                        len = 2;
                    }
                    case INDIRECT_Y -> {
                        int zp = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        operandStr = String.format("($%02X),Y", zp);
                        len = 2;
                    }
                    case RELATIVE -> {
                        int off = bus.read((pc + 1) & 0xFFFF) & 0xFF;
                        int rel = (pc + 2 + (byte) off) & 0xFFFF;
                        operandStr = String.format("$%04X", rel);
                        len = 2;
                    }
                    case ACCUMULATOR -> {
                        operandStr = "A";
                        len = 1;
                    }
                    case IMPLIED -> {
                        len = 1;
                    }
                }
                // Raw bytes
                String bytes;
                if (len == 1)
                    bytes = String.format("%02X", opcodeByte);
                else if (len == 2)
                    bytes = String.format("%02X %02X", opcodeByte, bus.read((pc + 1) & 0xFFFF) & 0xFF);
                else
                    bytes = String.format("%02X %02X %02X", opcodeByte, bus.read((pc + 1) & 0xFFFF) & 0xFF,
                            bus.read((pc + 2) & 0xFFFF) & 0xFF);
                sb.append(String.format("%04X:%-3s %-14s{%s}", pc, op.name(), operandStr, bytes));
                pc = (pc + len) & 0xFFFF;
            }
            if (i + 1 < count)
                sb.append(';');
        }
        return sb.toString();
    }

    /**
     * Standalone PPU mode (no ROM/mapper) for GUI black screen / diagnostics.
     * Provides a ticking PPU & CPU minimal loop so HUD/ESC still function.
     */
    public static NesEmulator createBlackScreenInstance() {
        NesEmulator emu = new NesEmulator();
        PPU p = new PPU();
        p.reset();
        // No mapper attached: background stays blank (pattern reads default 0).
        emu.bus.attachPPU(p);
        p.attachCPU(emu.cpu);
        try {
            java.lang.reflect.Field f = NesEmulator.class.getDeclaredField("ppu");
            f.setAccessible(true);
            f.set(emu, p);
        } catch (Exception ignore) {
        }
        return emu;
    }

    /**
     * Get the CPU instance (for direct control in tests or introspection).
     * 
     * @return
     */
    public CPU getCpu() {
        return cpu;
    }

    /**
     * Get the Bus instance (for direct memory access in tests or introspection).
     * 
     * @return
     */
    public NesBus getBus() {
        return bus;
    }

    /**
     * Get the PPU instance (for direct control in tests or introspection).
     * 
     * @return
     */
    public PPU getPpu() {
        return ppu;
    }

    /** Get the APU instance (for audio wiring). */
    public NesAPU getApu() {
        return apu;
    }

    /**
     * Reset CPU and PPU to power-on state (PC from reset vector).
     */
    public synchronized void reset() {
        cpu.reset();
        if (ppu != null)
            ppu.reset();
    }

    /**
     * Run N CPU cycles (each CPU cycle advances PPU 3 cycles).
     */
    public synchronized void runCycles(long cpuCycles) {
        runCyclesInternal(cpuCycles);
    }

    /**
     * Run N CPU cycles (each CPU cycle advances PPU 3 cycles).
     * Internal method with no synchronization (called from runCycles and stepFrame).
     * @param cpuCycles
     */
    private void runCyclesInternal(long cpuCycles) {
        if (bus == null) {
            // Legacy mode: no PPU stepping
            for (long i = 0; i < cpuCycles; i++)
                cpu.clock();
            return;
        }
        if (timingMode == TimingMode.SIMPLE) {
            for (long i = 0; i < cpuCycles; i++) {
                cpu.clock();
                if (apu != null)
                    apu.clockCpuCycle();
                spinWatchTick();
                ppu.clock();
                ppu.clock();
                ppu.clock();
                autoSaveTick();
            }
        } else { // INTERLEAVED
            for (long i = 0; i < cpuCycles; i++) {
                // 1º PPU antes da CPU para reduzir atraso de writes que afetam próximo pixel
                ppu.clock();
                cpu.clock();
                if (apu != null)
                    apu.clockCpuCycle();
                spinWatchTick();
                // 2 PPU restantes
                ppu.clock();
                ppu.clock();
                autoSaveTick();
            }
        }
    }

    /** Run a number of full instructions (blocking). */
    public synchronized void runInstructions(long count) {
        for (long i = 0; i < count; i++)
            cpu.stepInstruction();
    }

    /** Advance until end of current frame (when PPU scanline wraps to -1). */
    public synchronized void stepFrame() {
        long targetFrame = ppu.getFrame();
        while (ppu.getFrame() == targetFrame) {
            runCyclesInternal(1); // 1 CPU cycle -> 3 PPU cycles
        }
        // Apply any post-frame transformations (e.g., left column crop mode)
        // Done here so it affects both GUI and headless executions uniformly.
        ppu.applyPostFrameCroppingIfNeeded();
        // Notify controllers (turbo cadence)
        if (bus != null) {
            ((Bus) bus).onFrameEnd();
        }
    }

    /** Convenience: run a number of whole frames. */
    public synchronized void runFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            stepFrame();
        }
    }

    /** Expose current rendered frame index (proxy to PPU). */
    public long getFrame() {
        return ppu.getFrame();
    }

    /** Define modo de temporização CPU↔PPU (default SIMPLE). */
    public void setTimingMode(TimingMode mode) {
        if (mode != null)
            this.timingMode = mode;
    }

    /** Obtém modo de temporização atual. */
    public TimingMode getTimingMode() {
        return this.timingMode;
    }

    /** Expose current mapper (read-only) for diagnostics. */
    public Mapper getMapper() {
        return this.mapper;
    }

    /**
     * Persist battery-backed PRG RAM (if mapper exposes) to a .sav file.
     * 
     * @param path target path (e.g., romFile.withExtension(".sav"))
     * @throws IOException if IO fails
     */
    public void saveSram(Path path) throws IOException {
        if (mapper == null)
            return;
        byte[] prgRam = mapper.getPrgRam();
        if (prgRam == null)
            return; // nothing to save
        // Write to temp then atomic move to reduce corruption risk
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(tmp, prgRam);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load battery-backed PRG RAM from a .sav file if present.
     * Size mismatch larger than existing buffer is truncated; smaller fills prefix.
     * 
     * @param path path to .sav
     * @return true if loaded, false if file missing or mapper has no PRG RAM
     * @throws IOException on IO error
     */
    public boolean loadSram(Path path) throws IOException {
        if (mapper == null)
            return false;
        byte[] buf = mapper.getPrgRam();
        if (buf == null)
            return false;
        if (!Files.exists(path))
            return false;
        byte[] data = Files.readAllBytes(path);
        int len = Math.min(buf.length, data.length);
        System.arraycopy(data, 0, buf, 0, len);
        mapper.onPrgRamLoaded();
        return true;
    }

    /**
     * Derive automatic .sav path from ROM path if not explicitly set.
     * Called from constructors that receive a ROM path.
     */
    private void deriveAutoSavePath() {
        if (romPath == null)
            return;
        String fileName = romPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        this.autoSavePath = romPath.getParent().resolve(base + ".sav");
    }

    /**
     * Attempt autoload from autoSavePath if set and file exists.
     */
    private void tryAutoLoad() {
        if (autoSavePath == null)
            return;
        try {
            loadSram(autoSavePath);
        } catch (IOException ignored) {
        }
    }

    /**
     * Automatic periodic save tick (called from runCycles).
     */
    private void autoSaveTick() {
        if (!autoSaveEnabled || autoSavePath == null)
            return;
        if (mapper == null || mapper.getPrgRam() == null)
            return;
        long frame = ppu.getFrame();
        if (frame - lastAutoSaveFrame >= autoSaveIntervalFrames) {
            try {
                saveSram(autoSavePath);
                lastAutoSaveFrame = frame;
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Enable or disable automatic periodic saving of PRG RAM (default enabled).
     * If enabled, autosavePath must be non-null (set via constructor or setSaveDirectory).
     * @param enabled
     */
    public void setAutoSaveEnabled(boolean enabled) {
        this.autoSaveEnabled = enabled;
    }

    /**
     * Check if automatic periodic saving is enabled.
     * @param frames
     */
    public void setAutoSaveIntervalFrames(long frames) {
        if (frames > 0)
            this.autoSaveIntervalFrames = frames;
    }

    /**
     * Get current automatic periodic saving interval in frames.
     */
    public void forceAutoSave() {
        if (autoSavePath != null) {
            try {
                saveSram(autoSavePath);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Get current automatic periodic saving interval in frames.
     * @return
     */
    public Path getAutoSavePath() {
        return autoSavePath;
    }

    /**
     * Set directory to place automatic .sav file (creates if needed).
     * Attempts autoload from new location if file exists.
     * @param dir
     */
    public void setSaveDirectory(Path dir) {
        if (dir == null || romPath == null)
            return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        String fileName = romPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        this.autoSavePath = dir.resolve(base + ".sav");
        // Attempt autoload from new location if file exists
        try {
            loadSram(autoSavePath);
        } catch (IOException ignored) {
        }
    }

    // -------- Save State (snapshot) --------

    /**
     * Serialize full emulator state (CPU registers, internal RAM, PPU core
     * registers, VRAM/OAM/palettes, mapper + CHR RAM, PRG RAM)
     */
    public synchronized void saveState(Path path) throws IOException {
        if (cpu == null || bus == null || ppu == null)
            return;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(1024 * 64);
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        // Header
        dos.writeInt(STATE_MAGIC);
        dos.writeInt(STATE_VERSION);
        // CPU core
        dos.writeInt(cpu.getPC());
        dos.writeByte(cpu.getA());
        dos.writeByte(cpu.getX());
        dos.writeByte(cpu.getY());
        dos.writeByte(cpu.getSP());
        dos.writeByte(cpu.getStatusByte());
        // Internal RAM (2KB)
        var mem = bus.getMemory();
        for (int i = 0; i < 0x800; i++)
            dos.writeByte(mem.readInternalRam(i));
        // PPU core registers/state (direct accessors)
        dos.writeInt((int) (ppu.getFrame() & 0x7FFFFFFF));
        dos.writeInt(ppu.getScanline());
        dos.writeInt(ppu.getCycle());
        dos.writeByte(ppu.getMaskRegister()); // 0
        dos.writeByte(ppu.getStatusRegister()); // 1
        dos.writeByte(ppu.getCtrl()); // 2
        if (STATE_VERSION >= 2) {
            // Extra internal latch state (addrLatchHigh, oamAddr, readBuffer)
            dos.writeBoolean(ppu.isAddrLatchHigh()); // 3
            dos.writeByte(ppu.getOamAddr()); // 4
            dos.writeByte(ppu.getReadBuffer()); // 5
        }
        dos.writeShort(ppu.getVramAddress() & 0x3FFF);
        dos.writeShort(ppu.getTempAddress() & 0x3FFF);
        dos.writeByte(ppu.getFineX() & 0x07);
        // PPU memory copies
        byte[] oam = ppu.getOamCopy();
        dos.writeInt(oam.length);
        dos.write(oam);
        byte[] nt = ppu.getNameTableCopy();
        dos.writeInt(nt.length);
        dos.write(nt);
        byte[] pal = ppu.getPaletteCopy();
        dos.writeInt(pal.length);
        dos.write(pal);
        // Mapper specific
        byte[] mapperData = mapper != null ? mapper.saveState() : null;
        if (mapperData != null) {
            dos.writeInt(mapperData.length);
            dos.write(mapperData);
        } else {
            dos.writeInt(0);
        }
        // PRG RAM (battery) embed for completeness
        byte[] prgRam = mapper != null ? mapper.getPrgRam() : null;
        if (prgRam != null) {
            dos.writeInt(prgRam.length);
            dos.write(prgRam);
        } else {
            dos.writeInt(0);
        }
        dos.flush();
        byte[] blob = baos.toByteArray();
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(tmp, blob);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load full emulator state from a previously saved snapshot file.
     * @param path
     * @return
     * @throws IOException
     */
    public synchronized boolean loadState(Path path) throws IOException {
        if (!Files.exists(path))
            return false;
        if (cpu == null || bus == null || ppu == null)
            return false;
        byte[] data = Files.readAllBytes(path);
        java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
        int magic = dis.readInt();
        if (magic != STATE_MAGIC)
            return false;
        int ver = dis.readInt();
        if (ver > STATE_VERSION)
            return false; // unknown future version
        int pc = dis.readInt();
        int a = dis.readUnsignedByte();
        int x = dis.readUnsignedByte();
        int y = dis.readUnsignedByte();
        int sp = dis.readUnsignedByte();
        int p = dis.readUnsignedByte();
        // Restore RAM
        var mem = bus.getMemory();
        for (int i = 0; i < 0x800; i++) {
            int val = dis.readUnsignedByte();
            mem.writeInternalRam(i, val);
        }
        // PPU subset
        long frameVal = dis.readInt() & 0xFFFFFFFFL; // may ignore
        int scanline = dis.readInt();
        int cyc = dis.readInt();
        int mask = dis.readUnsignedByte();
        int status = dis.readUnsignedByte();
        int ctrl = dis.readUnsignedByte();
        boolean latchHigh = true;
        int oamAddrVal = 0;
        int readBuf = 0;
        if (ver >= 2) {
            latchHigh = dis.readBoolean();
            oamAddrVal = dis.readUnsignedByte();
            readBuf = dis.readUnsignedByte();
        }
        int vram = dis.readUnsignedShort();
        int tAddr = dis.readUnsignedShort();
        int fineX = dis.readUnsignedByte();
        // Reconstruct CPU core
        cpu.forceState(pc, a, x, y, p, sp);
        // Normalize potentially unsafe scanline/cycle values (defensive for older
        // states)
        if (scanline < -1 || scanline > 260)
            scanline = -1;
        if (cyc < 0 || cyc > 340)
            cyc = 0;
        ppu.forceCoreState(mask, status, ctrl, scanline, cyc, vram, tAddr, fineX, (int) (frameVal & 0xFFFFFFFFL));
        if (ver >= 2) {
            ppu.loadMiscInternalState(latchHigh, oamAddrVal, readBuf);
        }
        // Variable sections
        int oamLen = dis.readInt();
        if (oamLen > 0 && oamLen <= 4096) {
            byte[] oamR = new byte[oamLen];
            dis.readFully(oamR);
            ppu.loadOam(oamR);
        } else if (oamLen > 0) {
            dis.skipBytes(oamLen);
        }
        int ntLen = dis.readInt();
        if (ntLen > 0 && ntLen <= 0x2000) {
            byte[] ntR = new byte[ntLen];
            dis.readFully(ntR);
            ppu.loadNameTable(ntR);
        } else if (ntLen > 0) {
            dis.skipBytes(ntLen);
        }
        int palLen = dis.readInt();
        if (palLen > 0 && palLen <= 256) {
            byte[] palR = new byte[palLen];
            dis.readFully(palR);
            ppu.loadPalette(palR);
        } else if (palLen > 0) {
            dis.skipBytes(palLen);
        }
        int mapperLen = dis.readInt();
        if (mapperLen > 0 && mapperLen < 1_000_000) {
            byte[] mdat = new byte[mapperLen];
            dis.readFully(mdat);
            if (mapper != null)
                mapper.loadState(mdat);
        } else if (mapperLen > 0) {
            dis.skipBytes(mapperLen);
        }
        int prgRamLen = dis.readInt();
        if (prgRamLen > 0) {
            byte[] prg = new byte[prgRamLen];
            dis.readFully(prg);
            if (mapper != null && mapper.getPrgRam() != null && mapper.getPrgRam().length == prgRamLen) {
                System.arraycopy(prg, 0, mapper.getPrgRam(), 0, prgRamLen);
                mapper.onPrgRamLoaded();
            }
        }
        // If we restored mid-frame (cycle!=0 or scanline not at boundary), normalize
        // timing
        if (cyc != 0 || (scanline >= 0 && scanline <= 239)) {
            // This avoids frozen frame due to missing transient pipeline contents
            ppu.normalizeTimingAfterLoad();
        }
        // Reset APU to avoid stuck notes or invalid IRQ state from previous session
        if (apu != null) {
            apu.reset();
        }
        return true;
    }
}