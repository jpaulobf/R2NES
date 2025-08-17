package com.nesemu.emulator;

import com.nesemu.cpu.CPU;
import com.nesemu.memory.interfaces.iMemory;

/**
 * Class representing the NES emulator.
 * It initializes the CPU and memory, and provides methods to reset and run the emulator.
 */
public class NesEmulator {
    private final CPU cpu;
    private final iMemory memory;

    public NesEmulator(iMemory memory) {
        this.memory = memory;
        this.cpu = new CPU(memory);
    }

    public void reset() {
        cpu.reset();
    }

    public void run() {
        while (true) {
            cpu.clock();
            // TODO: Implement additional logic for handling interrupts, rendering, etc.
        }
    }
}
