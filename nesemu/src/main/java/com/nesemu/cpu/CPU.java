package com.nesemu.cpu;

import com.nesemu.cpu.interfaces.NesCPU;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.util.Log;

/**
 * Class representing the NES CPU.
 * Implements the main functionalities and instructions of the processor.
 */
public class CPU implements NesCPU {

    // Registers
    private Registers registers = new Registers();

    // Status flags
    private boolean carry;
    private boolean zero;
    private boolean interruptDisable;
    private boolean decimal;
    private boolean breakFlag;
    private boolean unused;
    private boolean overflow;
    private boolean negative;

    // reference to the bus (memory interface)
    private final NesBus busRef;

    // Page crossing flag for instructions that may apply an extra cycle
    private boolean lastPageCrossed = false;

    // Cycle counter for instruction timing
    private int cycles;

    // Dynamic extra cycles (branch taken, page crossing in branches, etc.) applied
    // after execution
    private int extraCycles;

    // Total executed CPU cycles (for tracing / nestest log)
    private long totalCycles;

    // --- DMA (OAM $4014) stall handling ---
    // When an OAM DMA is triggered, the CPU is suspended for 513 or 514 cycles
    // depending on the current cycle parity. We model this with a simple stall
    // counter that pauses micro-timing (instruction 'cycles' counter) until the
    // stall drains, then resume where the instruction left off.
    private int dmaStallCycles = 0;

    // --- Instrumentation for debugging timing mismatches ---
    private int lastOpcodeByte; // opcode of last fully executed instruction
    private int lastBaseCycles; // base cycles (from table) for that opcode
    private int lastExtraCycles; // dynamic extra cycles applied (branch taken, page cross, etc.)
    private boolean lastBranchTaken; // whether a branch was taken
    private boolean lastBranchPageCross; // whether a taken branch crossed a page (added +1)
    private int lastInstrPC; // PC at start (fetch) of last instruction - Aux for micro bus simulation
    private int lastZpOperand; // last zero-page operand byte (for (zp),Y store microsequence)
    private int lastIndirectBaseLo = -1;
    private int lastIndirectBaseHi = -1;

    // Reusable container for operand fetching to avoid GC pressure
    private final OperandResult opRes = new OperandResult(0, 0);

    // --- RMW (Read-Modify-Write) micro handling state ---
    private boolean rmwActive = false; // true while a memory RMW instruction still needs dummy/final write
    private int rmwAddress; // target address
    private int rmwOriginal; // original value read
    private int rmwModified; // final value to write (used for debug)
    private RmwKind rmwKind; // operation type for commit
    private int rmwCarryIn; // input carry (prior to modification)

    // Interrupt pending flags
    private boolean nmiPending = false;
    private boolean irqPending = false;

    // Instrumentation: count NMI handler entries and record last handler vector PC
    private long nmiHandlerCount = 0;
    private int lastNmiHandlerVector = 0;

    // NES 6502 cycle table (official opcodes only, 256 entries)
    private static final int[] CYCLE_TABLE = new int[] {
            7, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6, // 0x00-0x0F
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7, // 0x10-0x1F (adjustments: 0x14 DOP zpg,X=4, 0x18 CLC=2)
            6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6, // 0x20-0x2F
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7, // 0x30-0x3F (corrected: AND abs,Y 0x39=4)
            6, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6, // 0x40-0x4F
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7, // 0x50-0x5F (adjustments: 0x54 DOP zpg,X=4, 0x55 EOR
                                                            // zpg,X=4, 0x58 CLI=2, 0x5C TOP abs,X=4)
            6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 5, 4, 6, 6, // 0x60-0x6F (corrected: JMP (ind) 0x6C=5)
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7, // 0x70-0x7F (0x78 SEI=2)
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4, // 0x80-0x8F
            2, 6, 2, 6, 4, 4, 4, 4, 2, 5, 2, 5, 5, 5, 5, 5, // 0x90-0x9F
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4, // 0xA0-0xAF (corrected: AE=4; A6/A7 zpg=3; AF LAX abs=4)
            2, 5, 2, 5, 4, 4, 4, 4, 2, 4, 2, 4, 4, 4, 4, 4, // 0xB0-0xBF (corrected: LAS 0xBB=4, LDX/LAX abs,Y=4)
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6, // 0xC0-0xCF
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7, // 0xD0-0xDF (corrected: CMP abs,Y 0xD9=4)
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6, // 0xE0-0xEF
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7, // 0xF0-0xFF (corrected: SBC abs,Y 0xF9=4, INC/ISC
                                                            // adjustments 0xF6=6,0xF7=6,0xFE=7,0xFF=7)
    };

    // Debug logging flag (JSR/RTS tracing). Disable to silence verbose output.
    private static final boolean TRACE_JSR_RTS = false;

    /**
     * Kinds of RMW operations (affects how final value is computed and flags set).
     */
    private enum RmwKind {
        ASL, ROL, LSR, ROR, INC, DEC,
        RLA, RRA, SLO, SRE, DCP, ISC
    }

