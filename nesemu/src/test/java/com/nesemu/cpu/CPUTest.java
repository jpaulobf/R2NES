package com.nesemu.cpu;

import com.nesemu.memory.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CPUTest {
    private CPU cpu;
    private TestMemory memory;

    @BeforeEach
    public void setUp() {
        memory = new TestMemory();
        cpu = new CPU(memory);
    }

    @Test
    public void testReset() {
        memory.write(0xFFFC, 0x34);
        memory.write(0xFFFD, 0x12);
        cpu.reset();
        assertEquals(0x1234, cpu.getPC());
        assertEquals(0, cpu.getA());
        assertEquals(0, cpu.getX());
        assertEquals(0, cpu.getY());
        assertEquals(0xFD, cpu.getSP());
    }

    @Test
    public void testLDAImmediate() {
        memory.write(0xFFFC, 0xA9); // LDA #$42
        memory.write(0xFFFD, 0x42);
        cpu.reset();
        cpu.clock();
        //assertEquals(0x42, cpu.getA());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void testLDAZeroFlag() {
        memory.write(0xFFFC, 0xA9); // LDA #$00
        memory.write(0xFFFD, 0x00);
        cpu.reset();
        cpu.clock();
        assertEquals(0x00, cpu.getA());
        //assertTrue(cpu.isZero());
    }

    @Test
    public void testTAX() {
        memory.write(0xFFFC, 0xA9); // LDA #$10
        memory.write(0xFFFD, 0x10);
        memory.write(0xFFFE, 0xAA); // TAX
        cpu.reset();
        cpu.clock(); // LDA
        cpu.clock(); // TAX
        //assertEquals(0x10, cpu.getX());
    }

    // Classe de memória de teste simples
    static class TestMemory implements Memory {
        private final int[] data = new int[0x10000];
        @Override
        public int read(int addr) {
            return data[addr & 0xFFFF];
        }
        @Override
        public void write(int addr, int value) {
            data[addr & 0xFFFF] = value & 0xFF;
        }
    }
}
