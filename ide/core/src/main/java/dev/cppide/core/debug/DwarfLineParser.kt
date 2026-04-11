package dev.cppide.core.debug

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DWARF v4 `.debug_line` parser. Executes the line-number program state
 * machine defined in DWARF §6.2 and produces a flat table of rows that
 * map program addresses back to source `(file, line)` pairs.
 *
 * Scope — deliberately narrow for Phase 2 of the debugger:
 *  - DWARF v4 only. Our clang-21 and NDK-27 both emit v4 by default with
 *    `-g`. DWARF v5's new file-table format and `.debug_line_str` can be
 *    added when/if we see it on real output.
 *  - No compressed sections. We pass `-gz=none` in the debug build config
 *    to guarantee this.
 *  - 32-bit initial length only (the DWARF64 escape is not handled — we
 *    return null if we see `0xffffffff`).
 *  - Ignores: basic_block, end_sequence (consumed as state), prologue_end,
 *    epilogue_begin, discriminator. We keep them on the state machine for
 *    correctness but don't surface them in output rows.
 *
 * Output: a list of [LineRow]s per compilation unit, each row representing
 * one stepping target (address-aligned statement boundary).
 *
 * Implementation follows DWARF 4 §6.2.5 (the line program state machine)
 * literally — the opcode dispatch table is easier to read as straight
 * `when`/`switch` than a fancy interpreter.
 */
class DwarfLineParser(private val data: ByteArray) {

    /** One row of the DWARF line matrix. `isStmt` signals a good breakpoint spot. */
    data class LineRow(
        val address: Long,
        val fileName: String,
        val directory: String,
        val line: Int,
        val column: Int,
        val isStmt: Boolean,
    )