    /**
     * Constructor for the CPU class.
     * 
     * @param bus
     */
    public CPU(NesBus bus) {
        this.busRef = bus;
        bus.attachCPU(this);
        reset();
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
     * Starts an RMW sequence in memory. No flag/register modification happens here;
     * everything is applied in performRmwCommit() on the penultimate cycle (final
     * write).
     */
    private void startRmw(int address, int originalValue, RmwKind kind) {
        this.rmwActive = true;
        this.rmwAddress = address & 0xFFFF;
        this.rmwOriginal = originalValue & 0xFF;
        this.rmwKind = kind;
        this.rmwCarryIn = carry ? 1 : 0;
    }

    /**
     * Applies the modification and writes the final value to memory. Effect order
     * models 6502 behavior: carry/flags updated based on the original/modified
     * value only when the final write happens.
     */
    private void performRmwCommit() {
        int original = rmwOriginal & 0xFF;
        int newVal = original; // will be recomputed
        switch (rmwKind) {
            case ASL: {
                carry = (original & 0x80) != 0;
                newVal = (original << 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                setZeroAndNegative(newVal);
                break;
            }
            case LSR: {
                carry = (original & 0x01) != 0;
                newVal = (original >> 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                setZeroAndNegative(newVal);
                break;
            }
            case INC: {
                newVal = (original + 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                setZeroAndNegative(newVal);
                break;
            }
            case DEC: {
                newVal = (original - 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                setZeroAndNegative(newVal);
                break;
            }
            case ROL: {
                boolean oldCarry = rmwCarryIn != 0;
                carry = (original & 0x80) != 0;
                newVal = ((original << 1) | (oldCarry ? 1 : 0)) & 0xFF;
                busRef.write(rmwAddress, newVal);
                setZeroAndNegative(newVal);
                break;
            }
            case ROR: {
                boolean oldCarry = rmwCarryIn != 0;
                carry = (original & 0x01) != 0;
                newVal = ((original >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                busRef.write(rmwAddress, newVal);
                setZeroAndNegative(newVal);
                break;
            }
            case SLO: { // ASL then ORA
                carry = (original & 0x80) != 0;
                newVal = (original << 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                registers.A = registers.A | newVal;
                setZeroAndNegative(registers.A);
                break;
            }
            case SRE: { // LSR then EOR
                carry = (original & 0x01) != 0;
                newVal = (original >> 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                registers.A = registers.A ^ newVal;
                setZeroAndNegative(registers.A);
                break;
            }
            case RLA: { // ROL then AND
                boolean oldCarry = rmwCarryIn != 0;
                carry = (original & 0x80) != 0;
                newVal = ((original << 1) | (oldCarry ? 1 : 0)) & 0xFF;
                busRef.write(rmwAddress, newVal);
                registers.A = registers.A & newVal;
                setZeroAndNegative(registers.A);
                break;
            }
            case RRA: { // ROR then ADC
                boolean oldCarry = rmwCarryIn != 0;
                carry = (original & 0x01) != 0;
                newVal = ((original >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                busRef.write(rmwAddress, newVal);
                int acc = registers.A & 0xFF;
                int val = newVal & 0xFF;
                int cIn = (carry ? 1 : 0); // note: carry now holds bit0 original per 6502 ROR before ADC
                int result = acc + val + cIn;
                carry = result > 0xFF;
                overflow = (~(acc ^ val) & (acc ^ result) & 0x80) != 0;
                registers.A = result & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            }
            case DCP: { // DEC then CMP (A - newVal)
                newVal = (original - 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                int cmp = (registers.A & 0xFF) - newVal;
                carry = (registers.A & 0xFF) >= newVal;
                zero = (cmp & 0xFF) == 0;
                negative = (cmp & 0x80) != 0;
                break;
            }
            case ISC: { // INC then SBC
                newVal = (original + 1) & 0xFF;
                busRef.write(rmwAddress, newVal);
                int value = newVal ^ 0xFF; // invert para usar mesmo caminho de ADC
                int acc = registers.A & 0xFF;
                int cIn = carry ? 1 : 0; // carry before SBC (not yet altered within this instruction so far)
                int result = acc + value + cIn;
                carry = result > 0xFF; // carry = NOT borrow
                overflow = ((acc ^ result) & (value ^ result) & 0x80) != 0;
                registers.A = result & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            }
        }
        this.rmwModified = newVal & 0xFF; // store for future debugging
    }

    /**
     * Handles the actual NMI sequence.
     */
    private void handleNMI() {
        push((registers.PC >> 8) & 0xFF);
        push(registers.PC & 0xFF);
        push(getStatusByte() & ~0x10 | 0x20);
        interruptDisable = true;
        int lo = busRef.read(0xFFFA);
        int hi = busRef.read(0xFFFB);
        registers.PC = (hi << 8) | lo;
        // Instrumentation update
        nmiHandlerCount++;
        lastNmiHandlerVector = registers.PC & 0xFFFF;
    }

    /**
     * Handles the actual IRQ sequence.
     */
    private void handleIRQ() {
        push((registers.PC >> 8) & 0xFF);
        push(registers.PC & 0xFF);
        push(getStatusByte() & ~0x10 | 0x20);
        interruptDisable = true;
        int lo = busRef.read(0xFFFE);
        int hi = busRef.read(0xFFFF);
        registers.PC = (hi << 8) | lo;
    }

    @Override
    public void reset() {
        registers.A = registers.X = registers.Y = 0;
        registers.SP = 0xFD;
        // PC is initialized from the reset vector (0xFFFC/0xFFFD) - low byte first,
        // then high byte
        registers.PC = (busRef.read(0xFFFC) | (busRef.read(0xFFFD) << 8));
        carry = zero = interruptDisable = decimal = breakFlag = overflow = negative = false;
        unused = true; // Bit 5 of the status register is always set
        totalCycles = 0; // external tools may set a baseline (e.g., 7 for nestest) after forceState
    }

    @Override
    public void clock() {
        totalCycles++; // count every cycle

        // Handle DMA stall first (RDY low). During stall we neither advance the
        // current instruction cycle counter nor perform any bus side effects
        // besides incrementing total cycle count.
        if (dmaStallCycles > 0) {
            dmaStallCycles--;
            return;
        }

        // If cycles remain, process possible RMW phases and consume one cycle
        if (cycles > 0) {
            if (rmwActive) {
                // 6502 pattern for memory RMW instructions: ... read -> dummy write -> final
                // write
                // Our 'cycles' counter already includes all remaining cycles for this
                // instruction.
                // We'll trigger a dummy write when 2 cycles remain, and final commit when 1
                // remains.
                if (cycles == 2) {
                    // Dummy write of the original value (no logical side effects)
                    busRef.write(rmwAddress, rmwOriginal & 0xFF);
                } else if (cycles == 1) {
                    // Final commit (writes modified value and applies effects on A/flags according
                    // to type)
                    performRmwCommit();
                    rmwActive = false;
                }
            }
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

        int opcodeByte = busRef.read(registers.PC);
        registers.PC++;
        lastInstrPC = (registers.PC - 1) & 0xFFFF; // store starting PC

        Opcode opcode = Opcode.fromByte(opcodeByte);
        if (opcode == null)
            return;

        AddressingMode mode = AddressingMode.getAddressingMode(opcodeByte);
        boolean skipFinalRead = Opcode.isStoreOpcode(opcodeByte);
        fetchOperand(mode, skipFinalRead); // Updates this.opRes internally

        int baseCycles = CYCLE_TABLE[opcodeByte & 0xFF];
        int remaining = baseCycles;
        // reset instrumentation per instruction
        lastOpcodeByte = opcodeByte & 0xFF;
        lastBaseCycles = baseCycles;
        lastExtraCycles = 0;
        lastBranchTaken = false;
        lastBranchPageCross = false;
        // Add +1 cycle on page crossing for read-only indexed addressing modes.
        // Official opcodes already covered; include LAX (illegal load A & X) which
        // mirrors LDA timing.
        if (lastPageCrossed && (opcode == Opcode.LDA || opcode == Opcode.LDX || opcode == Opcode.LDY ||
                opcode == Opcode.ADC || opcode == Opcode.SBC || opcode == Opcode.CMP ||
                opcode == Opcode.AND || opcode == Opcode.ORA || opcode == Opcode.EOR ||
                opcode == Opcode.LAX)) {
            remaining += 1;
        }

        // Execute instruction work on this first cycle
        extraCycles = 0;
        execute(opcode, mode, opRes.value, opRes.address);

        // capture dynamic extra cycles applied inside execute()
        lastExtraCycles = extraCycles;

        // Set remaining cycles minus the one we just spent
        cycles = Math.max(0, remaining - 1 + extraCycles);
    }

    @Override
    public int getA() {
        return registers.A & 0xFF;
    }

    @Override
    public int getX() {
        return registers.X & 0xFF;
    }

    @Override
    public int getY() {
        return registers.Y & 0xFF;
    }

    @Override
    public int getSP() {
        return registers.SP & 0xFF;
    }

    @Override
    public int getPC() {
        return registers.PC & 0xFFFF;
    }

    @Override
    public int getLastInstrPC() {
        return lastInstrPC & 0xFFFF;
    }

    @Override
    public boolean isInstructionBoundary() {
        return cycles == 0;
    }

    @Override
    public int getStatusByte() {
        int p = 0;
        p |= (carry ? 1 : 0);
        p |= (zero ? 1 : 0) << 1;
        p |= (interruptDisable ? 1 : 0) << 2;
        p |= (decimal ? 1 : 0) << 3;
        p |= (breakFlag ? 1 : 0) << 4;
        p |= (unused ? 1 : 0) << 5;
        p |= (overflow ? 1 : 0) << 6;
        p |= (negative ? 1 : 0) << 7;
        return p & 0xFF;
    }

    @Override
    public void forceState(int pc, int a, int x, int y, int p, int sp) {
        registers.PC = pc & 0xFFFF;
        registers.A = a & 0xFF;
        registers.X = x & 0xFF;
        registers.Y = y & 0xFF;
        registers.SP = sp & 0xFF;
        carry = (p & 0x01) != 0;
        zero = (p & 0x02) != 0;
        interruptDisable = (p & 0x04) != 0;
        decimal = (p & 0x08) != 0;
        breakFlag = (p & 0x10) != 0;
        unused = (p & 0x20) != 0; // should stay set
        overflow = (p & 0x40) != 0;
        negative = (p & 0x80) != 0;
    }

    @Override
    public void stepInstruction() {
        while (cycles > 0)
            clock(); // finish current if mid-instruction
        clock(); // start next
        while (cycles > 0)
            clock(); // finish it
    }

    @Override
    public void nmi() {
        // Signal NMI to be handled at next instruction boundary
        nmiPending = true;
    }

    @Override
    public void irq() {
        // Signal IRQ to be handled at next instruction boundary
        irqPending = true;
    }

    /**
     * Triggers a DMA operation, stalling the CPU for the required cycles.
     * 
     * @param mode
     * @param skipFinalRead
     * @return
     */
    private void fetchOperand(AddressingMode mode, boolean skipFinalRead) {
        // Reset tracking before each new fetch
        lastPageCrossed = false;
        // We update the shared opRes object instead of creating a new one
        switch (mode) {
            case IMMEDIATE:
                opRes.value = busRef.read(registers.PC++);
                opRes.address = -1;
                break;
            case ZERO_PAGE: {
                int addr = busRef.read(registers.PC) & 0xFF;
                lastZpOperand = addr;
                int value = skipFinalRead ? 0 : busRef.read(addr);
                registers.PC++;
                opRes.value = value;
                opRes.address = addr;
                break;
            }
            case ZERO_PAGE_X: {
                int addr = (busRef.read(registers.PC++) + registers.X) & 0xFF;
                lastZpOperand = addr; // after index
                opRes.value = skipFinalRead ? 0 : busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case ZERO_PAGE_Y: {
                int addr = (busRef.read(registers.PC++) + registers.Y) & 0xFF;
                lastZpOperand = addr; // after index
                opRes.value = skipFinalRead ? 0 : busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case ABSOLUTE: {
                int lo = busRef.read(registers.PC++);
                int hi = busRef.read(registers.PC++);
                int addr = (hi << 8) | lo;
                opRes.value = skipFinalRead ? 0 : busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case ABSOLUTE_X: {
                int lo = busRef.read(registers.PC++);
                int hi = busRef.read(registers.PC++);
                int base = (hi << 8) | lo;
                int addr = (base + registers.X) & 0xFFFF;
                if (((base ^ addr) & 0xFF00) != 0)
                    lastPageCrossed = true;
                opRes.value = skipFinalRead ? 0 : busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case ABSOLUTE_Y: {
                int lo = busRef.read(registers.PC++);
                int hi = busRef.read(registers.PC++);
                int base = (hi << 8) | lo;
                int addr = (base + registers.Y) & 0xFFFF;
                if (((base ^ addr) & 0xFF00) != 0)
                    lastPageCrossed = true;
                opRes.value = skipFinalRead ? 0 : busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case INDIRECT: {
                int ptr = busRef.read(registers.PC++) | (busRef.read(registers.PC++) << 8);
                int lo = busRef.read(ptr);
                int hi = busRef.read((ptr & 0xFF00) | ((ptr + 1) & 0xFF));
                int addr = lo | (hi << 8);
                opRes.value = addr;
                opRes.address = addr;
                break;
            }
            case INDIRECT_X: {
                int zp = (busRef.read(registers.PC++) + registers.X) & 0xFF;
                lastZpOperand = zp;
                int lo = busRef.read(zp);
                int hi = busRef.read((zp + 1) & 0xFF);
                int addr = lo | (hi << 8);
                opRes.value = skipFinalRead ? 0 : busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case INDIRECT_Y: {
                int zp = busRef.read(registers.PC++) & 0xFF;
                lastZpOperand = zp;
                int lo = busRef.read(zp);
                int hi = busRef.read((zp + 1) & 0xFF);
                int base = (hi << 8) | lo;
                int addr = (base + registers.Y) & 0xFFFF;
                if (((base ^ addr) & 0xFF00) != 0)
                    lastPageCrossed = true;
                if (skipFinalRead) {
                    // Record base pointer bytes for later microsequence without re-reading
                    lastIndirectBaseLo = lo;
                    lastIndirectBaseHi = hi;
                    opRes.value = 0;
                    opRes.address = addr;
                    break;
                }
                opRes.value = busRef.read(addr);
                opRes.address = addr;
                break;
            }
            case RELATIVE:
                opRes.value = busRef.read(registers.PC++);
                opRes.address = -1;
                break;
            case ACCUMULATOR:
                opRes.value = registers.A;
                opRes.address = -1;
                break;
            case IMPLIED:
            default:
                opRes.value = 0;
                opRes.address = -1;
                break;
        }
    }

    /**
     * Executes the given opcode with the provided addressing mode and operand.
     * 
     * @param opcode
     * @param mode
     * @param operand
     * @param memAddr
     */
    private void execute(Opcode opcode, AddressingMode mode, int operand, int memAddr) {
        // memAddr already passed from clock(), avoiding double read
        switch (opcode) {
            // --- Official NES opcodes ---
            case ADC:
                int value = operand & 0xFF;
                int acc = registers.A & 0xFF;
                int carryIn = carry ? 1 : 0;
                int result = acc + value + carryIn;

                // Carry flag: set if result >= 0x100
                carry = result >= 0x100;

                // Overflow flag (bit 7 change unexpectedly)
                overflow = (~(acc ^ value) & (acc ^ result) & 0x80) != 0;
                registers.A = result & 0xFF;

                setZeroAndNegative(registers.A);
                break;
            case AND:
                registers.A = registers.A & (operand & 0xFF);
                setZeroAndNegative(registers.A);
                break;
            case ASL:
                if (mode == AddressingMode.ACCUMULATOR) {
                    carry = (registers.A & 0x80) != 0;
                    registers.A = (registers.A << 1) & 0xFF;
                    setZeroAndNegative(registers.A);
                } else if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.ASL);
                }
                break;
            case BCC: {
                if (!carry) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1; // branch taken
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1; // page cross
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BCS: {
                if (carry) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BEQ: {
                if (zero) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BIT:
                int bitResult = registers.A & (operand & 0xFF);
                zero = (bitResult == 0);
                negative = ((operand & 0x80) != 0);
                overflow = ((operand & 0x40) != 0);
                break;
            case BMI: {
                if (negative) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BNE: {
                if (!zero) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BPL: {
                if (!negative) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BRK:
                registers.PC = (registers.PC + 1) & 0xFFFF; // BRK increments PC by 2 (already incremented by 1 in
                                                            // clock)
                push((registers.PC >> 8) & 0xFF); // Push PCH
                push(registers.PC & 0xFF); // Push PCL
                breakFlag = true;
                push(getStatusByte() | 0x10); // Push status with B flag set
                interruptDisable = true;
                // Set PC to IRQ/BRK vector
                int lo = busRef.read(0xFFFE);
                int hi = busRef.read(0xFFFF);
                registers.PC = (hi << 8) | lo;
                break;
            case BVC: {
                if (!overflow) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
                }
                break;
            }
            case BVS: {
                if (overflow) {
                    int offset = (byte) (operand & 0xFF);
                    int oldPC = registers.PC;
                    registers.PC = (registers.PC + offset) & 0xFFFF;
                    extraCycles += 1;
                    lastBranchTaken = true;
                    if ((oldPC & 0xFF00) != (registers.PC & 0xFF00)) {
                        extraCycles += 1;
                        lastBranchPageCross = true;
                    }
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
                int cmpA = registers.A & 0xFF;
                int cmpResult = cmpA - cmpValue;
                carry = cmpA >= cmpValue;
                zero = (cmpResult & 0xFF) == 0;
                negative = (cmpResult & 0x80) != 0;
                break;
            case CPX:
                int cpxValue = operand & 0xFF;
                int cpxX = registers.X & 0xFF;
                int cpxResult = cpxX - cpxValue;
                carry = cpxX >= cpxValue;
                zero = (cpxResult & 0xFF) == 0;
                negative = (cpxResult & 0x80) != 0;
                break;
            case CPY:
                int cpyValue = operand & 0xFF;
                int cpyY = registers.Y & 0xFF;
                int cpyResult = cpyY - cpyValue;
                carry = cpyY >= cpyValue;
                zero = (cpyResult & 0xFF) == 0;
                negative = (cpyResult & 0x80) != 0;
                break;
            case DEC:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.DEC);
                }
                break;
            case DEX:
                registers.X = (registers.X - 1) & 0xFF;
                setZeroAndNegative(registers.X);
                break;
            case DEY:
                registers.Y = (registers.Y - 1) & 0xFF;
                setZeroAndNegative(registers.Y);
                break;
            case EOR:
                registers.A = registers.A ^ (operand & 0xFF);
                setZeroAndNegative(registers.A);
                break;
            case INC:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.INC);
                }
                break;
            case INX:
                registers.X = (registers.X + 1) & 0xFF;
                setZeroAndNegative(registers.X);
                break;
            case INY:
                registers.Y = (registers.Y + 1) & 0xFF;
                setZeroAndNegative(registers.Y);
                break;
            case JMP:
                // For absolute JMP we must use the computed address (memAddr), not the read
                // value.
                // For indirect JMP (INDIRECT mode) memAddr also holds the correct destination
                registers.PC = memAddr & 0xFFFF;
                break;
            case JSR:
                // JSR: Jump to SubRoutine
                // Push (PC-1) onto stack (high byte first, then low byte) - 6502 real behavior
                int returnAddr = (registers.PC - 1) & 0xFFFF;
                if (TRACE_JSR_RTS) {
                    Log.debug(Log.Cat.CPU, "[JSR] PC=%04X, returnAddr=%04X, push high=%02X, low=%02X, SP=%02X",
                            registers.PC, returnAddr, (returnAddr >> 8) & 0xFF, returnAddr & 0xFF, registers.SP);
                }
                push((returnAddr >> 8) & 0xFF); // High byte
                push(returnAddr & 0xFF); // Low byte
                registers.PC = memAddr & 0xFFFF;
                break;
            case LDA:
                registers.A = operand & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            case LDX:
                registers.X = operand & 0xFF;
                setZeroAndNegative(registers.X);
                break;
            case LDY:
                registers.Y = operand & 0xFF;
                setZeroAndNegative(registers.Y);
                break;
            case LSR:
                if (mode == AddressingMode.ACCUMULATOR) {
                    carry = (registers.A & 0x01) != 0;
                    registers.A = (registers.A >> 1) & 0xFF;
                    setZeroAndNegative(registers.A);
                } else if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.LSR);
                }
                break;
            case NOP:
                // NOP: No Operation.
                break;
            case ORA:
                registers.A = registers.A | (operand & 0xFF);
                setZeroAndNegative(registers.A);
                break;
            case PHA:
                push(registers.A & 0xFF);
                break;
            case PHP:
                // PHP: Push Processor Status with B flag set (bit4) & unused (bit5)
                // Internal breakFlag is not latched true permanently.
                int ps = getStatusByte() | 0x10; // ensure B=1 in pushed value
                push(ps);
                break;
            case PLA:
                registers.A = pop() & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            case PLP:
                // PLP: Pull Processor Status from stack
                int status = pop() & 0xFF;
                setStatusByte(status);
                // Real 6502: bit 5 forced 1, bit 4 (B) not a persistent latch except in pushes.
                unused = true;
                breakFlag = false; // clear B internally to match nestest expectations
                break;
            case ROL:
                if (mode == AddressingMode.ACCUMULATOR) {
                    boolean oldCarry = carry;
                    carry = (registers.A & 0x80) != 0;
                    registers.A = ((registers.A << 1) | (oldCarry ? 1 : 0)) & 0xFF;
                    setZeroAndNegative(registers.A);
                } else if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.ROL);
                }
                break;
            case ROR:
                if (mode == AddressingMode.ACCUMULATOR) {
                    boolean oldCarry = carry;
                    carry = (registers.A & 0x01) != 0;
                    registers.A = ((registers.A >> 1) | (oldCarry ? 0x80 : 0)) & 0xFF;
                    setZeroAndNegative(registers.A);
                } else if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.ROR);
                }
                break;
            case RTI:
                // RTI: Pull status, then pull PC (low, then high)
                setStatusByte(pop());
                int pcl = pop();
                int pch = pop();
                registers.PC = (pch << 8) | pcl;
                break;
            case RTS:
                // RTS: Pull PC (low, then high), then increment
                int pcl_rts = pop();
                int pch_rts = pop();
                int retAddr = ((pch_rts << 8) | pcl_rts);
                if (TRACE_JSR_RTS) {
                    Log.debug(Log.Cat.CPU, "[RTS] pop low=%02X, high=%02X, retAddr=%04X, PC=%04X, SP=%02X", pcl_rts,
                            pch_rts, retAddr, registers.PC, registers.SP);
                }
                registers.PC = (retAddr + 1) & 0xFFFF;
                break;
            case SBC:
                // SBC: Subtract with Carry
                int valueSBC = operand & 0xFF;
                int accSBC = registers.A & 0xFF;
                int carryInSBC = carry ? 1 : 0;
                int resultSBC = accSBC - valueSBC - (1 - carryInSBC);
                carry = resultSBC >= 0;
                // Overflow if sign bit of (A ^ result) and (A ^ operand) are both set (operand
                // here effectively added as two's complement)
                overflow = (((accSBC ^ resultSBC) & (accSBC ^ valueSBC) & 0x80) != 0);
                registers.A = resultSBC & 0xFF;
                setZeroAndNegative(registers.A);
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
                    if (mode == AddressingMode.INDIRECT_Y) {
                        // Approximate microsequence for STA (zp),Y: generate faithful dummy reads.
                        simulateStoreIndirectY(lastZpOperand, registers.Y, registers.A & 0xFF);
                    } else {
                        busRef.write(memAddr, registers.A & 0xFF);
                    }
                }
                break;
            case STX:
                if (memAddr != -1) {
                    busRef.write(memAddr, registers.X & 0xFF);
                }
                break;
            case STY:
                if (memAddr != -1) {
                    busRef.write(memAddr, registers.Y & 0xFF);
                }
                break;
            case TAX:
                registers.X = registers.A & 0xFF;
                setZeroAndNegative(registers.X);
                break;
            case TAY:
                registers.Y = registers.A & 0xFF;
                setZeroAndNegative(registers.Y);
                break;
            case TSX:
                registers.X = registers.SP & 0xFF;
                setZeroAndNegative(registers.X);
                break;
            case TXA:
                registers.A = registers.X & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            case TXS:
                registers.SP = registers.X & 0xFF;
                break;
            case TYA:
                registers.A = registers.Y & 0xFF;
                setZeroAndNegative(registers.A);
                break;

            // --- Most common undocumented (illegal) opcodes ---
            case AAC:
                // AAC (ANC): AND operand with A, set carry = bit 7 of result
                registers.A = registers.A & (operand & 0xFF);
                setZeroAndNegative(registers.A);
                carry = (registers.A & 0x80) != 0;
                break;
            case AAX:
            case SAX:
                // AAX (SAX): Store A & X to memory
                if (memAddr != -1) {
                    int val = registers.A & registers.X;
                    busRef.write(memAddr, val);
                }
                break;
            case AHX:
            case SHA:
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int value_r = registers.A & registers.X & (high & 0xFF);
                    busRef.write(memAddr, value_r);
                }
                break;
            case ALR:
            case ASR:
                // ALR / ASR (opcode 0x4B): A = (A & operand) >> 1 ; Carry = bit0 before shift
                registers.A = registers.A & (operand & 0xFF);
                carry = (registers.A & 0x01) != 0;
                registers.A = (registers.A >> 1) & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            case ARR:
                // ARR: AND operand with A, then ROR, set flags
                registers.A = registers.A & (operand & 0xFF);
                registers.A = ((registers.A >> 1) | (carry ? 0x80 : 0)) & 0xFF;
                setZeroAndNegative(registers.A);
                carry = (registers.A & 0x40) != 0;
                overflow = (((registers.A >> 5) & 1) ^ ((registers.A >> 6) & 1)) != 0;
                break;
            case AXA:
                // AXA: Store (A & X) & (high byte of address + 1) to memory
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int axaValue = (registers.A & registers.X) & (high & 0xFF);
                    busRef.write(memAddr, axaValue);
                }
                break;
            case AXS:
            case SBX:
                // SBX/AXS: X = (A & X_original) - operand ; Carry = no borrow
                int xOrig = registers.X & 0xFF;
                int maskAX = (registers.A & xOrig) & 0xFF;
                int subtrahend = operand & 0xFF;
                int resultAXS = (maskAX - subtrahend) & 0x1FF; // preserve borrow in bit 8
                carry = maskAX >= subtrahend; // 6502 style: carry = no borrow
                registers.X = resultAXS & 0xFF;
                setZeroAndNegative(registers.X);
                break;
            case DCP:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.DCP);
                }
                break;
            case DOP:
                // DOP: Double NOP (2-byte) - operand already consumed per addressing mode
                break;
            case TOP:
                // TOP: Triple NOP (abs or abs,X). If abs,X and page crossing detected during
                // fetch, add 1 cycle.
                if (mode == AddressingMode.ABSOLUTE_X && lastPageCrossed) {
                    extraCycles += 1;
                }
                break;
            case ISC:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.ISC);
                }
                break;
            case KIL:
                // KIL: Halts CPU (simulate by not advancing PC)
                registers.PC = (registers.PC - 1) & 0xFFFF;
                break;
            case LAR:
            case LAS:
                // Alias LAR / LAS: we adopt LAS semantics (SP = A & X & mem; A = X = SP)
                // (Traditional LAR = A & mem; if distinct behavior desired, separate later)
                registers.SP = registers.A & registers.X & (operand & 0xFF);
                registers.A = registers.X = registers.SP;
                setZeroAndNegative(registers.A);
                break;
            case LAX:
                // LAX: A = X = memory
                registers.A = registers.X = operand & 0xFF;
                setZeroAndNegative(registers.A);
                break;
            case LXA:
                // LXA: A = X = (A | 0xEE) & operand (unofficial, unstable)
                registers.A = registers.X = (registers.A | 0xEE) & (operand & 0xFF);
                setZeroAndNegative(registers.A);
                break;
            case RLA:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.RLA);
                }
                break;
            case RRA:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.RRA);
                }
                break;
            case SHS:
            case TAS:
                // SHS/TAS: SP = A & X; store (A & X & (high byte of address + 1)) at effective
                // address (ABSOLUTE_Y / INDIRECT_Y)
                registers.SP = registers.A & registers.X;
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int storeVal = (registers.A & registers.X) & (high & 0xFF);
                    busRef.write(memAddr, storeVal);
                }
                break;
            case SHX:
                // SHX: Store X & (high byte of address + 1) to memory
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_Y || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int shxValue = registers.X & (high & 0xFF);
                    busRef.write(memAddr, shxValue);
                }
                break;
            case SHY:
                // SHY: Store Y & (high byte of address + 1) to memory
                if (memAddr != -1 && (mode == AddressingMode.ABSOLUTE_X || mode == AddressingMode.INDIRECT_Y)) {
                    int high = (memAddr >> 8) + 1;
                    int shyValue = registers.Y & (high & 0xFF);
                    busRef.write(memAddr, shyValue);
                }
                break;
            case SLO:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.SLO);
                }
                break;
            case SRE:
                if (memAddr != -1) {
                    startRmw(memAddr, operand & 0xFF, RmwKind.SRE);
                }
                break;
            case XAA:
                // XAA (unofficial): A = (A & X) & operand
                registers.A = (registers.A & registers.X) & (operand & 0xFF);
                setZeroAndNegative(registers.A);
                break;

            // --- Any other opcodes (future/unknown) ---
            default:
                // NOP or handle as illegal
                break;
        }
    }

    /**
     * Simulates bus pattern for STA (zp),Y (6 cycles) with dummy read before final
     * write.
     */
    private void simulateStoreIndirectY(int zpPtr, int yReg, int value) {
        int lo;
        int hi;
        if (lastIndirectBaseLo >= 0 && lastIndirectBaseHi >= 0) {
            lo = lastIndirectBaseLo & 0xFF;
            hi = lastIndirectBaseHi & 0xFF;
        } else {
            lo = busRef.read(zpPtr & 0xFF); // fallback if not cached
            hi = busRef.read((zpPtr + 1) & 0xFF);
        }
        int sum = lo + (yReg & 0xFF);
        int effLow = sum & 0xFF;
        int carry = (sum >> 8) & 0x1;
        int dummyAddr = (hi << 8) | effLow; // dummy read (old high, even if carry)
        busRef.read(dummyAddr & 0xFFFF); // dummy fetch
        int effHigh = (hi + carry) & 0xFF;
        int effAddr = (effHigh << 8) | effLow;
        busRef.write(effAddr, value & 0xFF); // final store
        // Reset cache so next store cannot wrongly reuse
        lastIndirectBaseLo = lastIndirectBaseHi = -1;
    }

    /**
     * Pushes a value onto the stack.
     * The stack is located at 0x0100 in memory, and the stack pointer (SP) is
     * decremented before writing.
     * 
     * @param value
     */
    private void push(int value) {
        busRef.write(0x100 + (registers.SP & 0xFF), value & 0xFF);
        registers.SP = (registers.SP - 1) & 0xFF;
    }

    /**
     * Pops a value from the stack.
     * The stack is located at 0x0100 in memory, and the stack pointer (SP) is
     * incremented after reading.
     * 
     * @return
     */
    private int pop() {
        registers.SP = (registers.SP + 1) & 0xFF;
        return busRef.read(0x100 + (registers.SP & 0xFF));
    }

    /**
     * Sets the status flags based on the provided byte value.
     * This method updates the processor status flags based on the provided byte
     * value.
     * 
     * @param value
     */
    private void setStatusByte(int value) {
        carry = (value & 0x01) != 0;
        zero = (value & 0x02) != 0;
        interruptDisable = (value & 0x04) != 0;
        decimal = (value & 0x08) != 0;
        breakFlag = (value & 0x10) != 0;
        unused = true; // Status bit 5 is always set on the 6502
        overflow = (value & 0x40) != 0;
        negative = (value & 0x80) != 0;
    }

    @Override
    public int getCycles() {
        return cycles;
    }

    @Override
    public boolean isCarry() {
        return carry;
    }

    @Override
    public boolean isZero() {
        return zero;
    }

    @Override
    public boolean isInterruptDisable() {
        return interruptDisable;
    }

    @Override
    public boolean isDecimal() {
        return decimal;
    }

    @Override
    public boolean isBreakFlag() {
        return breakFlag;
    }

    @Override
    public boolean isUnused() {
        return unused;
    }

    @Override
    public boolean isOverflow() {
        return overflow;
    }

    @Override
    public boolean isNegative() {
        return negative;
    }

    @Override
    public int getLastOpcodeByte() {
        return lastOpcodeByte;
    }

    @Override
    public int getLastBaseCycles() {
        return lastBaseCycles;
    }

    @Override
    public int getLastExtraCycles() {
        return lastExtraCycles;
    }

    @Override
    public int getLastRmwModified() {
        return rmwModified;
    }

    @Override
    public boolean wasLastBranchTaken() {
        return lastBranchTaken;
    }

    @Override
    public boolean wasLastBranchPageCross() {
        return lastBranchPageCross;
    }

    @Override
    public long getTotalCycles() {
        return totalCycles;
    }

    @Override
    public void setTotalCycles(long cycles) {
        this.totalCycles = cycles;
    }

    @Override
    public long getNmiHandlerCount() {
        return nmiHandlerCount;
    }

    @Override
    public int getLastNmiHandlerVector() {
        return lastNmiHandlerVector & 0xFFFF;
    }

    @Override
    public void addDmaStall(int cycles) {
        if (cycles <= 0)
            return;
        dmaStallCycles += cycles;
    }

    @Override
    public int getDmaStallCycles() {
        return dmaStallCycles;
    }

    @Override
    public boolean isDmaStalling() {
        return dmaStallCycles > 0;
    }

    @Override
    public void setA(int a) {
        this.registers.A = a & 0xFF;
        setZeroAndNegative(this.registers.A);
    }

    @Override
    public void setX(int x) {
        this.registers.X = x & 0xFF;
        setZeroAndNegative(this.registers.X);
    }

    @Override
    public void setY(int y) {
        this.registers.Y = y & 0xFF;
        setZeroAndNegative(this.registers.Y);
    }

    @Override
    public void setSP(int sp) {
        this.registers.SP = sp & 0xFF;
        setZeroAndNegative(this.registers.SP);
    }

    @Override
    public void setPC(int pc) {
        this.registers.PC = pc & 0xFFFF;
    }

    @Override
    public void setCarry(boolean carry) {
        this.carry = carry;
    }

    @Override
    public void setZero(boolean zero) {
        this.zero = zero;
    }

    @Override
    public void setInterruptDisable(boolean interruptDisable) {
        this.interruptDisable = interruptDisable;
    }

    @Override
    public void setDecimal(boolean decimal) {
        this.decimal = decimal;
    }

    @Override
    public void setBreakFlag(boolean breakFlag) {
        this.breakFlag = breakFlag;
    }

    @Override
    public void setUnused(boolean unused) {
        this.unused = unused;
    }

    @Override
    public void setOverflow(boolean overflow) {
        this.overflow = overflow;
    }

    @Override
    public void setNegative(boolean negative) {
        this.negative = negative;
    }

    // ------------------------------ Inner classes ------------------------------

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
}