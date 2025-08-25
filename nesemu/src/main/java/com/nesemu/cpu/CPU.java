package com.nesemu.cpu;

import com.nesemu.cpu.interfaces.iCPU;
import com.nesemu.memory.interfaces.iMemory;

/**
 * Class representing the NES CPU.
 * Implements the main functionalities and instructions of the processor.
 */
public class CPU implements iCPU {
    private int a; // Acumulador
    private int x; // Register X
    private int y; // Register Y
    private int sp; // Stack Pointer
    private int pc; // Program Counter

    // Flags de status
    private boolean carry;
    private boolean zero;
    private boolean interruptDisable;
    private boolean decimal;
    private boolean breakFlag;
    private boolean unused;
    private boolean overflow;
    private boolean negative;
    private final iMemory memory;
    // Flag de crossing de página para instruções que desejam aplicar ciclo extra
    // (ex: TOP abs,X em modo fiel)
    private boolean lastPageCrossed = false;

    // Cycle counter for instruction timing
    private int cycles;
    // Dynamic extra cycles (branch taken, page crossing in branches, etc.)
    // aplicados após execução
    private int extraCycles;

    // Interrupt pending flags
    private boolean nmiPending = false;
    private boolean irqPending = false;

    // NES 6502 cycle table (official opcodes only, 256 entries)
    private static final int[] CYCLE_TABLE = new int[] {
            7, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6, // 0x00-0x0F
            2, 5, 2, 8, 3, 4, 6, 6, 3, 4, 2, 7, 4, 4, 7, 7, // 0x10-0x1F
            6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6, // 0x20-0x2F
            2, 5, 2, 8, 4, 4, 6, 6, 2, 5, 2, 7, 4, 4, 7, 7, // 0x30-0x3F
            6, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6, // 0x40-0x4F
            2, 5, 2, 8, 3, 4, 6, 6, 2, 5, 2, 7, 4, 4, 7, 7, // 0x50-0x5F
            6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6, // 0x60-0x6F
            2, 5, 2, 8, 4, 4, 6, 6, 2, 5, 2, 7, 4, 4, 7, 7, // 0x70-0x7F
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4, // 0x80-0x8F
            2, 6, 2, 6, 4, 4, 4, 4, 2, 5, 2, 5, 5, 5, 5, 5, // 0x90-0x9F
            2, 6, 2, 6, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6, // 0xA0-0xAF
            2, 5, 2, 5, 4, 4, 6, 6, 2, 5, 2, 5, 5, 5, 7, 7, // 0xB0-0xBF
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6, // 0xC0-0xCF
            2, 5, 2, 8, 4, 4, 6, 6, 2, 5, 2, 7, 4, 4, 7, 7, // 0xD0-0xDF
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6, // 0xE0-0xEF
            2, 5, 2, 8, 4, 4, 6, 6, 2, 5, 2, 7, 4, 4, 7, 7 // 0xF0-0xFF
    };

    /*
     * CPU constructor
     * 
     * @param memory Memory instance to be used by the CPU
     */
    public CPU(iMemory memory) {
        this.memory = memory;
        reset();
    }

    /**
     * Resets the CPU to its initial state.
     * Initializes the registers and the Program Counter (PC) from the reset vector.
     */
    public void reset() {
        a = x = y = 0;
        sp = 0xFD;
        // PC is initialized from the reset vector (0xFFFC/0xFFFD) - low byte first,
        // then high byte
        pc = (memory.read(0xFFFC) | (memory.read(0xFFFD) << 8));
        carry = zero = interruptDisable = decimal = breakFlag = overflow = negative = false;
        unused = true; // Bit 5 of the status register is always set
    }

