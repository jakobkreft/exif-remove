package si.jakobkreft.exifremove.engine

import java.io.RandomAccessFile

/** Metadata category a piece of metadata belongs to, shared by all engines. */
enum class MetaCategory { LOCATION, DATE, CAMERA, ORIENTATION, OTHER }

/** Shared low-level ISO BMFF (MP4/MOV/3GP) box utilities. */
internal object Mp4Boxes {

    class Box(val type: String, val start: Long, val payloadStart: Long, val end: Long)

    fun forEachBox(raf: RandomAccessFile, start: Long, end: Long, action: (Box) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = readU32(raf)
            val type = readType(raf)
            var payloadStart = pos + 8
            if (size == 1L) {
                size = raf.readLong()
                payloadStart = pos + 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < 8 || pos + size > end) return // corrupt; stop walking
            action(Box(type, pos, payloadStart, pos + size))
            pos += size
        }
    }

    /**
     * 'meta' is a FullBox in ISO files but a bare box in QuickTime/Android
     * files. Returns the offset of the first child box.
     */
    fun metaChildStart(raf: RandomAccessFile, meta: Box): Long {
        raf.seek(meta.payloadStart + 4)
        val maybeType = readType(raf)
        return if (maybeType in setOf("hdlr", "keys", "ilst", "free")) {
            meta.payloadStart
        } else {
            meta.payloadStart + 4
        }
    }

    /** Parses a 'keys' box into a map of 1-based index → key name. */
    fun readKeys(raf: RandomAccessFile, keys: Box): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        raf.seek(keys.payloadStart + 4) // version/flags
        val count = readU32(raf).toInt()
        var pos = keys.payloadStart + 8
        for (index in 1..count) {
            if (pos + 8 > keys.end) break
            raf.seek(pos)
            val entrySize = readU32(raf)
            readType(raf) // namespace, e.g. mdta
            if (entrySize < 8 || pos + entrySize > keys.end) break
            val name = ByteArray((entrySize - 8).toInt())
            raf.readFully(name)
            result[index] = String(name, Charsets.UTF_8)
            pos += entrySize
        }
        return result
    }

    /** ilst entry types are either a 1-based keys index or a classic 4cc. */
    fun ilstEntryIndex(type: String): Int =
        type.toByteArray(Charsets.ISO_8859_1)
            .fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    fun keyNameCategory(name: String): MetaCategory {
        val lower = name.lowercase()
        return when {
            lower.contains("location") -> MetaCategory.LOCATION
            lower.contains("creationdate") -> MetaCategory.DATE
            lower.contains("make") || lower.contains("model") ||
                lower.contains("software") || lower.contains("version") ||
                lower.contains("manufacturer") -> MetaCategory.CAMERA
            else -> MetaCategory.OTHER
        }
    }

    fun fourCcCategory(type: String): MetaCategory = when (type) {
        "©xyz", "loci" -> MetaCategory.LOCATION
        "©day" -> MetaCategory.DATE
        "©mak", "©mod", "©swr", "©too" -> MetaCategory.CAMERA
        else -> MetaCategory.OTHER
    }

    fun readU32(raf: RandomAccessFile): Long = raf.readInt().toLong() and 0xFFFFFFFFL

    fun readType(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }
}
