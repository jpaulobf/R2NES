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
import com.nesemu.mapper.Mapper;
import com.nesemu.ppu.Ppu2C02;
import com.nesemu.rom.INesRom;

/**
 * NES emulator façade. Now builds a proper Bus + Mapper0 + PPU stack.
 * Legacy constructor (iMemory) kept for CPU unit tests; new constructor accepts
 * an iNES ROM.
 */
public class NesEmulator {
    private final CPU cpu;
    private final Bus bus; // system bus (CPU visible view via iBus)
    private final Ppu2C02 ppu; // minimal PPU skeleton
    private final Mapper mapper; // current mapper (Mapper0 or Mapper3 for now)

    // Legacy path (kept for existing tests using Memory directly)
    public NesEmulator() {
        this.bus = new Bus();
        this.ppu = null;
        this.mapper = null;
        this.cpu = new CPU(bus);
    }

    // New path: build full stack from ROM (mapper 0 only for now)
    public NesEmulator(INesRom rom) {
        int mapperNum = rom.getHeader().getMapper();
        switch (mapperNum) {
            case 0 -> this.mapper = new Mapper0(rom);
            case 2 -> this.mapper = new Mapper2(rom);
            case 1 -> this.mapper = new Mapper1(rom); // MMC1
            case 3 -> this.mapper = new Mapper3(rom);
            case 4 -> this.mapper = new Mapper4(rom); // MMC3 (partial, no IRQ yet)
            case 5 -> this.mapper = new Mapper5(rom); // MMC5 (partial)
            default ->
                throw new IllegalArgumentException("Unsupported mapper " + mapperNum + " (only 0,1,2,3,4,5 implemented)");
        }
        this.ppu = new Ppu2C02();
        this.ppu.reset();
        this.ppu.attachMapper(this.mapper);
        this.bus = new Bus();
        bus.attachPPU(ppu);
        bus.attachMapper(mapper, rom);
        NesBus cpuBus = bus;
        this.cpu = new CPU(cpuBus);
        // Wire PPU -> CPU callback path for NMI generation
        this.ppu.attachCPU(this.cpu);
        // After CPU reset, PC set from reset vector.
    }

    public CPU getCpu() {
        return cpu;
    }

    public Bus getBus() {
        return bus;
    }

    public Ppu2C02 getPpu() {
        return ppu;
    }

    public void reset() {
        cpu.reset();
        if (ppu != null)
            ppu.reset();
    }

    /** Run N CPU cycles (each CPU cycle advances PPU 3 cycles). */
    public void runCycles(long cpuCycles) {
        if (bus == null) {
            // Legacy mode: no PPU stepping
            for (long i = 0; i < cpuCycles; i++)
                cpu.clock();
            return;
        }
        for (long i = 0; i < cpuCycles; i++) {
            cpu.clock();
            ppu.clock();
            ppu.clock();
            ppu.clock();
            // Future: APU clock (every CPU cycle) & poll NMI from PPU
        }
    }

    /** Run a number of full instructions (blocking). */
    public void runInstructions(long count) {
        for (long i = 0; i < count; i++)
            cpu.stepInstruction();
    }

    /** Advance until end of current frame (when PPU scanline wraps to -1). */
    public void stepFrame() {
        long targetFrame = ppu.getFrame();
        while (ppu.getFrame() == targetFrame) {
            runCycles(1); // 1 CPU cycle -> 3 PPU cycles
        }
    }

    /** Convenience: run a number of whole frames. */
    public void runFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            stepFrame();
        }
    }

    /** Expose current rendered frame index (proxy to PPU). */
    public long getFrame() {
        return ppu.getFrame();
    }
}