    /**
     * Executes a single CPU clock cycle. If an instruction is in progress,
     * decrements the cycle counter.
     * If no instruction is in progress, fetches and executes the next instruction
     * and sets the cycle counter.
     */
    public void clock() {
        // If instruction in progress, just consume a cycle
        if (cycles > 0) {
            cycles--;
            return;
        }
        // Instruction boundary: check interrupts first
        if (nmiPending) {
            handleNMI();
            nmiPending = false;
            cycles = 7 - 1; // we've spent this initiating cycle
            return;
        } else if (irqPending && !interruptDisable) {
            handleIRQ();
            irqPending = false;
            cycles = 7 - 1;
            return;
        }
        int opcodeByte = memory.read(pc);
        pc++;
        Opcode opcode = Opcode.fromByte(opcodeByte);
        if (opcode == null)
            return;
        AddressingMode mode = getAddressingMode(opcodeByte);
        OperandResult opRes = fetchOperand(mode);
        // Page crossing detection (only relevant for indexed modes)
        boolean pageCrossed = false;
        if (mode == AddressingMode.ABSOLUTE_X || mode == AddressingMode.ABSOLUTE_Y) {
            int index = (mode == AddressingMode.ABSOLUTE_X) ? x : y;
            int lo = (opRes.address - index) & 0xFFFF;
            pageCrossed = ((lo & 0xFF00) != (opRes.address & 0xFF00));
        } else if (mode == AddressingMode.INDIRECT_Y) {
            int zp = memory.read(pc - 1) & 0xFF;
            int base = (memory.read((zp + 1) & 0xFF) << 8) | memory.read(zp);
            pageCrossed = ((base & 0xFF00) != (opRes.address & 0xFF00));
        }
        int baseCycles = CYCLE_TABLE[opcodeByte & 0xFF];
        int remaining = baseCycles;
        // Update lastPageCrossed for instructions that may rely (e.g., TOP abs,X
        // timing)
        lastPageCrossed = pageCrossed;
        if (pageCrossed && (opcode == Opcode.LDA || opcode == Opcode.LDX || opcode == Opcode.LDY ||
                opcode == Opcode.ADC || opcode == Opcode.SBC || opcode == Opcode.CMP ||
                opcode == Opcode.AND || opcode == Opcode.ORA || opcode == Opcode.EOR)) {
            remaining += 1;
        }
        // Execute instruction work on this first cycle
        extraCycles = 0; // reset dynamic
        execute(opcode, mode, opRes.value, opRes.address);
        // Set remaining cycles minus the one we just spent
        cycles = Math.max(0, remaining - 1 + extraCycles);
    }

    /**
     * Executes the instruction based on the opcode and addressing mode.
     * 
     * @param value
     */
    private void setZeroAndNegative(int value) {
        zero = (value & 0xFF) == 0;
        negative = (value & 0x80) != 0;
    }

    /**
     * Pushes a byte onto the stack.
     */
    public void nmi() {
        // Signal NMI to be handled at next instruction boundary
        nmiPending = true;
    }

    /**
     * Executes an Interrupt Request (IRQ).
     * If interrupts are not disabled, saves the current state and jumps to the IRQ
     * vector.
     */
    public void irq() {
        // Signal IRQ to be handled at next instruction boundary
        irqPending = true;
    }

    // Handles the actual NMI sequence
    private void handleNMI() {
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        push(getStatusByte() & ~0x10 | 0x20);
        interruptDisable = true;
        int lo = memory.read(0xFFFA);
        int hi = memory.read(0xFFFB);
        pc = (hi << 8) | lo;
    }

    /**
     * Handles the actual IRQ sequence.
     */
    private void handleIRQ() {
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        push(getStatusByte() & ~0x10 | 0x20);
        interruptDisable = true;
        int lo = memory.read(0xFFFE);
        int hi = memory.read(0xFFFF);
        pc = (hi << 8) | lo;
    }

