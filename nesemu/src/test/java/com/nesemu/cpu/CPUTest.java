package com.nesemu.cpu;

import com.nesemu.bus.Bus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for ADC (Add with Carry) opcode (0x69 immediate variant primarily).
 * Covers: basic add, carry in, carry out, signed overflow, zero result, negative result absence, and combined carry/zero.
 * Uses only Bus read/write (no direct Memory usage) to honor architectural boundary.
 */
public class CPUTest {
    private CPU cpu;
    private Bus bus;

    @BeforeEach
    void setUp() {
        bus = new Bus();
        cpu = new CPU(bus);
        bus.attachCPU(cpu);
    }

    // Helper: write byte via bus
    private void w(int addr, int val) { bus.write(addr, val); }

    private void setReset(int pc) {
        w(0xFFFC, pc & 0xFF);
        w(0xFFFD, (pc >> 8) & 0xFF);
    }

    private int runInstr() {
        cpu.clock();
        int cycles = 1;
        while (cpu.getCycles() > 0) { cpu.clock(); cycles++; }
        return cycles;
    }

    @Test
    public void adcImmediateBasicNoCarry() {
        setReset(0x8000);
        w(0x8000, 0x69); // ADC #imm
        w(0x8001, 0x05);
        cpu.reset();
        cpu.setA(0x10);
        cpu.setCarry(false);
        int cycles = runInstr();
        assertEquals(0x15, cpu.getA());
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isOverflow());
        assertFalse(cpu.isNegative());
        assertFalse(cpu.isZero());
        assertEquals(2, cycles, "ADC imediato deve consumir 2 ciclos");
    }

    @Test
    public void adcImmediateWithCarryIn() {
        setReset(0x8000);
        w(0x8000, 0x69); w(0x8001, 0x05);
        cpu.reset();
        cpu.setA(0x10);
        cpu.setCarry(true); // carry-in = 1
        runInstr();
        assertEquals(0x16, cpu.getA());
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isOverflow());
    }

    @Test
    public void adcCarryOutNoOverflow() {
        setReset(0x8000);
        w(0x8000, 0x69); w(0x8001, 0x20);
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setCarry(false);
        runInstr();
        assertEquals(0x10, cpu.getA()); // 0xF0 + 0x20 = 0x110
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isOverflow());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void adcSignedOverflow() {
        setReset(0x8000);
        w(0x8000, 0x69); w(0x8001, 0x50);
        cpu.reset();
        cpu.setA(0x50);
        cpu.setCarry(false);
        runInstr();
        assertEquals(0xA0, cpu.getA());
        assertFalse(cpu.isCarry());
        assertTrue(cpu.isOverflow(), "Overflow esperado em 0x50 + 0x50");
        assertTrue(cpu.isNegative());
    }

    @Test
    public void adcZeroResultWithCarryOut() {
        setReset(0x8000);
        w(0x8000, 0x69); w(0x8001, 0x01);
        cpu.reset();
        cpu.setA(0xFF);
        cpu.setCarry(false);
        runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isOverflow());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void adcCarryInCausesCarryOutAndZero() {
        setReset(0x8000);
        w(0x8000, 0x69); w(0x8001, 0x00);
        cpu.reset();
        cpu.setA(0xFF);
        cpu.setCarry(true); // +1 via carry-in
        runInstr(); // 0xFF + 0x00 + 1 = 0x100
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isOverflow());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void andImmediateZeroResult() {
        setReset(0x8000);
        w(0x8000, 0x29); // AND #imm
        w(0x8001, 0x0F);
        cpu.reset();
        cpu.setA(0xF0); // 0xF0 & 0x0F = 0x00
        cpu.setCarry(true); // carry should be preserved
        int cycles = runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertTrue(cpu.isCarry(), "Carry deve permanecer inalterado");
        assertEquals(2, cycles, "AND imediato deve consumir 2 ciclos");
    }

    @Test
    public void andImmediateNegativeResult() {
        setReset(0x8000);
        w(0x8000, 0x29); w(0x8001, 0xF0);
        cpu.reset();
        cpu.setA(0xF0); // 0xF0 & 0xF0 = 0xF0 (negative)
        cpu.setCarry(false);
        runInstr();
        assertEquals(0xF0, cpu.getA());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isCarry());
    }

    @Test
    public void andZeroPageBasic() {
        setReset(0x8000);
        w(0x8000, 0x25); // AND zp
        w(0x8001, 0x42); // operand address
        w(0x0042, 0x3C); // value
        cpu.reset();
        cpu.setA(0xF0); // 0xF0 & 0x3C = 0x30
        int cycles = runInstr();
        assertEquals(0x30, cpu.getA());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(3, cycles, "AND zero page deve consumir 3 ciclos");
    }

    @Test
    public void andAbsoluteXNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0x3D); // AND abs,X
        w(0x8001, 0x01); // low
        w(0x8002, 0x80); // high -> base 0x8001
        // X = 0x0E -> effective 0x800F (no page cross)
        w(0x800F, 0x0F); // data
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setX(0x0E);
        int cycles = runInstr();
        assertEquals(0x00, cpu.getA()); // 0xF0 & 0x0F
        assertTrue(cpu.isZero());
        assertEquals(4, cycles, "Sem crossing deve ser 4 ciclos");
    }

    @Test
    public void andAbsoluteXPageCrossAddsCycle() {
        setReset(0x8000);
        w(0x8000, 0x3D); // AND abs,X
        w(0x8001, 0xFF); // low
        w(0x8002, 0x80); // high -> base 0x80FF
        // X = 0x01 -> effective 0x8100 (page cross)
        w(0x8100, 0x0F); // data
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setX(0x01);
        int cycles = runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
        assertEquals(5, cycles, "Page crossing deve adicionar 1 ciclo (4+1)");
    }

    @Test
    public void andAbsoluteYPageCrossAddsCycle() {
        setReset(0x8000);
        w(0x8000, 0x39); // AND abs,Y
        w(0x8001, 0xFF); w(0x8002, 0x80); // base 0x80FF
        w(0x8100, 0x80); // data after crossing
        cpu.reset();
        cpu.setA(0xFF);
        cpu.setY(0x01); // cross into 0x8100
        int cycles = runInstr();
        assertEquals(0x80, cpu.getA());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertEquals(5, cycles, "Page crossing abs,Y deve adicionar 1 ciclo");
    }
}
