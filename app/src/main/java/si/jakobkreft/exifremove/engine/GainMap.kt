// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import java.io.File

/**
 * Ultra HDR support.
 *
 * An HDR photo is an ordinary JPEG with a second, single-channel JPEG — the
 * gain map — appended after it, plus an XMP block in the primary image saying
 * it is there. Viewers use the map to recover highlight detail; without it the
 * picture still decodes correctly, just without the extra range. Dropping it is
 * therefore safe but visibly flattens an HDR photo on an HDR screen, which is
 * why a template can choose to keep it.
 *
 * The map itself holds no identifying data: it is a downscaled luminance
 * derivative of the picture being shared, carrying only JFIF and the handful of
 * `hdrgm` floats a viewer needs. That is verified rather than assumed, in
 * [problemWith].
 *
 * The XMP that declares the container is *rebuilt from scratch* rather than
 * filtered, so none of the camera's own XMP can survive by accident.
 */
internal object GainMap {

    class Info(val offset: Int, val length: Int)

    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val HDRGM_NAMESPACE = "http://ns.adobe.com/hdr-gain-map/1.0/"

    /**
     * Finds a gain map appended to [file], or null when there is none.
     * A trailer only counts when it is a JPEG that declares `hdrgm`; a motion
     * photo's video, or any other appended payload, is not a gain map.
     */
    fun find(file: File): Info? = try {
        val bytes = file.readBytes()
        val end = primaryEnd(bytes) ?: return null
        if (end >= bytes.size) return null
        val trailer = bytes.copyOfRange(end, bytes.size)
        val length = jpegLength(trailer) ?: return null
        val candidate = trailer.copyOfRange(0, length)
        if (!contains(candidate, HDRGM_NAMESPACE.toByteArray(Charsets.ISO_8859_1))) return null
        Info(end, length)
    } catch (e: Exception) {
        null
    }

    /**
     * Writes the container declaration and the map into an already-cleaned
     * file.
     *
     * This has to happen after every other edit. ExifInterface.saveAttributes
     * rewrites the whole JPEG, which discards both an appended gain map and
     * the XMP describing it, so attaching earlier would silently lose them.
     */
    fun attach(file: File, map: ByteArray) {
        val cleaned = file.readBytes()
        require(cleaned.size >= 2 && u8(cleaned, 0) == 0xFF && u8(cleaned, 1) == 0xD8) {
            "not a JPEG"
        }
        val payload = xmpPayload(map.size)
        val segmentLength = payload.size + 2
        file.outputStream().buffered().use { out ->
            out.write(cleaned, 0, 2) // SOI
            out.write(0xFF); out.write(0xE1)
            out.write((segmentLength shr 8) and 0xFF); out.write(segmentLength and 0xFF)
            out.write(payload)
            out.write(cleaned, 2, cleaned.size - 2)
            out.write(map)
        }
    }

    /** Reads the gain map's bytes out of [file]. */
    fun read(file: File, info: Info): ByteArray {
        val bytes = file.readBytes()
        return bytes.copyOfRange(info.offset, info.offset + info.length)
    }

