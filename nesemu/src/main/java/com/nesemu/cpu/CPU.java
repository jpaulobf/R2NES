package com.nesemu.cpu;

import com.nesemu.cpu.interfaces.iCPU;
import com.nesemu.memory.Memory;

/**
 * Class representing the NES CPU.
 * Implements the main functionalities and instructions of the processor.
 */
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

    // Dispatcher principal
    public void clock() {
        int opcodeByte = memory.read(pc++);
        Opcode opcode = Opcode.fromByte(opcodeByte);
        if (opcode == null) return;
    
        AddressingMode mode = getAddressingMode(opcodeByte);
        int operand = fetchOperand(mode);
    
        execute(opcode, mode, operand);
    }
    
    // Exemplo de tabela de modos de endereçamento (pode ser array ou switch)
    private AddressingMode getAddressingMode(int opcodeByte) {
        switch (opcodeByte) {
            case 0xA9: return AddressingMode.IMMEDIATE; // LDA #imm
            case 0xA5: return AddressingMode.ZERO_PAGE; // LDA zp
            // ...adicione todos os opcodes...
            default: return AddressingMode.IMPLIED;
        }
    }
    
    // Busca o operando conforme o modo
    private int fetchOperand(AddressingMode mode) {
        switch (mode) {
            case IMMEDIATE: return memory.read(pc++);
            case ZERO_PAGE: return memory.read(memory.read(pc++) & 0xFF);
            case ABSOLUTE: {
                int addr = memory.read(pc++) | (memory.read(pc++) << 8);
                return memory.read(addr);
            }
            // ...outros modos...
            default: return 0;
        }
    }
    
    // Dispatcher de execução
    private void execute(Opcode opcode, AddressingMode mode, int operand) {
        switch (opcode) {
            case LDA:
                setA(operand);
                break;
            case TAX:
                setX(a);
                break;
            // ...implemente as demais instruções...
            default:
                // NOP ou tratamento de ilegais
                break;
        }
    }
    
    // Métodos auxiliares para stack
    private void push(int value) {
        memory.write(0x100 + (sp & 0xFF), value & 0xFF);
        sp = (sp - 1) & 0xFF;
    }
    private int pop() {
        sp = (sp + 1) & 0xFF;
        return memory.read(0x100 + (sp & 0xFF));
    }
    
    // Status byte
    private int getStatusByte() {
        int p = 0;
        if (carry) p |= 0x01;
        if (zero) p |= 0x02;
        if (interruptDisable) p |= 0x04;
        if (decimal) p |= 0x08;
        if (breakFlag) p |= 0x10;
        if (unused) p |= 0x20;
        if (overflow) p |= 0x40;
        if (negative) p |= 0x80;
        return p;
    }
    private void setStatusByte(int value) {
        carry = (value & 0x01) != 0;
        zero = (value & 0x02) != 0;
        interruptDisable = (value & 0x04) != 0;
        decimal = (value & 0x08) != 0;
        breakFlag = (value & 0x10) != 0;
        unused = (value & 0x20) != 0;
        overflow = (value & 0x40) != 0;
        negative = (value & 0x80) != 0;
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