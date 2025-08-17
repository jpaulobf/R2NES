package com.nesemu.emulator;

import com.nesemu.cpu.CPU;
import com.nesemu.memory.Memory;

public class NesEmulator {
    private final CPU cpu;
    private final Memory memory;

    public NesEmulator(Memory memory) {
        this.memory = memory;
        this.cpu = new CPU(memory);
    }

    public void reset() {
        cpu.reset();
    }

    public void run() {
        while (true) {
            cpu.clock();
            // Aqui adicionar lógica para renderizar a tela, processar entrada, etc.
            // Por exemplo, chamar um método de renderização ou aguardar um intervalo de tempo.
        }
    }
}
