package si.jakobkreft.exifremove.engine

import si.jakobkreft.exifremove.engine.Mp4Boxes.Box
import si.jakobkreft.exifremove.engine.Mp4Boxes.forEachBox
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Reads the metadata of an ISO BMFF (MP4/MOV/3GP) file for display. */
object Mp4MetadataReader {

    private const val QT_EPOCH_OFFSET = 2082844800L
    private val DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

    fun read(file: File): List<MetaEntry> {
        val entries = mutableListOf<MetaEntry>()
        RandomAccessFile(file, "r").use { raf ->
            forEachBox(raf, 0, raf.length()) { box ->
                when (box.type) {
                    "moov" -> readMoov(raf, box, entries)
                    "uuid" -> entries += MetaEntry(
                        MetaCategory.OTHER, "UUID box (XMP)",
                        "${box.end - box.payloadStart} bytes",
                    )
                    else -> Unit
                }
            }
        }
        return entries.distinct()
    }

    private fun readMoov(raf: RandomAccessFile, moov: Box, entries: MutableList<MetaEntry>) {
        forEachBox(raf, moov.payloadStart, moov.end) { box ->
            when (box.type) {
                "mvhd" -> readTimes(raf, box, "Movie", entries)
                "tkhd" -> readTimes(raf, box, "Track", entries)
                "mdhd" -> readTimes(raf, box, "Media", entries)
                "trak", "mdia" -> readMoov(raf, box, entries)
                "udta" -> readUdta(raf, box, entries)
                "meta" -> readMeta(raf, box, entries)
                else -> Unit
            }
        }
    }

    private fun readTimes(raf: RandomAccessFile, box: Box, label: String, entries: MutableList<MetaEntry>) {
        raf.seek(box.payloadStart)
        val version = raf.read()
        raf.seek(box.payloadStart + 4)
        val creation = if (version == 1) raf.readLong() else Mp4Boxes.readU32(raf)
        if (creation != 0L) {
            val instant = Instant.ofEpochSecond(creation - QT_EPOCH_OFFSET)
            entries += MetaEntry(MetaCategory.DATE, "$label created", DATE_FORMAT.format(instant))
        }
    }

    private fun readUdta(raf: RandomAccessFile, udta: Box, entries: MutableList<MetaEntry>) {
        forEachBox(raf, udta.payloadStart, udta.end) { box ->
            when (box.type) {
                "meta" -> readMeta(raf, box, entries)
                "free", "skip" -> Unit
                "©xyz" -> {
                    raf.seek(box.payloadStart)
                    val length = raf.readUnsignedShort()
                    raf.seek(box.payloadStart + 4)
                    val bytes = ByteArray(minOf(length.toLong(), box.end - box.payloadStart - 4).toInt().coerceAtLeast(0))
                    raf.readFully(bytes)
                    entries += MetaEntry(MetaCategory.LOCATION, "Location", String(bytes, Charsets.UTF_8))
                }
                else -> {
                    val category = Mp4Boxes.fourCcCategory(box.type)
                    val name = when (box.type) {
                        "©mak" -> "Make"
                        "©mod" -> "Model"
                        "©swr", "©too" -> "Software"
                        "©day" -> "Creation date"
                        "loci" -> "Location info"
                        else -> "'${box.type}'"
                    }
                    entries += MetaEntry(category, name, readPrintable(raf, box))
                }
            }
        }
    }

    private fun readMeta(raf: RandomAccessFile, meta: Box, entries: MutableList<MetaEntry>) {
        val childStart = Mp4Boxes.metaChildStart(raf, meta)
        var keys = mapOf<Int, String>()
        var ilst: Box? = null
        forEachBox(raf, childStart, meta.end) { box ->
            when (box.type) {
                "keys" -> keys = Mp4Boxes.readKeys(raf, box)
                "ilst" -> ilst = box
                else -> Unit
            }
        }
        val list = ilst ?: return
        forEachBox(raf, list.payloadStart, list.end) { entry ->
            val keyName = keys[Mp4Boxes.ilstEntryIndex(entry.type)]
            val category = keyName?.let { Mp4Boxes.keyNameCategory(it) }
                ?: Mp4Boxes.fourCcCategory(entry.type)
            val displayName = keyName
                ?.removePrefix("com.apple.quicktime.")
                ?.removePrefix("com.android.")
                ?: "'${entry.type}'"
            forEachBox(raf, entry.payloadStart, entry.end) { data ->
                if (data.type != "data") return@forEachBox
                entries += MetaEntry(category, displayName, readDataValue(raf, data))
            }
        }
    }

    /** 'data' atom: [type indicator 4][locale 4][value]. */
    private fun readDataValue(raf: RandomAccessFile, data: Box): String {
        raf.seek(data.payloadStart)
        val type = Mp4Boxes.readU32(raf)
        val valueStart = data.payloadStart + 8
        val length = (data.end - valueStart).toInt()
        if (length <= 0) return ""
        raf.seek(valueStart)
        return when (type) {
            1L -> { // UTF-8
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).trim()
            }
            23L -> java.lang.Float.intBitsToFloat(raf.readInt()).toString() // BE float32
            21L, 22L -> { // signed/unsigned big-endian int
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }.toString()
            }
            else -> "$length bytes"
        }
    }

    private fun readPrintable(raf: RandomAccessFile, box: Box): String {
        val length = (box.end - box.payloadStart).toInt()
        if (length <= 0) return ""
        val bytes = ByteArray(minOf(length, 256))
        raf.seek(box.payloadStart)
        raf.readFully(bytes)
        val printable = bytes.count { it in 32..126 || it.toInt() == 10 }
        return if (printable >= bytes.size * 3 / 4) {
            String(bytes, Charsets.UTF_8).filter { it.code >= 32 }.trim()
        } else {
            "$length bytes"
        }
    }
}
