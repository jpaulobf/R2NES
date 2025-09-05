package com.nesemu.cpu;

/**
 * Enum representing the various addressing modes used in the NES CPU.
 */
public enum AddressingMode {
    IMMEDIATE,
    ZERO_PAGE,
    ZERO_PAGE_X,
    ZERO_PAGE_Y,
    ABSOLUTE,
    ABSOLUTE_X,
    ABSOLUTE_Y,
    INDIRECT,
    INDIRECT_X,
    INDIRECT_Y,
    ACCUMULATOR,
    IMPLIED,
    RELATIVE;

    /**
     * Gets the current status byte of the CPU.
     * 
     * @return The status byte with the current flags.
     */
    public static AddressingMode getAddressingMode(int opcodeByte) {
        switch (opcodeByte) {
            // --- Immediate (#) ---
            case 0xA9: // LDA #imm
            case 0xA2: // LDX #imm
            case 0xA0: // LDY #imm
            case 0x69: // ADC #imm
            case 0x29: // AND #imm
            case 0x0B: // ANC/AAC #imm (illegal)
            case 0x2B: // ANC/AAC #imm (illegal)
            case 0xAB: // LXA #imm (illegal)
            case 0xC9: // CMP #imm
            case 0xE0: // CPX #imm
            case 0xC0: // CPY #imm
            case 0x49: // EOR #imm
            case 0x09: // ORA #imm
            case 0xE9: // SBC #imm
            case 0xEB: // SBC #imm (illegal immediate variant)
            case 0x4B: // ALR #imm (illegal)
            case 0x6B: // ARR #imm (illegal)
            case 0xCB: // AXS #imm (illegal)
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
            case 0x47: // SRE zp (illegal)
            case 0x07: // SLO zp (illegal)
            case 0x67: // RRA zp (illegal)
            case 0x27: // RLA zp (illegal)
            case 0x85: // STA zp
            case 0x86: // STX zp
            case 0x84: // STY zp
            case 0x87: // SAX/AAX zp (illegal)
            case 0xA7: // LAX zp (illegal)
            case 0x24: // BIT zp
            case 0xC6: // DEC zp
            case 0xE6: // INC zp
            case 0xC7: // DCP zp (illegal)
            case 0xE7: // ISC zp (illegal)
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
            case 0x57: // SRE zp,X (illegal)
            case 0x17: // SLO zp,X (illegal)
            case 0x77: // RRA zp,X (illegal)
            case 0x37: // RLA zp,X (illegal)
            case 0x95: // STA zp,X
            case 0x94: // STY zp,X
            case 0xD6: // DEC zp,X
            case 0xF6: // INC zp,X
            case 0xD7: // DCP zp,X (illegal)
            case 0xF7: // ISC zp,X (illegal)
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
            case 0x97: // SAX/AAX zp,Y (illegal)
            case 0xB7: // LAX zp,Y (illegal)
                return AddressingMode.ZERO_PAGE_Y;

            // --- Absolute ---
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
            case 0x6F: // RRA abs (illegal)
            case 0x2F: // RLA abs (illegal)
            case 0x4F: // SRE abs (illegal)
            case 0x0F: // SLO abs (illegal)
            case 0x8D: // STA abs
            case 0x8E: // STX abs
            case 0x8C: // STY abs
            case 0x8F: // SAX/AAX abs (illegal)
            case 0x2C: // BIT abs
            case 0xCE: // DEC abs
            case 0xEE: // INC abs
            case 0xCF: // DCP abs (illegal)
            case 0xEF: // ISC abs (illegal)
            case 0xAF: // LAX abs (illegal)
                return AddressingMode.ABSOLUTE;

            // --- Absolute,X ---
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
            case 0x7F: // RRA abs,X (illegal)
            case 0x3F: // RLA abs,X (illegal)
            case 0x5F: // SRE abs,X (illegal)
            case 0x1F: // SLO abs,X (illegal)
            case 0x9D: // STA abs,X
            case 0x9C: // SHY abs,X (ilegal)
            case 0xDE: // DEC abs,X
            case 0xFE: // INC abs,X
            case 0xDF: // DCP abs,X (illegal)
            case 0xFF: // ISC abs,X (illegal)
                return AddressingMode.ABSOLUTE_X;

            // --- Absolute,Y ---
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
            case 0x7B: // RRA abs,Y (illegal)
            case 0x9F: // AHX abs,Y (illegal)
            case 0x9E: // SHX abs,Y (illegal)
            case 0x5B: // SRE abs,Y (illegal)
            case 0x1B: // SLO abs,Y (illegal)
            case 0xDB: // DCP abs,Y (illegal)
            case 0xFB: // ISC abs,Y (illegal)
            case 0xBF: // LAX abs,Y (illegal)
            case 0xBB: // LAS abs,Y (illegal)
            case 0x3B: // RLA abs,Y (illegal)
                return AddressingMode.ABSOLUTE_Y;

            // --- Indirect ---
            case 0x6C: // JMP (abs)
                return AddressingMode.INDIRECT;

            // --- Indirect,X ---
            case 0xA1: // LDA (zp,X)
            case 0x61: // ADC (zp,X)
            case 0x21: // AND (zp,X)
            case 0xC1: // CMP (zp,X)
            case 0x41: // EOR (zp,X)
            case 0x01: // ORA (zp,X)
            case 0xE1: // SBC (zp,X)
            case 0x81: // STA (zp,X)
            case 0x83: // SAX/AAX (zp,X) (illegal)
            case 0x43: // SRE (zp,X) illegal
            case 0x03: // SLO (zp,X) illegal
            case 0xC3: // DCP (zp,X) illegal
            case 0xE3: // ISC (zp,X) illegal
            case 0xA3: // LAX (zp,X) illegal
            case 0x63: // RRA (zp,X) illegal
            case 0x23: // RLA (zp,X) illegal
                return AddressingMode.INDIRECT_X;

            // --- Indirect,Y ---
            case 0xB1: // LDA (zp),Y
            case 0x71: // ADC (zp),Y
            case 0x31: // AND (zp),Y
            case 0xD1: // CMP (zp),Y
            case 0x51: // EOR (zp),Y
            case 0x11: // ORA (zp),Y
            case 0xF1: // SBC (zp),Y
            case 0x91: // STA (zp),Y
            case 0x93: // AHX (zp),Y (illegal)
            case 0x53: // SRE (zp),Y illegal
            case 0x13: // SLO (zp),Y illegal
            case 0xD3: // DCP (zp),Y illegal
            case 0xF3: // ISC (zp),Y illegal
            case 0xB3: // LAX (zp),Y illegal
            case 0x73: // RRA (zp),Y illegal
            case 0x33: // RLA (zp),Y illegal
                return AddressingMode.INDIRECT_Y;

            // --- Relative (branches) ---
            case 0x10: // BPL
            case 0x30: // BMI
            case 0x50: // BVC
            case 0x70: // BVS
            case 0x90: // BCC
            case 0xB0: // BCS
            case 0xD0: // BNE
            case 0xF0: // BEQ
                return AddressingMode.RELATIVE;

            // --- Accumulator ---
            case 0x0A: // ASL A
            case 0x4A: // LSR A
            case 0x2A: // ROL A
            case 0x6A: // ROR A
                return AddressingMode.ACCUMULATOR;

            // --- Implied ---
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
}
