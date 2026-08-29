package si.jakobkreft.exifremove.engine

/** What an appended payload turned out to be, identified by its magic bytes. */
enum class TrailerKind { VIDEO, IMAGE, UNKNOWN }

/** What kind of thing the cleaner found and dealt with. */
enum class FindingKind {
    /** Bytes appended after the image ended: a motion photo's video, a gain map, a vendor trailer. */
    TRAILING_DATA,

    /** An embedded thumbnail — a small but complete second copy of the picture. */
    EMBEDDED_THUMBNAIL,

    /** Metadata segments/chunks removed from the container (EXIF, XMP, IPTC, …). */
    METADATA_SEGMENT,

    /** Metadata boxes cleared inside a video container. */
    VIDEO_BOXES,

    /** The image had to be decoded and re-encoded to be cleanable at all. */
    RE_ENCODED,
}

/**
 * One thing the cleaner did, in structural terms rather than tag terms.
 * These are the removals a metadata listing cannot show, because after
 * cleaning there is nothing left to list — the whole point is that the
 * user would otherwise never learn a motion photo's video was in there.
 */
data class Finding(
    val kind: FindingKind,
    val count: Int = 1,
    val bytes: Long = 0,
    /** Container-level names, e.g. "APP1", "XMP ", "eXIf". */
    val names: List<String> = emptyList(),
    /** For TRAILING_DATA: what the appended payload was. */
    val trailer: TrailerKind = TrailerKind.UNKNOWN,
)

/**
 * Collects findings while a file is rewritten. Passed down into the
 * strippers so the report describes what actually happened to this file
 * rather than what the template intended.
 */
class StripLog {
    private val segmentNames = mutableListOf<String>()
    private var segmentBytes = 0L
    private var trailingBytes = 0L
    private var thumbnailBytes = 0L
    private var videoBoxes = 0
    private var reEncoded = false
    private var trailerKind = TrailerKind.UNKNOWN

    fun droppedSegment(name: String, bytes: Long) {
        if (name !in segmentNames) segmentNames += name
        segmentBytes += bytes
    }

    fun droppedTrailing(bytes: Long) {
        trailingBytes += bytes
    }

    fun trailerKind(kind: TrailerKind) {
        trailerKind = kind
    }

    fun droppedThumbnail(bytes: Long) {
        thumbnailBytes += bytes
    }

    fun clearedVideoBox() {
        videoBoxes++
    }

    fun reEncoded() {
        reEncoded = true
    }

    fun findings(): List<Finding> = buildList {
        if (trailingBytes > 0) {
            add(Finding(FindingKind.TRAILING_DATA, bytes = trailingBytes, trailer = trailerKind))
        }
        if (thumbnailBytes > 0) {
            add(Finding(FindingKind.EMBEDDED_THUMBNAIL, bytes = thumbnailBytes))
        }
        if (segmentNames.isNotEmpty()) {
            add(
                Finding(
                    FindingKind.METADATA_SEGMENT,
                    count = segmentNames.size,
                    bytes = segmentBytes,
                    names = segmentNames.toList(),
                )
            )
        }
        if (videoBoxes > 0) {
            add(Finding(FindingKind.VIDEO_BOXES, count = videoBoxes))
        }
        if (reEncoded) {
            add(Finding(FindingKind.RE_ENCODED))
        }
    }
}
