package si.jakobkreft.exifremove.engine

import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template

/** What happened to one piece of metadata between the original and the clean copy. */
enum class ChangeStatus { REMOVED, KEPT, CHANGED, ADDED }

data class MetaChange(
    val entry: MetaEntry,
    val status: ChangeStatus,
    /** The value in the cleaned file, when it differs from the original. */
    val newValue: String? = null,
    /** True when the change came from a RANDOMIZE rule, not an edit side effect. */
    val randomized: Boolean = false,
)

/**
 * Compares the metadata of an original against its cleaned copy.
 *
 * Shared by the metadata viewer (previewing a template) and by the share
 * flow's cleaning report (describing a clean that already happened), so
 * both describe a file in exactly the same terms.
 */
object MetadataDiff {

    fun compare(
        before: List<MetaEntry>,
        after: List<MetaEntry>?,
        template: Template,
    ): List<MetaChange> {
        // A null "after" means the format could not be cleaned in place and
        // was re-encoded instead, which removes everything.
        if (after == null) return before.map { MetaChange(it, ChangeStatus.REMOVED) }

        val afterByName = after.groupBy { it.name }
        val beforeNames = before.map { it.name }.toSet()

        val changes = before.map { entry ->
            val match = afterByName[entry.name]?.firstOrNull()
            val randomized = template.ruleFor(entry.category) == RuleAction.RANDOMIZE
            when {
                match == null -> MetaChange(entry, ChangeStatus.REMOVED)
                match.value.isBlank() -> MetaChange(entry, ChangeStatus.REMOVED)
                match.value.trim() == entry.value.trim() -> MetaChange(entry, ChangeStatus.KEPT)
                else -> MetaChange(entry, ChangeStatus.CHANGED, match.value, randomized)
            }
        }

        // Entries that exist only after cleaning, e.g. a scrambled location
        // written into a file that never had one.
        val added = after
            .filter { it.name !in beforeNames && it.value.isNotBlank() }
            .map { entry ->
                MetaChange(
                    entry,
                    ChangeStatus.ADDED,
                    randomized = template.ruleFor(entry.category) == RuleAction.RANDOMIZE,
                )
            }

        return changes + added
    }

    fun Template.ruleFor(category: MetaCategory): RuleAction = when (category) {
        MetaCategory.LOCATION -> gps
        MetaCategory.DATE -> dateTime
        MetaCategory.CAMERA -> cameraInfo
        MetaCategory.ORIENTATION -> otherExif
        MetaCategory.OTHER -> otherExif
    }
}
