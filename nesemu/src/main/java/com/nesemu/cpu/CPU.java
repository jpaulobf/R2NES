package com.nesemu.cpu;

import com.nesemu.cpu.interfaces.iCPU;
import com.nesemu.memory.Memory;

public class CPU implements iCPU {
    private int a;      // Acumulador
    private int x;      // Registrador X
    private int y;      // Registrador Y
    private int sp;     // Stack Pointer
    private int pc;     // Program Counter

    // Flags de status
    private boolean carry;
    private boolean zero;
    private boolean interruptDisable;
    private boolean decimal;
    private boolean breakFlag;
    private boolean unused;
    private boolean overflow;
    private boolean negative;

    private final Memory memory;

    public CPU(Memory memory) {
        this.memory = memory;
        reset();
    }

    public void reset() {
        a = x = y = 0;
        sp = 0xFD;
        // PC é inicializado a partir do vetor de reset (0xFFFC/0xFFFD)
        pc = (memory.read(0xFFFD) << 8) | memory.read(0xFFFC);
        carry = zero = interruptDisable = decimal = breakFlag = unused = overflow = negative = false;
    }

    public void clock() {
        int opcodeByte = memory.read(pc++);
        Opcode opcode = Opcode.fromByte(opcodeByte);
        if (opcode == null) {
            // Opcional: lançar exceção ou tratar como NOP
            return;
        }
        // TODO: decodificar modo de endereçamento e executar instrução
    }

    // Métodos utilitários para flags
    private void setZeroAndNegative(int value) {
        zero = (value & 0xFF) == 0;
        negative = (value & 0x80) != 0;
    }

    public void nmi() {
        // Implementar lógica de Non-Maskable Interrupt
        // Salvar PC e flags, atualizar PC para vetor NMI
    }

    public void irq() {
        if (!interruptDisable) {
            // Implementar lógica de Interrupt Request
            // Salvar PC e flags, atualizar PC para vetor IRQ
        }
    }

    // Getters and Setters 
    public int getA() { return a; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getSP() { return sp; }
    public int getPC() { return pc; }
    public boolean isCarry() { return carry; }
    public boolean isZero() { return zero; }
    public boolean isInterruptDisable() { return interruptDisable; }
    public boolean isDecimal() { return decimal; }
    public boolean isBreakFlag() { return breakFlag; }
    public boolean isUnused() { return unused; }
    public boolean isOverflow() { return overflow; }
    public boolean isNegative() { return negative; }
    public void setA(int a) { this.a = a & 0xFF; setZeroAndNegative(this.a); }
    public void setX(int x) { this.x = x & 0xFF; setZeroAndNegative(this.x); }
    public void setY(int y) { this.y = y & 0xFF; setZeroAndNegative(this.y); }
    public void setSP(int sp) { this.sp = sp & 0xFF; setZeroAndNegative(this.sp); }
    public void setPC(int pc) { this.pc = pc & 0xFFFF; }
    public void setCarry(boolean carry) { this.carry = carry; }
    public void setZero(boolean zero) { this.zero = zero; }
    public void setInterruptDisable(boolean interruptDisable) { this.interruptDisable = interruptDisable; }
    public void setDecimal(boolean decimal) { this.decimal = decimal; }
    public void setBreakFlag(boolean breakFlag) { this.breakFlag = breakFlag; }
    public void setUnused(boolean unused) { this.unused = unused; }
    public void setOverflow(boolean overflow) { this.overflow = overflow; }
    public void setNegative(boolean negative) { this.negative = negative; }
}