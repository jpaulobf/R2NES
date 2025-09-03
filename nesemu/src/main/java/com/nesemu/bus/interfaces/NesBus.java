package com.nesemu.bus.interfaces;

import com.nesemu.apu.APU;
import com.nesemu.cpu.interfaces.NesCPU;
import com.nesemu.io.Controller;
import com.nesemu.mapper.Mapper;
import com.nesemu.mapper.Mapper0;
import com.nesemu.memory.Memory;
import com.nesemu.ppu.interfaces.NesPPU;
import com.nesemu.rom.INesRom;

/**
 * Minimal CPU-facing bus interface.
 * The CPU only needs byte granularity read/write on a 16-bit address space.
 * Additional signalling lines (IRQ/NMI/RDY) can be added later if needed.
 */
public interface NesBus {
    /**
     * Read a byte from the CPU address space (delegates to mapper / RAM / PPU / APU
     * as appropriate).
     * 
     * @param address 16-bit CPU address
     * @return unsigned byte (0-255)
     */
    int read(int address);

    /**
     * Write a byte to the CPU address space.
     * 
     * @param address 16-bit CPU address
     * @param value   byte value (low 8 bits used)
     */
    void write(int address, int value);

    /**
     * Attach the CPU instance so the bus can raise signals (NMI/IRQ in future) or
     * access CPU helpers.
     */
    default void attachCPU(NesCPU cpu) {}

    /**
     * Current active mapper (generic interface) handling PRG / CHR banking.
     */
    default Mapper getMapper() {
        return null;
    }

    /**
     * Convenience accessor specifically for Mapper0 (legacy tests) – returns null
     * if different mapper.
     */
    default Mapper0 getMapper0() {
        return null;
    }

    /**
     * Underlying main memory / RAM abstraction (legacy path / direct tests).
     */
    default Memory getMemory() {
        return null;
    }

    /**
     * Clear system RAM to power-on state (often 0 or pattern depending on emulator
     * policy).
     */
    default void clearRam() {}

    /**
     * Attach and initialize a mapper for the loaded iNES ROM (sets PRG/CHR
     * pointers, mirroring, etc.).
     */
    default void attachMapper(Mapper mapper, INesRom rom) {}

    /**
     * Connect player 1 and player 2 controllers to the bus for input polling.
     */
    default void attachControllers(Controller p1, Controller p2) {}

    /**
     * Attach Audio Processing Unit instance so reads/writes to APU registers are
     * routed correctly.
     */
    default void attachAPU(APU apu) {}

    /**
     * Attach PPU (Picture Processing Unit) for register access and future interrupt
     * signalling.
     */
    default void attachPPU(NesPPU ppu) {}

    /**
     * Clear an active watch/read breakpoint trigger flag (used by debugging
     * helpers).
     */
    default void clearWatchTrigger() {}

    /**
     * Whether a configured watch/read breakpoint has been triggered since last
     * clear.
     */
    default boolean isWatchTriggered() {
        return false;
    }

    /**
     * Configure a watch (read breakpoint) at an address; 'limit' may define how
     * many hits before trigger.
     */
    default void setWatchReadAddress(int address, int limit) {}

    /**
     * Enable logging of PPU register accesses for a limited number of events
     * (useful for diagnostics).
     */
    default void enablePpuRegLogging(int limit) {}

    public static void enableQuietControllerDebug(boolean enable) {
        /* no-op (legacy) */
    }
}