package si.jakobkreft.exifremove.engine

import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import si.jakobkreft.exifremove.engine.Mp4Boxes.Box
import si.jakobkreft.exifremove.engine.Mp4Boxes.forEachBox
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
 * Compatibility note: Android's MPEG4Extractor (used by messengers to
 * validate incoming videos) is strict about the moov/meta/keys/ilst
 * structure. Entries inside 'ilst' are therefore never renamed or resized;
 * either the entire 'meta' box is freed, or entry *values* are blanked in
 * place, keeping the structure fully valid.
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

    // ------------------------------------------------------------- walking

    private fun walkTopLevel(ctx: Context, start: Long, end: Long) {
        forEachBox(ctx.raf, start, end) { box ->
            when (box.type) {
                "moov" -> walkMoov(ctx, box)
                "uuid" -> if (rule(ctx, MetaCategory.OTHER) == RuleAction.REMOVE) freeBox(ctx, box)
                "free", "skip" ->
                    if (rule(ctx, MetaCategory.OTHER) == RuleAction.REMOVE) zeroPayload(ctx, box)
                else -> Unit // ftyp, mdat, moof, …
            }
        }
    }

    private fun walkMoov(ctx: Context, moov: Box) {
        forEachBox(ctx.raf, moov.payloadStart, moov.end) { box ->
            when (box.type) {
                "mvhd", "tkhd", "mdhd" -> scrubTimestamps(ctx, box)
                "trak" -> walkMoov(ctx, box) // same child handling
                "mdia" -> walkMoov(ctx, box)
                "udta" -> walkUdta(ctx, box)
                "meta" -> walkMeta(ctx, box)
                "uuid" -> if (rule(ctx, MetaCategory.OTHER) == RuleAction.REMOVE) freeBox(ctx, box)
                "free", "skip" ->
                    if (rule(ctx, MetaCategory.OTHER) == RuleAction.REMOVE) zeroPayload(ctx, box)
                else -> Unit // minf/stbl/edts etc. are structural; don't descend
            }
        }
    }

    private fun walkUdta(ctx: Context, udta: Box) {
        forEachBox(ctx.raf, udta.payloadStart, udta.end) { box ->
            if (box.type == "meta") {
                walkMeta(ctx, box)
                return@forEachBox
            }
            val category = Mp4Boxes.fourCcCategory(box.type)
            when (rule(ctx, category)) {
                RuleAction.REMOVE -> freeBox(ctx, box)
                RuleAction.RANDOMIZE ->
                    if (category == MetaCategory.LOCATION && box.type == "©xyz") {
                        if (!randomizeUdtaXyz(ctx, box)) freeBox(ctx, box)
                    } else {
                        freeBox(ctx, box)
                    }
                RuleAction.KEEP -> Unit
            }
        }
    }

    private fun walkMeta(ctx: Context, meta: Box) {
        val childStart = Mp4Boxes.metaChildStart(ctx.raf, meta)

        var keys = mapOf<Int, String>()
        var ilst: Box? = null
        forEachBox(ctx.raf, childStart, meta.end) { box ->
            when (box.type) {
                "keys" -> keys = Mp4Boxes.readKeys(ctx.raf, box)
                "ilst" -> ilst = box
                else -> Unit
            }
        }
        val list = ilst ?: return

        class Entry(val box: Box, val category: MetaCategory)
        val entries = mutableListOf<Entry>()
        forEachBox(ctx.raf, list.payloadStart, list.end) { entry ->
            val keyName = keys[Mp4Boxes.ilstEntryIndex(entry.type)]
            val category = keyName?.let { Mp4Boxes.keyNameCategory(it) }
                ?: Mp4Boxes.fourCcCategory(entry.type)
            entries += Entry(entry, category)
        }
        if (entries.isEmpty()) return

        // If nothing must survive, free the whole meta box — a plain 'free'
        // child of moov/udta/trak, which every parser skips safely.
        if (entries.all { rule(ctx, it.category) == RuleAction.REMOVE }) {
            freeBox(ctx, meta)
            return
        }

        // Otherwise keep the structure byte-for-byte and only blank values.
        for (entry in entries) {
            when (rule(ctx, entry.category)) {
                RuleAction.KEEP -> Unit
                RuleAction.RANDOMIZE ->
                    if (entry.category == MetaCategory.LOCATION) {
                        if (!randomizeIlstLocation(ctx, entry.box)) blankIlstEntry(ctx, entry.box)
                    } else {
                        blankIlstEntry(ctx, entry.box)
                    }
                RuleAction.REMOVE -> blankIlstEntry(ctx, entry.box)
            }
        }
    }

    private fun rule(ctx: Context, category: MetaCategory): RuleAction = when (category) {
        MetaCategory.LOCATION -> ctx.template.gps
        MetaCategory.DATE -> ctx.template.dateTime
        MetaCategory.CAMERA -> ctx.template.cameraInfo
        MetaCategory.ORIENTATION -> RuleAction.KEEP // structural for video
        MetaCategory.OTHER -> ctx.template.otherExif
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

    /**
     * Blanks the value of an ilst entry without touching its structure:
     * UTF-8 values (type 1) become spaces, everything else becomes zeros.
     * Sizes, indexes and the 'data' headers stay byte-for-byte identical.
     */
    private fun blankIlstEntry(ctx: Context, entry: Box) {
        forEachBox(ctx.raf, entry.payloadStart, entry.end) { data ->
            if (data.type != "data") return@forEachBox
            ctx.raf.seek(data.payloadStart)
            val typeIndicator = Mp4Boxes.readU32(ctx.raf)
            val valueStart = data.payloadStart + 8
            if (valueStart >= data.end) return@forEachBox
            val fill = if (typeIndicator == 1L) ' '.code.toByte() else 0
            val filler = ByteArray((data.end - valueStart).toInt()) { fill }
            ctx.raf.seek(valueStart)
            ctx.raf.write(filler)
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
        forEachBox(ctx.raf, entry.payloadStart, entry.end) { data ->
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
}
