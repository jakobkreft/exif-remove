package si.jakobkreft.exifremove.engine

import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Removes metadata from ISO BMFF containers (MP4, MOV, 3GP) **in place,
 * without ever changing the file size**. Chunk-offset tables (stco/co64)
 * reference absolute file positions, so instead of deleting boxes this
 * scrubber renames them to `free` and zero-fills their payload. Audio and
 * video streams are never touched or re-encoded.
 *
 * Category mapping:
 *  - gps        → ©xyz / loci user-data boxes, *.location.* keys
 *  - dateTime   → mvhd/tkhd/mdhd timestamps, ©day, *.creationdate keys
 *  - cameraInfo → ©mak/©mod/©swr/©too, make/model/software/version keys
 *  - otherExif  → uuid boxes (XMP), existing free-box payloads,
 *                 all remaining user-data boxes and keys
 */
object Mp4Scrubber {

    private const val QT_EPOCH_OFFSET = 2082844800L // 1904-01-01 → 1970-01-01

    fun isMp4(file: File): Boolean {
        if (file.length() < 12) return false
        val header = ByteArray(8)
        file.inputStream().use { it.read(header) }
        val type = String(header, 4, 4, Charsets.US_ASCII)
        return type in setOf("ftyp", "moov", "mdat", "free", "skip", "wide")
    }

    fun scrub(file: File, template: Template) {
        RandomAccessFile(file, "rw").use { raf ->
            val ctx = Context(
                raf = raf,
                template = template,
                randomTime = if (template.dateTime == RuleAction.RANDOMIZE) {
                    val past = System.currentTimeMillis() / 1000 -
                        Random.nextLong(0L, 20L * 365 * 24 * 60 * 60)
                    past + QT_EPOCH_OFFSET
                } else 0L,
            )
            walkTopLevel(ctx, 0, raf.length())
        }
    }

    private class Context(
        val raf: RandomAccessFile,
        val template: Template,
        val randomTime: Long,
    )

    private class Box(val type: String, val start: Long, val payloadStart: Long, val end: Long)

    // ------------------------------------------------------------- walking

