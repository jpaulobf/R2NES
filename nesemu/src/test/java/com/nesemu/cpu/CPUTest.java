package com.nesemu.cpu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nesemu.memory.interfaces.iMemory;

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
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xA9); // LDA #$42
        memory.write(0x8001, 0x42);
        cpu.reset();
        runOne(); // LDA #$42
        assertEquals(0x42, cpu.getA());
    }

    @Test
    public void testAbsoluteYAddressing() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDY #$05
        memory.write(0x0000, 0xA0); // LDY #$05
        memory.write(0x0001, 0x05);
        // LDA $1230,Y  (base 0x1230 + Y(5) = 0x1235)
        memory.write(0x0002, 0xB9); // LDA abs,Y
        memory.write(0x0003, 0x30); // low
        memory.write(0x0004, 0x12); // high
        memory.write(0x1235, 0x5C); // valor esperado

        cpu.reset();
        // Executa LDY
        cpu.clock();
        while (cpu.getCycles() > 0) cpu.clock();
        // Executa LDA abs,Y
        cpu.clock();
        while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0x5C, cpu.getA(), "LDA abs,Y não carregou o valor correto");
    }

    @Test
    public void testAbsoluteYPageCrossing() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDY #$01
        memory.write(0x0000, 0xA0); // LDY #$01
        memory.write(0x0001, 0x01);
        // LDA $01FF,Y  (0x01FF + 0x01 = 0x0200)
        memory.write(0x0002, 0xB9); // LDA abs,Y
        memory.write(0x0003, 0xFF); // low
        memory.write(0x0004, 0x01); // high
        memory.write(0x0200, 0x7A); // valor esperado

        cpu.reset();
        // LDY
        cpu.clock();
        while (cpu.getCycles() > 0) cpu.clock();
        // LDA abs,Y com crossing de página
        cpu.clock();
        while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0x7A, cpu.getA(), "LDA abs,Y com crossing de página falhou");
    }
    

    @Test
    public void testLDAZeroFlag() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xA9); // LDA #$00
        memory.write(0x8001, 0x00);
        cpu.reset();
        cpu.clock();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
    }

    @Test
    public void testTAX() {
    memory.write(0xFFFC, 0x00); // low byte do vetor de reset
    memory.write(0xFFFD, 0x80); // high byte do vetor de reset
    memory.write(0x8000, 0xA9); // LDA #$10
    memory.write(0x8001, 0x10);
    memory.write(0x8002, 0xAA); // TAX
    cpu.reset();
    cpu.clock(); // inicia LDA
    while (cpu.getCycles() > 0) cpu.clock(); // termina LDA
    cpu.clock(); // inicia TAX
    while (cpu.getCycles() > 0) cpu.clock(); // termina TAX
    assertEquals(0x10, cpu.getX());
    }

    // Classe de memória de teste simples
    static class TestMemory implements iMemory {
        private final int[] data = new int[0x10000];
        @Override
        public int read(int addr) {
            return data[addr & 0xFFFF];
        }
        @Override
        public void write(int addr, int value) {
            data[addr & 0xFFFF] = value & 0xFF;
        }
        public void clear() {
            for (int i = 0; i < data.length; i++) data[i] = 0;
        }
    }

    private void runOne() {
        cpu.clock();
        while (cpu.getCycles() > 0) cpu.clock();
    }

    // Mede os ciclos usados pela próxima instrução (consome completamente)
    private int runMeasured() {
        cpu.clock();
        int used = 1 + cpu.getCycles();
        while (cpu.getCycles() > 0) cpu.clock();
        return used;
    }

    // --- Testes extensos ---
    @Test
    public void testLDA_NegativeFlag() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xA9); // LDA #$FF
        memory.write(0x8001, 0xFF);
        cpu.reset();
        cpu.clock();
        assertEquals(0xFF, cpu.getA());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void testADCWithCarry() {
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0x69);
        memory.write(0x8001, 0x05);
        cpu.reset();
        cpu.setCarry(true);
        cpu.setA(0x10); // <-- Aqui!
        cpu.clock();
        assertEquals(0x16, cpu.getA());
        assertFalse(cpu.isCarry());
    }

    @Test
    public void testADCOverflow() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0x69);
        memory.write(0x8001, 0x01);
        cpu.reset();
        cpu.setA(0x7F);
        cpu.clock();
        assertEquals(0x80, cpu.getA());
        assertTrue(cpu.isOverflow());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void testSBCWithBorrow() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xE9); // SBC #$01
        memory.write(0x8001, 0x01);
        cpu.reset();
        cpu.setA(0x10);
        cpu.setCarry(true);
        cpu.clock();
        while (cpu.getCycles() > 0) cpu.clock();
        assertEquals(0x0F, cpu.getA());
        assertTrue(cpu.isCarry());
    }

    @Test
    public void testBranchTakenAndPageCross() {
        cpu.setPC(0x80FE);
        memory.write(0x80FE, 0xD0); // BNE +2
        memory.write(0x80FF, 0x02);
        cpu.setZero(false);
        cpu.clock();
        assertEquals(0x8102, cpu.getPC());
    }

    @Test
    public void testJSRAndRTS() {
    memory.write(0xFFFC, 0x00); // low byte do vetor de reset
    memory.write(0xFFFD, 0x80); // high byte do vetor de reset
    memory.write(0x8000, 0x20); // JSR $9000
    memory.write(0x8001, 0x00);
    memory.write(0x8002, 0x90);
    memory.write(0x9000, 0x60); // RTS
    cpu.reset();
    cpu.clock(); // JSR
    while (cpu.getCycles() > 0) cpu.clock();
    System.err.printf("[TEST] Após JSR, PC=%04X\n", cpu.getPC());
    assertEquals(0x9000, cpu.getPC());
    cpu.clock(); // RTS
    while (cpu.getCycles() > 0) cpu.clock();
    System.err.printf("[TEST] Após RTS, PC=%04X\n", cpu.getPC());
    assertEquals(0x8003, cpu.getPC());
    }

    @Test
    public void testStackPushPop() {
        // Corrigir vetor de reset para 0x0000
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset
        memory.write(0xFFFD, 0x00); // high byte do vetor de reset
        memory.write(0x0000, 0x48); // PHA
        memory.write(0x0001, 0x68); // PLA
        cpu.reset();
        cpu.setSP(0xFF); // definir SP após reset
        cpu.setA(0x55); // definir A após reset
        cpu.setX(0xAA);
        cpu.setY(0x77);
        cpu.setCarry(true);
        cpu.setZero(true);
        cpu.setNegative(true);
        cpu.setOverflow(true);
        cpu.setBreakFlag(true);
        cpu.setUnused(true);
        cpu.setInterruptDisable(true);
        cpu.setDecimal(true);
        cpu.clock(); // PHA
        assertEquals(0xFE, cpu.getSP());
        cpu.clock(); // PLA
        assertEquals(0x55, cpu.getA());
    }

    @Test
    public void testNMI() {
        // Set NMI vector to 0x9000
        memory.write(0xFFFA, 0x00);
        memory.write(0xFFFB, 0x90);
        cpu.nmi();
        cpu.clock();
        assertEquals(0x9000, cpu.getPC());
        assertTrue(cpu.isInterruptDisable());
    }

    @Test
    public void testIRQ() {
        // Set IRQ vector to 0x9000
        memory.write(0xFFFE, 0x00);
        memory.write(0xFFFF, 0x90);
        cpu.setInterruptDisable(false);
        cpu.irq();
        cpu.clock();
        assertEquals(0x9000, cpu.getPC());
        assertTrue(cpu.isInterruptDisable());
    }

    @Test
    public void testBRK() {
        // Set IRQ vector to 0x9000
        memory.write(0xFFFE, 0x00);
        memory.write(0xFFFF, 0x90);
        memory.write(0xFFFC, 0x00); // BRK
        cpu.reset();
        cpu.clock();
        assertEquals(0x9000, cpu.getPC());
        assertTrue(cpu.isInterruptDisable());
    }

    @Test
    public void testStatusFlags() {
        cpu.setCarry(true);
        cpu.setZero(true);
        cpu.setInterruptDisable(true);
        cpu.setDecimal(true);
        cpu.setBreakFlag(true);
        cpu.setUnused(true);
        cpu.setOverflow(true);
        cpu.setNegative(true);
        int status = cpu.getStatusByte();
        assertEquals(0xFF, status);
        cpu.setStatusByte(0x00);
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isInterruptDisable());
        assertFalse(cpu.isDecimal());
        assertFalse(cpu.isBreakFlag());
        assertFalse(cpu.isOverflow());
        assertFalse(cpu.isNegative());
        assertTrue(cpu.isUnused());
    }

    // --- Testes adicionais sugeridos ---

    @Test
    public void testSTAZeroPage() {
    System.out.println("[TEST] testSTAZeroPage start");
    memory.write(0xFFFC, 0x00); // low byte do vetor de reset
    memory.write(0xFFFD, 0x80); // high byte do vetor de reset
    memory.write(0x8000, 0xA9); // LDA #$77
    memory.write(0x8001, 0x77);
    memory.write(0x8002, 0x85); // STA $10
    memory.write(0x8003, 0x10);
    cpu.reset();
    System.out.println("[TEST] After reset, PC=" + String.format("%04X", cpu.getPC()));
    runOne(); // LDA #$77
    System.out.println("[TEST] After LDA, A=" + String.format("%02X", cpu.getA()) + ", PC=" + String.format("%04X", cpu.getPC()));
    runOne(); // STA $10
    System.out.println("[TEST] After STA, memory[0x10]=" + String.format("%02X", memory.read(0x10)) + ", PC=" + String.format("%04X", cpu.getPC()));
    assertEquals(0x77, memory.read(0x10));
    }

    @Test
    public void testLDXAbsolute() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x8000)
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xAE); // LDX $1234
        memory.write(0x8001, 0x34); // low byte do endereço
        memory.write(0x8002, 0x12); // high byte do endereço
        memory.write(0x1234, 0x56); // valor a ser carregado em X
        cpu.reset();
        cpu.clock();
        assertEquals(0x56, cpu.getX());
    }

    @Test
    public void testINCDECAbsolute() {
        memory.write(0x1234, 0x10);
        // INC $1234
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x8000)
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xEE); // INC $1234
        memory.write(0x8001, 0x34);
        memory.write(0x8002, 0x12);
        cpu.reset();
    runOne();
        assertEquals(0x11, memory.read(0x1234));
        // DEC $1234
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0xCE); // DEC $1234
        memory.write(0x8001, 0x34);
        memory.write(0x8002, 0x12);
        cpu.reset();
    runOne();
        assertEquals(0x10, memory.read(0x1234));
    }

    @Test
    public void testAND_ORA_EOR_BIT() {
        // LDA #$F0; AND #$0F => 0x00
        memory.write(0xFFFC, 0xA9); memory.write(0xFFFD, 0xF0);
        memory.write(0xFFFE, 0x29); memory.write(0xFFFF, 0x0F);
        cpu.reset();
    runOne(); // LDA
    runOne(); // AND
        assertEquals(0x00, cpu.getA());
        // ORA #$AA
        memory.write(0x0000, 0x09); memory.write(0x0001, 0xAA);
        cpu.setPC(0x0000);
    runOne(); // ORA
        assertEquals(0xAA, cpu.getA());
        // EOR #$FF
        memory.write(0x0002, 0x49); memory.write(0x0003, 0xFF);
        cpu.setPC(0x0002);
    runOne(); // EOR
        assertEquals(0x55, cpu.getA());
        // BIT $10 (A=0x55, mem=0x80)
        memory.write(0x0010, 0x80);
        memory.write(0x0004, 0x24); memory.write(0x0005, 0x10);
        cpu.setPC(0x0004);
    runOne(); // BIT
        assertTrue(cpu.isNegative());
    }

    @Test
    public void testTransfers() {
        // Set up code and reset
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x8000)
        memory.write(0xFFFD, 0x80); // high byte do vetor de reset
        memory.write(0x8000, 0xA8); // TAY (Y = A)
        memory.write(0x8001, 0x8A); // TXA (A = X)
        memory.write(0x8002, 0x98); // TYA (A = Y)
        memory.write(0x8003, 0xAA); // TAX (X = A)
        cpu.reset();
        cpu.setA(0x22); // Set A after reset
        cpu.setX(0x00);
        cpu.setY(0x00);
        // TAY: Y = A (0x22)
    runOne();
        assertEquals(0x22, cpu.getY(), "TAY failed: Y should be 0x22");
        // TXA: A = X (0)
        cpu.setX(0x33); // Set X to 0x33 before TXA
    runOne();
        assertEquals(0x33, cpu.getA(), "TXA failed: A should be 0x33");
        // TYA: A = Y (0x22)
    runOne();
        assertEquals(0x22, cpu.getA(), "TYA failed: A should be 0x22");
        // TAX: X = A (0x22)
    runOne();
        assertEquals(0x22, cpu.getX(), "TAX failed: X should be 0x22");
    }

    @Test
    public void testIncrementDecrement() {
        // Set opcodes at 0x0000 and set reset vector to 0x0000
        memory.write(0x0000, 0xE8); // INX
        memory.write(0x0001, 0xC8); // INY
        memory.write(0x0002, 0xCA); // DEX
        memory.write(0x0003, 0x88); // DEY
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x0000)
        memory.write(0xFFFD, 0x00); // high byte do vetor de reset
        cpu.reset();
        cpu.setX(0x01); // Set X after reset
    runOne(); // INX
        assertEquals(0x02, cpu.getX());
    runOne(); // INY
        assertEquals(0x01, cpu.getY());
    runOne(); // DEX
        assertEquals(0x01, cpu.getX());
    runOne(); // DEY
        assertEquals(0x00, cpu.getY());
    }

    @Test
    public void testNOP() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x0000)
        memory.write(0xFFFD, 0x00); // high byte do vetor de reset
        memory.write(0x0000, 0xEA); // NOP
        cpu.reset();
        cpu.clock();
        assertEquals(0x0001, cpu.getPC());
    }

    @Test
    public void testJMPAbsolute() {
        // Vetor de reset -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // Instrução: JMP $1234
        memory.write(0x0000, 0x4C); // opcode JMP abs (0x4C)
        memory.write(0x0001, 0x34); // low
        memory.write(0x0002, 0x12); // high
        cpu.reset();
        cpu.clock(); // Executa JMP absoluto
        assertEquals(0x1234, cpu.getPC(), "JMP absoluto não posicionou PC corretamente");
    }

    @Test
    public void testZeroPageIndexedAddressing() {
        // Set opcodes at 0x0000 and set reset vector to 0x0000
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x0000)
        memory.write(0xFFFD, 0x00); // high byte do vetor de reset
        memory.write(0x0000, 0xA5); // LDA $10
        memory.write(0x0001, 0x10);
        memory.write(0x0010, 0x99); // valor em $10
        memory.write(0x0015, 0x99); // valor em $15 (para LDA $10,X)
        memory.write(0x0002, 0xB5); // LDA $10,X
        memory.write(0x0003, 0x10);
        cpu.reset();
        cpu.setX(0x05);
        cpu.clock(); // LDA $10
        assertEquals(0x99, cpu.getA());
        cpu.clock(); // LDA $10,X
        assertEquals(0x99, cpu.getA());
    }

    @Test
    public void testAbsoluteIndexedPageCrossPenalty() {
        memory.write(0xFFFC, 0x00); // low byte do vetor de reset (PC = 0x0000)
        memory.write(0xFFFD, 0x00); // high byte do vetor de reset
        memory.write(0x0000, 0xBD); // LDA $01FF,X
        memory.write(0x0001, 0xFF);
        memory.write(0x0002, 0x01);
        memory.write(0x0200, 0x77);
        cpu.reset();
        cpu.setX(0x01);
        cpu.clock();
        assertEquals(0x77, cpu.getA());
    }

    @Test
    public void testZeroPageYAddressing() {
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDY #$03
        memory.write(0x0000, 0xA0); // LDY #$03
        memory.write(0x0001, 0x03);
        // LDX $10,Y
        memory.write(0x0002, 0xB6); // LDX $10,Y
        memory.write(0x0003, 0x10);
        memory.write(0x0013, 0xAB); // valor esperado

        cpu.reset();
        cpu.clock(); // LDY #$03
        while (cpu.getCycles() > 0) cpu.clock();
        cpu.clock(); // LDX $10,Y
        while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0xAB, cpu.getX());
    }

    @Test
    public void testIndirectXAddressing() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x00);
        // LDX #$10
        memory.write(0x0000, 0xA2); memory.write(0x0001, 0x10);
        // LDA ($10,X)  ; operand = $10, final zero-page index = ($10 + X)= $20
        memory.write(0x0002, 0xA1); memory.write(0x0003, 0x10);
        // Zero-page pointer at $20/$21 -> target 0x3456
        memory.write(0x0020, 0x56); // low
        memory.write(0x0021, 0x34); // high
        // Target value
        memory.write(0x3456, 0x7E);

        cpu.reset();
        // LDX
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // LDA ($10,X)
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0x7E, cpu.getA(), "LDA (zp,X) não carregou valor correto");
    }

    @Test
    public void testIndirectXZeroPageWrap() {
        // Exercita wrap de índice: operand + X ultrapassa 0xFF
    // Usa vetor de reset para 0x0200 para não sobrescrever zero-page $00/$01
    memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x02); // PC=0x0200
    int base = 0x0200;
    // LDX #$10
    memory.write(base + 0, 0xA2); memory.write(base + 1, 0x10);
    // LDA ($F0,X) -> ($F0 + $10) = $100 -> wrap = $00
    memory.write(base + 2, 0xA1); memory.write(base + 3, 0xF0);
    // Pointer em $00/$01 -> 0x2345
    memory.write(0x0000, 0x45); // low target
    memory.write(0x0001, 0x23); // high target
    // Valor alvo
    memory.write(0x2345, 0x9A);

    cpu.reset();
    // Executa LDX
    cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
    // Executa LDA (zp,X) com wrap
    cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();

    assertEquals(0x9A, cpu.getA(), "LDA (zp,X) com wrap não carregou valor correto");
    }

    @Test
    public void testIndirectYAddressing() {
        // Reset em 0x0000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x00);
        // LDY #$05
        memory.write(0x0000, 0xA0); memory.write(0x0001, 0x05);
        // LDA ($20),Y  ; base pointer at $20/$21 -> 0x1230; +Y (5) => 0x1235
        memory.write(0x0002, 0xB1); memory.write(0x0003, 0x20);
        // Pointer bytes
        memory.write(0x0020, 0x30); // low
        memory.write(0x0021, 0x12); // high -> base 0x1230
        // Target value
        memory.write(0x1235, 0x4D);

        cpu.reset();
        // LDY
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // LDA (zp),Y
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0x4D, cpu.getA(), "LDA (zp),Y não carregou valor correto");
    }

    @Test
    public void testIndirectYPageCrossing() {
        // Testa crossing: base 0x12FF + Y=1 => 0x1300
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x00);
        // LDY #$01
        memory.write(0x0000, 0xA0); memory.write(0x0001, 0x01);
        // LDA ($40),Y
        memory.write(0x0002, 0xB1); memory.write(0x0003, 0x40);
        // Pointer at $40/$41 -> 0x12FF
        memory.write(0x0040, 0xFF); // low
        memory.write(0x0041, 0x12); // high
        // Target at 0x1300
        memory.write(0x1300, 0x99);

        cpu.reset();
        // LDY
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // LDA (zp),Y (page crossing)
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0x99, cpu.getA(), "LDA (zp),Y com crossing não carregou valor correto");
    }

    @Test
    public void testAccumulatorShiftRotate() {
        // Testa instruções no acumulador: ASL A, LSR A, ROL A, ROR A
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x00);
        int pc = 0x0000;
        // LDA #$80 ; A=80
        memory.write(pc++, 0xA9); memory.write(pc++, 0x80);
        // ASL A -> A=00, carry=1, zero=1, negative=0
        memory.write(pc++, 0x0A);
        // LDA #$01
        memory.write(pc++, 0xA9); memory.write(pc++, 0x01);
        // LSR A -> A=00, carry=1, zero=1
        memory.write(pc++, 0x4A);
        // LDA #$81
        memory.write(pc++, 0xA9); memory.write(pc++, 0x81);
        // CLC (clear carry) para testar ROL sem carry inicial
        memory.write(pc++, 0x18);
        // ROL A: 0x81 <<1 + carry0 => 0x02, carry=1 (bit7), negative=0, zero=0
        memory.write(pc++, 0x2A);
        // LDA #$01
        memory.write(pc++, 0xA9); memory.write(pc++, 0x01);
        // SEC (set carry) para testar ROR inserindo 1 em bit7
        memory.write(pc++, 0x38);
        // ROR A: A=0x01 -> carry out =1, A=(0x01>>1)|0x80 = 0x80, negative=1
        memory.write(pc++, 0x6A);

        cpu.reset();

        // LDA #$80
    runOne(); // LDA #$80
        assertEquals(0x80, cpu.getA());
        // ASL A
    runOne(); // ASL A
        assertEquals(0x00, cpu.getA(), "ASL A resultado incorreto");
        assertTrue(cpu.isCarry(), "ASL A deveria setar carry");
        assertTrue(cpu.isZero(), "ASL A deveria setar zero");
        assertFalse(cpu.isNegative(), "ASL A não deveria setar negativo");
        // LDA #$01
    runOne(); // LDA #$01
        assertEquals(0x01, cpu.getA());
        // LSR A
    runOne(); // LSR A
        assertEquals(0x00, cpu.getA(), "LSR A resultado incorreto");
        assertTrue(cpu.isCarry(), "LSR A deveria setar carry (bit0=1)");
        assertTrue(cpu.isZero(), "LSR A deveria setar zero");
        assertFalse(cpu.isNegative());
        // LDA #$81
    runOne(); // LDA #$81
        assertEquals(0x81, cpu.getA());
        // CLC
    runOne(); // CLC
        assertFalse(cpu.isCarry());
        // ROL A
    runOne(); // ROL A
        assertEquals(0x02, cpu.getA(), "ROL A resultado incorreto");
        assertTrue(cpu.isCarry(), "ROL A deveria setar carry (bit7 original)");
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // LDA #$01
    runOne(); // LDA #$01
        assertEquals(0x01, cpu.getA());
        // SEC
    runOne(); // SEC
        assertTrue(cpu.isCarry());
        // ROR A
    runOne(); // ROR A
        assertEquals(0x80, cpu.getA(), "ROR A deveria produzir 0x80");
        assertTrue(cpu.isCarry(), "ROR A deveria manter carry=1 (bit0 original=1)");
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative(), "ROR A deveria setar negativo (bit7=1)");
    }

    @Test
    public void testJMPIndirect() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // JMP ($0300)
        memory.write(0x0000, 0x6C); // opcode
        memory.write(0x0001, 0x00); // low byte pointer
        memory.write(0x0002, 0x03); // high byte pointer (pointer = 0x0300)
        // Pointer contents -> target 0x1234
        memory.write(0x0300, 0x34); // low target
        memory.write(0x0301, 0x12); // high target

        cpu.reset();
        cpu.clock(); // execute JMP (indirect)
        assertEquals(0x1234, cpu.getPC(), "JMP (indirect) não saltou para o endereço correto");
    }

    @Test
    public void testJMPIndirectPageWrapBug() {
        // Testa o bug de hardware do 6502: se o ponteiro termina em 0xFF, o high byte lê da mesma página (wrap)
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x00);
        // JMP ($02FF)
        memory.write(0x0000, 0x6C);
        memory.write(0x0001, 0xFF); // low pointer
        memory.write(0x0002, 0x02); // high pointer (pointer = 0x02FF)
        // Pointer bytes: low @0x02FF, high deve ser lido de 0x0200 (wrap dentro da página 0x02xx)
        memory.write(0x02FF, 0x78); // low target
        memory.write(0x0200, 0x56); // high target (NOT 0x0300!)

        cpu.reset();
        cpu.clock();
        assertEquals(0x5678, cpu.getPC(), "JMP (indirect) page-wrap bug não replicado corretamente");
    }

    @Test
    public void testSTAAbsoluteY() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDA #$AA
        memory.write(0x0000, 0xA9); memory.write(0x0001, 0xAA);
        // LDY #$20
        memory.write(0x0002, 0xA0); memory.write(0x0003, 0x20);
        // STA $12F0,Y  (destino = 0x12F0 + 0x20 = 0x1310)
        memory.write(0x0004, 0x99); // STA abs,Y
        memory.write(0x0005, 0xF0); // low
        memory.write(0x0006, 0x12); // high

        cpu.reset();
        // LDA
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // LDY
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // STA abs,Y
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0xAA, memory.read(0x1310), "STA abs,Y não escreveu no endereço esperado");
    }

    @Test
    public void testSTAAbsoluteYPageCrossing() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDA #$55
        memory.write(0x0000, 0xA9); memory.write(0x0001, 0x55);
        // LDY #$30
        memory.write(0x0002, 0xA0); memory.write(0x0003, 0x30);
        // STA $01F0,Y  (0x01F0 + 0x30 = 0x0220 cruza página)
        memory.write(0x0004, 0x99); // STA abs,Y
        memory.write(0x0005, 0xF0); // low
        memory.write(0x0006, 0x01); // high

        cpu.reset();
        // LDA
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // LDY
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        // STA abs,Y (page crossing)
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();

        assertEquals(0x55, memory.read(0x0220), "STA abs,Y com crossing de página não escreveu corretamente");
    }

    @Test
    public void testASLMemoryVariants() {
        // Mesma lógica anterior, porém executando código a partir de 0x0400 para não sobrescrever zero page via bytes de instrução.
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x04); // PC=0x0400
        int base = 0x0400;
        int pc = base;
        // 1) Zero page $10: 0x80 -> ASL
        memory.write(pc++, 0xA9); memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x85); memory.write(pc++, 0x10); // STA $10
        memory.write(pc++, 0x06); memory.write(pc++, 0x10); // ASL $10
        // 2) Zero page $11: 0x40 -> ASL
        memory.write(pc++, 0xA9); memory.write(pc++, 0x40); // LDA #$40
        memory.write(pc++, 0x85); memory.write(pc++, 0x11); // STA $11
        memory.write(pc++, 0x06); memory.write(pc++, 0x11); // ASL $11
        // 3) Zero page $12: 0x00 -> ASL
        memory.write(pc++, 0xA9); memory.write(pc++, 0x00); // LDA #$00
        memory.write(pc++, 0x85); memory.write(pc++, 0x12); // STA $12
        memory.write(pc++, 0x06); memory.write(pc++, 0x12); // ASL $12
        // 4) Zero Page,X ($1E + X=2 => $20)
        memory.write(pc++, 0xA2); memory.write(pc++, 0x02); // LDX #$02
        memory.write(pc++, 0xA9); memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x85); memory.write(pc++, 0x20); // STA $20
        memory.write(pc++, 0x16); memory.write(pc++, 0x1E); // ASL $1E,X
        // 5) Absolute $1234
        memory.write(pc++, 0xA9); memory.write(pc++, 0x40); // LDA #$40
        memory.write(pc++, 0x8D); memory.write(pc++, 0x34); memory.write(pc++, 0x12); // STA $1234
        memory.write(pc++, 0x0E); memory.write(pc++, 0x34); memory.write(pc++, 0x12); // ASL $1234
        // 6) Absolute,X $1300,X (X=0)
        memory.write(pc++, 0xA2); memory.write(pc++, 0x00); // LDX #$00
        memory.write(pc++, 0xA9); memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x8D); memory.write(pc++, 0x00); memory.write(pc++, 0x13); // STA $1300
        memory.write(pc++, 0x1E); memory.write(pc++, 0x00); memory.write(pc++, 0x13); // ASL $1300,X

        cpu.reset();

        // 1)
    runOne(); runOne(); runOne();
        assertEquals(0x00, memory.read(0x0010));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 2)
    runOne(); runOne(); runOne();
        assertEquals(0x80, memory.read(0x0011));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 3)
    runOne(); runOne(); runOne();
        assertEquals(0x00, memory.read(0x0012));
        assertFalse(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 4)
    runOne(); runOne(); runOne(); runOne();
        assertEquals(0x00, memory.read(0x0020));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        // 5)
    runOne(); runOne(); runOne();
        assertEquals(0x80, memory.read(0x1234));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 6)
    runOne(); runOne(); runOne(); runOne();
        assertEquals(0x00, memory.read(0x1300));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    // ================== Testes mínimos antes de refactor (item 1) ==================

    @Test
    public void testCLVClearsOverflow() {
        // Coloca CLV (0xB8) em 0x8000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0xB8); // CLV
        cpu.reset();
        cpu.setOverflow(true);
        cpu.clock();
        assertFalse(cpu.isOverflow(), "CLV deve limpar overflow");
        assertEquals(0x8001, cpu.getPC(), "PC deve avançar 1 após CLV");
    }

    @Test
    public void testIncDecDoNotAffectCarry() {
        // Código: INC $1234 ; DEC $1234
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80); // PC=0x8000
        memory.write(0x8000, 0xEE); // INC $1234
        memory.write(0x8001, 0x34); memory.write(0x8002, 0x12);
        memory.write(0x8003, 0xCE); // DEC $1234
        memory.write(0x8004, 0x34); memory.write(0x8005, 0x12);
        memory.write(0x1234, 0x01); // valor inicial
        cpu.reset();
        cpu.setCarry(true); // carry inicial true
        // INC
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        assertEquals(0x02, memory.read(0x1234));
        assertTrue(cpu.isCarry(), "INC não deveria alterar carry");
        // DEC
        cpu.clock(); while (cpu.getCycles() > 0) cpu.clock();
        assertEquals(0x01, memory.read(0x1234));
        assertTrue(cpu.isCarry(), "DEC não deveria alterar carry");
    }

    @Test
    public void testNmiHasPriorityOverIrq() {
        // Vetores NMI e IRQ
        memory.write(0xFFFA, 0x00); memory.write(0xFFFB, 0x90); // NMI -> 0x9000
        memory.write(0xFFFE, 0x00); memory.write(0xFFFF, 0xA0); // IRQ -> 0xA000
        // Reset para algum endereço válido
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80); // PC=0x8000
        memory.write(0x8000, 0xEA); // NOP
        cpu.reset();
        cpu.setInterruptDisable(false);
        cpu.irq(); // agenda IRQ
        cpu.nmi(); // agenda NMI (deve ter prioridade)
        cpu.clock();
        assertEquals(0x9000, cpu.getPC(), "NMI deve ter prioridade sobre IRQ");
        assertTrue(cpu.isInterruptDisable(), "Após NMI interruptDisable deve estar setado");
    }

    @Test
    public void testBRKPushesPCAndStatus() {
        // Vetor IRQ/BRK -> 0x9000
        memory.write(0xFFFE, 0x00); memory.write(0xFFFF, 0x90);
        // Reset vector -> 0x8000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0x00); // BRK
        cpu.reset();
        // Ajusta alguns flags para verificar status push
        cpu.setCarry(true);
        cpu.setZero(false);
        cpu.setInterruptDisable(false);
        cpu.setOverflow(true);
        cpu.setNegative(true);
        int spBefore = cpu.getSP(); // deve ser 0xFD
        cpu.clock(); // executa BRK
        // Após BRK, PC = vetor 0x9000
        assertEquals(0x9000, cpu.getPC(), "BRK deve saltar para vetor IRQ/BRK");
        // Verificar pilha: ordem push: PCH, PCL, STATUS
        int pStatusAddr = 0x0100 + ((spBefore - 2) & 0xFF); // STATUS em SP-2
        int pclAddr = 0x0100 + ((spBefore - 1) & 0xFF);
        int pchAddr = 0x0100 + (spBefore & 0xFF);
        int pushedStatus = memory.read(pStatusAddr);
        int pushedPCL = memory.read(pclAddr);
        int pushedPCH = memory.read(pchAddr);
        // PC empilhado deve ser (opcode address + 2)
        int expectedReturn = 0x8002;
        assertEquals((expectedReturn & 0xFF), pushedPCL, "PCL empilhado incorreto");
        assertEquals((expectedReturn >> 8) & 0xFF, pushedPCH, "PCH empilhado incorreto");
        assertTrue((pushedStatus & 0x10) != 0, "Bit B deve estar setado no status empilhado");
        assertTrue((pushedStatus & 0x01) != 0, "Carry deveria estar 1 no status empilhado");
        assertTrue((pushedStatus & 0x40) != 0, "Overflow deveria estar 1 no status empilhado");
        assertTrue((pushedStatus & 0x80) != 0, "Negative deveria estar 1 no status empilhado");
    }

    @Test
    public void testANDImmediateFlags() {
        // Reset vector -> 0x8000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80);
        int pc = 0x8000;
        // LDA #$F0 (A=F0)
        memory.write(pc++, 0xA9); memory.write(pc++, 0xF0);
        // AND #$0F => resultado 0x00 (zero=1, negative=0) carry deve permanecer setado
        memory.write(pc++, 0x29); memory.write(pc++, 0x0F);
        // LDA #$80 (A=80)
        memory.write(pc++, 0xA9); memory.write(pc++, 0x80);
        // AND #$C0 => 0x80 (negative=1, zero=0)
        memory.write(pc++, 0x29); memory.write(pc++, 0xC0);

        cpu.reset();
        cpu.setCarry(true); // carry não deve ser afetado por AND

        // LDA #$F0
    runOne(); // LDA #$F0
        assertEquals(0xF0, cpu.getA());
        // AND #$0F => 0x00
    runOne(); // AND #$0F
        assertEquals(0x00, cpu.getA(), "AND imediato não aplicou máscara corretamente (esperado 0x00)");
        assertTrue(cpu.isZero(), "Flag Z deveria estar setado após resultado 0");
        assertFalse(cpu.isNegative(), "Flag N não deveria estar setado para 0x00");
        assertTrue(cpu.isCarry(), "Flag C deveria permanecer inalterado");
        // LDA #$80
    runOne(); // LDA #$80
        assertEquals(0x80, cpu.getA());
        // AND #$C0 => 0x80
    runOne(); // AND #$C0
        assertEquals(0x80, cpu.getA(), "AND imediato não preservou bit 7");
        assertFalse(cpu.isZero(), "Flag Z não deveria estar setado (resultado != 0)");
        assertTrue(cpu.isNegative(), "Flag N deveria estar setado (bit7=1)");
    }

    // ================== Testes de ciclos ==================

    @Test
    public void testCyclesLDAImmediate() {
        // Reset em 0x8000
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0xA9); memory.write(0x8001, 0x42); // LDA #$42
        cpu.reset();
        int cycles = runMeasured();
        assertEquals(2, cycles, "LDA imediato deve consumir 2 ciclos");
    }

    @Test
    public void testCyclesLDAAbsoluteX_PageCrossPenalty() {
        // Sem crossing
        memory.write(0xFFFC, 0x00); memory.write(0xFFFD, 0x80); // reset -> 0x8000
        // Programa: LDA $1234,X
        memory.write(0x8000, 0xBD); // LDA abs,X
        memory.write(0x8001, 0x34); // low
        memory.write(0x8002, 0x12); // high (end = 0x1234)
        memory.write(0x1235, 0x77); // valor lido (X=1)
        cpu.reset();
        cpu.setX(0x01); // não cruza página (0x1234 +1 = 0x1235)
        int baseCycles = runMeasured();
        // Com crossing: base 0x12FF + X=1 => 0x1300
        cpu.setPC(0x9000); // colocar instrução em outro lugar para isolar
        memory.write(0x9000, 0xBD); // LDA abs,X
        memory.write(0x9001, 0xFF); // low
        memory.write(0x9002, 0x12); // high -> base 0x12FF
        memory.write(0x1300, 0x55);
        cpu.setX(0x01); // cruza (0x12FF +1 = 0x1300)
        int crossingCycles = runMeasured();
        assertEquals(baseCycles + 1, crossingCycles, "LDA abs,X com page crossing deve ter +1 ciclo");
    }

    @Test
    public void testCyclesBranchTakenAndPageCross() {
        // Usar BNE (0xD0)
        // Caso 1: não tomado (Z=1)
        cpu.setPC(0x2000);
        memory.write(0x2000, 0xD0); memory.write(0x2001, 0x02); // BNE +2
        cpu.setZero(true); // condição não satisfeita
        int notTaken = runMeasured();
    assertEquals(2, notTaken, "Branch não tomado deve consumir 2 ciclos");

        // Caso 2: tomado sem crossing (Z=0)
        cpu.setPC(0x2100);
        memory.write(0x2100, 0xD0); memory.write(0x2101, 0x02); // destino 0x2104 mesma página
        cpu.setZero(false);
    int takenNoCross = runMeasured();
    assertEquals(3, takenNoCross, "Branch tomado sem crossing deve consumir 3 ciclos");

        // Caso 3: tomado com crossing
    // Colocar instrução no início da página 0x8100 para que PC após fetch fique em 0x8102
    // e usar offset negativo para ir para página anterior 0x80xx
    cpu.setPC(0x8100);
    memory.write(0x8100, 0xD0); // BNE
    memory.write(0x8101, 0xE0); // offset = -0x20 (0xE0) -> destino = 0x8102 + (-0x20) = 0x80E2 (página diferente)
    cpu.setZero(false);
    int takenCross = runMeasured();
    assertEquals(4, takenCross, "Branch tomado com crossing deve consumir 4 ciclos");
    }
}