    /**
     * Gets the current status byte of the CPU.
     * 
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
            case 0x0B: // ANC/AAC #imm (ilegal)
            case 0x2B: // ANC/AAC #imm (ilegal)
            case 0xAB: // LXA #imm (ilegal)
            case 0xC9: // CMP #imm
            case 0xE0: // CPX #imm
            case 0xC0: // CPY #imm
            case 0x49: // EOR #imm
            case 0x09: // ORA #imm
            case 0xE9: // SBC #imm
            case 0x4B: // ALR #imm (ilegal)
            case 0x6B: // ARR #imm (ilegal)
            case 0xCB: // AXS #imm (ilegal)
            case 0x80: // DOP immediate (2-byte NOP)
            case 0x82: // DOP immediate
            case 0x89: // DOP immediate
            case 0xC2: // DOP immediate
            case 0xE2: // DOP immediate
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
            case 0x47: // SRE zp (ilegal)
            case 0x07: // SLO zp (ilegal)
            case 0x67: // RRA zp (ilegal)
            case 0x27: // RLA zp (ilegal)
            case 0x85: // STA zp
            case 0x86: // STX zp
            case 0x84: // STY zp
            case 0x87: // SAX/AAX zp (ilegal)
            case 0xA7: // LAX zp (ilegal)
            case 0x24: // BIT zp
            case 0xC6: // DEC zp
            case 0xE6: // INC zp
            case 0xC7: // DCP zp (ilegal)
            case 0xE7: // ISC zp (ilegal)
            case 0x04: // DOP (NOP zp)
            case 0x44: // DOP (NOP zp)
            case 0x64: // DOP (NOP zp)
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
            case 0x57: // SRE zp,X (ilegal)
            case 0x17: // SLO zp,X (ilegal)
            case 0x77: // RRA zp,X (ilegal)
            case 0x37: // RLA zp,X (ilegal)
            case 0x95: // STA zp,X
            case 0x94: // STY zp,X
            case 0xD6: // DEC zp,X
            case 0xF6: // INC zp,X
            case 0xD7: // DCP zp,X (ilegal)
            case 0xF7: // ISC zp,X (ilegal)
            case 0x14: // DOP (NOP zp,X)
            case 0x34: // DOP (NOP zp,X)
            case 0x54: // DOP (NOP zp,X)
            case 0x74: // DOP (NOP zp,X)
            case 0xD4: // DOP (NOP zp,X)
            case 0xF4: // DOP (NOP zp,X)
                return AddressingMode.ZERO_PAGE_X;

            // --- Zero Page,Y ---
            case 0xB6: // LDX zp,Y
            case 0x96: // STX zp,Y
            case 0x97: // SAX/AAX zp,Y (ilegal)
            case 0xB7: // LAX zp,Y (ilegal)
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
            case 0x0C: // TOP abs (triple NOP)
            case 0x6F: // RRA abs (ilegal)
            case 0x2F: // RLA abs (ilegal)
            case 0x4F: // SRE abs (ilegal)
            case 0x0F: // SLO abs (ilegal)
            case 0x8D: // STA abs
            case 0x8E: // STX abs
            case 0x8C: // STY abs
            case 0x8F: // SAX/AAX abs (ilegal)
            case 0x2C: // BIT abs
            case 0xCE: // DEC abs
            case 0xEE: // INC abs
            case 0xCF: // DCP abs (ilegal)
            case 0xEF: // ISC abs (ilegal)
            case 0xAF: // LAX abs (ilegal)
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
            case 0x1C: // TOP abs,X (triple NOP)
            case 0x3C: // TOP abs,X (triple NOP)
            case 0x5C: // TOP abs,X (triple NOP)
            case 0x7C: // TOP abs,X (triple NOP)
            case 0xDC: // TOP abs,X (triple NOP)
            case 0xFC: // TOP abs,X (triple NOP)
            case 0x7F: // RRA abs,X (ilegal)
            case 0x3F: // RLA abs,X (ilegal)
            case 0x5F: // SRE abs,X (ilegal)
            case 0x1F: // SLO abs,X (ilegal)
            case 0x9D: // STA abs,X
            case 0x9C: // SHY abs,X (ilegal)
            case 0xDE: // DEC abs,X
            case 0xFE: // INC abs,X
            case 0xDF: // DCP abs,X (ilegal)
            case 0xFF: // ISC abs,X (ilegal)
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
            case 0x9B: // SHS/TAS (ilegal) abs,Y
            case 0x7B: // RRA abs,Y (ilegal)
            case 0x9F: // AHX abs,Y (ilegal)
            case 0x9E: // SHX abs,Y (ilegal)
            case 0x5B: // SRE abs,Y (ilegal)
            case 0x1B: // SLO abs,Y (ilegal)
            case 0xDB: // DCP abs,Y (ilegal)
            case 0xFB: // ISC abs,Y (ilegal)
            case 0xBF: // LAX abs,Y (ilegal)
            case 0xBB: // LAS abs,Y (ilegal)
            case 0x3B: // RLA abs,Y (ilegal)
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
            case 0x83: // SAX/AAX (zp,X) (ilegal)
            case 0x43: // SRE (zp,X) ilegal
            case 0x03: // SLO (zp,X) ilegal
            case 0xC3: // DCP (zp,X) ilegal
            case 0xE3: // ISC (zp,X) ilegal
            case 0xA3: // LAX (zp,X) ilegal
            case 0x63: // RRA (zp,X) ilegal
            case 0x23: // RLA (zp,X) ilegal
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
            case 0x93: // AHX (zp),Y (ilegal)
            case 0x53: // SRE (zp),Y ilegal
            case 0x13: // SLO (zp),Y ilegal
            case 0xD3: // DCP (zp),Y ilegal
            case 0xF3: // ISC (zp),Y ilegal
            case 0xB3: // LAX (zp),Y ilegal
            case 0x73: // RRA (zp),Y ilegal
            case 0x33: // RLA (zp),Y ilegal
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
            case 0x02: // KIL (JAM)
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
     * 
     * @param mode The addressing mode to use for fetching the operand.
     * @return The value or address as required by the instruction.
     */
    private static class OperandResult {
        int value;
        int address;