    private fun forEachBox(ctx: Context, start: Long, end: Long, action: (Box) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            ctx.raf.seek(pos)
            var size = readU32(ctx.raf)
            val type = readType(ctx.raf)
            var payloadStart = pos + 8
            if (size == 1L) {
                size = ctx.raf.readLong()
                payloadStart = pos + 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < 8 || pos + size > end) return // corrupt; stop walking
            action(Box(type, pos, payloadStart, pos + size))
            pos += size
        }
    }

    private fun walkTopLevel(ctx: Context, start: Long, end: Long) {
        forEachBox(ctx, start, end) { box ->
            when (box.type) {
                "moov" -> walkMoov(ctx, box)
                "uuid" -> if (rule(ctx, Category.OTHER) == RuleAction.REMOVE) freeBox(ctx, box)
                "free", "skip" ->
                    if (rule(ctx, Category.OTHER) == RuleAction.REMOVE) zeroPayload(ctx, box)
                else -> Unit // ftyp, mdat, moof, …
            }
        }
    }

    private fun walkMoov(ctx: Context, moov: Box) {
        forEachBox(ctx, moov.payloadStart, moov.end) { box ->
            when (box.type) {
                "mvhd", "tkhd", "mdhd" -> scrubTimestamps(ctx, box)
                "trak" -> walkMoov(ctx, box) // same child handling
                "mdia" -> walkMoov(ctx, box)
                "udta" -> walkUdta(ctx, box)
                "meta" -> walkMeta(ctx, box)
                "uuid" -> if (rule(ctx, Category.OTHER) == RuleAction.REMOVE) freeBox(ctx, box)
                "free", "skip" ->
                    if (rule(ctx, Category.OTHER) == RuleAction.REMOVE) zeroPayload(ctx, box)
                else -> Unit // minf/stbl/edts etc. are structural; don't descend
            }
        }
    }

    private fun walkUdta(ctx: Context, udta: Box) {
        forEachBox(ctx, udta.payloadStart, udta.end) { box ->
            if (box.type == "meta") {
                walkMeta(ctx, box)
                return@forEachBox
            }
            val category = fourCcCategory(box.type)
            when (rule(ctx, category)) {
                RuleAction.REMOVE -> freeBox(ctx, box)
                RuleAction.RANDOMIZE ->
                    if (category == Category.GPS && box.type == "©xyz") {
                        if (!randomizeUdtaXyz(ctx, box)) freeBox(ctx, box)
                    } else {
                        freeBox(ctx, box)
                    }
                RuleAction.KEEP -> Unit
            }
        }
    }

    private fun walkMeta(ctx: Context, meta: Box) {
        // 'meta' is a FullBox in ISO files but a bare box in QuickTime.
        // Sniff: if the first child looks like a box, there is no version field.
        ctx.raf.seek(meta.payloadStart + 4)
        val maybeType = readType(ctx.raf)
        val childStart = if (maybeType in setOf("hdlr", "keys", "ilst", "free")) {
            meta.payloadStart
        } else {
            meta.payloadStart + 4
        }

        // First pass: collect the key list (mdta name per 1-based index)
        val keys = mutableMapOf<Int, String>()
        var ilst: Box? = null
        forEachBox(ctx, childStart, meta.end) { box ->
            when (box.type) {
                "keys" -> {
                    ctx.raf.seek(box.payloadStart + 4) // version/flags
                    val count = readU32(ctx.raf).toInt()
                    var pos = box.payloadStart + 8
                    for (index in 1..count) {
                        if (pos + 8 > box.end) break
                        ctx.raf.seek(pos)
                        val entrySize = readU32(ctx.raf)
                        readType(ctx.raf) // namespace, e.g. mdta
                        if (entrySize < 8 || pos + entrySize > box.end) break
                        val name = ByteArray((entrySize - 8).toInt())
                        ctx.raf.readFully(name)
                        keys[index] = String(name, Charsets.UTF_8)
                        pos += entrySize
                    }
                }
                "ilst" -> ilst = box
                else -> Unit
            }
        }

        val list = ilst ?: return
        forEachBox(ctx, list.payloadStart, list.end) { entry ->
            // Entry "type" is either a 1-based index into keys (mdta style)
            // or a classic 4cc (iTunes style).
            val index = entry.type.toByteArray(Charsets.ISO_8859_1)
                .fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val keyName = keys[index]
            val category = keyName?.let { keyNameCategory(it) } ?: fourCcCategory(entry.type)
            when (rule(ctx, category)) {
                RuleAction.REMOVE -> freeBox(ctx, entry)
                RuleAction.RANDOMIZE ->
                    if (category == Category.GPS) {
                        if (!randomizeIlstLocation(ctx, entry)) freeBox(ctx, entry)
                    } else {
                        freeBox(ctx, entry)
                    }
                RuleAction.KEEP -> Unit
            }
        }
    }

    // ---------------------------------------------------------- categories

    private enum class Category { GPS, DATE, CAMERA, OTHER }

    private fun rule(ctx: Context, category: Category): RuleAction = when (category) {
        Category.GPS -> ctx.template.gps
        Category.DATE -> ctx.template.dateTime
        Category.CAMERA -> ctx.template.cameraInfo
        Category.OTHER -> ctx.template.otherExif
    }

    private fun keyNameCategory(name: String): Category {
        val lower = name.lowercase()
        return when {
            lower.contains("location") -> Category.GPS
            lower.contains("creationdate") -> Category.DATE
            lower.contains("make") || lower.contains("model") ||
                lower.contains("software") || lower.contains("version") ||
                lower.contains("manufacturer") -> Category.CAMERA
            else -> Category.OTHER
        }
    }

    private fun fourCcCategory(type: String): Category = when (type) {
        "©xyz", "loci" -> Category.GPS
        "©day" -> Category.DATE
        "©mak", "©mod", "©swr", "©too" -> Category.CAMERA
        else -> Category.OTHER
    }

    // ------------------------------------------------------------- editing

    /** Renames a box to `free` and zero-fills its payload. Size unchanged. */
    private fun freeBox(ctx: Context, box: Box) {
        ctx.raf.seek(box.start + 4)
        ctx.raf.write("free".toByteArray(Charsets.US_ASCII))
        zeroRange(ctx, box.payloadStart, box.end)
    }

    private fun zeroPayload(ctx: Context, box: Box) {
        zeroRange(ctx, box.payloadStart, box.end)
    }

    private fun zeroRange(ctx: Context, from: Long, to: Long) {
        if (to <= from) return
        ctx.raf.seek(from)
        val zeros = ByteArray(8192)
        var remaining = to - from
        while (remaining > 0) {
            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
            ctx.raf.write(zeros, 0, chunk)
            remaining -= chunk
        }
    }

    /** mvhd/tkhd/mdhd: FullBox, creation+modification right after version/flags. */
    private fun scrubTimestamps(ctx: Context, box: Box) {
        when (ctx.template.dateTime) {
            RuleAction.KEEP -> return
            RuleAction.REMOVE, RuleAction.RANDOMIZE -> Unit
        }
        ctx.raf.seek(box.payloadStart)
        val version = ctx.raf.read()
        val value = if (ctx.template.dateTime == RuleAction.RANDOMIZE) ctx.randomTime else 0L
        ctx.raf.seek(box.payloadStart + 4)
        if (version == 1) {
            ctx.raf.writeLong(value)
            ctx.raf.writeLong(value)
        } else {
            ctx.raf.writeInt(value.toInt())
            ctx.raf.writeInt(value.toInt())
        }
    }

    /** ©xyz payload: [2-byte length][2-byte language][ISO6709 string]. */
    private fun randomizeUdtaXyz(ctx: Context, box: Box): Boolean {
        val stringLength = (box.end - box.payloadStart - 4).toInt()
        val replacement = randomIso6709(stringLength) ?: return false
        ctx.raf.seek(box.payloadStart)
        ctx.raf.writeShort(replacement.length)
        // keep the language code as-is
        ctx.raf.seek(box.payloadStart + 4)
        ctx.raf.write(replacement.toByteArray(Charsets.US_ASCII))
        return true
    }

    /** ilst entry: [data box: type 4 | locale 4 | payload]. */
    private fun randomizeIlstLocation(ctx: Context, entry: Box): Boolean {
        var replaced = false
        forEachBox(ctx, entry.payloadStart, entry.end) { data ->
            if (data.type != "data") return@forEachBox
            val payloadLength = (data.end - data.payloadStart - 8).toInt()
            val replacement = randomIso6709(payloadLength) ?: return@forEachBox
            ctx.raf.seek(data.payloadStart + 8)
            ctx.raf.write(replacement.toByteArray(Charsets.US_ASCII))
            replaced = true
        }
        return replaced
    }

    /**
     * Builds a random ISO 6709 string of exactly [length] bytes,
     * e.g. "+46.0511+014.5051/". Returns null when it can't fit.
     */
    private fun randomIso6709(length: Int): String? {
        if (length < 14) return null
        val lat = Random.nextDouble(-55.0, 70.0)
        val lon = Random.nextDouble(-180.0, 180.0)
        // "+DD.DDDD" (8) + "+DDD.DDDD" (9) + "/" (1) = 18 base characters
        var latDecimals = 4
        var lonDecimals = 4
        var total = 18
        while (total > length && lonDecimals > 1) { lonDecimals--; total-- }
        while (total > length && latDecimals > 1) { latDecimals--; total-- }
        while (total < length) { lonDecimals++; total++ }
        if (total != length) return null
        val latStr = String.format(java.util.Locale.US, "%+0${latDecimals + 4}.${latDecimals}f", lat)
        val lonStr = String.format(java.util.Locale.US, "%+0${lonDecimals + 5}.${lonDecimals}f", lon)
        return "$latStr$lonStr/"
    }

    // ------------------------------------------------------------- helpers

    private fun readU32(raf: RandomAccessFile): Long = raf.readInt().toLong() and 0xFFFFFFFFL

    private fun readType(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }
}
