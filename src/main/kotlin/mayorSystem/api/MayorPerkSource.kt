package mayorSystem.api

data class MayorPerkSection(
    val id: String,
    val enabled: Boolean,
    val pickLimit: Int,
    val displayName: String,
    val icon: String,
    val perks: List<MayorPerkDefinition>
)

data class MayorPerkDefinition(
    val id: String,
    val enabled: Boolean,
    val displayName: String,
    val icon: String,
    val lore: List<String>,
    val adminLore: List<String>,
    val onStart: List<String> = emptyList(),
    val onEnd: List<String> = emptyList()
)

interface MayorPerkSource {
    val id: String
    val displayName: String
    val available: Boolean

    fun sections(): List<MayorPerkSection>
}
