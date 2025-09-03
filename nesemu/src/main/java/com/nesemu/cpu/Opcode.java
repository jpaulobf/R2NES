package com.nesemu.cpu;

/**
 * Enumeration representing the operation codes (opcodes) of the NES.
 * Each opcode corresponds to a specific instruction that the CPU can execute.
 */
public enum Opcode {
    ADC, AND, ASL, BCC, BCS, BEQ, BIT, BMI, BNE, BPL, BRK, BVC, BVS,
    CLC, CLD, CLI, CLV, CMP, CPX, CPY,
    DEC, DEX, DEY,
    EOR,
    INC, INX, INY,
    JMP, JSR,
    LDA, LDX, LDY, LSR,
    NOP,
    ORA,
    PHA, PHP, PLA, PLP,
    ROL, ROR, RTI, RTS,
    SBC, SEC, SED, SEI, STA, STX, STY,
    TAX, TAY, TSX, TXA, TXS, TYA,

    // Most common undocumented (illegal) instructions:
    AAC, AAX, AHX, ALR, ANC, ARR, ASR, AXA, AXS, DCP, DOP, ISC, KIL, LAR, LAS, LAX, LXA, RLA, RRA, SAX, SBX, SHA,
    SHS, SHX, SHY, SLO, SRE, TAS, TOP, XAA;

