package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.perks.PerkDef
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import java.util.UUID

class MayorProfileMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val mayor: UUID,
    private val mayorName: String? = null,
    private val backTo: () -> Menu
) : Menu(plugin) {

    override val title: Component = gc("menus.mayor_profile.title", mapOf("name" to g("menus.mayor_profile.default_name")))
    override val rows: Int = 4

    private var perkPage: Int = 0

    override fun titleFor(player: Player): Component =
        gc("menus.mayor_profile.title", mapOf("name" to displayMayorName()))

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val name = displayMayorName()
        val profileName = profileMayorName()
        val votes = plugin.store.voteCounts(term)[mayor] ?: 0

        val bioRaw = plugin.store.candidateBio(term, mayor).trim()
        fun escapeMm(input: String): String = input.replace("<", "").replace(">", "")

        val bioLinesMm = if (bioRaw.isBlank()) {
            listOf(g("menus.mayor_profile.bio.empty"))
        } else {
            val wrapped = wrapLore(bioRaw, 34)
            val shown = wrapped.take(8).map { g("menus.mayor_profile.bio.line", mapOf("line" to escapeMm(it))) }
            if (wrapped.size > shown.size) shown + g("menus.mayor_profile.bio.more") else shown
        }

        val chosenIds = plugin.store.chosenPerks(term, mayor).toList()
        val defsById = plugin.perks.availablePerksForCandidate(term, mayor).associateBy { it.id }
        val perks: List<PerkDef> = chosenIds
            .map { id -> defsById[id] ?: unknownDef(id) }
            .sortedBy { it.displayNameMm.lowercase() }

        val headLore = buildList {
            add(g("menus.mayor_profile.head.lore.votes", mapOf("votes" to votes.toString())))
            add("")
            add(g("menus.mayor_profile.head.lore.bio_header"))
            bioLinesMm.forEach { add(it) }
        }

        val head = playerHead(
            mayor,
            profileName,
            g("menus.mayor_profile.head.name", mapOf("name" to name)),
            headLore
        )
        inv.setItem(13, head)

        val pageSize = 5
        val totalPages = ((perks.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        perkPage = perkPage.coerceIn(0, totalPages - 1)

        val from = perkPage * pageSize
        val pagePerks = perks.drop(from).take(pageSize)

        val baseSlots = listOf(20, 21, 22, 23, 24)
        val perkSlots = perkSlotsForCount(baseSlots, pagePerks.size)

        for (i in pagePerks.indices) {
            val perk = pagePerks[i]
            val slot = perkSlots[i]
            val perkName = plugin.perks.resolveText(player, perk.displayNameMm)
            val lore = plugin.perks.resolveLore(player, perk.loreMm)
            inv.setItem(slot, perkIcon(perk.icon, perk.id, perkName, lore))
        }

        if (totalPages > 1) {
            val prev = icon(Material.ARROW, g("menus.mayor_profile.prev.name"))
            inv.setItem(30, prev)
            set(30, prev) { p, _ ->
                if (perkPage <= 0) {
                    denyClick()
                } else {
                    perkPage -= 1
                    plugin.gui.open(p, this)
                }
            }

            val next = icon(Material.ARROW, g("menus.mayor_profile.next.name"))
            inv.setItem(32, next)
            set(32, next) { p, _ ->
                if (perkPage >= totalPages - 1) {
                    denyClick()
                } else {
                    perkPage += 1
                    plugin.gui.open(p, this)
                }
            }
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(27, back)
        set(27, back) { p -> plugin.gui.open(p, backTo.invoke()) }
    }

    private fun displayMayorName(): String =
        plugin.playerDisplayNames.resolveMayor(mayor, profileMayorName() ?: mayorName).mini

    private fun profileMayorName(): String? =
        plugin.store.winnerName(term)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Bukkit.getOfflinePlayer(mayor).name?.trim()?.takeIf { it.isNotBlank() }

    private fun perkSlotsForCount(baseSlots: List<Int>, n: Int): List<Int> {
        return when (n) {
            0 -> emptyList()
            1 -> listOf(baseSlots[2])
            2 -> listOf(baseSlots[1], baseSlots[3])
            3 -> listOf(baseSlots[1], baseSlots[2], baseSlots[3])
            4 -> listOf(baseSlots[0], baseSlots[1], baseSlots[3], baseSlots[4])
            else -> baseSlots
        }
    }

    private fun unknownDef(id: String): PerkDef = PerkDef(
        id = id,
        displayNameMm = g("menus.mayor_profile.unknown_perk.name"),
        loreMm = listOf(
            g("menus.mayor_profile.unknown_perk.lore.id", mapOf("id" to id)),
            g("menus.mayor_profile.unknown_perk.lore.reason")
        ),
        adminLoreMm = emptyList(),
        icon = Material.BARRIER,
        onStart = emptyList(),
        onEnd = emptyList(),
        sectionId = "unknown",
        origin = mayorSystem.perks.PerkOrigin.INTERNAL,
        enabled = false
    )

    private fun perkIcon(mat: Material, perkId: String, nameMm: String, loreMm: List<String>): ItemStack {
        val item = icon(mat, nameMm, loreMm)
        if (!isPotionMaterial(mat)) return item
        val potionType = potionTypeFor(perkId) ?: return item
        val meta = item.itemMeta as? PotionMeta ?: return item
        meta.setBasePotionType(potionType)
        item.itemMeta = meta
        return item
    }

    private fun isPotionMaterial(mat: Material): Boolean =
        mat == Material.POTION || mat == Material.SPLASH_POTION || mat == Material.LINGERING_POTION

    private fun potionTypeFor(perkId: String): PotionType? {
        val key = perkId.lowercase()
        return when {
            key.startsWith("speed") -> PotionType.SWIFTNESS
            key.startsWith("jump_boost") || key.startsWith("jump") -> PotionType.LEAPING
            key.startsWith("night_vision") -> PotionType.NIGHT_VISION
            key.startsWith("fire_resistance") -> PotionType.FIRE_RESISTANCE
            key.startsWith("strength") -> PotionType.STRENGTH
            key.startsWith("water_breathing") -> PotionType.WATER_BREATHING
            key.startsWith("slow_falling") -> PotionType.SLOW_FALLING
            key.startsWith("luck") -> PotionType.LUCK
            key.startsWith("regeneration") -> PotionType.REGENERATION
            key.startsWith("resistance") -> PotionType.TURTLE_MASTER
            else -> null
        }
    }
}
