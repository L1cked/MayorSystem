package mayorSystem.domain.perk

data class PerkCatalogEntry(
    val id: String,
    val sectionId: String,
    val enabled: Boolean = true
)

data class PerkSectionRule(
    val sectionId: String,
    val pickLimit: Int?
)

data class PerkSelectionInput(
    val selectedPerkIds: Set<String>,
    val requiredTotal: Int,
    val catalog: Collection<PerkCatalogEntry>,
    val sectionRules: Collection<PerkSectionRule>
)

sealed class PerkSelectionResult {
    data object Allowed : PerkSelectionResult()
    data class WrongTotal(val required: Int, val selected: Int) : PerkSelectionResult()
    data class UnknownPerks(val ids: Set<String>) : PerkSelectionResult()
    data class DisabledPerks(val ids: Set<String>) : PerkSelectionResult()
    data class SectionLimitExceeded(val sectionId: String, val limit: Int, val selected: Int) : PerkSelectionResult()
}

class PerkSelectionPolicy {
    fun evaluate(input: PerkSelectionInput): PerkSelectionResult {
        if (input.selectedPerkIds.size != input.requiredTotal) {
            return PerkSelectionResult.WrongTotal(input.requiredTotal, input.selectedPerkIds.size)
        }

        val catalogById = input.catalog.associateBy { it.id }
        val unknown = input.selectedPerkIds.filterNot { it.startsWith("custom:", ignoreCase = true) || it in catalogById }.toSet()
        if (unknown.isNotEmpty()) return PerkSelectionResult.UnknownPerks(unknown)

        val disabled = input.selectedPerkIds
            .mapNotNull { catalogById[it] }
            .filterNot { it.enabled }
            .map { it.id }
            .toSet()
        if (disabled.isNotEmpty()) return PerkSelectionResult.DisabledPerks(disabled)

        val sectionLimits = input.sectionRules
            .mapNotNull { rule -> rule.pickLimit?.takeIf { it >= 0 }?.let { rule.sectionId to it } }
            .toMap()

        val counts = linkedMapOf<String, Int>()
        for (perkId in input.selectedPerkIds) {
            val sectionId = if (perkId.startsWith("custom:", ignoreCase = true)) {
                "custom"
            } else {
                catalogById[perkId]?.sectionId ?: continue
            }
            counts[sectionId] = (counts[sectionId] ?: 0) + 1
        }

        for ((sectionId, selected) in counts) {
            val limit = sectionLimits[sectionId] ?: continue
            if (selected > limit) {
                return PerkSelectionResult.SectionLimitExceeded(sectionId, limit, selected)
            }
        }

        return PerkSelectionResult.Allowed
    }
}