        OperandResult(int value, int address) {
            this.value = value;
            this.address = address;
        }
    }

    private OperandResult fetchOperand(AddressingMode mode) {
        // Reset tracking antes de cada novo fetch
        lastPageCrossed = false;
        switch (mode) {
            case IMMEDIATE:
                return new OperandResult(memory.read(pc++), -1);
            case ZERO_PAGE: {
                int addr = memory.read(pc) & 0xFF;
                int value = memory.read(addr);
                pc++;
                return new OperandResult(value, addr);
            }
            case ZERO_PAGE_X: {
                int addr = (memory.read(pc++) + x) & 0xFF;
                return new OperandResult(memory.read(addr), addr);
            }
            case ZERO_PAGE_Y: {
                int addr = (memory.read(pc++) + y) & 0xFF;
                return new OperandResult(memory.read(addr), addr);
            }
            case ABSOLUTE: {
                int lo = memory.read(pc++);
                int hi = memory.read(pc++);
                int addr = (hi << 8) | lo;
                return new OperandResult(memory.read(addr), addr);
            }
            case ABSOLUTE_X: {
                int lo = memory.read(pc++);
                int hi = memory.read(pc++);
                int base = (hi << 8) | lo;
                int addr = (base + x) & 0xFFFF;
                if (((base ^ addr) & 0xFF00) != 0)
                    lastPageCrossed = true;
                return new OperandResult(memory.read(addr), addr);
            }
            case ABSOLUTE_Y: {
                int lo = memory.read(pc++);
                int hi = memory.read(pc++);
                int base = (hi << 8) | lo;
                int addr = (base + y) & 0xFFFF;
                return new OperandResult(memory.read(addr), addr);
            }
            case INDIRECT: {
                int ptr = memory.read(pc++) | (memory.read(pc++) << 8);
                int lo = memory.read(ptr);
                int hi = memory.read((ptr & 0xFF00) | ((ptr + 1) & 0xFF));
                int addr = lo | (hi << 8);
                return new OperandResult(addr, addr);
            }
            case INDIRECT_X: {
                int zp = (memory.read(pc++) + x) & 0xFF;
                int lo = memory.read(zp);
                int hi = memory.read((zp + 1) & 0xFF);
                int addr = lo | (hi << 8);
                return new OperandResult(memory.read(addr), addr);
            }
            case INDIRECT_Y: {
                int zp = memory.read(pc++) & 0xFF;
                int lo = memory.read(zp);
                int hi = memory.read((zp + 1) & 0xFF);
                int base = (hi << 8) | lo;
                int addr = (base + y) & 0xFFFF;
                return new OperandResult(memory.read(addr), addr);
            }
            case RELATIVE:
                return new OperandResult(memory.read(pc++), -1);
            case ACCUMULATOR:
                return new OperandResult(a, -1);
            case IMPLIED:
            default:
                return new OperandResult(0, -1);
        }
    }

