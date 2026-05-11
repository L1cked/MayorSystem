package mayorSystem.domain.perk

import kotlin.test.Test
import kotlin.test.assertEquals

class PerkSelectionPolicyTest {
    private val policy = PerkSelectionPolicy()
    private val catalog = listOf(
        PerkCatalogEntry("wheat", "farming", enabled = true),
        PerkCatalogEntry("ore", "mining", enabled = true),
        PerkCatalogEntry("coal", "mining", enabled = true),
        PerkCatalogEntry("disabled", "mining", enabled = false)
    )
    private val rules = listOf(
        PerkSectionRule("farming", pickLimit = 1),
        PerkSectionRule("mining", pickLimit = 1)
    )

    @Test
    fun `selection requires exact total before other checks`() {
        assertEquals(
            PerkSelectionResult.WrongTotal(required = 2, selected = 1),
            policy.evaluate(input(selected = setOf("wheat"), requiredTotal = 2))
        )
    }

    @Test
    fun `selection rejects unknown non custom perks`() {
        assertEquals(
            PerkSelectionResult.UnknownPerks(setOf("missing")),
            policy.evaluate(input(selected = setOf("wheat", "missing")))
        )
    }

    @Test
    fun `selection rejects disabled catalog perks`() {
        assertEquals(
            PerkSelectionResult.DisabledPerks(setOf("disabled")),
            policy.evaluate(input(selected = setOf("wheat", "disabled")))
        )
    }

    @Test
    fun `selection enforces section limits and allows custom ids`() {
        assertEquals(
            PerkSelectionResult.SectionLimitExceeded("mining", limit = 1, selected = 2),
            policy.evaluate(input(selected = setOf("ore", "coal")))
        )
        assertEquals(
            PerkSelectionResult.Allowed,
            policy.evaluate(input(selected = setOf("wheat", "bonus".custom())))
        )
    }

    private fun input(selected: Set<String>, requiredTotal: Int = selected.size): PerkSelectionInput =
        PerkSelectionInput(
            selectedPerkIds = selected,
            requiredTotal = requiredTotal,
            catalog = catalog,
            sectionRules = rules
        )

    private fun String.custom(): String = "custom:$this"
}
