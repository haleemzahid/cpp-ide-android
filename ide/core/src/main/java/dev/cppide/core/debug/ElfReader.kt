package dev.cppide.core.debug

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ELF64 little-endian reader for aarch64 .so files. Purpose-built:
 * just enough to find a section by name and return its bytes for the
 * DWARF line parser to consume. No symbol tables, no relocations, no
 * program headers, no 32-bit support.
 *
 * The ELF64 layout we care about:
 *
 *   [0..63]      file header         → section header offset, entry size, count,
 *                                      string-table section index
 *   [sh_off..]   section headers     → array of Shdr
 *     each Shdr has: name (offset into .shstrtab), type, flags, addr, offset,
 *                    size, link, info, addralign, entsize
 *
 * We parse the header, read all the Shdr entries, read .shstrtab to resolve
 * each section's name, and hand back the raw bytes of whichever section
 * [findSectionBytes] is asked for.
 */
class ElfReader(private val file: File) {

    /**
     * Find an ELF section by name (e.g. ".debug_line") and return its bytes.
     * Returns null if the file isn't a valid ELF64, isn't aarch64, or the
     * section doesn't exist.
     *
     * Caller owns the result ByteArray — safe to close the file after.
     */
    fun findSectionBytes(name: String): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            // ---- ELF header ----
            val ident = ByteArray(16)
            raf.readFully(ident)
            if (ident[0] != 0x7f.toByte() ||
                ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() ||
                ident[3] != 'F'.code.toByte()
            ) return null
            if (ident[4].toInt() != 2) return null          // EI_CLASS: ELFCLASS64
            if (ident[5].toInt() != 1) return null          // EI_DATA: little-endian

            // Remaining header fields (ELF64):
            //   e_type u16, e_machine u16, e_version u32,
            //   e_entry u64, e_phoff u64, e_shoff u64,
            //   e_flags u32, e_ehsize u16, e_phentsize u16, e_phnum u16,
            //   e_shentsize u16, e_shnum u16, e_shstrndx u16
            val rest = ByteArray(64 - 16)
            raf.readFully(rest)
            val hdr = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
            hdr.short                                        // e_type
            val machine = hdr.short.toInt() and 0xffff       // e_machine
            if (machine != 0xb7) return null                 // 0xb7 = EM_AARCH64
            hdr.int                                          // e_version
            hdr.long                                         // e_entry
            hdr.long                                         // e_phoff
            val eShoff = hdr.long                            // e_shoff
            hdr.int                                          // e_flags
            hdr.short                                        // e_ehsize
            hdr.short                                        // e_phentsize
            hdr.short                                        // e_phnum
            val eShentsize = hdr.short.toInt() and 0xffff    // e_shentsize
            val eShnum = hdr.short.toInt() and 0xffff        // e_shnum
            val eShstrndx = hdr.short.toInt() and 0xffff     // e_shstrndx

            if (eShentsize < 64 || eShnum == 0) return null

            // ---- read section header table in one go ----
            val shdrTable = ByteArray(eShentsize * eShnum)
            raf.seek(eShoff)
            raf.readFully(shdrTable)
            val shdrs = ByteBuffer.wrap(shdrTable).order(ByteOrder.LITTLE_ENDIAN)

            // ---- read .shstrtab to resolve section names ----
            val (shstrOff, shstrSize) = shdrOffsetAndSize(shdrs, eShstrndx, eShentsize)
            val shstrBytes = ByteArray(shstrSize.toInt())
            raf.seek(shstrOff)
            raf.readFully(shstrBytes)

            // ---- walk sections looking for the requested name ----
            for (i in 0 until eShnum) {
                val base = i * eShentsize
                shdrs.position(base)
                val shName = shdrs.int                       // sh_name (offset into shstrtab)
                shdrs.int                                    // sh_type
                shdrs.long                                   // sh_flags
                shdrs.long                                   // sh_addr
                val shOff = shdrs.long                       // sh_offset
                val shSize = shdrs.long                      // sh_size

                val sectionName = readCString(shstrBytes, shName)
                if (sectionName == name) {
                    val bytes = ByteArray(shSize.toInt())
                    raf.seek(shOff)
                    raf.readFully(bytes)
                    return bytes
                }
            }
        }
        return null
    }

    private fun shdrOffsetAndSize(
        shdrs: ByteBuffer,
        index: Int,
        entSize: Int,
    ): Pair<Long, Long> {
        shdrs.position(index * entSize)
        shdrs.int   // sh_name
        shdrs.int   // sh_type
        shdrs.long  // sh_flags
        shdrs.long  // sh_addr
        val off = shdrs.long
        val size = shdrs.long
        return off to size
    }

    private fun readCString(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }
}
