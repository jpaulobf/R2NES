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
    public void testIllegalSHY_AbsoluteX() {
        // SHY (opcode 0x9C): armazena Y & (high(address)+1) no endereço abs,X
        // Fórmula no nosso código: valor = Y & ( (addr>>8)+1 & 0xFF ).
        // Cenário 1: base=0x12F0, X=0x0A => endereço efetivo=0x12FA; high=0x12;
        // high+1=0x13; Y=0xFF => escrito 0x13
        // Cenário 2: base=0x80F0, X=0x0F => endereço=0x80FF; high=0x80; high+1=0x81;
        // Y=0x7E => 0x7E & 0x81 = 0x00
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xF2); // PC=0xF200
        int pc = 0xF200;
        // Caso 1
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0xFF); // LDY #$FF
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x0A); // LDX #$0A
        memory.write(pc++, 0x9C);
        memory.write(pc++, 0xF0);
        memory.write(pc++, 0x12); // SHY $12F0,X -> $12FA
        // Caso 2
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x7E); // LDY #$7E
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x0F); // LDX #$0F
        memory.write(pc++, 0x9C);
        memory.write(pc++, 0xF0);
        memory.write(pc++, 0x80); // SHY $80F0,X -> $80FF
        cpu.reset();
        // Caso 1 execuções
        runOne(); // LDY
        runOne(); // LDX
        int cyc = runMeasured(); // SHY
        assertEquals(5, cyc, "SHY abs,X deve consumir 5 ciclos (como STA abs,X base)");
        assertEquals(0x13, memory.read(0x12FA), "SHY caso1 valor incorreto");
        // Caso 2
        runOne(); // LDY #$7E
        runOne(); // LDX #$0F
        cyc = runMeasured(); // SHY segundo
        assertEquals(5, cyc);
        assertEquals(0x00, memory.read(0x80FF), "SHY caso2 valor incorreto (esperado 0x00)");
    }

    @Test
    public void testIllegalSHX_AbsoluteY() {
        // SHX (opcode 0x9E): X & (high(address)+1) armazenado em abs,Y
        // Cenário 1: base=0x20F0,Y=0x05 => addr=0x20F5 high=0x20 -> high+1=0x21; X=0xFF
        // -> 0x21
        // Cenário 2: base=0x45F0,Y=0x0F => addr=0x45FF high=0x45 -> high+1=0x46; X=0x33
        // -> 0x02
        memory.write(0xFFFC, 0x50);
        memory.write(0xFFFD, 0xF2); // PC=0xF250
        int pc = 0xF250;
        // Caso 1
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0xFF); // LDX #$FF
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x05); // LDY #$05
        memory.write(pc++, 0x9E);
        memory.write(pc++, 0xF0);
        memory.write(pc++, 0x20); // SHX $20F0,Y -> $20F5
        // Caso 2
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x33); // LDX #$33
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x0F); // LDY #$0F
        memory.write(pc++, 0x9E);
        memory.write(pc++, 0xF0);
        memory.write(pc++, 0x45); // SHX $45F0,Y -> $45FF
        cpu.reset();
        // Caso 1
        runOne(); // LDX
        runOne(); // LDY
        int cyc = runMeasured(); // SHX
        assertEquals(5, cyc, "SHX abs,Y deve consumir 5 ciclos (como STA abs,Y base)");
        assertEquals(0x21, memory.read(0x20F5), "SHX caso1 valor incorreto");
        // Caso 2
        runOne(); // LDX
        runOne(); // LDY
        cyc = runMeasured();
        assertEquals(5, cyc);
        assertEquals(0x02, memory.read(0x45FF), "SHX caso2 valor incorreto");
    }

    @Test
    public void testIllegalSHS_AbsoluteY() {
        // SHS/TAS (opcode 0x9B): SP = A & X; memória = (A & X) & (high(addr)+1)
        // Cenário 1: A=0xFF X=0x0F base=0x12F0,Y=0x0A -> addr=0x12FA high=0x12
        // high+1=0x13
        // A&X = 0x0F; valor = 0x0F & 0x13 = 0x03; SP final = 0x0F
        // Cenário 2: A=0x5A X=0x3C base=0x34F8,Y=0x07 -> addr=0x34FF high=0x34
        // high+1=0x35
        // A&X = 0x18; valor = 0x18 & 0x35 = 0x10; SP final = 0x18
        memory.write(0xFFFC, 0xA0);
        memory.write(0xFFFD, 0xF2); // PC=0xF2A0
        int pc2 = 0xF2A0;
        // Caso 1
        memory.write(pc2++, 0xA9);
        memory.write(pc2++, 0xFF); // LDA #$FF
        memory.write(pc2++, 0xA2);
        memory.write(pc2++, 0x0F); // LDX #$0F
        memory.write(pc2++, 0xA0);
        memory.write(pc2++, 0x0A); // LDY #$0A
        memory.write(pc2++, 0x9B);
        memory.write(pc2++, 0xF0);
        memory.write(pc2++, 0x12); // SHS $12F0,Y -> $12FA
        // Caso 2
        memory.write(pc2++, 0xA9);
        memory.write(pc2++, 0x5A); // LDA #$5A
        memory.write(pc2++, 0xA2);
        memory.write(pc2++, 0x3C); // LDX #$3C
        memory.write(pc2++, 0xA0);
        memory.write(pc2++, 0x07); // LDY #$07
        memory.write(pc2++, 0x9B);
        memory.write(pc2++, 0xF8);
        memory.write(pc2++, 0x34); // SHS $34F8,Y -> $34FF
        cpu.reset();
        // Caso 1
        runOne(); // LDA
        runOne(); // LDX
        runOne(); // LDY
        int cyc = runMeasured(); // SHS
        assertEquals(5, cyc, "SHS (0x9B) abs,Y deve consumir 5 ciclos (como STA abs,Y)");
        assertEquals(0x03, memory.read(0x12FA), "SHS caso1 valor incorreto");
        assertEquals(0x0F, cpu.getSP(), "SHS caso1 SP incorreto");
        // Caso 2
        runOne(); // LDA
        runOne(); // LDX
        runOne(); // LDY
        cyc = runMeasured(); // SHS
        assertEquals(5, cyc);
        assertEquals(0x10, memory.read(0x34FF), "SHS caso2 valor incorreto");
        assertEquals(0x18, cpu.getSP(), "SHS caso2 SP incorreto");
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
        // LDA $1230,Y (base 0x1230 + Y(5) = 0x1235)
        memory.write(0x0002, 0xB9); // LDA abs,Y
        memory.write(0x0003, 0x30); // low
        memory.write(0x0004, 0x12); // high
        memory.write(0x1235, 0x4D); // valor alvo
        cpu.reset();
        runOne(); // LDY
        runOne(); // LDA abs,Y
        assertEquals(0x4D, cpu.getA());
    }

    @Test
    public void testRORAllAddressingModesAndFlags() {
        // Casos:
        // 1) ROR A : A=0x01, CLC => 0x00, C=1, Z=1, N=0
        // 2) ROR A : A=0x02, SEC => 0x81, C=0, Z=0, N=1
        // 3) ROR $20 : 0x01, CLC => 0x00, C=1, Z=1, N=0 (5 ciclos)
        // 4) ROR $30,X : X=5, mem($35)=0x80 CLC => 0x40, C=0, Z=0, N=0 (6 ciclos)
        // 5) ROR $1234 : 0x02 SEC => 0x81, C=0, Z=0, N=1 (6 ciclos)
        // 6) ROR $2000,X : X=0x10, mem($2010)=0x03 CLC => 0x01, C=1, Z=0, N=0 (7
        // ciclos)
        memory.write(0xFFFC, 0x40);
        memory.write(0xFFFD, 0xB2); // reset -> 0xB240
        int pc = 0xB240;
        // 1) A=0x01 CLC ROR A
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01); // LDA #$01
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x6A); // ROR A
        // 2) A=0x02 SEC ROR A
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x02); // LDA #$02
        memory.write(pc++, 0x38); // SEC
        memory.write(pc++, 0x6A); // ROR A
        // 3) $20=0x01 CLC ROR $20
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01); // LDA #$01
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x20); // STA $20
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x66);
        memory.write(pc++, 0x20); // ROR $20
        // 4) X=5; $30=0x80; CLC; ROR $30,X
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x05); // LDX #$05
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x95);
        memory.write(pc++, 0x30); // STA $30,X (armazenará em $30+X -> $35)
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x76);
        memory.write(pc++, 0x30); // ROR $30,X (usa X=5 -> $35)
        // 5) $1234=0x02 SEC ROR $1234
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x02); // LDA #$02
        memory.write(pc++, 0x8D);
        memory.write(pc++, 0x34);
        memory.write(pc++, 0x12); // STA $1234
        memory.write(pc++, 0x38); // SEC
        memory.write(pc++, 0x6E);
        memory.write(pc++, 0x34);
        memory.write(pc++, 0x12); // ROR $1234
        // 6) X=0x10; $2000+X=0x03; CLC ROR $2000,X
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x10); // LDX #$10
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x03); // LDA #$03
        memory.write(pc++, 0x9D);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x20); // STA $2000,X -> $2010
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x7E);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x20); // ROR $2000,X

        cpu.reset();

        int cyc;
        // 1) ROR A (A=1, CLC)
        runOne(); // LDA
        runOne(); // CLC
        cyc = runMeasured(); // ROR A
        assertEquals(2, cyc, "ROR A consome 2 ciclos");
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 2) ROR A (A=2, SEC)
        runOne(); // LDA #$02
        runOne(); // SEC
        cyc = runMeasured(); // ROR A
        assertEquals(2, cyc);
        assertEquals(0x81, cpu.getA(), "ROR A (0x02 com carry=1) deve resultar 0x81");
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 3) ROR $20 (0x01, CLC)
        runOne(); // LDA #$01
        runOne(); // STA $20
        runOne(); // CLC
        cyc = runMeasured(); // ROR $20
        assertEquals(5, cyc);
        assertEquals(0x00, memory.read(0x20));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 4) ROR $30,X (mem $35=0x80)
        runOne(); // LDX #$05
        runOne(); // LDA #$80
        runOne(); // STA $30,X (escreve em $35)
        runOne(); // CLC
        cyc = runMeasured(); // ROR $30,X
        assertEquals(6, cyc);
        assertEquals(0x40, memory.read(0x35));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 5) ROR $1234 (0x02, SEC)
        runOne(); // LDA #$02
        runOne(); // STA $1234
        runOne(); // SEC
        cyc = runMeasured(); // ROR $1234
        assertEquals(6, cyc);
        assertEquals(0x81, memory.read(0x1234));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 6) ROR $2000,X (0x03, CLC)
        runOne(); // LDX #$10
        runOne(); // LDA #$03
        runOne(); // STA $2000,X ($2010)
        runOne(); // CLC
        cyc = runMeasured(); // ROR $2000,X
        assertEquals(7, cyc);
        assertEquals(0x01, memory.read(0x2010));
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

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
        while (cpu.getCycles() > 0)
            cpu.clock();
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
        while (cpu.getCycles() > 0)
            cpu.clock();
        System.err.printf("[TEST] Após JSR, PC=%04X\n", cpu.getPC());
        assertEquals(0x9000, cpu.getPC());
        cpu.clock(); // RTS
        while (cpu.getCycles() > 0)
            cpu.clock();
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
    public void testRTI_RestoresStatusAndPCAndCycles() {
        // Arrange program: only RTI at reset location 0x8000
        memory.write(0xFFFC, 0x00); // low
        memory.write(0xFFFD, 0x80); // high -> PC=0x8000 after reset
        memory.write(0x8000, 0x40); // RTI opcode
        cpu.reset();
        // Simulate prior interrupt frame (as handleIRQ would have pushed):
        // Original stack after pushes would be: [$01FD]=PCH, [$01FC]=PCL,
        // [$01FB]=Status, SP=0xFA
        // For RTI we need SP pointing to 0xFA so that first pop reads 0x01FB.
        cpu.setSP(0xFA);
        // Target PC we want to restore: 0x1234
        memory.write(0x01FD, 0x12); // PCH
        memory.write(0x01FC, 0x34); // PCL
        // Status byte to restore (bit5 ignored but stays set inside implementation):
        // 0xA5 = 1010 0101
        // N=1, V=0, U=1, B=0, D=0, I=1, Z=0, C=1
        memory.write(0x01FB, 0xA5);
        int cycles = runMeasured();
        assertEquals(6, cycles, "RTI deve consumir 6 ciclos");
        assertEquals(0x1234, cpu.getPC(), "PC deve ser restaurado pelo RTI");
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isInterruptDisable());
        assertFalse(cpu.isDecimal());
        assertFalse(cpu.isBreakFlag());
        assertFalse(cpu.isOverflow());
        assertTrue(cpu.isNegative());
        assertEquals(0xFD, cpu.getSP(), "SP deve retornar ao valor original após restaurar 3 bytes");
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
        System.out.println("[TEST] After LDA, A=" + String.format("%02X", cpu.getA()) + ", PC="
                + String.format("%04X", cpu.getPC()));
        runOne(); // STA $10
        System.out.println("[TEST] After STA, memory[0x10]=" + String.format("%02X", memory.read(0x10)) + ", PC="
                + String.format("%04X", cpu.getPC()));
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
        memory.write(0xFFFC, 0xA9);
        memory.write(0xFFFD, 0xF0);
        memory.write(0xFFFE, 0x29);
        memory.write(0xFFFF, 0x0F);
        cpu.reset();
        runOne(); // LDA
        runOne(); // AND
        assertEquals(0x00, cpu.getA());
        // ORA #$AA
        memory.write(0x0000, 0x09);
        memory.write(0x0001, 0xAA);
        cpu.setPC(0x0000);
        runOne(); // ORA
        assertEquals(0xAA, cpu.getA());
        // EOR #$FF
        memory.write(0x0002, 0x49);
        memory.write(0x0003, 0xFF);
        cpu.setPC(0x0002);
        runOne(); // EOR
        assertEquals(0x55, cpu.getA());
        // BIT $10 (A=0x55, mem=0x80)
        memory.write(0x0010, 0x80);
        memory.write(0x0004, 0x24);
        memory.write(0x0005, 0x10);
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
    public void testTSX_TXS_CyclesAndFlags() {
        // Programa: TSX ; (alteramos X) ; TXS ; TSX novamente para confirmar
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x90); // PC = 0x9000
        int pc = 0x9000;
        memory.write(pc++, 0xBA); // TSX
        memory.write(pc++, 0x9A); // TXS
        memory.write(pc++, 0xBA); // TSX (segunda vez)
        cpu.reset();
        // Caso 1: SP=0x80 (negativo, não zero) => TSX deve colocar X=0x80, N=1, Z=0
        cpu.setSP(0x80);
        int cyc = runMeasured();
        assertEquals(2, cyc, "TSX consome 2 ciclos");
        assertEquals(0x80, cpu.getX());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isZero());
        // Modificamos X para 0x00 (isso MUDA as flags: Z=1, N=0) e executamos TXS: SP
        // vira 0x00 e flags permanecem (TXS não altera flags)
        cpu.setX(0x00); // agora Zero=1, Negative=0
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative()); // pré-condição
        cyc = runMeasured(); // TXS
        assertEquals(2, cyc, "TXS consome 2 ciclos");
        assertEquals(0x00, cpu.getSP());
        // Flags devem permanecer conforme estavam antes do TXS (Z=1, N=0)
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // Segunda TSX lê SP=0x00 => X=0x00, Z=1, N=0
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x00, cpu.getX());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
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
        while (cpu.getCycles() > 0)
            cpu.clock();
        cpu.clock(); // LDX $10,Y
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0xAB, cpu.getX());
    }

    @Test
    public void testIndirectXAddressing() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDX #$10
        memory.write(0x0000, 0xA2);
        memory.write(0x0001, 0x10);
        // LDA ($10,X) ; operand = $10, final zero-page index = ($10 + X)= $20
        memory.write(0x0002, 0xA1);
        memory.write(0x0003, 0x10);
        // Zero-page pointer at $20/$21 -> target 0x3456
        memory.write(0x0020, 0x56); // low
        memory.write(0x0021, 0x34); // high
        // Target value
        memory.write(0x3456, 0x7E);

        cpu.reset();
        // LDX
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // LDA ($10,X)
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0x7E, cpu.getA(), "LDA (zp,X) não carregou valor correto");
    }

    @Test
    public void testIndirectXZeroPageWrap() {
        // Exercita wrap de índice: operand + X ultrapassa 0xFF
        // Usa vetor de reset para 0x0200 para não sobrescrever zero-page $00/$01
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x02); // PC=0x0200
        int base = 0x0200;
        // LDX #$10
        memory.write(base + 0, 0xA2);
        memory.write(base + 1, 0x10);
        // LDA ($F0,X) -> ($F0 + $10) = $100 -> wrap = $00
        memory.write(base + 2, 0xA1);
        memory.write(base + 3, 0xF0);
        // Pointer em $00/$01 -> 0x2345
        memory.write(0x0000, 0x45); // low target
        memory.write(0x0001, 0x23); // high target
        // Valor alvo
        memory.write(0x2345, 0x9A);

        cpu.reset();
        // Executa LDX
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // Executa LDA (zp,X) com wrap
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0x9A, cpu.getA(), "LDA (zp,X) com wrap não carregou valor correto");
    }

    @Test
    public void testIndirectYAddressing() {
        // Reset em 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDY #$05
        memory.write(0x0000, 0xA0);
        memory.write(0x0001, 0x05);
        // LDA ($20),Y ; base pointer at $20/$21 -> 0x1230; +Y (5) => 0x1235
        memory.write(0x0002, 0xB1);
        memory.write(0x0003, 0x20);
        // Pointer bytes
        memory.write(0x0020, 0x30); // low
        memory.write(0x0021, 0x12); // high -> base 0x1230
        // Target value
        memory.write(0x1235, 0x4D);

        cpu.reset();
        // LDY
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // LDA (zp),Y
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0x4D, cpu.getA(), "LDA (zp),Y não carregou valor correto");
    }

    @Test
    public void testIndirectYPageCrossing() {
        // Testa crossing: base 0x12FF + Y=1 => 0x1300
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDY #$01
        memory.write(0x0000, 0xA0);
        memory.write(0x0001, 0x01);
        // LDA ($40),Y
        memory.write(0x0002, 0xB1);
        memory.write(0x0003, 0x40);
        // Pointer at $40/$41 -> 0x12FF
        memory.write(0x0040, 0xFF); // low
        memory.write(0x0041, 0x12); // high
        // Target at 0x1300
        memory.write(0x1300, 0x99);

        cpu.reset();
        // LDY
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // LDA (zp),Y (page crossing)
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0x99, cpu.getA(), "LDA (zp),Y com crossing não carregou valor correto");
    }

    @Test
    public void testAccumulatorShiftRotate() {
        // Testa instruções no acumulador: ASL A, LSR A, ROL A, ROR A
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        int pc = 0x0000;
        // LDA #$80 ; A=80
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80);
        // ASL A -> A=00, carry=1, zero=1, negative=0
        memory.write(pc++, 0x0A);
        // LDA #$01
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01);
        // LSR A -> A=00, carry=1, zero=1
        memory.write(pc++, 0x4A);
        // LDA #$81
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x81);
        // CLC (clear carry) para testar ROL sem carry inicial
        memory.write(pc++, 0x18);
        // ROL A: 0x81 <<1 + carry0 => 0x02, carry=1 (bit7), negative=0, zero=0
        memory.write(pc++, 0x2A);
        // LDA #$01
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01);
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
        // Testa o bug de hardware do 6502: se o ponteiro termina em 0xFF, o high byte
        // lê da mesma página (wrap)
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // JMP ($02FF)
        memory.write(0x0000, 0x6C);
        memory.write(0x0001, 0xFF); // low pointer
        memory.write(0x0002, 0x02); // high pointer (pointer = 0x02FF)
        // Pointer bytes: low @0x02FF, high deve ser lido de 0x0200 (wrap dentro da
        // página 0x02xx)
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
        memory.write(0x0000, 0xA9);
        memory.write(0x0001, 0xAA);
        // LDY #$20
        memory.write(0x0002, 0xA0);
        memory.write(0x0003, 0x20);
        // STA $12F0,Y (destino = 0x12F0 + 0x20 = 0x1310)
        memory.write(0x0004, 0x99); // STA abs,Y
        memory.write(0x0005, 0xF0); // low
        memory.write(0x0006, 0x12); // high

        cpu.reset();
        // LDA
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // LDY
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // STA abs,Y
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0xAA, memory.read(0x1310), "STA abs,Y não escreveu no endereço esperado");
    }

    @Test
    public void testSTAAbsoluteYPageCrossing() {
        // Reset vector -> 0x0000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x00);
        // LDA #$55
        memory.write(0x0000, 0xA9);
        memory.write(0x0001, 0x55);
        // LDY #$30
        memory.write(0x0002, 0xA0);
        memory.write(0x0003, 0x30);
        // STA $01F0,Y (0x01F0 + 0x30 = 0x0220 cruza página)
        memory.write(0x0004, 0x99); // STA abs,Y
        memory.write(0x0005, 0xF0); // low
        memory.write(0x0006, 0x01); // high

        cpu.reset();
        // LDA
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // LDY
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        // STA abs,Y (page crossing)
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();

        assertEquals(0x55, memory.read(0x0220), "STA abs,Y com crossing de página não escreveu corretamente");
    }

    @Test
    public void testASLMemoryVariants() {
        // Mesma lógica anterior, porém executando código a partir de 0x0400 para não
        // sobrescrever zero page via bytes de instrução.
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x04); // PC=0x0400
        int base = 0x0400;
        int pc = base;
        // 1) Zero page $10: 0x80 -> ASL
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x10); // STA $10
        memory.write(pc++, 0x06);
        memory.write(pc++, 0x10); // ASL $10
        // 2) Zero page $11: 0x40 -> ASL
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x40); // LDA #$40
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x11); // STA $11
        memory.write(pc++, 0x06);
        memory.write(pc++, 0x11); // ASL $11
        // 3) Zero page $12: 0x00 -> ASL
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x00); // LDA #$00
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x12); // STA $12
        memory.write(pc++, 0x06);
        memory.write(pc++, 0x12); // ASL $12
        // 4) Zero Page,X ($1E + X=2 => $20)
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x02); // LDX #$02
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x20); // STA $20
        memory.write(pc++, 0x16);
        memory.write(pc++, 0x1E); // ASL $1E,X
        // 5) Absolute $1234
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x40); // LDA #$40
        memory.write(pc++, 0x8D);
        memory.write(pc++, 0x34);
        memory.write(pc++, 0x12); // STA $1234
        memory.write(pc++, 0x0E);
        memory.write(pc++, 0x34);
        memory.write(pc++, 0x12); // ASL $1234
        // 6) Absolute,X $1300,X (X=0)
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x00); // LDX #$00
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x8D);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x13); // STA $1300
        memory.write(pc++, 0x1E);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x13); // ASL $1300,X

        cpu.reset();

        // 1)
        runOne();
        runOne();
        runOne();
        assertEquals(0x00, memory.read(0x0010));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 2)
        runOne();
        runOne();
        runOne();
        assertEquals(0x80, memory.read(0x0011));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 3)
        runOne();
        runOne();
        runOne();
        assertEquals(0x00, memory.read(0x0012));
        assertFalse(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 4)
        runOne();
        runOne();
        runOne();
        runOne();
        assertEquals(0x00, memory.read(0x0020));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        // 5)
        runOne();
        runOne();
        runOne();
        assertEquals(0x80, memory.read(0x1234));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 6)
        runOne();
        runOne();
        runOne();
        runOne();
        assertEquals(0x00, memory.read(0x1300));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    // ================== Testes mínimos antes de refactor (item 1)
    // ==================

    @Test
    public void testCLVClearsOverflow() {
        // Coloca CLV (0xB8) em 0x8000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
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
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // PC=0x8000
        memory.write(0x8000, 0xEE); // INC $1234
        memory.write(0x8001, 0x34);
        memory.write(0x8002, 0x12);
        memory.write(0x8003, 0xCE); // DEC $1234
        memory.write(0x8004, 0x34);
        memory.write(0x8005, 0x12);
        memory.write(0x1234, 0x01); // valor inicial
        cpu.reset();
        cpu.setCarry(true); // carry inicial true
        // INC
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        assertEquals(0x02, memory.read(0x1234));
        assertTrue(cpu.isCarry(), "INC não deveria alterar carry");
        // DEC
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
        assertEquals(0x01, memory.read(0x1234));
        assertTrue(cpu.isCarry(), "DEC não deveria alterar carry");
    }

    @Test
    public void testNmiHasPriorityOverIrq() {
        // Vetores NMI e IRQ
        memory.write(0xFFFA, 0x00);
        memory.write(0xFFFB, 0x90); // NMI -> 0x9000
        memory.write(0xFFFE, 0x00);
        memory.write(0xFFFF, 0xA0); // IRQ -> 0xA000
        // Reset para algum endereço válido
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // PC=0x8000
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
    public void testNmiPriorityDuringMultiCycleInstructionAndDeferredIrq() {
        // Configura vetores
        memory.write(0xFFFA, 0x00);
        memory.write(0xFFFB, 0x90); // NMI -> 0x9000
        memory.write(0xFFFE, 0x00);
        memory.write(0xFFFF, 0xA0); // IRQ -> 0xA000
        // Programa: INC $1234 (6 ciclos) seguido de NOP
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
        memory.write(0x8000, 0xEE); // INC $1234 (abs)
        memory.write(0x8001, 0x34);
        memory.write(0x8002, 0x12);
        memory.write(0x8003, 0xEA); // NOP (não deverá executar antes das interrupções)
        memory.write(0x1234, 0x01);
        cpu.reset();
        cpu.setInterruptDisable(false);
        // Inicia execução da INC (primeiro ciclo inicia instrução e carrega ciclos
        // restantes)
        cpu.clock();
        int remaining = cpu.getCycles();
        assertTrue(remaining > 0, "INC deve ser multi-ciclo");
        // Agenda ambas as interrupções enquanto a instrução ainda está em progresso
        cpu.irq();
        cpu.nmi();
        // Consome os ciclos restantes da INC
        while (cpu.getCycles() > 0)
            cpu.clock();
        // Próximo clock atinge boundary e deve pegar NMI (prioridade)
        cpu.clock();
        assertEquals(0x9000, cpu.getPC(), "Ao término da instrução, NMI deve disparar primeiro");
        assertTrue(cpu.isInterruptDisable(), "NMI deve setar interruptDisable");
        // IRQ não deve ter sido atendido ainda (I=1). Limpar I e garantir que IRQ
        // pendente executa.
        while (cpu.getCycles() > 0)
            cpu.clock(); // termina latência da sequência de NMI
        cpu.setInterruptDisable(false);
        // Próximo boundary deve agora tratar IRQ pendente
        cpu.clock();
        assertEquals(0xA000, cpu.getPC(), "IRQ pendente deve disparar após limpar I");
    }

    @Test
    public void testBRKPushesPCAndStatus() {
        // Vetor IRQ/BRK -> 0x9000
        memory.write(0xFFFE, 0x00);
        memory.write(0xFFFF, 0x90);
        // Reset vector -> 0x8000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
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
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
        int pc = 0x8000;
        // LDA #$F0 (A=F0)
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF0);
        // AND #$0F => resultado 0x00 (zero=1, negative=0) carry deve permanecer setado
        memory.write(pc++, 0x29);
        memory.write(pc++, 0x0F);
        // LDA #$80 (A=80)
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80);
        // AND #$C0 => 0x80 (negative=1, zero=0)
        memory.write(pc++, 0x29);
        memory.write(pc++, 0xC0);

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

    // ================== Testes para opcodes ilegais específicos (TOP e XAA)
    // ==================

    @Test
    public void testIllegalTOPDoesNothing() {
        // Usamos opcode 0x0C (TOP variante de NOP de 3 bytes, mas implementação atual
        // trata como NOP simples)
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
        memory.write(0x8000, 0x0C); // TOP
        memory.write(0x8001, 0x34);
        memory.write(0x8002, 0x12); // operandos que deveriam ser ignorados
        cpu.reset();
        // Snapshot flags e registradores
        int a0 = cpu.getA();
        int x0 = cpu.getX();
        int y0 = cpu.getY();
        int sp0 = cpu.getSP();
        boolean c0 = cpu.isCarry(), z0 = cpu.isZero(), n0 = cpu.isNegative(), v0 = cpu.isOverflow();
        runOne();
        // Verifica que nada mudou (de acordo com implementação atual: PC avança 1
        // apenas)
        assertEquals(a0, cpu.getA(), "TOP não deve alterar A");
        assertEquals(x0, cpu.getX(), "TOP não deve alterar X");
        assertEquals(y0, cpu.getY(), "TOP não deve alterar Y");
        assertEquals(sp0, cpu.getSP(), "TOP não deve alterar SP");
        assertEquals(c0, cpu.isCarry(), "TOP não deve alterar Carry");
        assertEquals(z0, cpu.isZero(), "TOP não deve alterar Zero");
        assertEquals(n0, cpu.isNegative(), "TOP não deve alterar Negative");
        assertEquals(v0, cpu.isOverflow(), "TOP não deve alterar Overflow");
        assertEquals(0x8001, cpu.getPC(), "Implementação atual de TOP avança apenas 1 byte");
    }

    @Test
    public void testIllegalXAAProducesMaskedA() {
        // XAA (0x8B) implementação atual: modo IMPLIED -> operand = 0 => A=(A & X) & 0
        // => 0
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0x8B); // XAA
        memory.write(0x8001, 0xFF); // byte seguinte (ignorado na implementação atual)
        cpu.reset();
        cpu.setA(0xF3); // A & X não-zero para mostrar efeito do AND final com 0
        cpu.setX(0xF7);
        runOne();
        assertEquals(0x00, cpu.getA(), "XAA atual deve zerar A por AND com 0");
        assertTrue(cpu.isZero(), "Zero flag deve ser setada após resultado 0");
        assertFalse(cpu.isNegative(), "Negative não deve ser setado em 0");
        assertEquals(0x8001, cpu.getPC(), "PC deve avançar 1 para XAA na implementação atual");
    }

    @Test
    public void testIllegalTASStoresMaskedValueAndUpdatesSP() {
        // TAS (0x9B): implementação atual:
        // sp = a & x;
        // se modo ABSOLUTE_Y: escreve (a & x & (high(addr)+1)) em memAddr
        // Configurar cenário com crossing previsível do high-byte
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
        // Colocar opcode TAS abs,Y (usamos endereço 0x1234 + Y)
        memory.write(0x8000, 0x9B); // TAS
        memory.write(0x8001, 0x34); // low
        memory.write(0x8002, 0x12); // high -> base 0x1234
        cpu.reset();
        cpu.setA(0xF0); // 1111 0000
        cpu.setX(0xCC); // 1100 1100 => A & X = 0xC0
        cpu.setY(0x10); // endereço alvo = 0x1244 (high=0x12)
        runOne();
        int targetAddr = 0x1234 + cpu.getY(); // 0x1244
        int highPlus1Mask = ((targetAddr >> 8) + 1) & 0xFF; // (0x12 +1)=0x13
        int expectedStore = (0xF0 & 0xCC) & highPlus1Mask; // 0xC0 & 0x13 = 0x00
        assertEquals(expectedStore, memory.read(targetAddr), "TAS deve armazenar (A & X & (high+1))");
        assertEquals(0xC0 & 0xFF, cpu.getSP(), "SP deve ser A & X");
        assertEquals(0x8003, cpu.getPC(), "PC deve avançar 3 bytes após TAS abs,Y");
    }

    @Test
    public void testIllegalSRE_ShiftThenEORAccumulator() {
        // SRE: LSR memória (salva), depois EOR com A
        // Usar forma Zero Page (0x47)
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
        memory.write(0x8000, 0xA9);
        memory.write(0x8001, 0x55); // LDA #$55 (0101 0101)
        memory.write(0x8002, 0xA9);
        memory.write(0x8003, 0x00); // LDA #$00 para depois setar manualmente A antes de SRE
        memory.write(0x8004, 0x47);
        memory.write(0x8005, 0x10); // SRE $10
        memory.write(0x0010, 0x8B); // 1000 1011 -> após LSR = 0100 0101 (0x45), carry=1
        cpu.reset();
        // Executa primeiro LDA #$55
        runOne();
        assertEquals(0x55, cpu.getA());
        // Segundo LDA #$00
        runOne();
        assertEquals(0x00, cpu.getA());
        // Ajusta A para valor inicial customizado (ex: 0x55) antes de SRE
        cpu.setA(0x55); // 0101 0101
        // Executa SRE $10: LSR 0x8B -> 0x45, A = 0x55 EOR 0x45 = 0x10
        runOne();
        assertEquals(0x45, memory.read(0x0010), "Memória deve conter valor shiftado");
        assertEquals(0x10, cpu.getA(), "A deve ser resultado do EOR após SRE");
        assertTrue(cpu.isCarry(), "Carry deve refletir bit0 original (1)");
        assertFalse(cpu.isZero(), "Resultado 0x10 não deve ser zero");
        assertFalse(cpu.isNegative(), "Bit7 do resultado 0x10 é 0");
    }

    @Test
    public void testIllegalAXAAbsoluteYStoresMasked() {
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x91); // PC=0x9100
        int pc = 0x9100;
        // Caso 1
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF0); // LDA
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x0F); // LDX
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x05); // LDY
        memory.write(pc++, 0x9F);
        memory.write(pc++, 0xF0);
        memory.write(pc++, 0x20); // AXA $20F0,Y -> $20F5
        // Caso 2
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x5A); // LDA
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x3C); // LDX
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x07); // LDY
        memory.write(pc++, 0x9F);
        memory.write(pc++, 0xF8);
        memory.write(pc++, 0x34); // AXA $34F8,Y -> $34FF
        cpu.reset();
        runOne();
        runOne();
        runOne();
        int cyc = runMeasured();
        assertEquals(5, cyc);
        assertEquals(0x00, memory.read(0x20F5));
        runOne();
        runOne();
        runOne();
        cyc = runMeasured();
        assertEquals(5, cyc);
        assertEquals(0x10, memory.read(0x34FF));
    }

    @Test
    public void testIllegalAXSSubtractsImmediate() {
        // AXS: X = (A & X) - operando (imediato) opcode 0xCB
        // Casos: 1) resultado zero; 2) underflow
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xA0); // PC=0xA000
        int pc = 0xA000;
        // Caso 1
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF0); // LDA #$F0
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x3C); // LDX #$3C
        memory.write(pc++, 0xCB);
        memory.write(pc++, 0x30); // AXS #$30
        // Caso 2
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x0F); // LDA #$0F
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x0F); // LDX #$0F
        memory.write(pc++, 0xCB);
        memory.write(pc++, 0x20); // AXS #$20
        cpu.reset();
        // Caso 1
        runOne();
        runOne();
        int cyc2 = runMeasured();
        assertEquals(2, cyc2);
        assertEquals(0x00, cpu.getX());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertTrue(cpu.isCarry());
        // Caso 2
        runOne();
        runOne();
        cyc2 = runMeasured();
        assertEquals(2, cyc2);
        assertEquals(0xEF, cpu.getX());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertTrue(cpu.isCarry());
    }

    @Test
    public void testIllegalSLO_ASLThenORAAccumulator() {
        // SLO: ASL memória, depois ORA com A
        // Usar zero page (0x07)
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset
        memory.write(0x8000, 0xA9);
        memory.write(0x8001, 0x12); // LDA #$12 (0001 0010)
        memory.write(0x8002, 0x07);
        memory.write(0x8003, 0x20); // SLO $20 ; mem $20 = 0x81 -> ASL => 0x02, carry=1
        memory.write(0x0020, 0x81); // 1000 0001
        cpu.reset();
        // LDA #$12
        runOne();
        assertEquals(0x12, cpu.getA());
        // Executa SLO
        runOne();
        // Após SLO: memória $20 = 0x02; A = 0x12 OR 0x02 = 0x12 (bit já set) ->
        // continua 0x12
        assertEquals(0x02, memory.read(0x0020), "SLO deve armazenar valor shiftado (ASL)");
        assertEquals(0x12, cpu.getA(), "A deve ser OR entre acumulador inicial e valor shiftado");
        assertTrue(cpu.isCarry(), "Carry deve refletir bit7 original (1)");
        assertFalse(cpu.isZero(), "Resultado 0x12 não é zero");
        assertFalse(cpu.isNegative(), "Bit7 do resultado 0x12 é 0");
    }

    @Test
    public void testLSRAccumulatorAndZeroPageMemory() {
        // Cenário:
        // - LDA #$01; LSR A => A=0x00, carry=1, zero=1, negative=0
        // - LDA #$FF; STA $30; LSR $30 => mem=0x7F, carry=1, zero=0, negative=0
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
        int pc = 0x8000;
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01); // LDA #$01
        memory.write(pc++, 0x4A); // LSR A
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xFF); // LDA #$FF
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x30); // STA $30
        memory.write(pc++, 0x46);
        memory.write(pc++, 0x30); // LSR $30
        cpu.reset();
        // LDA #$01
        runOne();
        // LSR A
        runOne();
        assertEquals(0x00, cpu.getA(), "LSR A deve resultar 0x00 a partir de 0x01");
        assertTrue(cpu.isCarry(), "Carry deve carregar bit0 original=1");
        assertTrue(cpu.isZero(), "Zero deve ser setado");
        assertFalse(cpu.isNegative(), "Negative deve ser 0");
        // LDA #$FF
        runOne();
        assertEquals(0xFF, cpu.getA());
        // STA $30
        runOne();
        assertEquals(0xFF, memory.read(0x30));
        // LSR $30
        runOne();
        assertEquals(0x7F, memory.read(0x30), "LSR $30 deve armazenar 0x7F");
        assertTrue(cpu.isCarry(), "Carry deve refletir bit0 original=1");
        assertFalse(cpu.isZero(), "Resultado 0x7F não é zero");
        assertFalse(cpu.isNegative(), "Bit7 do resultado 0x7F é 0");
    }

    @Test
    public void testCyclesBCCTakenAndPageCross() {
        // Caso 1: BCC não tomado (carry=1) => 2 ciclos
        cpu.setPC(0x4000);
        memory.write(0x4000, 0x90);
        memory.write(0x4001, 0x02); // BCC +2
        cpu.setCarry(true); // condição falha
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BCC não tomado deve consumir 2 ciclos");

        // Caso 2: BCC tomado sem crossing (carry=0) => 3 ciclos
        cpu.setPC(0x4100);
        memory.write(0x4100, 0x90);
        memory.write(0x4101, 0x02); // destino 0x4104 mesma página
        cpu.setCarry(false);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BCC tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0x4104, cpu.getPC(), "PC deve apontar para destino do branch sem crossing");

        // Caso 3: BCC tomado com crossing => 4 ciclos
        // Colocamos em 0x8200 e usamos offset negativo para ir para 0x81E2 (mudança de
        // página 0x82 -> 0x81)
        cpu.setPC(0x8200);
        memory.write(0x8200, 0x90); // BCC
        memory.write(0x8201, 0xE0); // offset -0x20
        cpu.setCarry(false);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BCC tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x81E2, cpu.getPC(), "PC deve refletir destino com crossing de página");
    }

    @Test
    public void testCyclesBCSTakenAndPageCross() {
        // Caso 1: BCS não tomado (carry=0) => 2 ciclos
        cpu.setPC(0x5000);
        memory.write(0x5000, 0xB0);
        memory.write(0x5001, 0x02); // BCS +2
        cpu.setCarry(false); // condição falha
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BCS não tomado deve consumir 2 ciclos");

        // Caso 2: BCS tomado sem crossing (carry=1) => 3 ciclos
        cpu.setPC(0x5100);
        memory.write(0x5100, 0xB0);
        memory.write(0x5101, 0x02); // destino 0x5104 mesma página
        cpu.setCarry(true);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BCS tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0x5104, cpu.getPC(), "PC deve apontar para destino do BCS sem crossing");

        // Caso 3: BCS tomado com crossing => 4 ciclos
        cpu.setPC(0x8500);
        memory.write(0x8500, 0xB0); // BCS
        memory.write(0x8501, 0xF0); // offset -0x10: destino = 0x8502 - 0x10 = 0x84F2 (mudança de página 0x85 ->
                                    // 0x84)
        cpu.setCarry(true);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BCS tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x84F2, cpu.getPC(), "PC deve refletir destino de BCS com crossing de página");
    }

    @Test
    public void testCyclesBEQTakenAndPageCross() {
        // Caso 1: BEQ não tomado (Z=0) => 2 ciclos
        cpu.setPC(0x6000);
        memory.write(0x6000, 0xF0);
        memory.write(0x6001, 0x02); // BEQ +2
        cpu.setZero(false); // condição falha (Z=0)
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BEQ não tomado deve consumir 2 ciclos");

        // Caso 2: BEQ tomado sem crossing (Z=1) => 3 ciclos
        cpu.setPC(0x6100);
        memory.write(0x6100, 0xF0);
        memory.write(0x6101, 0x02); // destino 0x6104
        cpu.setZero(true);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BEQ tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0x6104, cpu.getPC(), "PC deve apontar para destino do BEQ sem crossing");

        // Caso 3: BEQ tomado com crossing (Z=1) => 4 ciclos
        cpu.setPC(0x8600);
        memory.write(0x8600, 0xF0); // BEQ
        memory.write(0x8601, 0xE0); // offset -0x20 => destino = 0x8602 - 0x20 = 0x85E2 (cross page)
        cpu.setZero(true);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BEQ tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x85E2, cpu.getPC(), "PC deve refletir destino de BEQ com crossing");
    }

    @Test
    public void testCyclesBVCTakenAndPageCross() {
        // Caso 1: BVC não tomado (V=1) => 2 ciclos
        cpu.setPC(0xA000);
        memory.write(0xA000, 0x50);
        memory.write(0xA001, 0x02); // BVC +2
        cpu.setOverflow(true); // condição falha
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BVC não tomado deve consumir 2 ciclos");

        // Caso 2: BVC tomado sem crossing (V=0) => 3 ciclos
        cpu.setPC(0xA100);
        memory.write(0xA100, 0x50);
        memory.write(0xA101, 0x02); // destino 0xA104 mesma página
        cpu.setOverflow(false);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BVC tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0xA104, cpu.getPC(), "PC deve apontar para destino do BVC sem crossing");

        // Caso 3: BVC tomado com crossing => 4 ciclos
        cpu.setPC(0x8A00);
        memory.write(0x8A00, 0x50); // BVC
        memory.write(0x8A01, 0xE0); // offset -0x20 => destino = 0x8A02 - 0x20 = 0x89E2 (cross page)
        cpu.setOverflow(false);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BVC tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x89E2, cpu.getPC(), "PC deve refletir destino de BVC com crossing");
    }

    @Test
    public void testCyclesBVSTakenAndPageCross() {
        // Caso 1: BVS não tomado (V=0) => 2 ciclos
        cpu.setPC(0xB000);
        memory.write(0xB000, 0x70);
        memory.write(0xB001, 0x02); // BVS +2
        cpu.setOverflow(false); // condição falha
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BVS não tomado deve consumir 2 ciclos");

        // Caso 2: BVS tomado sem crossing (V=1) => 3 ciclos
        cpu.setPC(0xB100);
        memory.write(0xB100, 0x70);
        memory.write(0xB101, 0x02); // destino 0xB104
        cpu.setOverflow(true);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BVS tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0xB104, cpu.getPC(), "PC deve apontar para destino do BVS sem crossing");

        // Caso 3: BVS tomado com crossing => 4 ciclos
        cpu.setPC(0x8B00);
        memory.write(0x8B00, 0x70); // BVS
        memory.write(0x8B01, 0xF0); // offset -0x10 => destino = 0x8B02 - 0x10 = 0x8AF2 (cross page)
        cpu.setOverflow(true);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BVS tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x8AF2, cpu.getPC(), "PC deve refletir destino de BVS com crossing");
    }

    // ================== Testes de ciclos ==================

    @Test
    public void testCyclesLDAImmediate() {
        // Reset em 0x8000
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80);
        memory.write(0x8000, 0xA9);
        memory.write(0x8001, 0x42); // LDA #$42
        cpu.reset();
        int cycles = runMeasured();
        assertEquals(2, cycles, "LDA imediato deve consumir 2 ciclos");
    }

    @Test
    public void testCyclesLDAAbsoluteX_PageCrossPenalty() {
        // Sem crossing
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
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
        memory.write(0x2000, 0xD0);
        memory.write(0x2001, 0x02); // BNE +2
        cpu.setZero(true); // condição não satisfeita
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "Branch não tomado deve consumir 2 ciclos");

        // Caso 2: tomado sem crossing (Z=0)
        cpu.setPC(0x2100);
        memory.write(0x2100, 0xD0);
        memory.write(0x2101, 0x02); // destino 0x2104 mesma página
        cpu.setZero(false);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "Branch tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0x2104, cpu.getPC(), "PC deve apontar para destino do branch sem crossing");

        // Caso 3: tomado com crossing
        // Colocar instrução no início da página 0x8100 para que PC após fetch fique em
        // 0x8102
        // e usar offset negativo para ir para página anterior 0x80xx
        cpu.setPC(0x8100);
        memory.write(0x8100, 0xD0); // BNE
        memory.write(0x8101, 0xE0); // offset = -0x20 (0xE0) -> destino = 0x8102 + (-0x20) = 0x80E2 (página
                                    // diferente)
        cpu.setZero(false);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "Branch tomado com crossing deve consumir 4 ciclos");
    }

    @Test
    public void testCyclesBMITakenAndPageCross() {
        // Caso 1: BMI não tomado (N=0) => 2 ciclos
        cpu.setPC(0x7000);
        memory.write(0x7000, 0x30);
        memory.write(0x7001, 0x02); // BMI +2
        cpu.setNegative(false); // condição falha
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BMI não tomado deve consumir 2 ciclos");

        // Caso 2: BMI tomado sem crossing (N=1) => 3 ciclos
        cpu.setPC(0x7100);
        memory.write(0x7100, 0x30);
        memory.write(0x7101, 0x02); // destino 0x7104 mesma página
        cpu.setNegative(true);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BMI tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0x7104, cpu.getPC(), "PC deve apontar para destino do BMI sem crossing");

        // Caso 3: BMI tomado com crossing => 4 ciclos
        cpu.setPC(0x8700);
        memory.write(0x8700, 0x30); // BMI
        memory.write(0x8701, 0xE0); // offset -0x20 => destino = 0x8702 - 0x20 = 0x86E2 (cross page)
        cpu.setNegative(true);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BMI tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x86E2, cpu.getPC(), "PC deve refletir destino de BMI com crossing");
    }

    @Test
    public void testCyclesBPLTakenAndPageCross() {
        // Caso 1: BPL não tomado (N=1) => 2 ciclos
        cpu.setPC(0x9000);
        memory.write(0x9000, 0x10);
        memory.write(0x9001, 0x02); // BPL +2
        cpu.setNegative(true); // condição falha
        int notTaken = runMeasured();
        assertEquals(2, notTaken, "BPL não tomado deve consumir 2 ciclos");

        // Caso 2: BPL tomado sem crossing (N=0) => 3 ciclos
        cpu.setPC(0x9100);
        memory.write(0x9100, 0x10);
        memory.write(0x9101, 0x02); // destino 0x9104 mesma página
        cpu.setNegative(false);
        int takenNoCross = runMeasured();
        assertEquals(3, takenNoCross, "BPL tomado sem crossing deve consumir 3 ciclos");
        assertEquals(0x9104, cpu.getPC(), "PC deve apontar para destino do BPL sem crossing");

        // Caso 3: BPL tomado com crossing => 4 ciclos
        cpu.setPC(0x8800);
        memory.write(0x8800, 0x10); // BPL
        memory.write(0x8801, 0xF0); // offset -0x10 => destino = 0x8802 - 0x10 = 0x87F2 (cross page 0x88 -> 0x87)
        cpu.setNegative(false);
        int takenCross = runMeasured();
        assertEquals(4, takenCross, "BPL tomado com crossing deve consumir 4 ciclos");
        assertEquals(0x87F2, cpu.getPC(), "PC deve refletir destino de BPL com crossing");
    }

    @Test
    public void testCLDAndCLIFlagClearingAndCycles() {
        // Programa: SED; SEI; CLD; CLI
        // Preparação vetor reset
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x90); // start 0x9000
        int pc = 0x9000;
        memory.write(pc++, 0xF8); // SED - set decimal
        memory.write(pc++, 0x78); // SEI - set interrupt disable
        memory.write(pc++, 0xD8); // CLD - clear decimal
        memory.write(pc++, 0x58); // CLI - clear interrupt disable
        cpu.reset();

        // Antes: flags devem estar limpos por reset
        assertFalse(cpu.isDecimal(), "Reset deve iniciar decimal=0");
        assertFalse(cpu.isInterruptDisable(), "Reset deve iniciar I=0");

        // SED
        int cyclesSED = runMeasured();
        assertTrue(cpu.isDecimal(), "SED deve setar decimal");
        assertEquals(2, cyclesSED, "SED consome 2 ciclos");
        // SEI
        int cyclesSEI = runMeasured();
        assertTrue(cpu.isInterruptDisable(), "SEI deve setar I");
        assertEquals(2, cyclesSEI, "SEI consome 2 ciclos");
        // CLD
        int cyclesCLD = runMeasured();
        assertFalse(cpu.isDecimal(), "CLD deve limpar decimal");
        assertEquals(2, cyclesCLD, "CLD consome 2 ciclos");
        // CLI
        int cyclesCLI = runMeasured();
        assertFalse(cpu.isInterruptDisable(), "CLI deve limpar I");
        assertEquals(2, cyclesCLI, "CLI consome 2 ciclos");
    }

    @Test
    public void testCMP_CPX_CPY_FlagBehaviorAndCycles() {
        // Programa:
        // LDA #$40 ; CMP #$40 (igual)
        // CMP #$41 (A < op)
        // CMP #$3F (A > op)
        // STA $20 ; CMP $20 (igual via zp)
        // LDX #$10 ; CPX #$10 ; CPX #$11 ; CPX #$0F
        // STX $21 ; CPX $21
        // LDY #$80 ; CPY #$80 ; CPY #$81 ; CPY #$7F
        // STY $22 ; CPY $22
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xA0); // start 0xA000
        int pc = 0xA000;
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x40); // LDA #$40
        memory.write(pc++, 0xC9);
        memory.write(pc++, 0x40); // CMP #$40 (igual)
        memory.write(pc++, 0xC9);
        memory.write(pc++, 0x41); // CMP #$41 (A < op)
        memory.write(pc++, 0xC9);
        memory.write(pc++, 0x3F); // CMP #$3F (A > op)
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x20); // STA $20
        memory.write(pc++, 0xC5);
        memory.write(pc++, 0x20); // CMP $20 (igual)
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x10); // LDX #$10
        memory.write(pc++, 0xE0);
        memory.write(pc++, 0x10); // CPX #$10 (igual)
        memory.write(pc++, 0xE0);
        memory.write(pc++, 0x11); // CPX #$11 (X < op)
        memory.write(pc++, 0xE0);
        memory.write(pc++, 0x0F); // CPX #$0F (X > op)
        memory.write(pc++, 0x86);
        memory.write(pc++, 0x21); // STX $21
        memory.write(pc++, 0xE4);
        memory.write(pc++, 0x21); // CPX $21 (igual)
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x80); // LDY #$80
        memory.write(pc++, 0xC0);
        memory.write(pc++, 0x80); // CPY #$80 (igual)
        memory.write(pc++, 0xC0);
        memory.write(pc++, 0x81); // CPY #$81 (Y < op)
        memory.write(pc++, 0xC0);
        memory.write(pc++, 0x7F); // CPY #$7F (Y > op)
        memory.write(pc++, 0x84);
        memory.write(pc++, 0x22); // STY $22
        memory.write(pc++, 0xC4);
        memory.write(pc++, 0x22); // CPY $22 (igual)
        cpu.reset();

        // LDA #$40
        runOne();
        // CMP #$40 (igual) => Carry=1, Zero=1, Negative= (0x40-0x40)=0 => N=0
        int cyc = runMeasured();
        assertEquals(2, cyc, "CMP # imediato consome 2 ciclos");
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertEquals(0x40, cpu.getA(), "CMP não altera A");
        // CMP #$41 (A < op) => resultado negativo => Carry=0, Zero=0, N=1
        // (0x40-0x41=0xFF)
        runOne();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // CMP #$3F (A > op) => Carry=1, Zero=0, N=0
        runOne();
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // STA $20
        runOne();
        assertEquals(0x40, memory.read(0x20));
        // CMP $20 (igual) => 3 ciclos zp read
        int cycCmpZp = runMeasured();
        assertEquals(3, cycCmpZp, "CMP zp consome 3 ciclos");
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());

        // LDX #$10
        runOne();
        assertEquals(0x10, cpu.getX());
        // CPX #$10 igual
        int cycCpxEq = runMeasured();
        assertEquals(2, cycCpxEq);
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // CPX #$11 X < op => Carry=0, N=1
        runOne();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // CPX #$0F X > op => Carry=1, N=0
        runOne();
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // STX $21
        runOne();
        assertEquals(0x10, memory.read(0x21));
        // CPX $21 igual => 3 ciclos
        int cycCpxZp = runMeasured();
        assertEquals(3, cycCpxZp);
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());

        // LDY #$80
        runOne();
        assertEquals(0x80, cpu.getY());
        // CPY #$80 igual
        int cycCpyEq = runMeasured();
        assertEquals(2, cycCpyEq);
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // CPY #$81 Y < op => Carry=0, N=1
        runOne();
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // CPY #$7F Y > op => Carry=1, N=0
        runOne();
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // STY $22
        runOne();
        assertEquals(0x80, memory.read(0x22));
        // CPY $22 igual => 3 ciclos
        int cycCpyZp = runMeasured();
        assertEquals(3, cycCpyZp);
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void testPHP_PLA_PLP_StatusAndStackCycles() {
        // Programa em 0xA200: SED ; SEI ; LDA #$3C ; PHP ; LDA #$00 ; PLA ; CLV? (não
        // altera V) ; PLP
        // Vamos: setar algumas flags, empurrar status, modificar A, recuperar A via PLA
        // (que deve ser status pushed? Atenção: PLA retorna dado empilhado por PHA, não
        // por PHP) então precisamos empilhar um valor com PHA também para validar PLA.
        // Ajuste:
        // Novo programa: LDA #$3C ; PHA ; SED ; SEI ; PHP ; LDA #$00 ; PLA ; LDA #$00 ;
        // PLP
        // Objetivos:
        // 1) PHA armazena 0x3C na pilha
        // 2) PHP armazena status com bits B e U forçados
        // 3) PLA recupera 0x3C
        // 4) PLP restaura flags originais (exceto B e U comportamento pós-PLP: U=1, B
        // conforme empilhado)
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xA2); // reset -> 0xA200
        int pc = 0xA200;
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x3C); // LDA #$3C
        memory.write(pc++, 0x48); // PHA
        memory.write(pc++, 0xF8); // SED
        memory.write(pc++, 0x78); // SEI
        memory.write(pc++, 0x08); // PHP
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x00); // LDA #$00 (limpa N/Z via setZeroAndNegative)
        memory.write(pc++, 0x68); // PLA -> recupera 0x3C
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x00); // LDA #$00 novamente
        memory.write(pc++, 0x28); // PLP
        cpu.reset();
        int initialSP = cpu.getSP();

        // LDA #$3C
        int cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x3C, cpu.getA());
        // PHA
        cyc = runMeasured();
        assertEquals(3, cyc, "PHA deve consumir 3 ciclos");
        assertEquals((initialSP - 1) & 0xFF, cpu.getSP());
        int spAfterPHA = cpu.getSP();
        // SED (decimal=1)
        runMeasured();
        assertTrue(cpu.isDecimal());
        // SEI (I=1)
        runMeasured();
        assertTrue(cpu.isInterruptDisable());
        // PHP push status (com B|U forçados). SP deve decrementar.
        int spBeforePHP = cpu.getSP();
        cyc = runMeasured();
        assertEquals(3, cyc, "PHP deve consumir 3 ciclos");
        assertEquals((spBeforePHP - 1) & 0xFF, cpu.getSP());
        int spAfterPHP = cpu.getSP();
        int pushedStatus = memory.read(0x0100 | ((spAfterPHP + 1) & 0xFF));
        assertTrue((pushedStatus & 0x10) != 0, "Flag B deve estar setada no byte empilhado por PHP");
        assertTrue((pushedStatus & 0x20) != 0, "Flag U (unused) deve estar setada no byte empilhado por PHP");
        // LDA #$00
        runMeasured();
        assertEquals(0x00, cpu.getA());
        // PLA -> recupera 0x3C
        cyc = runMeasured();
        assertEquals(4, cyc, "PLA deve consumir 4 ciclos");
        assertEquals(0x3C, cpu.getA());
        assertEquals(spAfterPHA, cpu.getSP(), "Após PLA SP volta ao valor pós-PHA");
        // LDA #$00 de novo
        runMeasured();
        assertEquals(0x00, cpu.getA());
        // PLP -> restaura flags que estavam no status empilhado (decimal e
        // interruptDisable devem voltar a 1)
        cyc = runMeasured();
        assertEquals(4, cyc, "PLP deve consumir 4 ciclos");
        assertTrue(cpu.isDecimal(), "Decimal deve ser restaurado");
        assertTrue(cpu.isInterruptDisable(), "Interrupt disable deve ser restaurado");
    }

    @Test
    public void testROLVariantsAccumulatorAndMemory() {
        // Programa completo para validar todos os modos/combinações principais de ROL:
        // 1) ROL A com bit7=1 e carry=0
        // 2) ROL A com bit7=0 e carry=1 (inserindo 1 em bit0)
        // 3) ROL $30 (zero page) valor 0x40
        // 4) ROL $31 (zero page) valor 0x80
        // 5) ROL $32 (zero page) valor 0x01 com carry=1
        // 6) ROL $20,X (zero page,X) onde X=5 e memória $25 = 0x80
        // 7) ROL $1234 (absolute) valor 0x40
        // 8) ROL $1235 (absolute) valor 0x80 carry=1
        // 9) ROL $2000,X (absolute,X) X=0x10 valor em $2010 = 0x81
        // Ciclos esperados: A=2, zp=5, zp,X=6, abs=6, abs,X=7

        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x80); // reset -> 0x8000
        int pc = 0x8000;
        // 1) LDA #$80 ; CLC ; ROL A
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x2A); // ROL A
        // 2) LDA #$01 ; SEC ; ROL A
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01); // LDA #$01
        memory.write(pc++, 0x38); // SEC
        memory.write(pc++, 0x2A); // ROL A => (1<<1)|1 = 3
        // 3) LDA #$40 ; STA $30 ; CLC ; ROL $30
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x40);
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x30);
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x26);
        memory.write(pc++, 0x30); // ROL $30
        // 4) LDA #$80 ; STA $31 ; CLC ; ROL $31
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80);
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x31);
        memory.write(pc++, 0x18);
        memory.write(pc++, 0x26);
        memory.write(pc++, 0x31); // ROL $31
        // 5) LDA #$01 ; STA $32 ; SEC ; ROL $32 (usa carry=1 para inserir 1)
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01);
        memory.write(pc++, 0x85);
        memory.write(pc++, 0x32);
        memory.write(pc++, 0x38); // SEC
        memory.write(pc++, 0x26);
        memory.write(pc++, 0x32);
        // 6) LDX #$05 ; LDA #$80 ; STA $20,X (-> $25) ; CLC ; ROL $20,X
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x05); // LDX #$05
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x95);
        memory.write(pc++, 0x20); // STA $20,X -> $25
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x36);
        memory.write(pc++, 0x20); // ROL $20,X
        // 7) LDA #$40 ; STA $1234 ; CLC ; ROL $1234
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x40);
        memory.write(pc++, 0x8D);
        memory.write(pc++, 0x34);
        memory.write(pc++, 0x12); // STA $1234
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x2E);
        memory.write(pc++, 0x34);
        memory.write(pc++, 0x12); // ROL $1234
        // 8) LDA #$80 ; STA $1235 ; SEC ; ROL $1235
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80);
        memory.write(pc++, 0x8D);
        memory.write(pc++, 0x35);
        memory.write(pc++, 0x12); // STA $1235
        memory.write(pc++, 0x38); // SEC
        memory.write(pc++, 0x2E);
        memory.write(pc++, 0x35);
        memory.write(pc++, 0x12); // ROL $1235
        // 9) LDX #$10 ; LDA #$81 ; STA $2000,X (-> $2010) ; CLC ; ROL $2000,X
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x10); // LDX #$10
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x81); // LDA #$81
        memory.write(pc++, 0x9D);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x20); // STA $2000,X
        memory.write(pc++, 0x18); // CLC
        memory.write(pc++, 0x3E);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x20); // ROL $2000,X

        cpu.reset();

        int cyc;
        // 1) ROL A (A=0x80, CLC)
        runOne(); // LDA #$80
        runOne(); // CLC
        cyc = runMeasured();
        assertEquals(2, cyc, "ROL A consome 2 ciclos");
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 2) ROL A (A=0x01, SEC)
        runOne(); // LDA #$01
        runOne(); // SEC
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x03, cpu.getA());
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 3) ROL $30 (0x40, CLC)
        runOne(); // LDA #$40
        runOne(); // STA $30
        runOne(); // CLC
        cyc = runMeasured();
        assertEquals(5, cyc);
        assertEquals(0x80, memory.read(0x30));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 4) ROL $31 (0x80, CLC)
        runOne(); // LDA #$80
        runOne(); // STA $31
        runOne(); // CLC
        cyc = runMeasured();
        assertEquals(5, cyc);
        assertEquals(0x00, memory.read(0x31));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 5) ROL $32 (0x01, SEC)
        runOne(); // LDA #$01
        runOne(); // STA $32
        runOne(); // SEC
        cyc = runMeasured();
        assertEquals(5, cyc);
        assertEquals(0x03, memory.read(0x32));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 6) ROL $20,X (mem $25=0x80, CLC)
        runOne(); // LDX #$05
        runOne(); // LDA #$80
        runOne(); // STA $20,X -> $25
        runOne(); // CLC
        cyc = runMeasured();
        assertEquals(6, cyc);
        assertEquals(0x00, memory.read(0x25));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 7) ROL $1234 (0x40, CLC)
        runOne(); // LDA #$40
        runOne(); // STA $1234
        runOne(); // CLC
        cyc = runMeasured();
        assertEquals(6, cyc);
        assertEquals(0x80, memory.read(0x1234));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        // 8) ROL $1235 (0x80, SEC)
        runOne(); // LDA #$80
        runOne(); // STA $1235
        runOne(); // SEC
        cyc = runMeasured();
        assertEquals(6, cyc);
        assertEquals(0x01, memory.read(0x1235)); // (0x80<<1)|1 => 0x01
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // 9) ROL $2000,X (0x81, CLC)
        runOne(); // LDX #$10
        runOne(); // LDA #$81
        runOne(); // STA $2000,X -> $2010 (valor 0x81)
        runOne(); // CLC
        cyc = runMeasured();
        assertEquals(7, cyc);
        assertEquals(0x02, memory.read(0x2010)); // (0x81<<1)&0xFF = 0x02
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    // ================== Testes para opcodes ilegais solicitados (AAC, AAX, AHX,
    // ALR) ==================

    @Test
    public void testIllegalAAC_ANC_ImmediateVariants() {
        // Usamos as duas variantes de ANC/AAC: 0x0B e 0x2B (ambas imediatas)
        // Programa em 0xC000: LDA #$F0 ; AAC #$0F -> A=0xF0 & 0x0F = 0x00 (Z=1,N=0,C=0)
        // ; LDA #$80 ; AAC #$FF -> A=0x80 (N=1,C=1)
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xC0); // PC=0xC000
        int pc = 0xC000;
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF0); // LDA #$F0
        memory.write(pc++, 0x0B);
        memory.write(pc++, 0x0F); // AAC #$0F (opcode 0x0B)
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x2B);
        memory.write(pc++, 0xFF); // AAC #$FF (opcode 0x2B)
        cpu.reset();
        // LDA #$F0
        int cyc = runMeasured();
        assertEquals(2, cyc);
        // AAC #$0F -> resultado 0x00 => Z=1, N=0, Carry=0
        cyc = runMeasured();
        assertEquals(2, cyc, "AAC imediato consome 2 ciclos");
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        assertFalse(cpu.isCarry());
        // LDA #$80
        cyc = runMeasured();
        assertEquals(2, cyc);
        // AAC #$FF -> 0x80 & 0xFF = 0x80 => N=1, C=1 (bit7 set)
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x80, cpu.getA());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
        assertTrue(cpu.isCarry());
    }

    @Test
    public void testIllegalAAX_SAX_StoresMask_NoFlagChange() {
        // Testa todas as variantes conhecidas de AAX (SAX): 0x87 (zp), 0x97 (zp,Y),
        // 0x83 (indirect,X), 0x8F (abs)
        // Comportamento: escreve (A & X) em memória, não altera flags.
        // Ciclos esperados (análogos ao STA/SAX): zp=3, zp,Y=4, (zp,X)=6, abs=4
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xC1); // PC=0xC100
        int pc = 0xC100;
        // 1) AAX zp $10: A=F0, X=CC => C0
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF0); // LDA #$F0
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0xCC); // LDX #$CC
        memory.write(pc++, 0x87);
        memory.write(pc++, 0x10); // AAX $10
        // 2) AAX zp,Y base $20,Y=5 => destino $25 : A=3F, X=0F => 0x0F
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x3F); // LDA #$3F
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x0F); // LDX #$0F
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x05); // LDY #$05
        memory.write(pc++, 0x97);
        memory.write(pc++, 0x20); // AAX $20,Y -> $25
        // 3) AAX ($10,X) com X=0x34 -> pointer em ($10+X)=0x44 -> 0x2468 ; A=F3, X=34
        // => 0x30
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF3); // LDA #$F3
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x34); // LDX #$34
        memory.write(pc++, 0x83);
        memory.write(pc++, 0x10); // AAX ($10,X)
        // 4) AAX abs $3000 : A=5A, X=3C => 0x18
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x5A); // LDA #$5A
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x3C); // LDX #$3C
        memory.write(pc++, 0x8F);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x30); // AAX $3000

        // Preparar ponteiro para caso indireto: endereço alvo 0x2468 em zero page
        // 0x44/0x45
        memory.write(0x0044, 0x68); // low
        memory.write(0x0045, 0x24); // high

        cpu.reset();

        // ---- 1) AAX zp ----
        runOne(); // LDA
        runOne(); // LDX
        boolean c = cpu.isCarry(), z = cpu.isZero(), n = cpu.isNegative(), v = cpu.isOverflow();
        int cyc = runMeasured();
        assertEquals(3, cyc, "AAX zp deve consumir 3 ciclos");
        assertEquals(0xC0, memory.read(0x0010));
        assertEquals(c, cpu.isCarry());
        assertEquals(z, cpu.isZero());
        assertEquals(n, cpu.isNegative());
        assertEquals(v, cpu.isOverflow());

        // ---- 2) AAX zp,Y ----
        runOne(); // LDA #$3F
        runOne(); // LDX #$0F
        runOne(); // LDY #$05
        c = cpu.isCarry();
        z = cpu.isZero();
        n = cpu.isNegative();
        v = cpu.isOverflow();
        cyc = runMeasured();
        assertEquals(4, cyc, "AAX zp,Y deve consumir 4 ciclos");
        assertEquals(0x0F, memory.read(0x0025));
        assertEquals(c, cpu.isCarry());
        assertEquals(z, cpu.isZero());
        assertEquals(n, cpu.isNegative());
        assertEquals(v, cpu.isOverflow());

        // ---- 3) AAX (zp,X) ----
        runOne(); // LDA #$F3
        runOne(); // LDX #$34
        c = cpu.isCarry();
        z = cpu.isZero();
        n = cpu.isNegative();
        v = cpu.isOverflow();
        cyc = runMeasured();
        assertEquals(6, cyc, "AAX (zp,X) deve consumir 6 ciclos");
        assertEquals(0x30, memory.read(0x2468));
        assertEquals(c, cpu.isCarry());
        assertEquals(z, cpu.isZero());
        assertEquals(n, cpu.isNegative());
        assertEquals(v, cpu.isOverflow());

        // ---- 4) AAX abs ----
        runOne(); // LDA #$5A
        runOne(); // LDX #$3C
        c = cpu.isCarry();
        z = cpu.isZero();
        n = cpu.isNegative();
        v = cpu.isOverflow();
        cyc = runMeasured();
        assertEquals(4, cyc, "AAX abs deve consumir 4 ciclos");
        assertEquals(0x18, memory.read(0x3000));
        assertEquals(c, cpu.isCarry());
        assertEquals(z, cpu.isZero());
        assertEquals(n, cpu.isNegative());
        assertEquals(v, cpu.isOverflow());
    }

    @Test
    public void testIllegalAHX_AbsoluteYAndIndirectY() {
        // AHX armazena (A & X & (high(dest)+1)) em memória para modos abs,Y e (zp),Y
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xE0); // PC=0xE000
        int pc = 0xE000;
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x5A); // LDA #$5A (0101 1010)
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x3C); // LDX #$3C (0011 1100) => A&X=0x18
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x10); // LDY #$10
        // AHX $12F0,Y -> destino = 0x1300 (0x12F0 + 0x10), high=0x13 => (A&X)&0x13 =
        // 0x18 & 0x13 = 0x10
        memory.write(pc++, 0x9F);
        memory.write(pc++, 0xF0);
        memory.write(pc++, 0x12);
        // Preparar ponteiro ($40) -> base 0x2200; Y=0x10 => destino 0x2210, high=0x22
        // => mask=0x23
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x10); // LDY #$10 (de novo para clareza)
        memory.write(0x0040, 0x00);
        memory.write(0x0041, 0x22); // pointer base 0x2200
        // AHX ($40),Y opcode 0x93
        memory.write(pc++, 0x93);
        memory.write(pc++, 0x40);
        cpu.reset();
        // LDA
        runOne();
        // LDX
        runOne();
        // LDY #$10
        runOne();
        int cyc = runMeasured(); // AHX abs,Y
        assertEquals(5, cyc, "AHX abs,Y deve consumir 5 ciclos (como STA abs,Y)");
        int dest1 = 0x12F0 + 0x10; // 0x1300
        assertEquals(0x10, memory.read(dest1), "AHX abs,Y armazenou valor incorreto");
        // LDY #$10 novamente
        runOne();
        cyc = runMeasured(); // AHX (zp),Y
        assertEquals(6, cyc, "AHX (zp),Y deve consumir 6 ciclos (como STA (zp),Y)");
        int dest2 = 0x2200 + 0x10; // 0x2210 high=0x22 -> mask high+1=0x23
        int expected2 = (0x5A & 0x3C) & 0x23; // (A & X) & (high+1)
        assertEquals(expected2, memory.read(dest2), "AHX (zp),Y armazenou valor incorreto");
        // Flags não alteradas (store)
    }

    @Test
    public void testIllegalALR_AndThenLsrImmediate() {
        // ALR (opcode 0x4B): A = (A & imm) >>1 ; Carry = bit0 do AND antes do shift
        // Programa: LDA #$FF ; ALR #$03 -> (0xFF & 0x03)=0x03 -> carry=1 -> shift =>
        // 0x01 (Z=0,N=0)
        // LDA #$80 ; ALR #$80 -> (0x80 & 0x80)=0x80 -> carry=0 -> shift => 0x40
        // (N=0,Z=0,C=0)
        // LDA #$01 ; ALR #$01 -> (0x01 & 0x01)=0x01 -> carry=1 -> shift => 0x00
        // (Z=1,N=0,C=1)
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xF0); // PC=0xF000
        int pc = 0xF000;
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xFF); // LDA #$FF
        memory.write(pc++, 0x4B);
        memory.write(pc++, 0x03); // ALR #$03
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x80); // LDA #$80
        memory.write(pc++, 0x4B);
        memory.write(pc++, 0x80); // ALR #$80
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01); // LDA #$01
        memory.write(pc++, 0x4B);
        memory.write(pc++, 0x01); // ALR #$01
        cpu.reset();
        // LDA #$FF
        runOne();
        // ALR #$03
        int cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x01, cpu.getA());
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // LDA #$80
        runOne();
        // ALR #$80 -> (0x80 & 0x80)=0x80 carry=0 -> >>1 =0x40
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x40, cpu.getA());
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // LDA #$01
        runOne();
        // ALR #$01 -> (0x01 & 0x01)=0x01 carry=1 -> >>1=0x00 Z=1
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
    }

    @Test
    public void testIllegalATX_Immediate() {
        // ATX (mapeado aqui no opcode 0x02) semântica adotada: A = A & X; em seguida A
        // = X = A & operando.
        // Isso equivale a A_final = X_final = (A_inicial & X_inicial & imediato).
        // Cenários calculados:
        // 1) A=0xF0, X=0xCC, imm=0x3C => (F0 & CC)=C0; C0 & 3C = 0x00 => resultado
        // final 0x00
        // 2) A=0x55, X=0x0F, imm=0x01 => (55 & 0F)=05; 05 & 01 = 0x01 => resultado
        // final 0x01
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0xF1); // PC=0xF100
        int pc = 0xF100;
        // Caso 1
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0xF0); // LDA #$F0
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0xCC); // LDX #$CC
        memory.write(pc++, 0x02);
        memory.write(pc++, 0x3C); // ATX #$3C
        // Caso 2
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x55); // LDA #$55
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x0F); // LDX #$0F
        memory.write(pc++, 0x02);
        memory.write(pc++, 0x01); // ATX #$01
        cpu.reset();
        // Caso 1
        runOne(); // LDA
        runOne(); // LDX
        int cyc = runMeasured();
        assertEquals(2, cyc, "ATX imediato deve consumir 2 ciclos (como imediato padrão)");
        assertEquals(0x00, cpu.getA(), "ATX caso1 A incorreto (esperado 0x00)");
        assertEquals(0x00, cpu.getX(), "ATX caso1 X incorreto (esperado 0x00)");
        // Caso 2
        runOne(); // LDA
        runOne(); // LDX
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x01, cpu.getA(), "ATX caso2 A incorreto");
        assertEquals(0x01, cpu.getX(), "ATX caso2 X incorreto");
    }

    @Test
    public void testIllegalASR_AliasBehaviorImmediate() {
        // ASR é um alias comum usado em documentação para o mesmo opcode 0x4B (também
        // chamado ALR): A = (A & imm) >> 1
        // Aqui repetimos cenários diferentes do teste de ALR para cobrir explicitamente
        // o nome ASR.
        // Casos:
        // 1) LDA #$FF ; ASR #$01 -> (0xFF & 0x01)=0x01 -> C=1 -> >>1 => 0x00 (Z=1, N=0,
        // C=1)
        // 2) LDA #$AA ; ASR #$0F -> (0xAA & 0x0F)=0x0A -> C=0 -> >>1 => 0x05 (Z=0, N=0,
        // C=0)
        // 3) LDA #$03 ; ASR #$03 -> (0x03 & 0x03)=0x03 -> C=1 -> >>1 => 0x01 (Z=0, N=0,
        // C=1)
        memory.write(0xFFFC, 0x20);
        memory.write(0xFFFD, 0xF0); // PC=0xF020 para separar dos outros testes
        int pc2 = 0xF020;
        // Caso 1
        memory.write(pc2++, 0xA9);
        memory.write(pc2++, 0xFF); // LDA #$FF
        memory.write(pc2++, 0x4B);
        memory.write(pc2++, 0x01); // ASR #$01 (opcode 0x4B)
        // Caso 2
        memory.write(pc2++, 0xA9);
        memory.write(pc2++, 0xAA); // LDA #$AA
        memory.write(pc2++, 0x4B);
        memory.write(pc2++, 0x0F); // ASR #$0F
        // Caso 3
        memory.write(pc2++, 0xA9);
        memory.write(pc2++, 0x03); // LDA #$03
        memory.write(pc2++, 0x4B);
        memory.write(pc2++, 0x03); // ASR #$03
        cpu.reset();

        // Caso 1
        runOne(); // LDA #$FF
        int cyc = runMeasured();
        assertEquals(2, cyc, "ASR imediato deve consumir 2 ciclos");
        assertEquals(0x00, cpu.getA(), "ASR caso1 resultado incorreto");
        assertTrue(cpu.isCarry(), "ASR caso1 deveria setar Carry (bit0 do AND=1)");
        assertTrue(cpu.isZero(), "ASR caso1 deveria setar Zero");
        assertFalse(cpu.isNegative(), "ASR caso1 não deveria setar Negative");
        // Caso 2
        runOne(); // LDA #$AA
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x05, cpu.getA(), "ASR caso2 resultado incorreto (esperado 0x05)");
        assertFalse(cpu.isCarry(), "ASR caso2 não deveria setar Carry");
        assertFalse(cpu.isZero(), "ASR caso2 não deveria setar Zero");
        assertFalse(cpu.isNegative(), "ASR caso2 não deveria setar Negative");
        // Caso 3
        runOne(); // LDA #$03
        cyc = runMeasured();
        assertEquals(2, cyc);
        assertEquals(0x01, cpu.getA(), "ASR caso3 resultado incorreto (esperado 0x01)");
        assertTrue(cpu.isCarry(), "ASR caso3 deveria setar Carry");
        assertFalse(cpu.isZero(), "ASR caso3 não deveria setar Zero");
        assertFalse(cpu.isNegative(), "ASR caso3 não deveria setar Negative");
    }

    @Test
    public void dcpZeroPageFlagMatrix() {
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x40); // PC=0x4000
        int pc = 0x4000;
        // A=0x02 mem=0x01 -> 0x00; A-0=2 => C=1,Z=0,N=0
        memory.write(0x0040, 0x01);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x02);
        memory.write(pc++, 0xC7);
        memory.write(pc++, 0x40);
        // A=0x7F mem=0x80 -> 0x7F; A-0x7F=0 => C=1,Z=1,N=0
        memory.write(0x0041, 0x80);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x7F);
        memory.write(pc++, 0xC7);
        memory.write(pc++, 0x41);
        // A=0x00 mem=0x02 -> 0x01; A-1=0xFF => C=0,Z=0,N=1
        memory.write(0x0042, 0x02);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0xC7);
        memory.write(pc++, 0x42);
        cpu.reset();
        // Caso1
        runOne();
        runMeasured(5, 7); // tolerância
        assertEquals(0x00, memory.read(0x0040));
        assertTrue(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertFalse(cpu.isNegative());
        // Caso2
        runOne();
        runMeasured(5, 7);
        assertEquals(0x7F, memory.read(0x0041));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
        assertFalse(cpu.isNegative());
        // Caso3
        runOne();
        runMeasured(5, 7);
        assertEquals(0x01, memory.read(0x0042));
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isZero());
        assertTrue(cpu.isNegative());
    }

    @Test
    public void dcpIndexedVariants() {
        memory.write(0xFFFC, 0x00);
        memory.write(0xFFFD, 0x50);
        int pc = 0x5000;
        // (zp,X) 0xC3: set pointer at $20+$04 -> $24/$25 to 0x6000 value 0x05; A=0x07
        memory.write(0x0024, 0x00);
        memory.write(0x0025, 0x60);
        memory.write(0x6000, 0x05);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x07); // LDA #$07
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x04); // LDX #$04
        memory.write(pc++, 0xC3);
        memory.write(pc++, 0x20);
        // (zp),Y 0xD3: pointer $30/$31 -> 0x6100 + Y=3 => 0x6103 val 0x02 ; A=0x00
        memory.write(0x0030, 0x00);
        memory.write(0x0031, 0x61);
        memory.write(0x6103, 0x02);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x00); // LDA #$00
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x03); // LDY #$03
        memory.write(pc++, 0xD3);
        memory.write(pc++, 0x30);
        // abs,X 0xDF base 0x6200 X=2 mem[0x6202]=0x01 A=0x01
        memory.write(0x6202, 0x01);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x01);
        memory.write(pc++, 0xA2);
        memory.write(pc++, 0x02);
        memory.write(pc++, 0xDF);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x62);
        // abs,Y 0xDB base 0x6300 Y=3 mem[0x6303]=0x80 A=0x7F
        memory.write(0x6303, 0x80);
        memory.write(pc++, 0xA9);
        memory.write(pc++, 0x7F);
        memory.write(pc++, 0xA0);
        memory.write(pc++, 0x03);
        memory.write(pc++, 0xDB);
        memory.write(pc++, 0x00);
        memory.write(pc++, 0x63);
        cpu.reset();
        // (zp,X)
        runOne();
        runOne();
        runMeasured(7, 9);
        assertEquals(0x04, memory.read(0x6000));
        assertTrue(cpu.isCarry());
        // (zp),Y
        runOne();
        runOne();
        runMeasured(7, 9);
        assertEquals(0x01, memory.read(0x6103));
        assertFalse(cpu.isCarry());
        assertTrue(cpu.isNegative());
        // abs,X
        runOne();
        runOne();
        runMeasured(7, 9);
        assertEquals(0x00, memory.read(0x6202));
        assertTrue(cpu.isCarry());
        // abs,Y
        runOne();
        runOne();
        runMeasured(7, 9);
        assertEquals(0x7F, memory.read(0x6303));
        assertTrue(cpu.isCarry());
        assertTrue(cpu.isZero());
    }

    @Test
    public void testArrBasicNoCarry() {
        // Reset vector -> 0x4000
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x40);
        int pc = 0x4000;
        // LDA #$FF ; ARR #$FF com carry inicial = 0
        memory.write(pc++,0xA9); memory.write(pc++,0xFF);
        memory.write(pc++,0x6B); memory.write(pc++,0xFF);
        cpu.reset();
        cpu.setCarry(false);
        // LDA
        runOne();
        assertEquals(0xFF, cpu.getA());
        // ARR
        runOne();
        // A & imm = 0xFF -> ROR sem carry-in => 0x7F
        assertEquals(0x7F, cpu.getA());
        assertTrue(cpu.isCarry(), "Carry deve vir do bit6 (1)");
        assertFalse(cpu.isOverflow(), "Overflow = bit5 ^ bit6 => 1 ^ 1 = 0");
        assertFalse(cpu.isNegative());
        assertFalse(cpu.isZero());
    }

    @Test
    public void testArrCarryInAffectsBit7() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x40);
        int pc = 0x4000;
        // LDA #$80 ; ARR #$FF com carry inicial = 1
        memory.write(pc++,0xA9); memory.write(pc++,0x80);
        memory.write(pc++,0x6B); memory.write(pc++,0xFF);
        cpu.reset();
        cpu.setCarry(true); // carry-in para ROR
        runOne(); // LDA
        runOne(); // ARR
        // A & FF = 0x80 -> ROR com carry-in 1 => ((0x80>>1)|0x80)=0xC0
        assertEquals(0xC0, cpu.getA());
        assertTrue(cpu.isCarry(), "Carry = bit6 de 0xC0 (1)");
        // Bits5=0, bit6=1 => overflow = 1
        assertTrue(cpu.isOverflow());
        assertTrue(cpu.isNegative());
        assertFalse(cpu.isZero());
    }

    @Test
    public void testArrZeroResult() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x40);
        int pc = 0x4000;
        // LDA #$01 ; ARR #$01 carry=0
        memory.write(pc++,0xA9); memory.write(pc++,0x01);
        memory.write(pc++,0x6B); memory.write(pc++,0x01);
        cpu.reset();
        cpu.setCarry(false);
        runOne(); // LDA
        runOne(); // ARR
        // (0x01 & 0x01)=0x01 -> ROR => 0x00
        assertEquals(0x00, cpu.getA());
        assertFalse(cpu.isCarry());
        assertFalse(cpu.isOverflow());
        assertFalse(cpu.isNegative());
        assertTrue(cpu.isZero());
    }

    @Test
    public void testArrOverflowBit5SetBit6Clear() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x40);
        int pc = 0x4000;
        // LDA #$40 ; ARR #$FF carry=0 -> (0x40>>1)=0x20
        memory.write(pc++,0xA9); memory.write(pc++,0x40);
        memory.write(pc++,0x6B); memory.write(pc++,0xFF);
        cpu.reset();
        cpu.setCarry(false);
        runOne(); // LDA
        runOne(); // ARR
        assertEquals(0x20, cpu.getA());
        // bit6=0 => carry false; bits5=1 bit6=0 => overflow true
        assertFalse(cpu.isCarry());
        assertTrue(cpu.isOverflow());
        assertFalse(cpu.isNegative());
        assertFalse(cpu.isZero());
    }

    // Caso base zero page: memória 0x10=0x05 -> vira 0x06; A=0x0A; SBC 0x06 => A = 0x04, flags: C=1, Z=0, N=0
    @Test
    public void iscZeroPage() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x40); int pc=0x4000;
        memory.write(0x0010,0x05);
        memory.write(pc++,0xA9); memory.write(pc++,0x0A); // LDA #$0A
        memory.write(pc++,0xE7); memory.write(pc++,0x10); // ISC $10
        cpu.reset(); cpu.setCarry(true); // SBC usa (1-carry) então carry true = sem borrow
        runOne(); // LDA
        runOne(); // ISC
        assertEquals(0x06, memory.read(0x0010));
        assertEquals(0x04, cpu.getA());
        assertTrue(cpu.isCarry()); assertFalse(cpu.isZero()); assertFalse(cpu.isNegative());
    }

    // Zero page,X: memória 0x0020=0xFF -> 0x00; A=0x00; resultado SBC 0x00 = 0x00; C=1, Z=1
    @Test
    public void iscZeroPageXWrap() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x40); int pc=0x4000;
        memory.write(0x0020,0xFF);
        memory.write(pc++,0xA2); memory.write(pc++,0x20); // LDX #$20
        memory.write(pc++,0xA9); memory.write(pc++,0x00); // LDA #$00
        memory.write(pc++,0xF7); memory.write(pc++,0x00); // ISC $00,X -> efetivo $20
        cpu.reset(); cpu.setCarry(true);
        runOne(); runOne(); runOne();
        assertEquals(0x00, memory.read(0x0020));
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry()); assertTrue(cpu.isZero()); assertFalse(cpu.isNegative());
    }

    // Absoluto: 0x6000=0x7F -> 0x80; A=0x7F; 0x7F - 0x80 = 0xFF -> N=1, C=0
    @Test
    public void iscAbsoluteNegativeResult() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x50); int pc=0x5000;
        memory.write(0x6000,0x7F);
        memory.write(pc++,0xA9); memory.write(pc++,0x7F); // LDA #$7F
        memory.write(pc++,0xEF); memory.write(pc++,0x00); memory.write(pc++,0x60); // ISC $6000
        cpu.reset(); cpu.setCarry(true);
        runOne(); runOne();
        assertEquals(0x80, memory.read(0x6000));
        assertEquals(0xFF, cpu.getA());
        assertFalse(cpu.isCarry()); assertFalse(cpu.isZero()); assertTrue(cpu.isNegative());
    }

    // Absoluto,X: 0x6102=0x00 -> 0x01; A=0x00 -> SBC 0x01 => 0xFF (borrow) C=0,N=1
    @Test
    public void iscAbsoluteXBorrow() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x50); int pc=0x5000;
        memory.write(0x6102,0x00);
        memory.write(pc++,0xA2); memory.write(pc++,0x02); // LDX #$02
        memory.write(pc++,0xA9); memory.write(pc++,0x00); // LDA #$00
        memory.write(pc++,0xFF); memory.write(pc++,0x00); memory.write(pc++,0x61); // ISC $6100,X
        cpu.reset(); cpu.setCarry(true);
        runOne(); runOne(); runOne();
        assertEquals(0x01, memory.read(0x6102));
        assertEquals(0xFF, cpu.getA());
        assertFalse(cpu.isCarry()); assertFalse(cpu.isZero()); assertTrue(cpu.isNegative());
    }

    // (zp,X): pointer (0x30 + X=4) -> 0x34/0x35 => 0x6200=0xFE -> 0xFF; A=0xFF -> SBC 0xFF = 0x00 C=1,Z=1
    @Test
    public void iscIndirectX() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x60); int pc=0x6000;
        memory.write(0x6200,0xFE);
        memory.write(0x0034,0x00); memory.write(0x0035,0x62); // base pointer
        memory.write(pc++,0xA2); memory.write(pc++,0x04); // LDX #4
        memory.write(pc++,0xA9); memory.write(pc++,0xFF); // LDA #FF
        memory.write(pc++,0xE3); memory.write(pc++,0x30); // ISC ($30,X)
        cpu.reset(); cpu.setCarry(true);
        runOne(); runOne(); runOne();
        assertEquals(0xFF, memory.read(0x6200));
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.isCarry()); assertTrue(cpu.isZero()); assertFalse(cpu.isNegative());
    }

    // (zp),Y: pointer $40/$41 -> 0x6300 + Y=3 => 0x6303=0x01 -> 0x02; A=0x01; 0x01-0x02=0xFF (borrow)
    @Test
    public void iscIndirectY() {
        memory.write(0xFFFC,0x00); memory.write(0xFFFD,0x60); int pc=0x6000;
        memory.write(0x6303,0x01);
        memory.write(0x0040,0x00); memory.write(0x0041,0x63);
        memory.write(pc++,0xA0); memory.write(pc++,0x03); // LDY #3
        memory.write(pc++,0xA9); memory.write(pc++,0x01); // LDA #1
        memory.write(pc++,0xF3); memory.write(pc++,0x40); // ISC ($40),Y
        cpu.reset(); cpu.setCarry(true);
        runOne(); runOne(); runOne();
        assertEquals(0x02, memory.read(0x6303));
        assertEquals(0xFF, cpu.getA());
        assertFalse(cpu.isCarry()); assertFalse(cpu.isZero()); assertTrue(cpu.isNegative());
    }

    // --- Helpers & Test Memory ---
    private void runOne() {
        cpu.clock();
        while (cpu.getCycles() > 0)
            cpu.clock();
    }

    private void runMeasured(int expectedMin, int expectedMax) {
        cpu.clock();
        int c = 1;
        while (cpu.getCycles() > 0) {
            cpu.clock();
            c++;
        }
        assertTrue(c >= expectedMin && c <= expectedMax, "ciclos fora do esperado:" + c);
    }

    private int runMeasured() {
        cpu.clock();
        int consumed = 1;
        while (cpu.getCycles() > 0) {
            cpu.clock();
            consumed++;
        }
        return consumed;
    }

    private static class TestMemory implements iMemory {
        private final int[] data = new int[65536];

        @Override
        public int read(int address) {
            return data[address & 0xFFFF] & 0xFF;
        }

        @Override
        public void write(int address, int value) {
            data[address & 0xFFFF] = value & 0xFF;
        }
    }
}