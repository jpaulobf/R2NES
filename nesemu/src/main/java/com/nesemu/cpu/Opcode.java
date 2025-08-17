package com.nesemu.cpu;

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

    // Instruções não documentadas (ilegais) mais comuns:
    AAC, AAX, AHX, ALR, ANC, ARR, ASR, ATX, AXA, AXS, DCP, DOP, ISC, KIL, LAR, LAS, LAX, LXA, RLA, RRA, SAX, SBX, SHA, SHS, SHX, SHY, SLO, SRE, TAS, TOP, XAA;

    // Utilitário: mapeia um nome para o Opcode (útil para decodificação)
    public static Opcode fromName(String name) {
        try {
            return Opcode.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Opcode fromByte(int opcodeByte) {
        if (opcodeByte < 0 || opcodeByte > 0xFF) return null;
        return OPCODE_TABLE[opcodeByte];
    }

    private static final Opcode[] OPCODE_TABLE = new Opcode[256];
    static {
        for (int i = 0; i < 256; i++) OPCODE_TABLE[i] = NOP;
        OPCODE_TABLE[0x00] = BRK;  
        OPCODE_TABLE[0x01] = ORA;  
        OPCODE_TABLE[0x02] = KIL;  
        OPCODE_TABLE[0x03] = SLO;
        OPCODE_TABLE[0x04] = DOP;  
        OPCODE_TABLE[0x05] = ORA;  
        OPCODE_TABLE[0x06] = ASL;  
        OPCODE_TABLE[0x07] = SLO;
        OPCODE_TABLE[0x08] = PHP;  
        OPCODE_TABLE[0x09] = ORA;  
        OPCODE_TABLE[0x0A] = ASL;  
        OPCODE_TABLE[0x0B] = ANC;
        OPCODE_TABLE[0x0C] = TOP;  
        OPCODE_TABLE[0x0D] = ORA;  
        OPCODE_TABLE[0x0E] = ASL;  
        OPCODE_TABLE[0x0F] = SLO;
        OPCODE_TABLE[0x10] = BPL;  
        OPCODE_TABLE[0x11] = ORA;  
        OPCODE_TABLE[0x12] = KIL;  
        OPCODE_TABLE[0x13] = SLO;
        OPCODE_TABLE[0x14] = DOP;  
        OPCODE_TABLE[0x15] = ORA;  
        OPCODE_TABLE[0x16] = ASL; 
        OPCODE_TABLE[0x17] = SLO;
        OPCODE_TABLE[0x18] = CLC; 
        OPCODE_TABLE[0x19] = ORA;  
        OPCODE_TABLE[0x1A] = NOP;  
        OPCODE_TABLE[0x1B] = SLO;
        OPCODE_TABLE[0x1C] = TOP;  
        OPCODE_TABLE[0x1D] = ORA;  
        OPCODE_TABLE[0x1E] = ASL;  
        OPCODE_TABLE[0x1F] = SLO;
        OPCODE_TABLE[0x20] = JSR;  
        OPCODE_TABLE[0x21] = AND;  
        OPCODE_TABLE[0x22] = KIL;  
        OPCODE_TABLE[0x23] = RLA;
        OPCODE_TABLE[0x24] = BIT; 
        OPCODE_TABLE[0x25] = AND;  
        OPCODE_TABLE[0x26] = ROL;  
        OPCODE_TABLE[0x27] = RLA;
        OPCODE_TABLE[0x28] = PLP;  
        OPCODE_TABLE[0x29] = AND;  
        OPCODE_TABLE[0x2A] = ROL;  
        OPCODE_TABLE[0x2B] = ANC;
        OPCODE_TABLE[0x2C] = BIT;  
        OPCODE_TABLE[0x2D] = AND;  
        OPCODE_TABLE[0x2E] = ROL;  
        OPCODE_TABLE[0x2F] = RLA;
        OPCODE_TABLE[0x30] = BMI;  
        OPCODE_TABLE[0x31] = AND;  
        OPCODE_TABLE[0x32] = KIL;  
        OPCODE_TABLE[0x33] = RLA;
        OPCODE_TABLE[0x34] = DOP;  
        OPCODE_TABLE[0x35] = AND;  
        OPCODE_TABLE[0x36] = ROL;  
        OPCODE_TABLE[0x37] = RLA;
        OPCODE_TABLE[0x38] = SEC;  
        OPCODE_TABLE[0x39] = AND;  
        OPCODE_TABLE[0x3A] = NOP;  
        OPCODE_TABLE[0x3B] = RLA;
        OPCODE_TABLE[0x3C] = TOP;  
        OPCODE_TABLE[0x3D] = AND;  
        OPCODE_TABLE[0x3E] = ROL;  
        OPCODE_TABLE[0x3F] = RLA;
        OPCODE_TABLE[0x40] = RTI; 
        OPCODE_TABLE[0x41] = EOR;  
        OPCODE_TABLE[0x42] = KIL;  
        OPCODE_TABLE[0x43] = SRE;
        OPCODE_TABLE[0x44] = DOP;  
        OPCODE_TABLE[0x45] = EOR;  
        OPCODE_TABLE[0x46] = LSR;  
        OPCODE_TABLE[0x47] = SRE;
        OPCODE_TABLE[0x48] = PHA;  
        OPCODE_TABLE[0x49] = EOR;  
        OPCODE_TABLE[0x4A] = LSR;  
        OPCODE_TABLE[0x4B] = ALR;
        OPCODE_TABLE[0x4C] = JMP; 
        OPCODE_TABLE[0x4D] = EOR;  
        OPCODE_TABLE[0x4E] = LSR;  
        OPCODE_TABLE[0x4F] = SRE;
        OPCODE_TABLE[0x50] = BVC;  
        OPCODE_TABLE[0x51] = EOR;  
        OPCODE_TABLE[0x52] = KIL;  
        OPCODE_TABLE[0x53] = SRE;
        OPCODE_TABLE[0x54] = DOP;  
        OPCODE_TABLE[0x55] = EOR;  
        OPCODE_TABLE[0x56] = LSR;  
        OPCODE_TABLE[0x57] = SRE;
        OPCODE_TABLE[0x58] = CLI;  
        OPCODE_TABLE[0x59] = EOR;  
        OPCODE_TABLE[0x5A] = NOP;  
        OPCODE_TABLE[0x5B] = SRE;
        OPCODE_TABLE[0x5C] = TOP;  
        OPCODE_TABLE[0x5D] = EOR;  
        OPCODE_TABLE[0x5E] = LSR;  
        OPCODE_TABLE[0x5F] = SRE;
        OPCODE_TABLE[0x60] = RTS;  
        OPCODE_TABLE[0x61] = ADC;  
        OPCODE_TABLE[0x62] = KIL;  
        OPCODE_TABLE[0x63] = RRA;
        OPCODE_TABLE[0x64] = DOP; 
        OPCODE_TABLE[0x65] = ADC;  
        OPCODE_TABLE[0x66] = ROR;  
        OPCODE_TABLE[0x67] = RRA;
        OPCODE_TABLE[0x68] = PLA;  
        OPCODE_TABLE[0x69] = ADC;  
        OPCODE_TABLE[0x6A] = ROR;  
        OPCODE_TABLE[0x6B] = ARR;
        OPCODE_TABLE[0x6C] = JMP;  
        OPCODE_TABLE[0x6D] = ADC;  
        OPCODE_TABLE[0x6E] = ROR; 
        OPCODE_TABLE[0x6F] = RRA;
        OPCODE_TABLE[0x70] = BVS;  
        OPCODE_TABLE[0x71] = ADC;  
        OPCODE_TABLE[0x72] = KIL;  
        OPCODE_TABLE[0x73] = RRA;
        OPCODE_TABLE[0x74] = DOP;  
        OPCODE_TABLE[0x75] = ADC;  
        OPCODE_TABLE[0x76] = ROR;  
        OPCODE_TABLE[0x77] = RRA;
        OPCODE_TABLE[0x78] = SEI; 
        OPCODE_TABLE[0x79] = ADC; 
        OPCODE_TABLE[0x7A] = NOP;  
        OPCODE_TABLE[0x7B] = RRA;
        OPCODE_TABLE[0x7C] = TOP;  
        OPCODE_TABLE[0x7D] = ADC;  
        OPCODE_TABLE[0x7E] = ROR;  
        OPCODE_TABLE[0x7F] = RRA;
        OPCODE_TABLE[0x80] = DOP;  
        OPCODE_TABLE[0x81] = STA;  
        OPCODE_TABLE[0x82] = DOP;  
        OPCODE_TABLE[0x83] = SAX;
        OPCODE_TABLE[0x84] = STY;  
        OPCODE_TABLE[0x85] = STA;  
        OPCODE_TABLE[0x86] = STX;  
        OPCODE_TABLE[0x87] = SAX;
        OPCODE_TABLE[0x88] = DEY;  
        OPCODE_TABLE[0x89] = DOP;  
        OPCODE_TABLE[0x8A] = TXA;  
        OPCODE_TABLE[0x8B] = XAA;
        OPCODE_TABLE[0x8C] = STY;  
        OPCODE_TABLE[0x8D] = STA;  
        OPCODE_TABLE[0x8E] = STX;  
        OPCODE_TABLE[0x8F] = SAX;
        OPCODE_TABLE[0x90] = BCC;  
        OPCODE_TABLE[0x91] = STA;  
        OPCODE_TABLE[0x92] = KIL;  
        OPCODE_TABLE[0x93] = AHX;
        OPCODE_TABLE[0x94] = STY;  
        OPCODE_TABLE[0x95] = STA;  
        OPCODE_TABLE[0x96] = STX;  
        OPCODE_TABLE[0x97] = SAX;
        OPCODE_TABLE[0x98] = TYA; 
        OPCODE_TABLE[0x99] = STA;  
        OPCODE_TABLE[0x9A] = TXS;  
        OPCODE_TABLE[0x9B] = TAS;
        OPCODE_TABLE[0x9C] = SHY; 
        OPCODE_TABLE[0x9D] = STA;  
        OPCODE_TABLE[0x9E] = SHX;  
        OPCODE_TABLE[0x9F] = AHX;
        OPCODE_TABLE[0xA0] = LDY; 
        OPCODE_TABLE[0xA1] = LDA;  
        OPCODE_TABLE[0xA2] = LDX;  
        OPCODE_TABLE[0xA3] = LAX;
        OPCODE_TABLE[0xA4] = LDY;  
        OPCODE_TABLE[0xA5] = LDA;  
        OPCODE_TABLE[0xA6] = LDX;  
        OPCODE_TABLE[0xA7] = LAX;
        OPCODE_TABLE[0xA8] = TAY;  
        OPCODE_TABLE[0xA9] = LDA;  
        OPCODE_TABLE[0xAA] = TAX;  
        OPCODE_TABLE[0xAB] = LXA;
        OPCODE_TABLE[0xAC] = LDY;  
        OPCODE_TABLE[0xAD] = LDA;  
        OPCODE_TABLE[0xAE] = LDX;  
        OPCODE_TABLE[0xAF] = LAX;
        OPCODE_TABLE[0xB0] = BCS;  
        OPCODE_TABLE[0xB1] = LDA;  
        OPCODE_TABLE[0xB2] = KIL;  
        OPCODE_TABLE[0xB3] = LAX;
        OPCODE_TABLE[0xB4] = LDY; 
        OPCODE_TABLE[0xB5] = LDA;  
        OPCODE_TABLE[0xB6] = LDX;  
        OPCODE_TABLE[0xB7] = LAX;
        OPCODE_TABLE[0xB8] = CLV;  
        OPCODE_TABLE[0xB9] = LDA;  
        OPCODE_TABLE[0xBA] = TSX;  
        OPCODE_TABLE[0xBB] = LAS;
        OPCODE_TABLE[0xBC] = LDY;  
        OPCODE_TABLE[0xBD] = LDA;  
        OPCODE_TABLE[0xBE] = LDX;  
        OPCODE_TABLE[0xBF] = LAX;
        OPCODE_TABLE[0xC0] = CPY;  
        OPCODE_TABLE[0xC1] = CMP;  
        OPCODE_TABLE[0xC2] = DOP;  
        OPCODE_TABLE[0xC3] = DCP;
        OPCODE_TABLE[0xC4] = CPY;  
        OPCODE_TABLE[0xC5] = CMP;  
        OPCODE_TABLE[0xC6] = DEC;  
        OPCODE_TABLE[0xC7] = DCP;
        OPCODE_TABLE[0xC8] = INY;  
        OPCODE_TABLE[0xC9] = CMP;  
        OPCODE_TABLE[0xCA] = DEX;  
        OPCODE_TABLE[0xCB] = SBX;
        OPCODE_TABLE[0xCC] = CPY;  
        OPCODE_TABLE[0xCD] = CMP;  
        OPCODE_TABLE[0xCE] = DEC;  
        OPCODE_TABLE[0xCF] = DCP;
        OPCODE_TABLE[0xD0] = BNE;  
        OPCODE_TABLE[0xD1] = CMP;  
        OPCODE_TABLE[0xD2] = KIL;  
        OPCODE_TABLE[0xD3] = DCP;
        OPCODE_TABLE[0xD4] = DOP;  
        OPCODE_TABLE[0xD5] = CMP;  
        OPCODE_TABLE[0xD6] = DEC;  
        OPCODE_TABLE[0xD7] = DCP;
        OPCODE_TABLE[0xD8] = CLD;  
        OPCODE_TABLE[0xD9] = CMP;  
        OPCODE_TABLE[0xDA] = NOP;  
        OPCODE_TABLE[0xDB] = DCP;
        OPCODE_TABLE[0xDC] = TOP;  
        OPCODE_TABLE[0xDD] = CMP;  
        OPCODE_TABLE[0xDE] = DEC;  
        OPCODE_TABLE[0xDF] = DCP;
        OPCODE_TABLE[0xE0] = CPX;  
        OPCODE_TABLE[0xE1] = SBC;  
        OPCODE_TABLE[0xE2] = DOP;  
        OPCODE_TABLE[0xE3] = ISC;
        OPCODE_TABLE[0xE4] = CPX;  
        OPCODE_TABLE[0xE5] = SBC;  
        OPCODE_TABLE[0xE6] = INC;  
        OPCODE_TABLE[0xE7] = ISC;
        OPCODE_TABLE[0xE8] = INX;  
        OPCODE_TABLE[0xE9] = SBC;  
        OPCODE_TABLE[0xEA] = NOP;  
        OPCODE_TABLE[0xEB] = SBC;
        OPCODE_TABLE[0xEC] = CPX;  
        OPCODE_TABLE[0xED] = SBC;  
        OPCODE_TABLE[0xEE] = INC;  
        OPCODE_TABLE[0xEF] = ISC;
        OPCODE_TABLE[0xF0] = BEQ;  
        OPCODE_TABLE[0xF1] = SBC;  
        OPCODE_TABLE[0xF2] = KIL;  
        OPCODE_TABLE[0xF3] = ISC;
        OPCODE_TABLE[0xF4] = DOP;  
        OPCODE_TABLE[0xF5] = SBC;  
        OPCODE_TABLE[0xF6] = INC;  
        OPCODE_TABLE[0xF7] = ISC;
        OPCODE_TABLE[0xF8] = SED;  
        OPCODE_TABLE[0xF9] = SBC;  
        OPCODE_TABLE[0xFA] = NOP;  
        OPCODE_TABLE[0xFB] = ISC;
        OPCODE_TABLE[0xFC] = TOP;  
        OPCODE_TABLE[0xFD] = SBC;  
        OPCODE_TABLE[0xFE] = INC;  
        OPCODE_TABLE[0xFF] = ISC;
    }
}