    // Utility: maps a name to the Opcode (useful for decoding)
    public static Opcode fromName(String name) {
        try {
            return Opcode.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Opcode fromByte(int opcodeByte) {
        if (opcodeByte < 0 || opcodeByte > 0xFF)
            return null;
        return OPCODE_TABLE[opcodeByte];
    }

    /**
     * Determines if the opcode is a store instruction (which do not require a final
     * read during operand fetch).
     * @param opcodeByte
     * @return
     */
    static boolean isStoreOpcode(int opcodeByte) {
        switch (opcodeByte & 0xFF) {
            case 0x85:
            case 0x95:
            case 0x8D:
            case 0x9D:
            case 0x99:
            case 0x81:
            case 0x91: // STA variants
            case 0x86:
            case 0x96:
            case 0x8E: // STX
            case 0x84:
            case 0x94:
            case 0x8C: // STY
            case 0x87:
            case 0x97:
            case 0x8F: // SAX/AAX
            case 0x9E:
            case 0x9C: // SHX/SHY
            case 0x9A: // TXS (not memory store but does not need operand read; harmless)
            case 0x9B:
            case 0x9F:
            case 0x93: // SHS/TAS, AHX, AHX (zp),Y
            case 0x83: // SAX (zp,X)
                return true;
            default:
                return false;
        }
    }

    private static final Opcode[] OPCODE_TABLE = new Opcode[256];
    static {
        for (int i = 0; i < 256; i++)
            OPCODE_TABLE[i] = NOP;
        OPCODE_TABLE[0x00] = BRK; // Break
        OPCODE_TABLE[0x01] = ORA; // ORA (Indirect, X)
        OPCODE_TABLE[0x02] = KIL; // 0x02 é JAM/KIL no hardware real
        OPCODE_TABLE[0x03] = SLO; // Shift Left, ORA (Indirect, X)
        OPCODE_TABLE[0x04] = DOP; // Double Operand
        OPCODE_TABLE[0x05] = ORA; // ORA (Zero Page)
        OPCODE_TABLE[0x06] = ASL; // Arithmetic Shift Left
        OPCODE_TABLE[0x07] = SLO; // Shift Left, ORA (Zero Page)
        OPCODE_TABLE[0x08] = PHP; // Push Processor Status
        OPCODE_TABLE[0x09] = ORA; // ORA (Immediate)
        OPCODE_TABLE[0x0A] = ASL; // Arithmetic Shift Left
        OPCODE_TABLE[0x0B] = AAC; // AAC/ANC undocumented immediate
        OPCODE_TABLE[0x0C] = TOP; // Top of Page
        OPCODE_TABLE[0x0D] = ORA; // ORA (Absolute)
        OPCODE_TABLE[0x0E] = ASL; // Arithmetic Shift Left
        OPCODE_TABLE[0x0F] = SLO; // Shift Left, ORA (Absolute)
        OPCODE_TABLE[0x10] = BPL; // Branch if Positive
        OPCODE_TABLE[0x11] = ORA; // ORA (Indirect, Y)
        OPCODE_TABLE[0x12] = KIL; // Illegal opcode
        OPCODE_TABLE[0x13] = SLO; // Shift Left, ORA (Indirect, Y)
        OPCODE_TABLE[0x14] = DOP; // Double Operand
        OPCODE_TABLE[0x15] = ORA; // ORA (Zero Page, X)
        OPCODE_TABLE[0x16] = ASL; // Arithmetic Shift Left
        OPCODE_TABLE[0x17] = SLO; // Shift Left, ORA (Zero Page, X)
        OPCODE_TABLE[0x18] = CLC; // Clear Carry Flag
        OPCODE_TABLE[0x19] = ORA; // ORA (Absolute, Y)
        OPCODE_TABLE[0x1A] = NOP; // No Operation
        OPCODE_TABLE[0x1B] = SLO; // Shift Left, ORA (Absolute, Y)
        OPCODE_TABLE[0x1C] = TOP; // Top of Page
        OPCODE_TABLE[0x1D] = ORA; // ORA (Absolute, X)
        OPCODE_TABLE[0x1E] = ASL; // Arithmetic Shift Left
        OPCODE_TABLE[0x1F] = SLO; // Shift Left, ORA (Absolute, X)
        OPCODE_TABLE[0x20] = JSR; // Jump to Subroutine
        OPCODE_TABLE[0x21] = AND; // AND (Indirect, X)
        OPCODE_TABLE[0x22] = KIL; // Illegal opcode
        OPCODE_TABLE[0x23] = RLA; // Rotate Left, AND (Indirect, X)
        OPCODE_TABLE[0x24] = BIT; // Bit Test (Zero Page)
        OPCODE_TABLE[0x25] = AND; // AND (Zero Page)
        OPCODE_TABLE[0x26] = ROL; // Rotate Left
        OPCODE_TABLE[0x27] = RLA; // Rotate Left, AND (Zero Page)
        OPCODE_TABLE[0x28] = PLP; // Pull Processor Status
        OPCODE_TABLE[0x29] = AND; // AND (Immediate)
        OPCODE_TABLE[0x2A] = ROL; // Rotate Left
        OPCODE_TABLE[0x2B] = AAC; // AAC/ANC undocumented immediate
        OPCODE_TABLE[0x2C] = BIT; // Bit Test (Absolute)
        OPCODE_TABLE[0x2D] = AND; // AND (Absolute)
        OPCODE_TABLE[0x2E] = ROL; // Rotate Left
        OPCODE_TABLE[0x2F] = RLA; // Rotate Left, AND (Absolute)
        OPCODE_TABLE[0x30] = BMI; // Branch if Minus
        OPCODE_TABLE[0x31] = AND; // AND (Indirect, Y)
        OPCODE_TABLE[0x32] = KIL; // Illegal opcode
        OPCODE_TABLE[0x33] = RLA; // Rotate Left, AND (Indirect, Y)
        OPCODE_TABLE[0x34] = DOP; // Double Operand
        OPCODE_TABLE[0x35] = AND; // AND (Zero Page, X)
        OPCODE_TABLE[0x36] = ROL; // Rotate Left
        OPCODE_TABLE[0x37] = RLA; // Rotate Left, AND (Zero Page, X)
        OPCODE_TABLE[0x38] = SEC; // Set Carry Flag
        OPCODE_TABLE[0x39] = AND; // AND (Absolute, Y)
        OPCODE_TABLE[0x3A] = NOP; // No Operation
        OPCODE_TABLE[0x3B] = RLA; // Rotate Left, AND (Absolute, Y)
        OPCODE_TABLE[0x3C] = TOP; // Top of Page
        OPCODE_TABLE[0x3D] = AND; // AND (Absolute, X)
        OPCODE_TABLE[0x3E] = ROL; // Rotate Left
        OPCODE_TABLE[0x3F] = RLA; // Rotate Left, AND (Absolute, X)
        OPCODE_TABLE[0x40] = RTI; // Return from Interrupt
        OPCODE_TABLE[0x41] = EOR; // EOR (Indirect, X)
        OPCODE_TABLE[0x42] = KIL; // Illegal opcode
        OPCODE_TABLE[0x43] = SRE; // Shift Right, EOR (Indirect, X)
        OPCODE_TABLE[0x44] = DOP; // Double Operand
        OPCODE_TABLE[0x45] = EOR; // EOR (Zero Page)
        OPCODE_TABLE[0x46] = LSR; // Logical Shift Right
        OPCODE_TABLE[0x47] = SRE; // Shift Right, EOR (Zero Page)
        OPCODE_TABLE[0x48] = PHA; // Push Accumulator
        OPCODE_TABLE[0x49] = EOR; // EOR (Immediate)
        OPCODE_TABLE[0x4A] = LSR; // Logical Shift Right
        // 0x4B é o opcode ilegal conhecido como ALR/ASR (mesmo comportamento). Mapeamos
        // para ASR para exercitar o case ASR.
        OPCODE_TABLE[0x4B] = ASR; // ALR/ASR (Immediate)
        OPCODE_TABLE[0x4C] = JMP; // Jump to Address
        OPCODE_TABLE[0x4D] = EOR; // EOR (Absolute)
        OPCODE_TABLE[0x4E] = LSR; // Logical Shift Right
        OPCODE_TABLE[0x4F] = SRE; // Shift Right, EOR (Absolute)
        OPCODE_TABLE[0x50] = BVC; // Branch if Overflow Clear
        OPCODE_TABLE[0x51] = EOR; // EOR (Indirect, Y)
        OPCODE_TABLE[0x52] = KIL; // Illegal opcode
        OPCODE_TABLE[0x53] = SRE; // Shift Right, EOR (Indirect, Y)
        OPCODE_TABLE[0x54] = DOP; // Double Operand
        OPCODE_TABLE[0x55] = EOR; // EOR (Zero Page, X)
        OPCODE_TABLE[0x56] = LSR; // Logical Shift Right
        OPCODE_TABLE[0x57] = SRE; // Shift Right, EOR (Zero Page, X)
        OPCODE_TABLE[0x58] = CLI; // Clear Interrupt Disable Flag
        OPCODE_TABLE[0x59] = EOR; // EOR (Absolute, Y)
        OPCODE_TABLE[0x5A] = NOP; // No Operation
        OPCODE_TABLE[0x5B] = SRE; // Shift Right, EOR (Absolute, Y)
        OPCODE_TABLE[0x5C] = TOP; // Top of Page
        OPCODE_TABLE[0x5D] = EOR; // EOR (Absolute, X)
        OPCODE_TABLE[0x5E] = LSR; // Logical Shift Right
        OPCODE_TABLE[0x5F] = SRE; // Shift Right, EOR (Absolute, X)
        OPCODE_TABLE[0x60] = RTS; // Return from Subroutine
        OPCODE_TABLE[0x61] = ADC; // ADC (Indirect, X)
        OPCODE_TABLE[0x62] = KIL; // Illegal opcode
        OPCODE_TABLE[0x63] = RRA; // Rotate Right, ADC (Indirect, X)
        OPCODE_TABLE[0x64] = DOP; // Double Operand
        OPCODE_TABLE[0x65] = ADC; // ADC (Zero Page)
        OPCODE_TABLE[0x66] = ROR; // Rotate Right
        OPCODE_TABLE[0x67] = RRA; // Rotate Right, ADC (Zero Page)
        OPCODE_TABLE[0x68] = PLA; // Pull Accumulator
        OPCODE_TABLE[0x69] = ADC; // ADC (Immediate)
        OPCODE_TABLE[0x6A] = ROR; // Rotate Right
        OPCODE_TABLE[0x6B] = ARR; // AND with Rotate Right, ADC (Immediate)
        OPCODE_TABLE[0x6C] = JMP; // Jump to Address (Indirect)
        OPCODE_TABLE[0x6D] = ADC; // ADC (Absolute)
        OPCODE_TABLE[0x6E] = ROR; // Rotate Right (Absolute)
        OPCODE_TABLE[0x6F] = RRA; // Rotate Right, ADC (Absolute)
        OPCODE_TABLE[0x70] = BVS; // Branch if Overflow Set
        OPCODE_TABLE[0x71] = ADC; // ADC (Indirect, Y)
        OPCODE_TABLE[0x72] = KIL; // Illegal opcode
        OPCODE_TABLE[0x73] = RRA; // Rotate Right, ADC (Indirect, Y)
        OPCODE_TABLE[0x74] = DOP; // Double Operand
        OPCODE_TABLE[0x75] = ADC; // ADC (Zero Page, X)
        OPCODE_TABLE[0x76] = ROR; // Rotate Right (Zero Page, X)
        OPCODE_TABLE[0x77] = RRA; // Rotate Right, ADC (Zero Page, X)
        OPCODE_TABLE[0x78] = SEI; // Set Interrupt Disable
        OPCODE_TABLE[0x79] = ADC; // ADC (Absolute, Y)
        OPCODE_TABLE[0x7A] = NOP; // No Operation
        OPCODE_TABLE[0x7B] = RRA; // Rotate Right, ADC (Absolute, Y)
        OPCODE_TABLE[0x7C] = TOP; // Top of Page
        OPCODE_TABLE[0x7D] = ADC; // ADC (Absolute, X)
        OPCODE_TABLE[0x7E] = ROR; // Rotate Right (Absolute, X)
        OPCODE_TABLE[0x7F] = RRA; // Rotate Right, ADC (Absolute, X)
        OPCODE_TABLE[0x80] = DOP; // Double Operand
        OPCODE_TABLE[0x81] = STA; // STA (Indirect, X)
        OPCODE_TABLE[0x82] = DOP; // Double Operand
        // Map SAX variants to AAX to exercise CPU case AAX
        OPCODE_TABLE[0x83] = AAX; // AAX/SAX (Indirect, X)
        OPCODE_TABLE[0x84] = STY; // STY (Zero Page)
        OPCODE_TABLE[0x85] = STA; // STA (Zero Page)
        OPCODE_TABLE[0x86] = STX; // STX (Zero Page)
        OPCODE_TABLE[0x87] = AAX; // AAX/SAX (Zero Page)
        OPCODE_TABLE[0x88] = DEY; // Decrement Y
        OPCODE_TABLE[0x89] = DOP; // Double Operand
        OPCODE_TABLE[0x8A] = TXA; // Transfer X to A
        OPCODE_TABLE[0x8B] = XAA; // AND A with X and Immediate (Illegal)
        OPCODE_TABLE[0x8C] = STY; // STY (Absolute)
        OPCODE_TABLE[0x8D] = STA; // STA (Absolute)
        OPCODE_TABLE[0x8E] = STX; // STX (Absolute)
        OPCODE_TABLE[0x8F] = AAX; // AAX/SAX (Absolute)
        OPCODE_TABLE[0x90] = BCC; // Branch if Carry Clear
        OPCODE_TABLE[0x91] = STA; // STA (Indirect, Y)
        OPCODE_TABLE[0x92] = KIL; // Illegal opcode
        OPCODE_TABLE[0x93] = AHX; // AHX (Indirect, Y)
        OPCODE_TABLE[0x94] = STY; // STY (Zero Page, X)
        OPCODE_TABLE[0x95] = STA; // STA (Zero Page, X)
        OPCODE_TABLE[0x96] = STX; // STX (Zero Page, Y)
        OPCODE_TABLE[0x97] = AAX; // AAX/SAX (Zero Page, Y)
        OPCODE_TABLE[0x98] = TYA; // Transfer Y to A
        OPCODE_TABLE[0x99] = STA; // STA (Absolute, Y)
        OPCODE_TABLE[0x9A] = TXS; // Transfer X to Stack Pointer
        // 0x9B known as SHS/TAS: choose SHS to hit SHS case in CPU (aliases TAS)
        OPCODE_TABLE[0x9B] = SHS; // SHS/TAS: Transfer A & X to SP; store (A & X & (high+1))
        OPCODE_TABLE[0x9C] = SHY; // Store Y & (High Byte + 1) (Absolute, X) (Illegal)
        OPCODE_TABLE[0x9D] = STA; // STA (Absolute, X)
        OPCODE_TABLE[0x9E] = SHX; // Store X & (High Byte + 1) (Absolute, Y) (Illegal)
        OPCODE_TABLE[0x9F] = AXA; // AXA (Absolute, Y) para testar case AXA
        OPCODE_TABLE[0xA0] = LDY; // LDY (Immediate)
        OPCODE_TABLE[0xA1] = LDA; // LDA (Indirect, X)
        OPCODE_TABLE[0xA2] = LDX; // LDX (Immediate)
        OPCODE_TABLE[0xA3] = LAX; // LDA & LDX (Indirect, X) (Illegal)
        OPCODE_TABLE[0xA4] = LDY; // LDY (Zero Page)
        OPCODE_TABLE[0xA5] = LDA; // LDA (Zero Page)
        OPCODE_TABLE[0xA6] = LDX; // LDX (Zero Page)
        OPCODE_TABLE[0xA7] = LAX; // LDA & LDX (Zero Page) (Illegal)
        OPCODE_TABLE[0xA8] = TAY; // Transfer A to Y
        OPCODE_TABLE[0xA9] = LDA; // LDA (Immediate)
        OPCODE_TABLE[0xAA] = TAX; // Transfer A to X
        OPCODE_TABLE[0xAB] = LXA; // LDA & TAX (Immediate) (Illegal)
        OPCODE_TABLE[0xAC] = LDY; // LDY (Absolute)
        OPCODE_TABLE[0xAD] = LDA; // LDA (Absolute)
        OPCODE_TABLE[0xAE] = LDX; // LDX (Absolute)
        OPCODE_TABLE[0xAF] = LAX; // LDA & LDX (Absolute) (Illegal)
        OPCODE_TABLE[0xB0] = BCS; // Branch if Carry Set
        OPCODE_TABLE[0xB1] = LDA; // LDA (Indirect, Y)
        OPCODE_TABLE[0xB2] = KIL; // Illegal opcode
        OPCODE_TABLE[0xB3] = LAX; // LDA & LDX (Indirect, Y) (Illegal)
        OPCODE_TABLE[0xB4] = LDY; // LDY (Zero Page, X)
        OPCODE_TABLE[0xB5] = LDA; // LDA (Zero Page, X)
        OPCODE_TABLE[0xB6] = LDX; // LDX (Zero Page, Y)
        OPCODE_TABLE[0xB7] = LAX; // LDA & LDX (Zero Page, Y) (Illegal)
        OPCODE_TABLE[0xB8] = CLV; // Clear Overflow Flag
        OPCODE_TABLE[0xB9] = LDA; // LDA (Absolute, Y)
        OPCODE_TABLE[0xBA] = TSX; // Transfer Stack Pointer to X
        OPCODE_TABLE[0xBB] = LAS; // LDA & LDX & Stack Pointer (Absolute, Y) (Illegal)
        OPCODE_TABLE[0xBC] = LDY; // LDY (Absolute, X)
        OPCODE_TABLE[0xBD] = LDA; // LDA (Absolute, X)
        OPCODE_TABLE[0xBE] = LDX; // LDX (Absolute, Y)
        OPCODE_TABLE[0xBF] = LAX; // LDA & LDX (Absolute, Y) (Illegal)
        OPCODE_TABLE[0xC0] = CPY; // CPY (Immediate)
        OPCODE_TABLE[0xC1] = CMP; // CMP (Indirect, X)
        OPCODE_TABLE[0xC2] = DOP; // Double Operand
        OPCODE_TABLE[0xC3] = DCP; // DEC & CMP (Indirect, X) (Illegal)
        OPCODE_TABLE[0xC4] = CPY; // CPY (Zero Page)
        OPCODE_TABLE[0xC5] = CMP; // CMP (Zero Page)
        OPCODE_TABLE[0xC6] = DEC; // Decrement (Zero Page)
        OPCODE_TABLE[0xC7] = DCP; // DEC & CMP (Zero Page) (Illegal)
        OPCODE_TABLE[0xC8] = INY; // Increment Y
        OPCODE_TABLE[0xC9] = CMP; // CMP (Immediate)
        OPCODE_TABLE[0xCA] = DEX; // Decrement X
        OPCODE_TABLE[0xCB] = AXS; // AXS (SBX variante) Immediate
        OPCODE_TABLE[0xCC] = CPY; // CPY (Absolute)
        OPCODE_TABLE[0xCD] = CMP; // CMP (Absolute)
        OPCODE_TABLE[0xCE] = DEC; // Decrement (Absolute)
        OPCODE_TABLE[0xCF] = DCP; // DEC & CMP (Absolute) (Illegal)
        OPCODE_TABLE[0xD0] = BNE; // Branch if Not Equal
        OPCODE_TABLE[0xD1] = CMP; // CMP (Indirect, Y)
        OPCODE_TABLE[0xD2] = KIL; // Illegal opcode
        OPCODE_TABLE[0xD3] = DCP; // DEC & CMP (Indirect, Y) (Illegal)
        OPCODE_TABLE[0xD4] = DOP; // Double Operand
        OPCODE_TABLE[0xD5] = CMP; // CMP (Zero Page, X)
        OPCODE_TABLE[0xD6] = DEC; // Decrement (Zero Page, X)
        OPCODE_TABLE[0xD7] = DCP; // DEC & CMP (Zero Page, X) (Illegal)
        OPCODE_TABLE[0xD8] = CLD; // Clear Decimal Mode
        OPCODE_TABLE[0xD9] = CMP; // CMP (Absolute, Y)
        OPCODE_TABLE[0xDA] = NOP; // No Operation
        OPCODE_TABLE[0xDB] = DCP; // DEC & CMP (Absolute, Y) (Illegal)
        OPCODE_TABLE[0xDC] = TOP; // Top of Page
        OPCODE_TABLE[0xDD] = CMP; // CMP (Absolute, X)
        OPCODE_TABLE[0xDE] = DEC; // Decrement (Absolute, X)
        OPCODE_TABLE[0xDF] = DCP; // DEC & CMP (Absolute, X) (Illegal)
        OPCODE_TABLE[0xE0] = CPX; // CPX (Immediate)
        OPCODE_TABLE[0xE1] = SBC; // SBC (Indirect, X)
        OPCODE_TABLE[0xE2] = DOP; // Double Operand
        OPCODE_TABLE[0xE3] = ISC; // INC & SBC (Indirect, X) (Illegal)
        OPCODE_TABLE[0xE4] = CPX; // CPX (Zero Page)
        OPCODE_TABLE[0xE5] = SBC; // SBC (Zero Page)
        OPCODE_TABLE[0xE6] = INC; // Increment (Zero Page)
        OPCODE_TABLE[0xE7] = ISC; // INC & SBC (Zero Page) (Illegal)
        OPCODE_TABLE[0xE8] = INX; // Increment X
        OPCODE_TABLE[0xE9] = SBC; // SBC (Immediate)
        OPCODE_TABLE[0xEA] = NOP; // No Operation
        OPCODE_TABLE[0xEB] = SBC; // SBC (Immediate, Illegal)
        OPCODE_TABLE[0xEC] = CPX; // CPX (Absolute)
        OPCODE_TABLE[0xED] = SBC; // SBC (Absolute)
        OPCODE_TABLE[0xEE] = INC; // Increment (Absolute)
        OPCODE_TABLE[0xEF] = ISC; // INC & SBC (Absolute) (Illegal)
        OPCODE_TABLE[0xF0] = BEQ; // Branch if Equal
        OPCODE_TABLE[0xF1] = SBC; // SBC (Indirect, Y)
        OPCODE_TABLE[0xF2] = KIL; // Illegal opcode
        OPCODE_TABLE[0xF3] = ISC; // INC & SBC (Indirect, Y) (Illegal)
        OPCODE_TABLE[0xF4] = DOP; // Double Operand
        OPCODE_TABLE[0xF5] = SBC; // SBC (Zero Page, X)
        OPCODE_TABLE[0xF6] = INC; // Increment (Zero Page, X)
        OPCODE_TABLE[0xF7] = ISC; // INC & SBC (Zero Page, X) (Illegal)
        OPCODE_TABLE[0xF8] = SED; // Set Decimal Flag
        OPCODE_TABLE[0xF9] = SBC; // SBC (Absolute, Y)
        OPCODE_TABLE[0xFA] = NOP; // No Operation
        OPCODE_TABLE[0xFB] = ISC; // INC & SBC (Absolute, Y) (Illegal)
        OPCODE_TABLE[0xFC] = TOP; // Top of Page
        OPCODE_TABLE[0xFD] = SBC; // SBC (Absolute, X)
        OPCODE_TABLE[0xFE] = INC; // Increment (Absolute, X)
        OPCODE_TABLE[0xFF] = ISC; // INC & SBC (Absolute, X) (Illegal)
    }
}