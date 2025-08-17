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

    /*
     * Construtor da CPU
     * @param memory Instância de memória que a CPU irá utilizar
     */
    public CPU(Memory memory) {
        this.memory = memory;
        reset();
    }

    /**
     * Reseta a CPU para o estado inicial.
     * Inicializa os registradores e o Program Counter (PC) a partir do vetor de reset.
     */
    public void reset() {
        a = x = y = 0;
        sp = 0xFD;
        // PC é inicializado a partir do vetor de reset (0xFFFC/0xFFFD)
        pc = (memory.read(0xFFFD) << 8) | memory.read(0xFFFC);
        carry = zero = interruptDisable = decimal = breakFlag = unused = overflow = negative = false;
    }

    /**
     * Executa um ciclo de clock da CPU.
     * Lê o próximo opcode da memória, decodifica e executa a instrução
     */
    public void clock() {
        int opcodeByte = memory.read(pc++);
        Opcode opcode = Opcode.fromByte(opcodeByte);

        // Se o opcode não for reconhecido, retorna.
        if (opcode == null) return;
    
        // Obtém o modo de endereçamento e o operando
        AddressingMode mode = getAddressingMode(opcodeByte);
        int operand = fetchOperand(mode);
    
        execute(opcode, mode, operand);
    }


    // Métodos utilitários para flags
    private void setZeroAndNegative(int value) {
        zero = (value & 0xFF) == 0;
        negative = (value & 0x80) != 0;
    }

    // Métodos de interrupção
    public void nmi() {
        // Implementar lógica de Non-Maskable Interrupt
        // Salvar PC e flags, atualizar PC para vetor NMI
    }

    /**
     * Executa uma interrupção de requisição (IRQ).
     * Se as interrupções não estiverem desabilitadas, salva o estado atual e pula para o vetor IRQ.
     */
    public void irq() {
        if (!interruptDisable) {
            // Implementar lógica de Interrupt Request
            // Salvar PC e flags, atualizar PC para vetor IRQ
        }
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
            // --- Official NES opcodes ---
            case ADC: 
                int value = operand & 0xFF;
                int acc = a & 0xFF;
                int carryIn = carry ? 1 : 0;
                int result = acc + value + carryIn;

                // Carry flag
                carry = result > 0xFF;

                // Overflow flag (bit 7 muda de forma inesperada)
                overflow = (~(acc ^ value) & (acc ^ result) & 0x80) != 0;
                a = result & 0xFF;

                setZeroAndNegative(a);
                break;
            case AND: 
                a = a & (operand & 0xFF);
                setZeroAndNegative(a);
                break;
            case ASL: 
                if (mode == AddressingMode.ACCUMULATOR) {
                    carry = (a & 0x80) != 0;
                    a = (a << 1) & 0xFF;
                    setZeroAndNegative(a);
                } else {
                    // For memory, operand is the value, but we need the address to write back
                    // This requires fetchOperand to return the address for ASL memory modes
                    // For now, assume operand is the value and address is in a temp variable (not implemented)
                    // int addr = ...; // get address from context (not available in current code)
                    int valueASL = operand & 0xFF;
                    carry = (valueASL & 0x80) != 0;
                    valueASL = (valueASL << 1) & 0xFF;
                    setZeroAndNegative(valueASL);
                    // memory.write(addr, value); // Uncomment and implement address logic
                }
                break;
            case BCC: 
                if (!carry) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BCS: 
                if (carry) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BEQ: 
                if (zero) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BIT: 
                int bitResult = a & (operand & 0xFF);
                zero = (bitResult == 0);
                negative = ((operand & 0x80) != 0);
                overflow = ((operand & 0x40) != 0);
                break;
            case BMI: 
                if (negative) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BNE: 
                if (!zero) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BPL: 
                if (!negative) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BRK: 
                pc = (pc + 1) & 0xFFFF; // BRK increments PC by 2 (already incremented by 1 in clock)
                push((pc >> 8) & 0xFF); // Push PCH
                push(pc & 0xFF);        // Push PCL
                breakFlag = true;
                push(getStatusByte() | 0x10); // Push status with B flag set
                interruptDisable = true;
                // Set PC to IRQ/BRK vector
                int lo = memory.read(0xFFFE);
                int hi = memory.read(0xFFFF);
                pc = (hi << 8) | lo;
                break;
            case BVC: 
                if (!overflow) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case BVS: 
                if (overflow) {
                    // Operand is a signed 8-bit offset (relative addressing)
                    int offset = (byte)(operand & 0xFF); // sign-extend
                    pc = (pc + offset) & 0xFFFF;
                }
                break;
            case CLC: 
                carry = false;
                break;
            case CLD: 
                decimal = false;
                break;
            case CLI: 
                interruptDisable = false;
                break;
            case CLV: 
                overflow = false;
                break;
            case CMP: 
                int cmpValue = operand & 0xFF;
                int cmpA = a & 0xFF;
                int cmpResult = cmpA - cmpValue;
                carry = cmpA >= cmpValue;
                zero = (cmpResult & 0xFF) == 0;
                negative = (cmpResult & 0x80) != 0;
                break;
            case CPX: 
                int cpxValue = operand & 0xFF;
                int cpxX = x & 0xFF;
                int cpxResult = cpxX - cpxValue;
                carry = cpxX >= cpxValue;
                zero = (cpxResult & 0xFF) == 0;
                negative = (cpxResult & 0x80) != 0;
                break;
            case CPY: 
                break;
            case DEC: 
                break;
            case DEX: 
                break;
            case DEY: 
                break;
            case EOR: 
                break;
            case INC: 
                break;
            case INX: 
                break;
            case INY: 
                break;
            case JMP: 
                break;
            case JSR: 
                break;
            case LDA: 
                break;
            case LDX: 
                break;
            case LDY: 
                break;
            case LSR: 
                break;
            case NOP: 
                break;
            case ORA: 
                break;
            case PHA: 
                break;
            case PHP: 
                break;
            case PLA: 
                break;
            case PLP: 
                break;
            case ROL: 
                break;
            case ROR: 
                break;
            case RTI: 
                break;
            case RTS: 
                break;
            case SBC: 
                break;
            case SEC: 
                break;
            case SED: 
                break;
            case SEI: 
                break;
            case STA: 
                break;
            case STX: 
                break;
            case STY: 
                break;
            case TAX: 
                break;
            case TAY: 
                break;
            case TSX: 
                break;
            case TXA: 
                break;
            case TXS: 
                break;
            case TYA: 
                break;

            // --- Most common undocumented (illegal) opcodes ---
            case AAC: 
                break;
            case AAX: 
                break;
            case AHX: 
                break;
            case ALR: 
                break;
            case ANC: 
                break;
            case ARR: 
                break;
            case ASR: 
                break;
            case ATX: 
                break;
            case AXA: 
                break;
            case AXS: 
                break;
            case DCP: 
                break;
            case DOP: 
                break;
            case ISC: 
                break;
            case KIL: 
                break;
            case LAR: 
                break;
            case LAS: 
                break;
            case LAX: 
                break;
            case LXA: 
                break;
            case RLA: 
                break;
            case RRA: 
                break;
            case SAX: 
                break;
            case SBX: 
                break;
            case SHA: 
                break;
            case SHS: 
                break;
            case SHX: 
                break;
            case SHY: 
                break;
            case SLO: 
                break;
            case SRE: 
                break;
            case TAS: 
                break;
            case TOP: 
                break;
            case XAA: 
                break;

            // --- Any other opcodes (future/unknown) ---
            default:
                // NOP or handle as illegal
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