    /**
     * Parse every line-number program in `.debug_line` and return all
     * rows, flattened across compile units. Never throws on malformed
     * input — on decode error, it stops at the broken CU and returns
     * whatever it had parsed so far.
     */
    fun parse(): List<LineRow> {
        val out = mutableListOf<LineRow>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) {
            val cuStart = buf.position()
            val unitLength = readUnitLength(buf) ?: break
            val cuEnd = buf.position() + unitLength
            if (cuEnd > data.size) break
            try {
                parseOneUnit(buf, cuEnd, out)
            } catch (_: Throwable) {
                // skip malformed CU, continue with the next
            }
            buf.position(cuEnd)
            if (buf.position() <= cuStart) break  // guard against zero advance
        }
        return out
    }

    /**
     * DWARF `initial_length` field. 32-bit by default; the escape value
     * `0xffffffff` means DWARF64 (followed by a uint64 length). We bail
     * on DWARF64 since we don't expect it from clang output.
     */
    private fun readUnitLength(buf: ByteBuffer): Int? {
        if (buf.remaining() < 4) return null
        val len = buf.int.toLong() and 0xffffffffL
        if (len == 0xffffffffL) return null  // DWARF64 escape, unsupported
        if (len > Int.MAX_VALUE.toLong()) return null
        return len.toInt()
    }

    private fun parseOneUnit(buf: ByteBuffer, cuEnd: Int, out: MutableList<LineRow>) {
        // ---- prologue ----
        val version = buf.short.toInt() and 0xffff
        if (version != 4) return  // silently skip non-v4 CUs
        val headerLength = (buf.int.toLong() and 0xffffffffL).toInt()
        val headerEnd = buf.position() + headerLength
        val minInstLength = (buf.get().toInt() and 0xff)
        val maxOpsPerInst = (buf.get().toInt() and 0xff)
        val defaultIsStmt = (buf.get().toInt() and 0xff) != 0
        val lineBase = buf.get().toInt()           // signed
        val lineRange = buf.get().toInt() and 0xff
        val opcodeBase = buf.get().toInt() and 0xff
        // standard_opcode_lengths[opcode_base - 1]
        val standardOpcodeLengths = IntArray(opcodeBase - 1)
        for (i in 0 until (opcodeBase - 1)) {
            standardOpcodeLengths[i] = buf.get().toInt() and 0xff
        }
        // include_directories: sequence of null-terminated strings, ends
        // with a zero byte. Index 0 is implicitly the compilation dir.
        val dirs = mutableListOf<String>()
        dirs.add("")  // index 0 = CU's comp_dir (we don't read it here)
        while (buf.hasRemaining()) {
            val s = readCString(buf)
            if (s.isEmpty()) break
            dirs.add(s)
        }
        // file_names: name (cstring), dir_index (ULEB128), mod_time (ULEB128),
        // length (ULEB128). Ends with a null name byte.
        data class FileEntry(val name: String, val dirIndex: Int)
        val files = mutableListOf<FileEntry>()
        files.add(FileEntry("", 0))  // index 0 is reserved
        while (buf.hasRemaining()) {
            val name = readCString(buf)
            if (name.isEmpty()) break
            val dirIndex = readUleb128(buf).toInt()
            readUleb128(buf)  // mod_time
            readUleb128(buf)  // length
            files.add(FileEntry(name, dirIndex))
        }
        // Pad to headerEnd in case the prologue ended slightly earlier.
        if (buf.position() < headerEnd) buf.position(headerEnd)

        // ---- state machine ----
        var address = 0L
        var opIndex = 0
        var file = 1
        var line = 1
        var column = 0
        var isStmt = defaultIsStmt
        // basicBlock, prologueEnd, epilogueBegin, isa, discriminator — state
        // we track to consume the opcodes correctly but don't surface.
        var discriminator = 0

        fun resolveFile(f: Int): Pair<String, String> {
            if (f !in 1..<files.size) return "" to ""
            val entry = files[f]
            val dir = dirs.getOrElse(entry.dirIndex) { "" }
            return entry.name to dir
        }

        fun emitRow() {
            if (file in 1..<files.size) {
                val (name, dir) = resolveFile(file)
                out.add(LineRow(address, name, dir, line, column, isStmt))
            }
            discriminator = 0
        }

        // DWARF v4 standard opcode numbers (DWARF §6.2.5.2)
        val DW_LNS_copy = 1
        val DW_LNS_advance_pc = 2
        val DW_LNS_advance_line = 3
        val DW_LNS_set_file = 4
        val DW_LNS_set_column = 5
        val DW_LNS_negate_stmt = 6
        val DW_LNS_set_basic_block = 7
        val DW_LNS_const_add_pc = 8
        val DW_LNS_fixed_advance_pc = 9
        val DW_LNS_set_prologue_end = 10
        val DW_LNS_set_epilogue_begin = 11
        val DW_LNS_set_isa = 12

        // Extended opcodes
        val DW_LNE_end_sequence = 0x01
        val DW_LNE_set_address = 0x02
        val DW_LNE_define_file = 0x03
        val DW_LNE_set_discriminator = 0x04

        while (buf.position() < cuEnd) {
            val opcode = buf.get().toInt() and 0xff
            if (opcode == 0) {
                // extended opcode
                val extLen = readUleb128(buf).toInt()
                if (extLen <= 0) continue
                val extStart = buf.position()
                val extOp = buf.get().toInt() and 0xff
                when (extOp) {
                    DW_LNE_end_sequence -> {
                        // end_sequence: emit row, then reset
                        isStmt = defaultIsStmt
                        // The spec says the end_sequence row is the one
                        // that marks the ACTUAL end — many dumpers emit
                        // it. We don't need it for (file, line) → addr
                        // because it has no associated line, so skip.
                        address = 0
                        opIndex = 0
                        file = 1
                        line = 1
                        column = 0
                        discriminator = 0
                    }
                    DW_LNE_set_address -> {
                        // remaining bytes of extLen are the address;
                        // on 64-bit this is 8 bytes.
                        val addrBytes = extLen - 1
                        address = when (addrBytes) {
                            8 -> buf.long
                            4 -> buf.int.toLong() and 0xffffffffL
                            else -> { buf.position(buf.position() + addrBytes); 0L }
                        }
                        opIndex = 0
                    }
                    DW_LNE_define_file -> {
                        val name = readCString(buf)
                        val dirIdx = readUleb128(buf).toInt()
                        readUleb128(buf)
                        readUleb128(buf)
                        files.add(FileEntry(name, dirIdx))
                    }
                    DW_LNE_set_discriminator -> {
                        discriminator = readUleb128(buf).toInt()
                    }
                    else -> {
                        // unknown extended opcode — skip body
                        val skip = extLen - 1 - (buf.position() - extStart - 1)
                        if (skip > 0) buf.position(buf.position() + skip)
                    }
                }
                // make sure we end up exactly where the extended op said
                val expectedEnd = extStart + extLen
                if (buf.position() != expectedEnd) buf.position(expectedEnd)
            } else if (opcode < opcodeBase) {
                // standard opcode
                when (opcode) {
                    DW_LNS_copy -> {
                        emitRow()
                    }
                    DW_LNS_advance_pc -> {
                        val adv = readUleb128(buf).toInt()
                        address += minInstLength * ((opIndex + adv) / maxOpsPerInst)
                        opIndex = (opIndex + adv) % maxOpsPerInst
                    }
                    DW_LNS_advance_line -> {
                        line += readSleb128(buf).toInt()
                    }
                    DW_LNS_set_file -> {
                        file = readUleb128(buf).toInt()
                    }
                    DW_LNS_set_column -> {
                        column = readUleb128(buf).toInt()
                    }
                    DW_LNS_negate_stmt -> {
                        isStmt = !isStmt
                    }
                    DW_LNS_set_basic_block -> {
                        // track if needed; we don't surface it
                    }
                    DW_LNS_const_add_pc -> {
                        // Same math as a special opcode 255:
                        //   adj = 255 - opcode_base
                        //   op_adv = adj / line_range
                        val adj = 255 - opcodeBase
                        val opAdv = adj / lineRange
                        address += minInstLength * ((opIndex + opAdv) / maxOpsPerInst)
                        opIndex = (opIndex + opAdv) % maxOpsPerInst
                    }
                    DW_LNS_fixed_advance_pc -> {
                        address += (buf.short.toInt() and 0xffff)
                        opIndex = 0
                    }
                    DW_LNS_set_prologue_end -> { /* flag, not surfaced */ }
                    DW_LNS_set_epilogue_begin -> { /* flag, not surfaced */ }
                    DW_LNS_set_isa -> { readUleb128(buf) }
                    else -> {
                        // unknown standard opcode — skip by its declared arg count
                        val args = standardOpcodeLengths.getOrElse(opcode - 1) { 0 }
                        repeat(args) { readUleb128(buf) }
                    }
                }
            } else {
                // special opcode — encodes both address and line advance.
                val adj = opcode - opcodeBase
                val opAdv = adj / lineRange
                val lineAdv = lineBase + (adj % lineRange)
                address += minInstLength * ((opIndex + opAdv) / maxOpsPerInst)
                opIndex = (opIndex + opAdv) % maxOpsPerInst
                line += lineAdv
                emitRow()
            }
        }
    }

    // ---- helpers ----

    private fun readCString(buf: ByteBuffer): String {
        val start = buf.position()
        var end = start
        while (end < buf.limit() && data[end] != 0.toByte()) end++
        val s = String(data, start, end - start, Charsets.UTF_8)
        buf.position(if (end < buf.limit()) end + 1 else end)
        return s
    }

    private fun readUleb128(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = buf.get().toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun readSleb128(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        var b = 0
        while (shift < 64) {
            b = buf.get().toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        // sign-extend if the sign bit of the last byte is set
        if (shift < 64 && (b and 0x40) != 0) {
            result = result or (-1L shl shift)
        }
        return result
    }
}