    // Dispatcher de execução
    private void execute(Opcode opcode, AddressingMode mode, int operand, int memAddr) {
        // memAddr já é passado de clock(), evitando dupla leitura
        switch (opcode) {
            // --- Official NES opcodes ---
            case ADC:
                int value = operand & 0xFF;
                int acc = a & 0xFF;
                int carryIn = carry ? 1 : 0;
                int result = acc + value + carryIn;

                // Carry flag: set if result >= 0x100
                carry = result >= 0x100;

                // Overflow flag (bit 7 change unexpectedly)
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
                } else if (memAddr != -1) {
                    int valueASL = operand & 0xFF;
                    carry = (valueASL & 0x80) != 0;
                    valueASL = (valueASL << 1) & 0xFF;
                    setZeroAndNegative(valueASL);
                    memory.write(memAddr, valueASL);
                }
                break;
            case BCC: {
                if (!carry) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1; // branch taken
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1; // page cross
                }
                break;
            }
            case BCS: {
                if (carry) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
            case BEQ: {
                if (zero) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
            case BIT:
                int bitResult = a & (operand & 0xFF);
                zero = (bitResult == 0);
                negative = ((operand & 0x80) != 0);
                overflow = ((operand & 0x40) != 0);
                break;
            case BMI: {
                if (negative) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
            case BNE: {
                if (!zero) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
            case BPL: {
                if (!negative) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
            case BRK:
                pc = (pc + 1) & 0xFFFF; // BRK increments PC by 2 (already incremented by 1 in clock)
                push((pc >> 8) & 0xFF); // Push PCH
                push(pc & 0xFF); // Push PCL
                breakFlag = true;
                push(getStatusByte() | 0x10); // Push status with B flag set
                interruptDisable = true;
                // Set PC to IRQ/BRK vector
                int lo = memory.read(0xFFFE);
                int hi = memory.read(0xFFFF);
                pc = (hi << 8) | lo;
                break;
            case BVC: {
                if (!overflow) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
            case BVS: {
                if (overflow) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = pc;
                    pc = (pc + offset) & 0xFFFF;
                    extraCycles += 1;
                    if ((oldPC & 0xFF00) != (pc & 0xFF00))
                        extraCycles += 1;
                }
                break;
            }
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
                if (memAddr != -1) {
                    int decValue = (operand - 1) & 0xFF;
                    setZeroAndNegative(decValue);
                    memory.write(memAddr, decValue);
                }
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
                if (memAddr != -1) {
                    int incValue = (operand + 1) & 0xFF;
                    setZeroAndNegative(incValue);
                    memory.write(memAddr, incValue);
                }
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
                // Para JMP absoluto precisamos usar o endereço calculado (memAddr), não o valor
                // lido.
                // Para JMP indireto (modo INDIRECT) memAddr também contém o destino correto.
                pc = memAddr & 0xFFFF;
                break;
            case JSR:
                // JSR: Jump to SubRoutine
                // Push (PC-1) onto stack (high byte first, then low byte) - 6502 real behavior
                int returnAddr = (pc - 1) & 0xFFFF;
                System.err.printf("[JSR] PC=%04X, returnAddr=%04X, push high=%02X, low=%02X, SP=%02X\n", pc, returnAddr,
                        (returnAddr >> 8) & 0xFF, returnAddr & 0xFF, sp);
                push((returnAddr >> 8) & 0xFF); // High byte
                push(returnAddr & 0xFF); // Low byte
                pc = memAddr & 0xFFFF;
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
                } else if (memAddr != -1) {
                    int valueLSR = operand & 0xFF;
                    carry = (valueLSR & 0x01) != 0;
                    valueLSR = (valueLSR >> 1) & 0xFF;
                    setZeroAndNegative(valueLSR);
                    memory.write(memAddr, valueLSR);
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
                } else if (memAddr != -1) {
                    int valueROL = operand & 0xFF;
                    boolean oldCarry = carry;
                    carry = (valueROL & 0x80) != 0;
                    valueROL = ((valueROL << 1) | (oldCarry ? 1 : 0)) & 0xFF;
                    setZeroAndNegative(valueROL);
                    memory.write(memAddr, valueROL);
                }
                break;
            case ROR:
                if (mode == AddressingMode.ACCUMULATOR) {
                    boolean oldCarry = carry;
                    carry = (a & 0x01) != 0;
                    a = ((a >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                    setZeroAndNegative(a);
                } else if (memAddr != -1) {
                    int valueROR = operand & 0xFF;
                    boolean oldCarry = carry;
                    carry = (valueROR & 0x01) != 0;
                    valueROR = ((valueROR >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                    setZeroAndNegative(valueROR);
                    memory.write(memAddr, valueROR);
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
                int retAddr = ((pch_rts << 8) | pcl_rts);
                System.err.printf("[RTS] pop low=%02X, high=%02X, retAddr=%04X, PC=%04X, SP=%02X\n", pcl_rts, pch_rts,
                        retAddr, pc, sp);
                pc = (retAddr + 1) & 0xFFFF;
                break;
            case SBC:
                // SBC: Subtract with Carry
                int valueSBC = operand & 0xFF;
                int accSBC = a & 0xFF;
                int carryInSBC = carry ? 1 : 0;
                int resultSBC = accSBC - valueSBC - (1 - carryInSBC);
                carry = resultSBC >= 0;
                // Overflow if sign bit of (A ^ result) and (A ^ operand) are both set (operand
                // here effectively added as two's complement)
                overflow = (((accSBC ^ resultSBC) & (accSBC ^ valueSBC) & 0x80) != 0);
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
                if (memAddr != -1) {
                    memory.write(memAddr, a & 0xFF);
                }
                break;
            case STX:
                if (memAddr != -1) {
                    memory.write(memAddr, x & 0xFF);
                }
                break;
            case STY:
                if (memAddr != -1) {
                    memory.write(memAddr, y & 0xFF);
                }
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
            case SAX:
                // AAX (SAX): Store A & X to memory
                if (memAddr != -1) {
                    int val = a & x;
                    memory.write(memAddr, val);
                }
                break;
            case AHX:
            case SHA:
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int value_r = a & x & (high & 0xFF);
                    memory.write(memAddr, value_r);
                }
                break;
            case ALR:
            case ASR:
                // ALR / ASR (opcode 0x4B): A = (A & operand) >> 1 ; Carry = bit0 antes do shift
                a = a & (operand & 0xFF);
                carry = (a & 0x01) != 0;
                a = (a >> 1) & 0xFF;
                setZeroAndNegative(a);
                break;
            case ARR:
                // ARR: AND operand with A, then ROR, set flags
                a = a & (operand & 0xFF);
                a = ((a >> 1) | (carry ? 0x80 : 0)) & 0xFF;
                setZeroAndNegative(a);
                carry = (a & 0x40) != 0;
                overflow = (((a >> 5) & 1) ^ ((a >> 6) & 1)) != 0;
                break;
            case AXA:
                // AXA: Store (A & X) & (high byte of address + 1) to memory
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int axaValue = (a & x) & (high & 0xFF);
                    memory.write(memAddr, axaValue);
                }
                break;
            case AXS:
            case SBX:
                // SBX/AXS: X = (A & X_original) - operand ; Carry = no borrow
                int xOrig = x & 0xFF;
                int maskAX = (a & xOrig) & 0xFF;
                int subtrahend = operand & 0xFF;
                int resultAXS = (maskAX - subtrahend) & 0x1FF; // preserve borrow in bit 8
                carry = maskAX >= subtrahend; // 6502 style: carry = no borrow
                x = resultAXS & 0xFF;
                setZeroAndNegative(x);
                break;
            case DCP:
                // DCP: DEC memory, then CMP with A
                if (memAddr != -1) {
                    int dcpValue = (operand - 1) & 0xFF;
                    memory.write(memAddr, dcpValue);
                    int dcpCmp = a - dcpValue;
                    carry = (a & 0xFF) >= dcpValue;
                    zero = (dcpCmp & 0xFF) == 0;
                    negative = (dcpCmp & 0x80) != 0;
                }
                break;
            case DOP:
                // DOP: Double NOP (2-byte) - operand já consumido conforme addressing mode.
                break;
            case TOP:
                // TOP: Triple NOP (abs ou abs,X). Se abs,X e houve page crossing detectado no
                // fetch, adiciona 1 ciclo.
                if (mode == AddressingMode.ABSOLUTE_X && lastPageCrossed) {
                    extraCycles += 1;
                }
                break;
            case ISC:
                // ISC: INC memory, then SBC with A
                if (memAddr != -1) {
                    int iscValue = (operand + 1) & 0xFF;
                    memory.write(memAddr, iscValue);
                    // Now SBC: A = A - iscValue - (1 - carry)
                    int sbcVal = iscValue ^ 0xFF;
                    int accIsc = a & 0xFF;
                    int carryInIsc = carry ? 1 : 0;
                    int resultIsc = accIsc + sbcVal + carryInIsc;
                    carry = resultIsc > 0xFF;
                    overflow = ((accIsc ^ resultIsc) & (sbcVal ^ resultIsc) & 0x80) != 0;
                    a = resultIsc & 0xFF;
                    setZeroAndNegative(a);
                }
                break;
            case KIL:
                // KIL: Halts CPU (simulate by not advancing PC)
                pc = (pc - 1) & 0xFFFF;
                break;
            case LAR:
            case LAS:
                // Alias LAR / LAS: adotamos semântica de LAS (SP = A & X & mem; A = X = SP)
                // (LAR tradicional = A & mem; se quiser comport. distinto, separar novamente)
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
                if (memAddr != -1) {
                    int rlaValue = ((operand << 1) | (carry ? 1 : 0)) & 0xFF;
                    carry = (operand & 0x80) != 0;
                    memory.write(memAddr, rlaValue);
                    a = a & rlaValue;
                    setZeroAndNegative(a);
                }
                break;
            case RRA:
                // RRA: ROR memory, then ADC with A
                if (memAddr != -1) {
                    int rraValue = ((operand >> 1) | (carry ? 0x80 : 0)) & 0xFF;
                    carry = (operand & 0x01) != 0;
                    memory.write(memAddr, rraValue);
                    int adcVal = rraValue;
                    int accRra = a & 0xFF;
                    int carryInRra = carry ? 1 : 0;
                    int resultRra = accRra + adcVal + carryInRra;
                    carry = resultRra > 0xFF;
                    overflow = (~(accRra ^ adcVal) & (accRra ^ resultRra) & 0x80) != 0;
                    a = resultRra & 0xFF;
                    setZeroAndNegative(a);
                }
                break;
            case SHS:
            case TAS:
                // SHS/TAS: SP = A & X; store (A & X & (high byte of address + 1)) at effective
                // address (ABSOLUTE_Y / INDIRECT_Y)
                sp = a & x;
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int storeVal = (a & x) & (high & 0xFF);
                    memory.write(memAddr, storeVal);
                }
                break;
            case SHX:
                // SHX: Store X & (high byte of address + 1) to memory
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int shxValue = x & (high & 0xFF);
                    memory.write(memAddr, shxValue);
                }
                break;
            case SHY:
                // SHY: Store Y & (high byte of address + 1) to memory
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_X || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int shyValue = y & (high & 0xFF);
                    memory.write(memAddr, shyValue);
                }
                break;
            case SLO:
                // SLO: ASL value in memory, then ORA with A
                if (memAddr != -1) {
                    int sloValue = operand & 0xFF;
                    carry = (sloValue & 0x80) != 0;
                    sloValue = (sloValue << 1) & 0xFF;
                    memory.write(memAddr, sloValue);
                    a = a | sloValue;
                    setZeroAndNegative(a);
                }
                break;
            case SRE:
                // SRE: LSR value in memory, then EOR with A
                if (memAddr != -1) {
                    int sreValue = operand & 0xFF;
                    carry = (sreValue & 0x01) != 0;
                    sreValue = (sreValue >> 1) & 0xFF;
                    memory.write(memAddr, sreValue);
                    a = a ^ sreValue;
                    setZeroAndNegative(a);
                }
                break;
            case XAA:
                // XAA (unofficial): A = (A & X) & operand
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
     * The stack is located at 0x0100 in memory, and the stack pointer (SP) is
     * decremented before writing.
     * 
     * @param value
     */
    private void push(int value) {
        memory.write(0x100 + (sp & 0xFF), value & 0xFF);
        sp = (sp - 1) & 0xFF;
    }

    /**
     * Pops a value from the stack.
     * The stack is located at 0x0100 in memory, and the stack pointer (SP) is
     * incremented after reading.
     * 
     * @return
     */
    private int pop() {
        sp = (sp + 1) & 0xFF;
        return memory.read(0x100 + (sp & 0xFF));
    }

    /**
     * Sets the zero and negative flags based on the value.
     * This method updates the zero and negative flags based on the provided value.
     * 
     * @return
     */
    int getStatusByte() {
        int p = 0;
        if (carry)
            p |= 0x01;
        if (zero)
            p |= 0x02;
        if (interruptDisable)
            p |= 0x04;
        if (decimal)
            p |= 0x08;
        if (breakFlag)
            p |= 0x10;
        if (unused)
            p |= 0x20;
        if (overflow)
            p |= 0x40;
        if (negative)
            p |= 0x80;
        return p;
    }

    /**
     * Sets the status flags based on the provided byte value.
     * This method updates the processor status flags based on the provided byte
     * value.
     * 
     * @param value
     */
    void setStatusByte(int value) {
        carry = (value & 0x01) != 0;
        zero = (value & 0x02) != 0;
        interruptDisable = (value & 0x04) != 0;
        decimal = (value & 0x08) != 0;
        breakFlag = (value & 0x10) != 0;
        unused = true; // Bit 5 do status é sempre setado no 6502
        overflow = (value & 0x40) != 0;
        negative = (value & 0x80) != 0;
    }

    // Getters and Setters
    public int getA() {
        return a;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getSP() {
        return sp;
    }

    public int getPC() {
        return pc;
    }

    public int getCycles() {
        return cycles;
    }

    public boolean isCarry() {
        return carry;
    }

    public boolean isZero() {
        return zero;
    }

    public boolean isInterruptDisable() {
        return interruptDisable;
    }

    public boolean isDecimal() {
        return decimal;
    }

    public boolean isBreakFlag() {
        return breakFlag;
    }

    public boolean isUnused() {
        return unused;
    }

    public boolean isOverflow() {
        return overflow;
    }

    public boolean isNegative() {
        return negative;
    }

    public void setA(int a) {
        this.a = a & 0xFF;
        setZeroAndNegative(this.a);
    }

    public void setX(int x) {
        this.x = x & 0xFF;
        setZeroAndNegative(this.x);
    }

    public void setY(int y) {
        this.y = y & 0xFF;
        setZeroAndNegative(this.y);
    }

    public void setSP(int sp) {
        this.sp = sp & 0xFF;
        setZeroAndNegative(this.sp);
    }

    public void setPC(int pc) {
        this.pc = pc & 0xFFFF;
    }

    public void setCarry(boolean carry) {
        this.carry = carry;
    }

    public void setZero(boolean zero) {
        this.zero = zero;
    }

    public void setInterruptDisable(boolean interruptDisable) {
        this.interruptDisable = interruptDisable;
    }

    public void setDecimal(boolean decimal) {
        this.decimal = decimal;
    }

    public void setBreakFlag(boolean breakFlag) {
        this.breakFlag = breakFlag;
    }

    public void setUnused(boolean unused) {
        this.unused = unused;
    }

    public void setOverflow(boolean overflow) {
        this.overflow = overflow;
    }

    public void setNegative(boolean negative) {
        this.negative = negative;
    }
}