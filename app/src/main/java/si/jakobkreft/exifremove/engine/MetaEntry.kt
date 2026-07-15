package si.jakobkreft.exifremove.engine

/** One human-readable piece of metadata found in a file. */
data class MetaEntry(
    val category: MetaCategory,
    val name: String,
    val value: String,
)
