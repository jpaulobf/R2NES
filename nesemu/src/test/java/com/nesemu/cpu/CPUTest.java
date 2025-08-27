package com.nesemu.cpu;

import com.nesemu.bus.Bus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for ADC (Add with Carry) opcode (0x69 immediate variant
 * primarily).
 * Covers: basic add, carry in, carry out, signed overflow, zero result,
 * negative result absence, and combined carry/zero.
 * Uses only Bus read/write (no direct Memory usage) to honor architectural
 * boundary.
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
    private void w(int addr, int val) {
        bus.write(addr, val);
    }

    private void setReset(int pc) {
        w(0xFFFC, pc & 0xFF);
        w(0xFFFD, (pc >> 8) & 0xFF);
    }

    private int runInstr() {
        cpu.clock();
        int cycles = 1;
        while (cpu.getCycles() > 0) {
            cpu.clock();
            cycles++;
        }
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
        w(0x8000, 0x69);
        w(0x8001, 0x05);
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
        w(0x8000, 0x69);
        w(0x8001, 0x20);
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
        w(0x8000, 0x69);
        w(0x8001, 0x50);
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
        w(0x8000, 0x69);
        w(0x8001, 0x01);
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
        w(0x8000, 0x69);
        w(0x8001, 0x00);
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
        w(0x8000, 0x29);
        w(0x8001, 0xF0);
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
        w(0x8001, 0xFF);
        w(0x8002, 0x80); // base 0x80FF
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

    @Test
    public void aslAccumulatorSetsFlags() {
        setReset(0x8000);
        w(0x8000, 0x0A); // ASL A
        cpu.reset();
        cpu.setA(0xC1); // 1100_0001
        int cycles = runInstr();
        assertEquals(0x82, cpu.getA()); // 1000_0010
        assertTrue(cpu.isCarry(), "Bit 7 should move into carry");
        assertTrue(cpu.isNegative(), "Result bit7 should be set");
        assertFalse(cpu.isZero());
        assertEquals(2, cycles, "ASL accumulator should take 2 cycles");
    }

    @Test
    public void aslZeroPageRmw() {
        setReset(0x8000);
        w(0x8000, 0x06); // ASL zp
        w(0x8001, 0x42); // operand address
        w(0x0042, 0x80); // value -> becomes 0x00, carry=1, zero=1
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x00, bus.read(0x0042));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(5, cycles, "ASL zero page should take 5 cycles (RMW)");
    }

    // -------- Branches: BCC --------

    @Test
    public void bccNotTaken() {
        setReset(0x8000);
        w(0x8000, 0x90); // BCC +2
        w(0x8001, 0x02);
        cpu.reset();
        cpu.setCarry(true); // condition false
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC(), "PC should just advance past operand");
        assertEquals(2, cycles, "Branch not taken consumes 2 cycles");
        assertFalse(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bccTakenNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0x90); // BCC +5
        w(0x8001, 0x05);
        cpu.reset();
        cpu.setCarry(false); // branch taken
        int cycles = runInstr();
        assertEquals(0x8007, cpu.getPC()); // base PC after fetch 0x8002 + 5
        assertEquals(3, cycles, "Taken branch without page cross = 3 cycles");
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bccTakenPageCross() {
        // Arrange so base PC after fetch is 0x80F0 then add +0x20 -> 0x8110 (page
        // cross)
        setReset(0x80EE);
        w(0x80EE, 0x90); // BCC
        w(0x80EF, 0x20); // +32
        cpu.reset();
        cpu.setCarry(false);
        int cycles = runInstr();
        assertEquals(0x8110, cpu.getPC());
        assertEquals(4, cycles, "Taken branch with page cross = 4 cycles");
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    // -------- Branches: BCS --------

    @Test
    public void bcsNotTaken() {
        setReset(0x8000);
        w(0x8000, 0xB0); // BCS +4
        w(0x8001, 0x04);
        cpu.reset();
        cpu.setCarry(false); // not taken
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    // -------- Branches: BEQ --------

    @Test
    public void beqNotTaken() {
        setReset(0x8000);
        w(0x8000, 0xF0); // BEQ +6
        w(0x8001, 0x06);
        cpu.reset();
        cpu.setZero(false);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    @Test
    public void beqTakenNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0xF0); // BEQ +3
        w(0x8001, 0x03);
        cpu.reset();
        cpu.setZero(true);
        int cycles = runInstr();
        assertEquals(0x8005, cpu.getPC()); // 0x8002 + 3
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void beqTakenPageCrossBackward() {
        // base PC after fetch 0x8105 then add -0x10 -> 0x80F5 (crosses page down)
        setReset(0x8103);
        w(0x8103, 0xF0); // BEQ
        w(0x8104, 0xF0); // -16
        cpu.reset();
        cpu.setZero(true);
        int cycles = runInstr();
        assertEquals(0x80F5, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bitZeroPageZeroResultClearsNV() {
        setReset(0x8000);
        w(0x8000, 0x24); // BIT zp
        w(0x8001, 0x40); // addr
        w(0x0040, 0x0F); // bits 6/7 = 0
        cpu.reset();
        cpu.setA(0xF0); // A & 0x0F = 0 -> Z=1
        cpu.setCarry(true); // carry deve permanecer
        int oldA = cpu.getA();
        int cycles = runInstr();
        assertEquals(oldA, cpu.getA());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertFalse(cpu.isOverflow());
        assertTrue(cpu.isCarry());
        assertEquals(3, cycles, "BIT zero page = 3 ciclos");
    }

    @Test
    public void bitAbsoluteNonZeroSetsNVAndClearsZ() {
        setReset(0x9000);
        w(0x9000, 0x2C); // BIT abs
        w(0x9001, 0x34); // low
        w(0x9002, 0x12); // high => 0x1234
        w(0x1234, 0xC0); // 1100_0000 -> N=1 V=1
        cpu.reset();
        cpu.setA(0xFF); // A & 0xC0 = 0xC0 != 0
        cpu.setCarry(false);
        int oldA = cpu.getA();
        int cycles = runInstr();
        assertEquals(oldA, cpu.getA());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertTrue(cpu.isOverflow());
        assertFalse(cpu.isCarry());
        assertEquals(4, cycles, "BIT absolute = 4 ciclos");
    }

    @Test
    public void bitZeroPageMixedFlags() {
        setReset(0x8000);
        w(0x8000, 0x24);
        w(0x8001, 0x10); // BIT zp $10
        w(0x0010, 0x40); // somente bit6 (V) setado
        cpu.reset();
        cpu.setA(0x40); // A & 0x40 = 0x40 => Z=0
        cpu.setCarry(true);
        runInstr();
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertTrue(cpu.isOverflow());
        assertTrue(cpu.isCarry());
    }

    // ---------------- BMI (branch if negative) ----------------

    @Test
    public void bmiNotTaken() {
        setReset(0x8000);
        w(0x8000, 0x30); // BMI +5
        w(0x8001, 0x05);
        cpu.reset();
        cpu.setNegative(false);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    @Test
    public void bmiTakenNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0x30);
        w(0x8001, 0x06); // +6
        cpu.reset();
        cpu.setNegative(true);
        int cycles = runInstr();
        assertEquals(0x8008, cpu.getPC()); // 0x8002 + 6
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bmiTakenPageCrossForward() {
        setReset(0x80F0);
        w(0x80F0, 0x30);
        w(0x80F1, 0x25); // +0x25 -> PC base 0x80F2 -> 0x8117 (page cross)
        cpu.reset();
        cpu.setNegative(true);
        int cycles = runInstr();
        assertEquals(0x8117, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    // ---------------- BNE (branch if not zero) ----------------

    @Test
    public void bneNotTaken() {
        setReset(0x8000);
        w(0x8000, 0xD0);
        w(0x8001, 0x02); // BNE +2
        cpu.reset();
        cpu.setZero(true);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    @Test
    public void bneTakenBackwardNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0xD0);
        w(0x8001, 0xFE); // -2 -> dest 0x8000
        cpu.reset();
        cpu.setZero(false);
        int cycles = runInstr();
        assertEquals(0x8000, cpu.getPC());
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bneTakenPageCrossBackward() {
        setReset(0x8103);
        w(0x8103, 0xD0);
        w(0x8104, 0xF0); // -16 -> 0x80F5 (page cross down)
        cpu.reset();
        cpu.setZero(false);
        int cycles = runInstr();
        assertEquals(0x80F5, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    // ---------------- BPL (branch if positive / not negative) ----------------

    @Test
    public void bplNotTaken() {
        setReset(0x8000);
        w(0x8000, 0x10);
        w(0x8001, 0x05); // BPL +5
        cpu.reset();
        cpu.setNegative(true);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    @Test
    public void bplTakenNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0x10);
        w(0x8001, 0x06); // +6
        cpu.reset();
        cpu.setNegative(false);
        int cycles = runInstr();
        assertEquals(0x8008, cpu.getPC());
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bplTakenPageCrossBackward() {
        setReset(0x8104);
        w(0x8104, 0x10);
        w(0x8105, 0xF0); // -16 -> base 0x8106 - 0x10 = 0x80F6 (page cross down)
        cpu.reset();
        cpu.setNegative(false);
        int cycles = runInstr();
        assertEquals(0x80F6, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bcsNotTakenCarryClear() {
        setReset(0x8000);
        w(0x8000, 0xB0); // BCS +4
        w(0x8001, 0x04);
        cpu.reset();
        cpu.setCarry(false);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bcsTakenZeroOffset() {
        setReset(0x8000);
        w(0x8000, 0xB0); // BCS +0
        w(0x8001, 0x00);
        cpu.reset();
        cpu.setCarry(true);
        int cycles = runInstr();
        // base PC após fetch = 0x8002, offset 0 => permanece
        assertEquals(0x8002, cpu.getPC());
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bcsTakenForwardNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0xB0); // BCS +6
        w(0x8001, 0x06);
        cpu.reset();
        cpu.setCarry(true);
        int cycles = runInstr();
        assertEquals(0x8008, cpu.getPC()); // 0x8002 + 6
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bcsTakenForwardPageCrossUp() {
        // start em 0x80F0 => base pós fetch 0x80F2; +0x20 => 0x8112 cruza página
        setReset(0x80F0);
        w(0x80F0, 0xB0); // BCS
        w(0x80F1, 0x20);
        cpu.reset();
        cpu.setCarry(true);
        int cycles = runInstr();
        assertEquals(0x8112, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bcsTakenBackwardNoPageCross() {
        setReset(0x8000);
        w(0x8000, 0xB0); // BCS -2 (0xFE)
        w(0x8001, 0xFE);
        cpu.reset();
        cpu.setCarry(true);
        int cycles = runInstr();
        assertEquals(0x8000, cpu.getPC()); // 0x8002 - 2
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertFalse(cpu.wasLastBranchPageCross());
    }

    @Test
    public void bcsTakenBackwardPageCrossDown() {
        // start 0x8104 => base pós fetch 0x8106; offset -0x20 (0xE0) => 0x80E6 cruza
        // página p/ baixo
        setReset(0x8104);
        w(0x8104, 0xB0); // BCS
        w(0x8105, 0xE0); // -32
        cpu.reset();
        cpu.setCarry(true);
        int cycles = runInstr();
        assertEquals(0x80E6, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchTaken());
        assertTrue(cpu.wasLastBranchPageCross());
    }

    @Test
    public void brkPushesPCAndStatusAndJumpsVector() {
        // Set vector to 0x3456
        w(0xFFFE, 0x56);
        w(0xFFFF, 0x34);
        setReset(0x8000);
        w(0x8000, 0x00); // BRK
        cpu.reset();
        // Arrange flags (some set some clear) to verify they are pushed
        cpu.setCarry(true);
        cpu.setZero(false);
        cpu.setInterruptDisable(false);
        cpu.setDecimal(true);
        cpu.setOverflow(true);
        cpu.setNegative(true);
        cpu.setBreakFlag(false); // BRK will set breakFlag when pushing
        int preSP = cpu.getSP();
        int cycles = runInstr();
        assertEquals(0x3456, cpu.getPC(), "PC deve ir para vetor IRQ/BRK");
        // Stack layout after BRK: PCH, PCL, Status (B set). SP decrements each push.
        int spAfter = cpu.getSP();
        assertEquals((preSP - 3) & 0xFF, spAfter);
        int statusPushed = bus.read(0x0100 + ((spAfter + 1) & 0xFF)); // last pushed (status)
        assertTrue((statusPushed & 0x10) != 0, "Break flag deve estar set no valor empilhado");
        assertTrue((statusPushed & 0x01) != 0, "Carry preservado");
        assertTrue((statusPushed & 0x08) != 0, "Decimal preservado");
        assertTrue((statusPushed & 0x40) != 0, "Overflow preservado");
        assertTrue((statusPushed & 0x80) != 0, "Negative preservado");
        assertTrue(cpu.isInterruptDisable(), "BRK deve setar Interrupt Disable");
        assertEquals(7, cycles, "Ciclos BRK = 7");
    }

    // ---------- BVC (branch if overflow clear) ----------
    @Test
    public void bvcNotTaken() {
        setReset(0x8000);
        w(0x8000, 0x50);
        w(0x8001, 0x05); // BVC +5
        cpu.reset();
        cpu.setOverflow(true);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    @Test
    public void bvcTakenNoCross() {
        setReset(0x8000);
        w(0x8000, 0x50);
        w(0x8001, 0x06);
        cpu.reset();
        cpu.setOverflow(false);
        int cycles = runInstr();
        assertEquals(0x8008, cpu.getPC());
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
    }

    @Test
    public void bvcTakenPageCross() {
        setReset(0x80F0);
        w(0x80F0, 0x50);
        w(0x80F1, 0x20); // +0x20 -> cross
        cpu.reset();
        cpu.setOverflow(false);
        int cycles = runInstr();
        assertEquals(0x8112, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchPageCross());
    }

    // ---------- BVS (branch if overflow set) ----------
    @Test
    public void bvsNotTaken() {
        setReset(0x8000);
        w(0x8000, 0x70);
        w(0x8001, 0x04);
        cpu.reset();
        cpu.setOverflow(false);
        int cycles = runInstr();
        assertEquals(0x8002, cpu.getPC());
        assertEquals(2, cycles);
        assertFalse(cpu.wasLastBranchTaken());
    }

    @Test
    public void bvsTakenBackwardNoCross() {
        setReset(0x8000);
        w(0x8000, 0x70);
        w(0x8001, 0xFE); // -2 -> self
        cpu.reset();
        cpu.setOverflow(true);
        int cycles = runInstr();
        assertEquals(0x8000, cpu.getPC());
        assertEquals(3, cycles);
        assertTrue(cpu.wasLastBranchTaken());
    }

    @Test
    public void bvsTakenBackwardPageCross() {
        setReset(0x8105);
        w(0x8105, 0x70);
        w(0x8106, 0xF0); // -16
        cpu.reset();
        cpu.setOverflow(true);
        int cycles = runInstr();
        assertEquals(0x80F7, cpu.getPC());
        assertEquals(4, cycles);
        assertTrue(cpu.wasLastBranchPageCross());
    }

    // ---------- Flag clear instructions ----------
    @Test
    public void clcClearsOnlyCarry() {
        setReset(0x8000);
        w(0x8000, 0x18);
        cpu.reset();
        cpu.setCarry(true);
        cpu.setZero(true);
        cpu.setNegative(true);
        int c = runInstr();
        assertFalse(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertEquals(2, c);
    }

    @Test
    public void cldClearsOnlyDecimal() {
        setReset(0x8000);
        w(0x8000, 0xD8);
        cpu.reset();
        cpu.setDecimal(true);
        cpu.setCarry(true);
        int c = runInstr();
        assertFalse(cpu.isDecimal());
        assertTrue(cpu.isCarry());
        assertEquals(2, c);
    }

    @Test
    public void cliClearsInterruptDisable() {
        setReset(0x8000);
        w(0x8000, 0x58);
        cpu.reset();
        cpu.setInterruptDisable(true);
        int c = runInstr();
        assertFalse(cpu.isInterruptDisable());
        assertEquals(2, c);
    }

    @Test
    public void clvClearsOverflow() {
        setReset(0x8000);
        w(0x8000, 0xB8);
        cpu.reset();
        cpu.setOverflow(true);
        int c = runInstr();
        assertFalse(cpu.isOverflow());
        assertEquals(2, c);
    }

    // ---------- CMP (immediate variants & zero page) ----------
    @Test
    public void cmpImmediateAEqual() {
        setReset(0x8000);
        w(0x8000, 0xC9);
        w(0x8001, 0x44); // CMP #$44
        cpu.reset();
        cpu.setA(0x44);
        cpu.setCarry(false);
        cpu.setZero(false);
        cpu.setNegative(true);
        int cycles = runInstr();
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void cmpImmediateAGreater() {
        setReset(0x8000);
        w(0x8000, 0xC9);
        w(0x8001, 0x10);
        cpu.reset();
        cpu.setA(0x20);
        cpu.setCarry(false);
        cpu.setZero(true);
        cpu.setNegative(true);
        runInstr();
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void cmpImmediateALess() {
        setReset(0x8000);
        w(0x8000, 0xC9);
        w(0x8001, 0x50);
        cpu.reset();
        cpu.setA(0x40);
        cpu.setCarry(true);
        cpu.setZero(true);
        cpu.setNegative(false);
        runInstr();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void cmpZeroPageBoundaryNegativeResult() {
        setReset(0x8000);
        w(0x8000, 0xC5);
        w(0x8001, 0x80); // CMP $80
        w(0x0080, 0xFF); // 0x40 - 0xFF => 0x141 -> low byte 0x41 (bit7=0) => carry clear, negative CLEAR
        cpu.reset();
        cpu.setA(0x40);
        int cycles = runInstr();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(3, cycles);
    }

    // ---------- CPX ----------
    @Test
    public void cpxImmediateEqual() {
        setReset(0x8000);
        w(0x8000, 0xE0);
        w(0x8001, 0x42);
        cpu.reset();
        cpu.setX(0x42);
        int cycles = runInstr();
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void cpxImmediateGreater() {
        setReset(0x8000);
        w(0x8000, 0xE0);
        w(0x8001, 0x10);
        cpu.reset();
        cpu.setX(0x20);
        runInstr();
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void cpxImmediateLessNegative() {
        setReset(0x8000);
        w(0x8000, 0xE0);
        w(0x8001, 0x80);
        cpu.reset();
        cpu.setX(0x10);
        runInstr();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void cpxZeroPage() {
        setReset(0x8000);
        w(0x8000, 0xE4);
        w(0x8001, 0x20);
        w(0x0020, 0x10);
        cpu.reset();
        cpu.setX(0x10);
        int cycles = runInstr();
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(3, cycles);
    }

    // ---------- CPY ----------
    @Test
    public void cpyImmediateEqual() {
        setReset(0x8000);
        w(0x8000, 0xC0);
        w(0x8001, 0x33);
        cpu.reset();
        cpu.setY(0x33);
        int cycles = runInstr();
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void cpyImmediateGreater() {
        setReset(0x8000);
        w(0x8000, 0xC0);
        w(0x8001, 0x0F);
        cpu.reset();
        cpu.setY(0x1F);
        runInstr();
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void cpyImmediateLessNegative() {
        setReset(0x8000);
        w(0x8000, 0xC0);
        w(0x8001, 0xF0);
        cpu.reset();
        cpu.setY(0x10);
        runInstr();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void cpyZeroPage() {
        setReset(0x8000);
        w(0x8000, 0xC4);
        w(0x8001, 0x2A);
        w(0x002A, 0x55);
        cpu.reset();
        cpu.setY(0x55);
        int cycles = runInstr();
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(3, cycles);
    }

    // ---------- DEC (RMW) ----------
    @Test
    public void decZeroPageToZero() {
        setReset(0x8000);
        w(0x8000, 0xC6);
        w(0x8001, 0x40);
        w(0x0040, 0x01);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x00, bus.read(0x0040));
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(5, cycles);
    }

    @Test
    public void decZeroPageWrapToFF() {
        setReset(0x8000);
        w(0x8000, 0xC6);
        w(0x8001, 0x41);
        w(0x0041, 0x00);
        cpu.reset();
        runInstr();
        assertEquals(0xFF, bus.read(0x0041));
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void decAbsoluteX() {
        setReset(0x8000);
        w(0x8000, 0xDE);
        w(0x8001, 0x00);
        w(0x8002, 0x90); // DEC $9000,X
        cpu.reset();
        cpu.setX(0x05);
        w(0x9005, 0x80);
        int cycles = runInstr();
        assertEquals(0x7F, bus.read(0x9005));
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(7, cycles);
    }

    // ---------- INC (RMW) ----------
    @Test
    public void incZeroPageToZero() {
        setReset(0x8000);
        w(0x8000, 0xE6);
        w(0x8001, 0x10);
        w(0x0010, 0xFF);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x00, bus.read(0x0010));
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(5, cycles);
    }

    @Test
    public void incZeroPageToNegative() {
        setReset(0x8000);
        w(0x8000, 0xE6);
        w(0x8001, 0x11);
        w(0x0011, 0x7F);
        cpu.reset();
        runInstr();
        assertEquals(0x80, bus.read(0x0011));
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void incAbsoluteX() {
        setReset(0x8000);
        w(0x8000, 0xFE);
        w(0x8001, 0x34);
        w(0x8002, 0xA0);
        cpu.reset();
        cpu.setX(0x02);
        w(0xA036, 0x00);
        int cycles = runInstr();
        assertEquals(0x01, bus.read(0xA036));
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(7, cycles);
    }

    // ---------- DEX / DEY ----------
    @Test
    public void dexToZero() {
        setReset(0x8000);
        w(0x8000, 0xCA);
        cpu.reset();
        cpu.setX(0x01);
        int cycles = runInstr();
        assertEquals(0x00, cpu.getX());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void dexUnderflowToFF() {
        setReset(0x8000);
        w(0x8000, 0xCA);
        cpu.reset();
        cpu.setX(0x00);
        runInstr();
        assertEquals(0xFF, cpu.getX());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void deyToZero() {
        setReset(0x8000);
        w(0x8000, 0x88);
        cpu.reset();
        cpu.setY(0x01);
        int cycles = runInstr();
        assertEquals(0x00, cpu.getY());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void deyUnderflowToFF() {
        setReset(0x8000);
        w(0x8000, 0x88);
        cpu.reset();
        cpu.setY(0x00);
        runInstr();
        assertEquals(0xFF, cpu.getY());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    // ---------- EOR ----------
    @Test
    public void eorImmediateNegative() {
        setReset(0x8000);
        w(0x8000, 0x49);
        w(0x8001, 0x0F);
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setCarry(true);
        int cycles = runInstr();
        assertEquals(0xFF, cpu.getA());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isCarry(), "Carry deve permanecer");
        assertEquals(2, cycles);
    }

    @Test
    public void eorImmediateZeroResult() {
        setReset(0x8000);
        w(0x8000, 0x49);
        w(0x8001, 0xAA);
        cpu.reset();
        cpu.setA(0xAA);
        cpu.setCarry(false);
        runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertFalse(cpu.isCarry());
    }

    @Test
    public void eorAbsoluteXNoCross() {
        setReset(0x8000);
        w(0x8000, 0x5D);
        w(0x8001, 0x01);
        w(0x8002, 0x80); // base 0x8001
        w(0x800F, 0x0F);
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setX(0x0E);
        int cycles = runInstr();
        assertEquals(0xFF, cpu.getA());
        assertTrue(cpu.isNegative());
        assertEquals(4, cycles);
    }

    @Test
    public void eorAbsoluteXPageCrossAddsCycle() {
        setReset(0x8000);
        w(0x8000, 0x5D);
        w(0x8001, 0xFF);
        w(0x8002, 0x80);
        w(0x8100, 0x0F);
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setX(0x01);
        int cycles = runInstr();
        assertEquals(0xFF, cpu.getA());
        assertTrue(cpu.isNegative());
        assertEquals(5, cycles);
    }

    // ---------- INX / INY ----------
    @Test
    public void inxBasic() {
        setReset(0x8000);
        w(0x8000, 0xE8);
        cpu.reset();
        cpu.setX(0x10);
        int cycles = runInstr();
        assertEquals(0x11, cpu.getX());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void inxWrapToZeroSetsZClearsN() {
        setReset(0x8000);
        w(0x8000, 0xE8);
        cpu.reset();
        cpu.setX(0xFF);
        runInstr();
        assertEquals(0x00, cpu.getX());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void inxToNegative() {
        setReset(0x8000);
        w(0x8000, 0xE8);
        cpu.reset();
        cpu.setX(0x7F);
        runInstr();
        assertEquals(0x80, cpu.getX());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void inyBasic() {
        setReset(0x8000);
        w(0x8000, 0xC8);
        cpu.reset();
        cpu.setY(0x01);
        int cycles = runInstr();
        assertEquals(0x02, cpu.getY());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void inyWrapToZero() {
        setReset(0x8000);
        w(0x8000, 0xC8);
        cpu.reset();
        cpu.setY(0xFF);
        runInstr();
        assertEquals(0x00, cpu.getY());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void inyToNegative() {
        setReset(0x8000);
        w(0x8000, 0xC8);
        cpu.reset();
        cpu.setY(0x7F);
        runInstr();
        assertEquals(0x80, cpu.getY());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    // ---------- JMP Absolute ----------
    @Test
    public void jmpAbsolute() {
        setReset(0x8000);
        w(0x8000, 0x4C);
        w(0x8001, 0x34);
        w(0x8002, 0x12);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x1234, cpu.getPC());
        assertEquals(3, cycles);
    }

    // ---------- JMP Indirect (including 6502 page wrap bug) ----------
    @Test
    public void jmpIndirectBasic() {
        setReset(0x8000); // pointer at 0x2000 holds 0x5678
        w(0x8000, 0x6C);
        w(0x8001, 0x00);
        w(0x8002, 0x20);
        w(0x2000, 0x78);
        w(0x2001, 0x56);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x5678, cpu.getPC());
        assertEquals(5, cycles);
    }

    @Test
    public void jmpIndirectPageWrapBug() {
        setReset(0x8000); // pointer at 0x21FF; hi byte read from 0x2100? bug uses 0x2100 low page wrap =>
                          // 0x2100 becomes 0x2100? need emulate: CPU code uses (ptr & 0xFF00) |
                          // ((ptr+1)&0xFF)
        int ptr = 0x21FF;
        w(0x8000, 0x6C);
        w(0x8001, ptr & 0xFF);
        w(0x8002, (ptr >> 8) & 0xFF);
        w(0x21FF, 0xCD);
        w(0x2100, 0xAB);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0xABCD, cpu.getPC());
        assertEquals(5, cycles);
    }

    // ---------- JSR ----------
    @Test
    public void jsrPushesReturnAddress() {
        setReset(0x8000);
        w(0x8000, 0x20);
        w(0x8001, 0x00);
        w(0x8002, 0x90);
        cpu.reset();
        int preSP = cpu.getSP();
        int cycles = runInstr();
        assertEquals(0x9000, cpu.getPC());
        assertEquals(6, cycles);
        int spAfter = cpu.getSP();
        assertEquals((preSP - 2) & 0xFF, spAfter);
        int retLow = bus.read(0x100 + ((spAfter + 1) & 0xFF));
        int retHigh = bus.read(0x100 + ((spAfter + 2) & 0xFF));
        int ret = (retHigh << 8) | retLow;
        assertEquals(0x8002, ret);
    }

    // ---------- LDA Immediate / Zero Page / Absolute,X crossing ----------
    @Test
    public void ldaImmediateNegative() {
        setReset(0x8000);
        w(0x8000, 0xA9);
        w(0x8001, 0x80);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x80, cpu.getA());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isZero());
        assertEquals(2, cycles);
    }

    @Test
    public void ldaZeroPageZero() {
        setReset(0x8000);
        w(0x8000, 0xA5);
        w(0x8001, 0x44);
        w(0x0044, 0x00);
        cpu.reset();
        runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void ldaAbsoluteXPageCrossAddsCycle() {
        setReset(0x8000);
        w(0x8000, 0xBD);
        w(0x8001, 0xFF);
        w(0x8002, 0x80);
        w(0x8100, 0x7F);
        cpu.reset();
        cpu.setX(0x01);
        int cycles = runInstr();
        assertEquals(0x7F, cpu.getA());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(5, cycles);
    }

    // ---------- LDX Immediate / Zero Page,Y ----------
    @Test
    public void ldxImmediateZero() {
        setReset(0x8000);
        w(0x8000, 0xA2);
        w(0x8001, 0x00);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x00, cpu.getX());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void ldxZeroPageYIndexed() {
        setReset(0x8000);
        w(0x8000, 0xB6);
        w(0x8001, 0x10);
        w(0x0015, 0xAA);
        cpu.reset();
        cpu.setY(0x05);
        int cycles = runInstr();
        assertEquals(0xAA, cpu.getX());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertEquals(4, cycles);
    }

    // -------- LDY --------
    @Test
    public void ldyImmediateNegative() {
        setReset(0x8000);
        w(0x8000, 0xA0);
        w(0x8001, 0x80);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x80, cpu.getY());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isZero());
        assertEquals(2, cycles);
    }

    @Test
    public void ldyZeroPageZero() {
        setReset(0x8000);
        w(0x8000, 0xA4);
        w(0x8001, 0x10);
        w(0x0010, 0x00);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x00, cpu.getY());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(3, cycles);
    }

    @Test
    public void ldyZeroPageXIndexed() {
        setReset(0x8000);
        w(0x8000, 0xB4);
        w(0x8001, 0x20);
        w(0x0025, 0x7F);
        cpu.reset();
        cpu.setX(0x05);
        int cycles = runInstr();
        assertEquals(0x7F, cpu.getY());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(4, cycles);
    }

    @Test
    public void ldyAbsoluteXPageCrossAddsCycle() {
        setReset(0x8000);
        w(0x8000, 0xBC);
        w(0x8001, 0xFF);
        w(0x8002, 0x80);
        w(0x8100, 0x40);
        cpu.reset();
        cpu.setX(0x01);
        int cycles = runInstr();
        assertEquals(0x40, cpu.getY());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(5, cycles);
    }

    // -------- LSR (shift right) --------
    @Test
    public void lsrAccumulatorCarryAndZero() {
        setReset(0x8000);
        w(0x8000, 0x4A);
        cpu.reset();
        cpu.setA(0x01);
        int cycles = runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(2, cycles);
    }

    @Test
    public void lsrAccumulatorFrom80() {
        setReset(0x8000);
        w(0x8000, 0x4A);
        cpu.reset();
        cpu.setA(0x80);
        runInstr();
        assertEquals(0x40, cpu.getA());
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void lsrZeroPageRmw() {
        setReset(0x8000);
        w(0x8000, 0x46);
        w(0x8001, 0x30);
        w(0x0030, 0x02);
        cpu.reset();
        int cycles = runInstr();
        assertEquals(0x01, bus.read(0x0030));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(5, cycles);
    }

    @Test
    public void lsrZeroPageToZeroSetsCarry() {
        setReset(0x8000);
        w(0x8000, 0x46);
        w(0x8001, 0x31);
        w(0x0031, 0x01);
        cpu.reset();
        runInstr();
        assertEquals(0x00, bus.read(0x0031));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
    }

    // -------- NOP --------
    @Test
    public void nopDoesNothingExceptAdvancePC() {
        setReset(0x8000);
        w(0x8000, 0xEA);
        cpu.reset();
        cpu.setA(0x55);
        cpu.setCarry(true);
        int cycles = runInstr();
        assertEquals(0x8001, cpu.getPC());
        assertEquals(0x55, cpu.getA());
        assertTrue(cpu.isCarry());
        assertEquals(2, cycles);
    }

    // -------- ORA --------
    @Test
    public void oraImmediateNegative() {
        setReset(0x8000);
        w(0x8000, 0x09);
        w(0x8001, 0x80);
        cpu.reset();
        cpu.setA(0x01);
        int cycles = runInstr();
        assertEquals(0x81, cpu.getA());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isZero());
        assertEquals(2, cycles);
    }

    @Test
    public void oraImmediateZeroResult() {
        setReset(0x8000);
        w(0x8000, 0x09);
        w(0x8001, 0x00);
        cpu.reset();
        cpu.setA(0x00);
        runInstr();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void oraAbsoluteYPageCross() {
        setReset(0x8000);
        w(0x8000, 0x19);
        w(0x8001, 0xFF);
        w(0x8002, 0x80);
        w(0x8100, 0x0F);
        cpu.reset();
        cpu.setA(0xF0);
        cpu.setY(0x01);
        int cycles = runInstr();
        assertEquals(0xFF, cpu.getA());
        assertTrue(cpu.isNegative());
        assertEquals(5, cycles);
    }

    // -------- PHA / PLA --------
    @Test
    public void phaPlaRoundTrip() {
        setReset(0x8000);
        w(0x8000, 0x48);
        w(0x8001, 0xA9);
        w(0x8002, 0x00);
        w(0x8003, 0x68); // PHA(3); LDA #$00 (2); PLA(4)
        cpu.reset();
        cpu.setA(0x7E);
        int c1 = runInstr();
        int c2 = runInstr();
        int c3 = runInstr();
        assertEquals(3 + 2 + 4, c1 + c2 + c3);
        assertEquals(0x7E, cpu.getA());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void plaSetsZeroAndNegative() { // prepara valor 0x80 na pilha via PHA e restaura
        setReset(0x8000);
        w(0x8000, 0xA9);
        w(0x8001, 0x80);
        w(0x8002, 0x48);
        w(0x8003, 0xA9);
        w(0x8004, 0x01);
        w(0x8005, 0x68); // LDA #$80; PHA; LDA #$01; PLA
        cpu.reset();
        runInstr();
        runInstr();
        runInstr();
        runInstr();
        runInstr(); // executar sequência
        assertEquals(0x80, cpu.getA());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    // -------- PHP --------
    @Test
    public void phpPushesStatusWithBreakAndUnusedSet() {
        setReset(0x8000);
        w(0x8000, 0x08); // PHP
        cpu.reset();
        cpu.setCarry(true);
        cpu.setZero(true);
        cpu.setOverflow(true);
        cpu.setNegative(true);
        int preSP = cpu.getSP();
        int cycles = runInstr();
        int spAfter = cpu.getSP();
        assertEquals((preSP - 1) & 0xFF, spAfter);
        int pushed = bus.read(0x100 + ((spAfter + 1) & 0xFF));
        assertEquals(3, cycles);
        assertTrue((pushed & 0x10) != 0, "Break bit");
        assertTrue((pushed & 0x20) != 0, "Unused bit");
        assertTrue((pushed & 0x01) != 0);
        assertTrue((pushed & 0x02) != 0);
        assertTrue((pushed & 0x40) != 0);
        assertTrue((pushed & 0x80) != 0);
    }

    // -------- Helpers para empilhar estado manual (usar JSR/RTS não aqui) --------
    private void pushByte(int value){ // utiliza PHA sequência: LDA #value; PHA
        // allocate at current reset PC end; we just push via stack for RTI test
        int sp = cpu.getSP();
        bus.write(0x100 + (sp & 0xFF), value & 0xFF); // direct write then decrement like push does
        cpu.setSP((sp - 1) & 0xFF);
    }

    // -------- PLP --------
    @Test public void plpRestoresFlagsClearsInternalBreak(){ setReset(0x8000); w(0x8000,0x28); // PLP
        cpu.reset(); // Prepare status byte with C,Z,V,N set plus break bit (should clear internally)
        int status = 0xC3; // 1100_0011 : N V - - B D I Z C -> bits: N=1,V=1,B=0x10 set, Z=1,C=1
        pushByte(status); int cycles = runInstr();
        assertEquals(4, cycles); // PLP expected 4
        assertTrue(cpu.isCarry()); assertTrue(cpu.isZero()); assertTrue(cpu.isOverflow()); assertTrue(cpu.isNegative());
        assertFalse(cpu.isBreakFlag(), "breakFlag interno deve ficar limpo");
    }

    // -------- ROL (accumulator) --------
    @Test public void rolAccumulatorWithCarryIn(){ setReset(0x8000); w(0x8000,0x2A); cpu.reset(); cpu.setA(0x40); cpu.setCarry(true); int cycles=runInstr(); // 0x40<<1 + carry=0x81
        assertEquals(0x81,cpu.getA()); assertFalse(cpu.isZero()); assertTrue(cpu.isNegative()); assertFalse(cpu.isCarry()); assertEquals(2,cycles); }
    @Test public void rolAccumulatorSetsCarry(){ setReset(0x8000); w(0x8000,0x2A); cpu.reset(); cpu.setA(0xC0); cpu.setCarry(false); runInstr(); // 1100_0000->1000_0000 carry=1
        assertEquals(0x80,cpu.getA()); assertTrue(cpu.isNegative()); assertTrue(cpu.isCarry()); }
    @Test public void rolZeroPageRmw(){ setReset(0x8000); w(0x8000,0x26); w(0x8001,0x10); w(0x0010,0x01); cpu.reset(); cpu.setCarry(false); int cycles=runInstr(); assertEquals(0x02,bus.read(0x0010)); assertFalse(cpu.isCarry()); assertFalse(cpu.isZero()); assertFalse(cpu.isNegative()); assertEquals(5,cycles); }

    // -------- ROR (accumulator) --------
    @Test public void rorAccumulatorCarryInToBit7(){ setReset(0x8000); w(0x8000,0x6A); cpu.reset(); cpu.setA(0x01); cpu.setCarry(true); int cycles=runInstr(); // new A=0x80 carry=1(from bit0)
        assertEquals(0x80,cpu.getA()); assertTrue(cpu.isNegative()); assertTrue(cpu.isCarry()); assertFalse(cpu.isZero()); assertEquals(2,cycles); }
    @Test public void rorAccumulatorResultZeroSetsCarry(){ setReset(0x8000); w(0x8000,0x6A); cpu.reset(); cpu.setA(0x01); cpu.setCarry(false); int cycles=runInstr();
        assertEquals(0x00,cpu.getA()); assertTrue(cpu.isZero()); assertFalse(cpu.isNegative()); assertTrue(cpu.isCarry()); assertEquals(2,cycles);
    }
    @Test public void rorZeroPageToZeroSetsCarry(){ setReset(0x8000); w(0x8000,0x66); w(0x8001,0x20); w(0x0020,0x01); cpu.reset(); cpu.setCarry(false); int cycles=runInstr(); assertEquals(0x00,bus.read(0x0020)); assertTrue(cpu.isCarry()); assertTrue(cpu.isZero()); assertFalse(cpu.isNegative()); assertEquals(5,cycles); }

    // -------- RTI --------
    @Test public void rtiRestoresPCAndStatus(){ setReset(0x9000); w(0x9000,0x40); // RTI
        cpu.reset(); // push status then PC low/high (reverse order for pop sequence: status, PCL, PCH?)
        // For RTI: it pops status, then PCL, then PCH; so we must push in reverse order (PCH, PCL, status) to mimic BRK
        int targetPC = 0x8123; int status=0xA5; // N|V|Z|C pattern
        pushByte((targetPC >> 8) & 0xFF); pushByte(targetPC & 0xFF); pushByte(status); int cycles=runInstr();
        assertEquals(0x8123,cpu.getPC()); assertEquals(6,cycles); assertEquals((status & 0x01)!=0, cpu.isCarry()); }

    // -------- RTS --------
    @Test public void rtsReturnsToCallerPlusOne(){ setReset(0x8000); w(0x8000,0x60); // RTS
        cpu.reset(); // push low then high (RTS pops low then high)
        int returnTo=0x8122; // after RTS should be +1 => 0x8123
        pushByte((returnTo >> 8) & 0xFF); pushByte(returnTo & 0xFF); int cycles=runInstr(); assertEquals(0x8123,cpu.getPC()); assertEquals(6,cycles); }

    // -------- SBC (immediate and page cross) --------
    @Test public void sbcImmediateNoBorrowNoOverflow(){ setReset(0x8000); w(0x8000,0xE9); w(0x8001,0x10); cpu.reset(); cpu.setA(0x30); cpu.setCarry(true); int cycles=runInstr(); assertEquals(0x20,cpu.getA()); assertTrue(cpu.isCarry()); assertFalse(cpu.isOverflow()); assertFalse(cpu.isNegative()); assertFalse(cpu.isZero()); assertEquals(2,cycles); }
    @Test public void sbcImmediateBorrowSetsNegative(){ setReset(0x8000); w(0x8000,0xE9); w(0x8001,0x40); cpu.reset(); cpu.setA(0x20); cpu.setCarry(true); runInstr(); assertEquals(0xE0,cpu.getA()); assertFalse(cpu.isCarry()); assertFalse(cpu.isZero()); assertTrue(cpu.isNegative()); }
    @Test public void sbcImmediateBorrowZeroResult(){ setReset(0x8000); w(0x8000,0xE9); w(0x8001,0x01); cpu.reset(); cpu.setA(0x01); cpu.setCarry(true); runInstr(); assertEquals(0x00,cpu.getA()); assertTrue(cpu.isCarry()); assertTrue(cpu.isZero()); }
    @Test public void sbcImmediateWithBorrowIn(){ setReset(0x8000); w(0x8000,0xE9); w(0x8001,0x01); cpu.reset(); cpu.setA(0x05); cpu.setCarry(false); runInstr(); // carry=0 means subtract 1 extra
        assertEquals(0x03,cpu.getA()); assertTrue(cpu.isCarry()); }
    @Test public void sbcSignedOverflow(){ setReset(0x8000); w(0x8000,0xE9); w(0x8001,0x80); cpu.reset(); cpu.setA(0x7F); cpu.setCarry(true); runInstr(); // 0x7F - 0x80 = -1 => 0xFF, overflow occurs (pos - neg giving neg with sign change?)
        assertEquals(0xFF,cpu.getA()); assertFalse(cpu.isCarry()); assertTrue(cpu.isOverflow()); assertTrue(cpu.isNegative()); }
    @Test public void sbcAbsoluteXPageCrossCycles(){ setReset(0x8000); w(0x8000,0xFD); w(0x8001,0xFF); w(0x8002,0x80); w(0x8100,0x01); cpu.reset(); cpu.setA(0x03); cpu.setX(0x01); cpu.setCarry(true); int cycles=runInstr(); assertEquals(0x02,cpu.getA()); assertTrue(cpu.isCarry()); assertEquals(5,cycles); }
}