    /**
     * The APP1 payload declaring a two-item container: the picture, then the
     * gain map. Generated, so the verifier can demand an exact match and no
     * unexpected XMP can slip through alongside it.
     */
    fun xmpPayload(gainMapLength: Int): ByteArray {
        val xml = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>""" +
            """<x:xmpmeta xmlns:x="adobe:ns:meta/">""" +
            """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" """ +
            """xmlns:hdrgm="$HDRGM_NAMESPACE" """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/" """ +
            """xmlns:Item="http://ns.google.com/photos/1.0/container/item/" """ +
            """hdrgm:Version="1.0">""" +
            """<Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item """ +
            """Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Length="0"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item """ +
            """Item:Semantic="GainMap" Item:Mime="image/jpeg" """ +
            """Item:Length="$gainMapLength"/></rdf:li>""" +
            """</rdf:Seq></Container:Directory>""" +
            """</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
        return XMP_HEADER.toByteArray(Charsets.ISO_8859_1) + xml.toByteArray(Charsets.UTF_8)
    }

    /**
     * Checks a gain map carries nothing but rendering data. Returns a reason
     * when it does not, so the caller can refuse the file.
     */
    fun problemWith(map: ByteArray): String? {
        if (map.size < 4 || u8(map, 0) != 0xFF || u8(map, 1) != 0xD8) {
            return "gain map is not a JPEG"
        }
        var declaresHdrgm = false
        var pos = 2
        while (true) {
            if (pos + 1 >= map.size) return "gain map is truncated"
            if (u8(map, pos) != 0xFF) return "gain map has a malformed segment"
            val marker = u8(map, pos + 1)
            when {
                marker == 0xD9 ->
                    return if (pos + 2 == map.size && declaresHdrgm) null
                    else if (!declaresHdrgm) "gain map does not declare hdrgm"
                    else "gain map has trailing data"

                marker == 0xDA -> {
                    var i = pos + 2
                    while (i + 1 < map.size) {
                        if (u8(map, i) == 0xFF && u8(map, i + 1) == 0xD9) {
                            return if (i + 2 == map.size && declaresHdrgm) null
                            else if (!declaresHdrgm) "gain map does not declare hdrgm"
                            else "gain map has trailing data"
                        }
                        i++
                    }
                    return "gain map scan is unterminated"
                }

                marker in 0xD0..0xD7 || marker == 0x01 -> pos += 2

                else -> {
                    if (pos + 3 >= map.size) return "gain map segment header is truncated"
                    val size = (u8(map, pos + 2) shl 8) or u8(map, pos + 3)
                    if (size < 2 || pos + 2 + size > map.size) return "gain map segment overruns"
                    val start = pos + 4
                    val length = size - 2
                    when (marker) {
                        0xE0 -> Unit // JFIF: structural
                        0xE1 -> {
                            // The only metadata a gain map may carry is its own
                            // rendering parameters. EXIF in particular is not.
                            if (!startsWith(map, start, length, XMP_HEADER)) {
                                return "gain map carries a non-XMP APP1"
                            }
                            val text = String(map, start, length, Charsets.ISO_8859_1)
                            if (!text.contains("hdrgm")) return "gain map XMP is not hdrgm data"
                            for (bad in listOf("exif:", "tiff:", "GPS", "GCamera", "photoshop:")) {
                                if (text.contains(bad)) return "gain map XMP carries $bad data"
                            }
                            declaresHdrgm = true
                        }
                        else ->
                            if (marker in 0xE2..0xEF || marker == 0xFE) {
                                return "gain map carries an unexpected metadata segment"
                            }
                    }
                    pos += 2 + size
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** Offset just past the primary image's end-of-image marker. */
    private fun primaryEnd(b: ByteArray): Int? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var pos = 2
        while (pos + 1 < b.size) {
            if (u8(b, pos) != 0xFF) return null
            val marker = u8(b, pos + 1)
            when {
                marker == 0xD9 -> return pos + 2
                marker == 0xDA -> {
                    var i = pos + 2
                    while (i + 1 < b.size) {
                        if (u8(b, i) == 0xFF && u8(b, i + 1) == 0xD9) return i + 2
                        i++
                    }
                    return null
                }
                marker in 0xD0..0xD7 || marker == 0x01 -> pos += 2
                else -> {
                    if (pos + 3 >= b.size) return null
                    val size = (u8(b, pos + 2) shl 8) or u8(b, pos + 3)
                    if (size < 2) return null
                    pos += 2 + size
                }
            }
        }
        return null
    }

    /** Length of the JPEG starting at the beginning of [b], if it is one. */
    private fun jpegLength(b: ByteArray): Int? = primaryEnd(b)

    private fun startsWith(b: ByteArray, start: Int, length: Int, prefix: String): Boolean {
        if (length < prefix.length) return false
        for (i in prefix.indices) {
            if (b[start + i] != prefix[i].code.toByte()) return false
        }
        return true
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }
}
