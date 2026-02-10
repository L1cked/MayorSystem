package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.RequestStatus
import mayorSystem.ui.Menu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import java.time.Instant

/**
 * Apply Wizard — Page 2/2 (Perks list for a section)
 *
 * The player toggles perks here. Selections are stored in ApplyFlowService (memory only)
 * until they confirm on ApplyConfirmMenu.
 */
class ApplyPerksMenu(
    plugin: MayorPlugin,
    private val sectionId: String
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>📜 Apply</gradient> <gray>• Perks</gray>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, "<red>Applications unavailable</red>", listOf(blocked)))
            val back = icon(Material.ARROW, "<gray>â¬… Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        // Defensive: if the window closed while the player was clicking around.
        if (!plugin.termService.isElectionOpen(now, term)) {
            inv.setItem(22, icon(Material.BARRIER, "<red>Applications are closed</red>"))
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        if (sectionId != CUSTOM_SECTION_ID && !plugin.perks.isPerkSectionAvailable(sectionId)) {
            val reason = plugin.perks.perkSectionBlockReason(sectionId) ?: "Section unavailable."
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>Section unavailable</red>",
                    listOf("<gray>$reason</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, ApplySectionsMenu(plugin)) }
            return
        }

        val allowed = plugin.settings.perksAllowed(term)
        val session = plugin.applyFlow.getOrStart(player, term)
        val chosen = session.chosenPerks
        val sectionLimit = plugin.perks.sectionPickLimit(sectionId)
        val sectionSelected = plugin.perks.countSelectedInSection(chosen, sectionId)

        // Header
        inv.setItem(
            4,
            icon(
                Material.DIAMOND,
                "<light_purple>Selected:</light_purple> <white>${chosen.size}/$allowed</white>",
                buildList {
                    add("<dark_gray>Section:</dark_gray> <white>$sectionId</white>")
                    if (sectionLimit != null) {
                        add("<dark_gray>Section limit:</dark_gray> <white>$sectionSelected/$sectionLimit</white>")
                    }
                }
            )
        )

        // List perks
        val perkItems: List<Pair<String, Triple<String, List<String>, Material>>> = when (sectionId) {
            CUSTOM_SECTION_ID -> {
                // Virtual section: approved custom requests for this player
                val approved = plugin.store.listRequests(term)
                    .filter { it.candidate == player.uniqueId && it.status == RequestStatus.APPROVED }
                    .sortedBy { it.id }

                approved.map { req ->
                    val perkId = "custom:${req.id}"
                    val safeTitle = mmSafe(req.title)
                    val safeDesc = mmSafe(req.description)
                    val name = "<gold>$safeTitle</gold>"
                    val lore = buildList {
                        add("<gray>Custom perk request</gray> <yellow>#${req.id}</yellow>")
                        if (safeDesc.isNotBlank()) {
                            add("")
                            add("<dark_gray>$safeDesc</dark_gray>")
                        }
                    }
                    perkId to Triple(name, lore, Material.ANVIL)
                }
            }

            else -> {
                // Config-driven section
                val perks = plugin.perks.perksForSection(sectionId, includeDisabled = false)
                perks.map { perk ->
                    val name = plugin.perks.resolveText(player, perk.displayNameMm)
                    val lore = plugin.perks.resolveLore(player, perk.loreMm)
                    perk.id to Triple(name, lore, perk.icon)
                }
            }
        }

        if (perkItems.isEmpty()) {
            val reason = plugin.perks.sectionEmptyReason(sectionId)
                ?: "This section has no enabled perks."
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No perks found</red>",
                    listOf("<gray>$reason</gray>")
                )
            )
        } else {
            var slot = 10
            for ((perkId, triple) in perkItems) {
                if (slot >= inv.size - 10) break
                val (name, lore, fallback) = triple
                val selected = chosen.contains(perkId)

                val item = perkIcon(
                    // Show the actual perk icon instead of a generic dye.
                    // Selection is indicated by the "✓" prefix and lore.
                    fallback,
                    perkId,
                    (if (selected) "<green>✓</green> " else "<gray>+</gray> ") + name,
                    lore + listOf(
                        "",
                        "<gray>Click to ${if (selected) "remove" else "select"}.</gray>",
                        "<dark_gray>Selected:</dark_gray> <white>${chosen.size}/$allowed</white>"
                    )
                )
                if (selected) glow(item)
                inv.setItem(slot, item)
                setConfirm(slot, item) { p, _ ->
                    val next = chosen.toMutableSet()
                    if (selected) {
                        next.remove(perkId)
                    } else {
                        if (next.size >= allowed) {
                            overrideClickSound(UiClickSound.NOT_ALLOWED)
                            plugin.messages.msg(p, "public.perk_limit", mapOf("limit" to allowed.toString()))
                            return@setConfirm
                        }
                        if (sectionLimit != null) {
                            val sectionCount = plugin.perks.countSelectedInSection(next, sectionId)
                            if (sectionCount >= sectionLimit) {
                                overrideClickSound(UiClickSound.NOT_ALLOWED)
                                plugin.messages.msg(p, "public.perk_section_limit", mapOf("section" to sectionId, "limit" to sectionLimit.toString()))
                                return@setConfirm
                            }
                        }
                        next.add(perkId)
                    }
                    // Replace the set in-place (session holds LinkedHashSet, but we want deterministic order)
                    session.chosenPerks.clear()
                    session.chosenPerks.addAll(next)
                    plugin.gui.open(p, ApplyPerksMenu(plugin, sectionId))
                }

                slot++
                if (slot % 9 == 8) slot += 2 // skip right border + next row left border
            }
        }

        // Back to sections
        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>", listOf("<dark_gray>Return to section list.</dark_gray>"))
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, ApplySectionsMenu(plugin)) }

        // Next -> confirm
        val next = icon(
            Material.LIME_WOOL,
            "<green>Next</green>",
            listOf(
                "<gray>Go to confirmation.</gray>",
                "<dark_gray>You’ll confirm & pay on the next page.</dark_gray>"
            )
        )
        inv.setItem(53, next)
        set(53, next) { p, _ -> plugin.gui.open(p, ApplyConfirmMenu(plugin)) }
    }

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
            key.startsWith("water_breathing") -> PotionType.WATER_BREATHING
            key.startsWith("slow_falling") -> PotionType.SLOW_FALLING
            key.startsWith("luck") -> PotionType.LUCK
            key.startsWith("strength") -> PotionType.STRENGTH
            key.startsWith("regeneration") -> PotionType.REGENERATION
            else -> null
        }
    }

    companion object {
        /**
         * Virtual section id used for approved custom requests.
         * This isn't stored in config under perks.sections.*.
         */
        const val CUSTOM_SECTION_ID: String = "__custom__"
    }
}






