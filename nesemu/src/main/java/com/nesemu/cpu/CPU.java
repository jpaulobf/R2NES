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
    
    /**
     * Gets the current status byte of the CPU.
     * @return The status byte with the current flags.
     */
    private AddressingMode getAddressingMode(int opcodeByte) {
        switch (opcodeByte) {
            // --- Imediato (#) ---
            case 0xA9: // LDA #imm
            case 0xA2: // LDX #imm
            case 0xA0: // LDY #imm
            case 0x69: // ADC #imm
            case 0x29: // AND #imm
            case 0xC9: // CMP #imm
            case 0xE0: // CPX #imm
            case 0xC0: // CPY #imm
            case 0x49: // EOR #imm
            case 0x09: // ORA #imm
            case 0xE9: // SBC #imm
                return AddressingMode.IMMEDIATE;

            // --- Zero Page ---
            case 0xA5: // LDA zp
            case 0xA6: // LDX zp
            case 0xA4: // LDY zp
            case 0x65: // ADC zp
            case 0x25: // AND zp
            case 0xC5: // CMP zp
            case 0xE4: // CPX zp
            case 0xC4: // CPY zp
            case 0x45: // EOR zp
            case 0x05: // ORA zp
            case 0xE5: // SBC zp
            case 0x06: // ASL zp
            case 0x26: // ROL zp
            case 0x46: // LSR zp
            case 0x66: // ROR zp
            case 0x85: // STA zp
            case 0x86: // STX zp
            case 0x84: // STY zp
            case 0x24: // BIT zp
            case 0xC6: // DEC zp
            case 0xE6: // INC zp
                return AddressingMode.ZERO_PAGE;

            // --- Zero Page,X ---
            case 0xB5: // LDA zp,X
            case 0xB4: // LDY zp,X
            case 0x75: // ADC zp,X
            case 0x35: // AND zp,X
            case 0xD5: // CMP zp,X
            case 0x55: // EOR zp,X
            case 0x15: // ORA zp,X
            case 0xF5: // SBC zp,X
            case 0x16: // ASL zp,X
            case 0x36: // ROL zp,X
            case 0x56: // LSR zp,X
            case 0x76: // ROR zp,X
            case 0x95: // STA zp,X
            case 0x94: // STY zp,X
            case 0xD6: // DEC zp,X
            case 0xF6: // INC zp,X
                return AddressingMode.ZERO_PAGE_X;

            // --- Zero Page,Y ---
            case 0xB6: // LDX zp,Y
            case 0x96: // STX zp,Y
                return AddressingMode.ZERO_PAGE_Y;

            // --- Absoluto ---
            case 0xAD: // LDA abs
            case 0xAE: // LDX abs
            case 0xAC: // LDY abs
            case 0x6D: // ADC abs
            case 0x2D: // AND abs
            case 0xCD: // CMP abs
            case 0xEC: // CPX abs
            case 0xCC: // CPY abs
            case 0x4D: // EOR abs
            case 0x0D: // ORA abs
            case 0xED: // SBC abs
            case 0x0E: // ASL abs
            case 0x2E: // ROL abs
            case 0x4E: // LSR abs
            case 0x6E: // ROR abs
            case 0x8D: // STA abs
            case 0x8E: // STX abs
            case 0x8C: // STY abs
            case 0x2C: // BIT abs
            case 0xCE: // DEC abs
            case 0xEE: // INC abs
                return AddressingMode.ABSOLUTE;

            // --- Absoluto,X ---
            case 0xBD: // LDA abs,X
            case 0xBC: // LDY abs,X
            case 0x7D: // ADC abs,X
            case 0x3D: // AND abs,X
            case 0xDD: // CMP abs,X
            case 0x5D: // EOR abs,X
            case 0x1D: // ORA abs,X
            case 0xFD: // SBC abs,X
            case 0x1E: // ASL abs,X
            case 0x3E: // ROL abs,X
            case 0x5E: // LSR abs,X
            case 0x7E: // ROR abs,X
            case 0x9D: // STA abs,X
            case 0xDE: // DEC abs,X
            case 0xFE: // INC abs,X
                return AddressingMode.ABSOLUTE_X;

            // --- Absoluto,Y ---
            case 0xB9: // LDA abs,Y
            case 0xBE: // LDX abs,Y
            case 0x79: // ADC abs,Y
            case 0x39: // AND abs,Y
            case 0xD9: // CMP abs,Y
            case 0x59: // EOR abs,Y
            case 0x19: // ORA abs,Y
            case 0xF9: // SBC abs,Y
            case 0x99: // STA abs,Y
                return AddressingMode.ABSOLUTE_Y;

            // --- Indireto ---
            case 0x6C: // JMP (abs)
                return AddressingMode.INDIRECT;

            // --- Indireto,X ---
            case 0xA1: // LDA (zp,X)
            case 0x61: // ADC (zp,X)
            case 0x21: // AND (zp,X)
            case 0xC1: // CMP (zp,X)
            case 0x41: // EOR (zp,X)
            case 0x01: // ORA (zp,X)
            case 0xE1: // SBC (zp,X)
            case 0x81: // STA (zp,X)
                return AddressingMode.INDIRECT_X;

            // --- Indireto,Y ---
            case 0xB1: // LDA (zp),Y
            case 0x71: // ADC (zp),Y
            case 0x31: // AND (zp),Y
            case 0xD1: // CMP (zp),Y
            case 0x51: // EOR (zp),Y
            case 0x11: // ORA (zp),Y
            case 0xF1: // SBC (zp),Y
            case 0x91: // STA (zp),Y
                return AddressingMode.INDIRECT_Y;

            // --- Relativo (branches) ---
            case 0x10: // BPL
            case 0x30: // BMI
            case 0x50: // BVC
            case 0x70: // BVS
            case 0x90: // BCC
            case 0xB0: // BCS
            case 0xD0: // BNE
            case 0xF0: // BEQ
                return AddressingMode.RELATIVE;

            // --- Acumulador ---
            case 0x0A: // ASL A
            case 0x4A: // LSR A
            case 0x2A: // ROL A
            case 0x6A: // ROR A
                return AddressingMode.ACCUMULATOR;

            // --- Implied (implícito) ---
            case 0x00: // BRK
            case 0x08: // PHP
            case 0x18: // CLC
            case 0x28: // PLP
            case 0x38: // SEC
            case 0x40: // RTI
            case 0x48: // PHA
            case 0x58: // CLI
            case 0x60: // RTS
            case 0x68: // PLA
            case 0x78: // SEI
            case 0x88: // DEY
            case 0x8A: // TXA
            case 0x98: // TYA
            case 0x9A: // TXS
            case 0xA8: // TAY
            case 0xAA: // TAX
            case 0xBA: // TSX
            case 0xC8: // INY
            case 0xCA: // DEX
            case 0xD8: // CLD
            case 0xE8: // INX
            case 0xEA: // NOP
            case 0xF8: // SED
                return AddressingMode.IMPLIED;

            // --- JMP Absolute ---
            case 0x4C: // JMP abs
                return AddressingMode.ABSOLUTE;

            // --- JSR Absolute ---
            case 0x20: // JSR abs
                return AddressingMode.ABSOLUTE;

            default:
                return AddressingMode.IMPLIED;
        }
    }
    
    /**
     * Fetches the operand according to the addressing mode.
     * Reads the next byte(s) from memory and returns the required value or address.
     * @param mode The addressing mode to use for fetching the operand.
     * @return The value or address as required by the instruction.
     */
    private int fetchOperand(AddressingMode mode) {
        switch (mode) {
            case IMMEDIATE:
                // Operando imediato: próximo byte
                return memory.read(pc++);
            case ZERO_PAGE:
                // Endereço de 8 bits (página zero)
                return memory.read(memory.read(pc++) & 0xFF);
            case ZERO_PAGE_X:
                // Endereço de 8 bits + X (página zero, wrap-around)
                return memory.read((memory.read(pc++) + x) & 0xFF);
            case ZERO_PAGE_Y:
                // Endereço de 8 bits + Y (página zero, wrap-around)
                return memory.read((memory.read(pc++) + y) & 0xFF);
            case ABSOLUTE: {
                // Endereço absoluto de 16 bits
                int addr = memory.read(pc++) | (memory.read(pc++) << 8);
                return memory.read(addr);
            }
            case ABSOLUTE_X: {
                // Endereço absoluto + X
                int addr = (memory.read(pc++) | (memory.read(pc++) << 8)) + x;
                return memory.read(addr & 0xFFFF);
            }
            case ABSOLUTE_Y: {
                // Endereço absoluto + Y
                int addr = (memory.read(pc++) | (memory.read(pc++) << 8)) + y;
                return memory.read(addr & 0xFFFF);
            }
            case INDIRECT: {
                // Apenas JMP (ind): pega endereço de 16 bits, lê ponteiro
                int ptr = memory.read(pc++) | (memory.read(pc++) << 8);
                // Emula bug do 6502 para páginas cruzadas
                int lo = memory.read(ptr);
                int hi = memory.read((ptr & 0xFF00) | ((ptr + 1) & 0xFF));
                int addr = lo | (hi << 8);
                return addr;
            }
            case INDIRECT_X: {
                // (zp,X): lê byte, soma X, pega ponteiro de 16 bits
                int zp = (memory.read(pc++) + x) & 0xFF;
                int lo = memory.read(zp);
                int hi = memory.read((zp + 1) & 0xFF);
                int addr = lo | (hi << 8);
                return memory.read(addr);
            }
            case INDIRECT_Y: {
                // (zp),Y: lê byte, pega ponteiro de 16 bits, soma Y
                int zp = memory.read(pc++) & 0xFF;
                int lo = memory.read(zp);
                int hi = memory.read((zp + 1) & 0xFF);
                int addr = ((hi << 8) | lo) + y;
                return memory.read(addr & 0xFFFF);
            }
            case RELATIVE:
                // Para branches: retorna offset (signed byte)
                return memory.read(pc++);
            case ACCUMULATOR:
                // Operação direta no acumulador
                return a;
            case IMPLIED:
            default:
                // Sem operando
                return 0;
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
                int cpyValue = operand & 0xFF;
                int cpyY = y & 0xFF;
                int cpyResult = cpyY - cpyValue;
                carry = cpyY >= cpyValue;
                zero = (cpyResult & 0xFF) == 0;
                negative = (cpyResult & 0x80) != 0;
                break;
            case DEC: 
                // DEC: decrementa valor em memória
                int decValue = (operand - 1) & 0xFF;
                setZeroAndNegative(decValue);
                // memory.write(addr, decValue); // Não temos o endereço aqui
                break;
            case DEX: 
                x = (x - 1) & 0xFF;
                setZeroAndNegative(x);
                break;
            case DEY: 
                y = (y - 1) & 0xFF;
                setZeroAndNegative(y);
                break;
            case EOR: 
                a = a ^ (operand & 0xFF);
                setZeroAndNegative(a);
                break;
            case INC: 
                // INC: incrementa valor em memória
                int incValue = (operand + 1) & 0xFF;
                setZeroAndNegative(incValue);
                // memory.write(addr, incValue); // Não temos o endereço aqui
                break;
            case INX: 
                x = (x + 1) & 0xFF;
                setZeroAndNegative(x);
                break;
            case INY: 
                y = (y + 1) & 0xFF;
                setZeroAndNegative(y);
                break;
            case JMP: 
                // JMP: salta para o endereço fornecido
                pc = operand & 0xFFFF;
                break;
            case JSR: 
                // JSR: Jump to SubRoutine
                // Push (PC-1) onto stack (high byte first, then low byte)
                int returnAddr = (pc - 1) & 0xFFFF;
                push((returnAddr >> 8) & 0xFF); // High byte
                push(returnAddr & 0xFF);        // Low byte
                pc = operand & 0xFFFF;
                break;
            case LDA: 
                a = operand & 0xFF;
                setZeroAndNegative(a);
                break;
            case LDX: 
                x = operand & 0xFF;
                setZeroAndNegative(x);
                break;
            case LDY: 
                y = operand & 0xFF;
                setZeroAndNegative(y);
                break;
            case LSR: 
                if (mode == AddressingMode.ACCUMULATOR) {
                    carry = (a & 0x01) != 0;
                    a = (a >> 1) & 0xFF;
                    setZeroAndNegative(a);
                } else {
                    // Para memória, operand é o valor lido; seria necessário o endereço para escrever de volta
                    int valueLSR = operand & 0xFF;
                    carry = (valueLSR & 0x01) != 0;
                    valueLSR = (valueLSR >> 1) & 0xFF;
                    setZeroAndNegative(valueLSR);
                    // memory.write(addr, valueLSR); // Não temos o endereço aqui
                }
                break;
            case NOP: 
                // NOP: No Operation.
                break;
            case ORA: 
                a = a | (operand & 0xFF);
                setZeroAndNegative(a);
                break;
            case PHA: 
                push(a & 0xFF);
                break;
            case PHP: 
                // PHP: Push Processor Status (com flag B setada)
                push(getStatusByte() | 0x10);
                break;
            case PLA: 
                a = pop() & 0xFF;
                setZeroAndNegative(a);
                break;
            case PLP: 
                // PLP: Pull Processor Status from stack
                int status = pop() & 0xFF;
                setStatusByte(status);
                // On 6502, the unused flag is always set to true after PLP
                unused = true;
                break;
            case ROL: 
                if (mode == AddressingMode.ACCUMULATOR) {
                    boolean oldCarry = carry;
                    carry = (a & 0x80) != 0;
                    a = ((a << 1) | (oldCarry ? 1 : 0)) & 0xFF;
                    setZeroAndNegative(a);
                } else {
                    // For memory, operand is the value, but we need the address to write back
                    int valueROL = operand & 0xFF;
                    boolean oldCarry = carry;
                    carry = (valueROL & 0x80) != 0;
                    valueROL = ((valueROL << 1) | (oldCarry ? 1 : 0)) & 0xFF;
                    setZeroAndNegative(valueROL);
                    // memory.write(addr, valueROL); // Address not available
                }
                break;
            case ROR: 
                if (mode == AddressingMode.ACCUMULATOR) {
                    boolean oldCarry = carry;
                    carry = (a & 0x01) != 0;
                    a = ((a >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                    setZeroAndNegative(a);
                } else {
                    int valueROR = operand & 0xFF;
                    boolean oldCarry = carry;
                    carry = (valueROR & 0x01) != 0;
                    valueROR = ((valueROR >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                    setZeroAndNegative(valueROR);
                    // memory.write(addr, valueROR); // Address not available
                }
                break;
            case RTI: 
                // RTI: Pull status, then pull PC (low, then high)
                setStatusByte(pop());
                int pcl = pop();
                int pch = pop();
                pc = (pch << 8) | pcl;
                break;
            case RTS: 
                // RTS: Pull PC (low, then high), then increment
                int pcl_rts = pop();
                int pch_rts = pop();
                pc = ((pch_rts << 8) | pcl_rts) + 1;
                pc &= 0xFFFF;
                break;
            case SBC: 
                // SBC: Subtract with Carry
                int valueSBC = operand & 0xFF;
                int accSBC = a & 0xFF;
                int carryInSBC = carry ? 1 : 0;
                int resultSBC = accSBC - valueSBC - (1 - carryInSBC);
                carry = resultSBC >= 0;
                overflow = ((accSBC ^ resultSBC) & 0x80) != 0 && ((accSBC ^ valueSBC) & 0x80) != 0;
                a = resultSBC & 0xFF;
                setZeroAndNegative(a);
                break;
            case SEC: 
                carry = true;
                break;
            case SED: 
                decimal = true;
                break;
            case SEI: 
                interruptDisable = true;
                break;
            case STA: 
                // Store A to memory (needs address)
                // memory.write(addr, a); // Address not available
                break;
            case STX: 
                // Store X to memory (needs address)
                // memory.write(addr, x); // Address not available
                break;
            case STY: 
                // Store Y to memory (needs address)
                // memory.write(addr, y); // Address not available
                break;
            case TAX: 
                x = a & 0xFF;
                setZeroAndNegative(x);
                break;
            case TAY: 
                y = a & 0xFF;
                setZeroAndNegative(y);
                break;
            case TSX: 
                x = sp & 0xFF;
                setZeroAndNegative(x);
                break;
            case TXA: 
                a = x & 0xFF;
                setZeroAndNegative(a);
                break;
            case TXS: 
                sp = x & 0xFF;
                break;
            case TYA: 
                a = y & 0xFF;
                setZeroAndNegative(a);
                break;

            // --- Most common undocumented (illegal) opcodes ---
            case AAC: 
                // AAC (ANC): AND operand with A, set carry = bit 7 of result
                a = a & (operand & 0xFF);
                setZeroAndNegative(a);
                carry = (a & 0x80) != 0;
                break;
            case AAX: 
                // AAX (SAX): Store A & X to memory (needs address)
                // int addr = ...; // Not available
                // memory.write(addr, a & x);
                break;
            case AHX: 
                // AHX: Store (A & X & (high byte + 1)) to memory (needs address)
                // int addr = ...; // Not available
                // int value = a & x & (((addr >> 8) + 1) & 0xFF);
                // memory.write(addr, value);
                break;
            case ALR: 
                // ALR: AND operand with A, then LSR
                a = a & (operand & 0xFF);
                carry = (a & 0x01) != 0;
                a = (a >> 1) & 0xFF;
                setZeroAndNegative(a);
                break;
            case ANC: 
                // ANC: AND operand with A, set carry = bit 7 of result
                a = a & (operand & 0xFF);
                setZeroAndNegative(a);
                carry = (a & 0x80) != 0;
                break;
            case ARR: 
                // ARR: AND operand with A, then ROR, set flags
                a = a & (operand & 0xFF);
                a = ((a >> 1) | (carry ? 0x80 : 0)) & 0xFF;
                setZeroAndNegative(a);
                carry = (a & 0x40) != 0;
                overflow = (((a >> 5) & 1) ^ ((a >> 6) & 1)) != 0;
                break;
            case ASR: 
                // ASR: AND operand with A, then LSR
                a = a & (operand & 0xFF);
                carry = (a & 0x01) != 0;
                a = (a >> 1) & 0xFF;
                setZeroAndNegative(a);
                break;
            case ATX: 
                // ATX: A = A & X, then A = X = A & operand
                a = a & x;
                a = x = a & (operand & 0xFF);
                setZeroAndNegative(a);
                break;
            case AXA: 
                // AXA: Store (A & X) & (high byte + 1) to memory (needs address)
                // int addr = ...; // Not available
                // int value = (a & x) & (((addr >> 8) + 1) & 0xFF);
                // memory.write(addr, value);
                break;
            case AXS: 
                // AXS: X = (A & X) - operand
                x = (a & x) - (operand & 0xFF);
                x &= 0xFF;
                setZeroAndNegative(x);
                carry = x <= 0xFF;
                break;
            case DCP: 
                // DCP: DEC memory, then CMP with A
                // int addr = ...; // Not available
                int dcpValue = (operand - 1) & 0xFF;
                // memory.write(addr, dcpValue);
                int dcpCmp = a - dcpValue;
                carry = (a & 0xFF) >= dcpValue;
                zero = (dcpCmp & 0xFF) == 0;
                negative = (dcpCmp & 0x80) != 0;
                break;
            case DOP: 
                // DOP: Double NOP (2-byte NOP)
                break;
            case ISC: 
                // ISC: INC memory, then SBC with A
                // int addr = ...; // Not available
                int iscValue = (operand + 1) & 0xFF;
                // memory.write(addr, iscValue);
                // Now SBC: A = A - iscValue - (1 - carry)
                int sbcVal = iscValue ^ 0xFF;
                int accIsc = a & 0xFF;
                int carryInIsc = carry ? 1 : 0;
                int resultIsc = accIsc + sbcVal + carryInIsc;
                carry = resultIsc > 0xFF;
                overflow = ((accIsc ^ resultIsc) & (sbcVal ^ resultIsc) & 0x80) != 0;
                a = resultIsc & 0xFF;
                setZeroAndNegative(a);
                break;
            case KIL: 
                // KIL: Halts CPU (simulate by not advancing PC)
                pc = (pc - 1) & 0xFFFF;
                break;
            case LAR: 
                // LAR: SP = A & memory, X = SP, A = SP
                sp = a & (operand & 0xFF);
                x = sp;
                a = sp;
                setZeroAndNegative(a);
                break;
            case LAS: 
                // LAS: SP = A & X & memory, A = X = SP
                sp = a & x & (operand & 0xFF);
                a = x = sp;
                setZeroAndNegative(a);
                break;
            case LAX: 
                // LAX: A = X = memory
                a = x = operand & 0xFF;
                setZeroAndNegative(a);
                break;
            case LXA: 
                // LXA: A = X = (A | 0xEE) & operand (unofficial, unstable)
                a = x = (a | 0xEE) & (operand & 0xFF);
                setZeroAndNegative(a);
                break;
            case RLA: 
                // RLA: ROL memory, then AND with A
                // int addr = ...; // Not available
                int rlaValue = ((operand << 1) | (carry ? 1 : 0)) & 0xFF;
                carry = (operand & 0x80) != 0;
                // memory.write(addr, rlaValue);
                a = a & rlaValue;
                setZeroAndNegative(a);
                break;
            case RRA: 
                // RRA: ROR memory, then ADC with A
                // int addr = ...; // Not available
                int rraValue = ((operand >> 1) | (carry ? 0x80 : 0)) & 0xFF;
                carry = (operand & 0x01) != 0;
                // memory.write(addr, rraValue);
                int adcVal = rraValue;
                int accRra = a & 0xFF;
                int carryInRra = carry ? 1 : 0;
                int resultRra = accRra + adcVal + carryInRra;
                carry = resultRra > 0xFF;
                overflow = (~(accRra ^ adcVal) & (accRra ^ resultRra) & 0x80) != 0;
                a = resultRra & 0xFF;
                setZeroAndNegative(a);
                break;
            case SAX: 
                // SAX: Store A & X to memory (needs address)
                // int addr = ...; // Not available
                // memory.write(addr, a & x);
                break;
            case SBX: 
                // SBX: X = (A & X) - operand
                x = (a & x) - (operand & 0xFF);
                x &= 0xFF;
                setZeroAndNegative(x);
                carry = x <= 0xFF;
                break;
            case SHA: 
                // SHA: Store (A & X & (high byte + 1)) to memory (needs address)
                // int addr = ...; // Not available
                // int value = a & x & (((addr >> 8) + 1) & 0xFF);
                // memory.write(addr, value);
                break;
            case SHS: 
                // SHS: SP = A & X, store (A & X & (high byte + 1)) to memory (needs address)
                sp = a & x;
                // int addr = ...; // Not available
                // int value = sp & (((addr >> 8) + 1) & 0xFF);
                // memory.write(addr, value);
                break;
            case SHX: 
                // SHX: Store X & (high byte + 1) to memory (needs address)
                // int addr = ...; // Not available
                // int value = x & (((addr >> 8) + 1) & 0xFF);
                // memory.write(addr, value);
                break;
            case SHY: 
                // SHY: Store Y & (high byte + 1) to memory (needs address)
                // int addr = ...; // Not available
                // int value = y & (((addr >> 8) + 1) & 0xFF);
                // memory.write(addr, value);
                break;
            case SLO: 
                // SLO: ASL valor em memória, depois ORA com A
                // operand é o valor lido; para precisão, seria necessário o endereço de escrita
                int sloValue = operand & 0xFF;
                carry = (sloValue & 0x80) != 0;
                sloValue = (sloValue << 1) & 0xFF;
                // memory.write(addr, sloValue); // Não temos o endereço aqui
                a = a | sloValue;
                setZeroAndNegative(a);
                break;
            case SRE: 
                // SRE: LSR valor em memória, depois EOR com A
                // operand é o valor lido; para precisão, seria necessário o endereço de escrita
                int sreValue = operand & 0xFF;
                carry = (sreValue & 0x01) != 0;
                sreValue = (sreValue >> 1) & 0xFF;
                // memory.write(addr, sreValue); // Não temos o endereço aqui
                a = a ^ sreValue;
                setZeroAndNegative(a);
                break;
            case TAS: 
                // TAS (SHS): S = A & X; armazena (A & X) & (high byte do endereço + 1) em memória
                // Implementação simplificada: S = A & X
                sp = a & x;
                // Emula o comportamento de armazenamento (A & X) & (high byte do endereço + 1)
                // operand é o valor lido, mas normalmente seria o endereço absoluto
                // Para precisão, seria necessário obter o endereço real (não disponível aqui)
                // memory.write(addr, (a & x) & (((addr >> 8) + 1) & 0xFF));
                break;
            case TOP: 
                // TOP (NOP de 2/3 bytes): não faz nada, já avançou PC ao buscar operandos
                break;
            case XAA: 
                // XAA (unofficial): A = (A & X) & operando
                a = (a & x) & (operand & 0xFF);
                setZeroAndNegative(a);
                break;

            // --- Any other opcodes (future/unknown) ---
            default:
                // NOP or handle as illegal
                break;
        }
    }
    
    /**
     * Pushes a value onto the stack.
     * The stack is located at 0x0100 in memory, and the stack pointer (SP) is decremented before writing.
     * @param value
     */
    private void push(int value) {
        memory.write(0x100 + (sp & 0xFF), value & 0xFF);
        sp = (sp - 1) & 0xFF;
    }

    /**
     * Pops a value from the stack.
     * The stack is located at 0x0100 in memory, and the stack pointer (SP) is incremented after reading.
     * @return
     */
    private int pop() {
        sp = (sp + 1) & 0xFF;
        return memory.read(0x100 + (sp & 0xFF));
    }
    
    /**
     * Sets the zero and negative flags based on the value.
     * This method updates the zero and negative flags based on the provided value.
     * @return
     */
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

    /**
     * Sets the status flags based on the provided byte value.
     * This method updates the processor status flags based on the provided byte value.
     * @param value
     */
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