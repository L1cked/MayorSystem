package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.RequestStatus
import mayorSystem.ui.Menu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class ApplyPerksMenu(
    plugin: MayorPlugin,
    private val sectionId: String
) : Menu(plugin) {

    override val title: Component = gc("menus.apply_perks.title")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, g("menus.apply_perks.unavailable.name"), listOf(blocked)))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        if (!plugin.termService.isElectionOpen(now, term)) {
            inv.setItem(22, icon(Material.BARRIER, g("menus.apply_perks.closed.name")))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        if (sectionId != CUSTOM_SECTION_ID && !plugin.perks.isPerkSectionAvailable(sectionId)) {
            val reason = plugin.perks.perkSectionBlockReason(sectionId) ?: g("menus.apply_perks.unavailable.default_reason")
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.apply_perks.section_unavailable.name"),
                    listOf(g("menus.apply_perks.section_unavailable.lore", mapOf("reason" to reason)))
                )
            )
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, ApplySectionsMenu(plugin)) }
            return
        }

        val allowed = plugin.settings.perksAllowed(term)
        val session = plugin.applyFlow.getOrStart(player, term)
        val chosen = session.chosenPerks
        val sectionLimit = plugin.perks.sectionPickLimit(sectionId)
        val sectionSelected = plugin.perks.countSelectedInSection(chosen, sectionId)

        inv.setItem(
            4,
            icon(
                Material.DIAMOND,
                g("menus.apply_perks.header.name", mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString())),
                buildList {
                    add(g("menus.apply_perks.header.lore.section", mapOf("section" to sectionId)))
                    if (sectionLimit != null) {
                        add(
                            g(
                                "menus.apply_perks.header.lore.section_limit",
                                mapOf("selected" to sectionSelected.toString(), "limit" to sectionLimit.toString())
                            )
                        )
                    }
                }
            )
        )

        val perkItems: List<Pair<String, Triple<String, List<String>, Material>>> = when (sectionId) {
            CUSTOM_SECTION_ID -> {
                val approved = plugin.store.listRequests(term)
                    .filter { it.candidate == player.uniqueId && it.status == RequestStatus.APPROVED }
                    .sortedBy { it.id }

                approved.map { req ->
                    val perkId = "custom:${req.id}"
                    val safeTitle = mmSafe(req.title)
                    val safeDesc = mmSafe(req.description)
                    val name = g("menus.apply_perks.custom.item.name", mapOf("title" to safeTitle))
                    val lore = buildList {
                        add(g("menus.apply_perks.custom.item.lore.id", mapOf("id" to req.id.toString())))
                        if (safeDesc.isNotBlank()) {
                            add("")
                            add(g("menus.apply_perks.custom.item.lore.description", mapOf("description" to safeDesc)))
                        }
                    }
                    perkId to Triple(name, lore, Material.ANVIL)
                }
            }

            else -> {
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
                ?: g("menus.apply_perks.empty.default_reason")
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.apply_perks.empty.name"),
                    listOf(g("menus.apply_perks.empty.lore", mapOf("reason" to reason)))
                )
            )
        } else {
            var slot = 10
            for ((perkId, triple) in perkItems) {
                if (slot >= inv.size - 10) break
                val (name, lore, fallback) = triple
                val selected = chosen.contains(perkId)

                val item = perkIcon(
                    fallback,
                    perkId,
                    (if (selected) g("menus.apply_perks.perk.selected_prefix") else g("menus.apply_perks.perk.unselected_prefix")) + name,
                    lore + listOf(
                        "",
                        if (selected) g("menus.apply_perks.perk.toggle_remove") else g("menus.apply_perks.perk.toggle_select"),
                        g("menus.apply_perks.perk.progress", mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString()))
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
                                plugin.messages.msg(
                                    p,
                                    "public.perk_section_limit",
                                    mapOf("section" to sectionId, "limit" to sectionLimit.toString())
                                )
                                return@setConfirm
                            }
                        }
                        next.add(perkId)
                    }
                    session.chosenPerks.clear()
                    session.chosenPerks.addAll(next)
                    plugin.gui.open(p, ApplyPerksMenu(plugin, sectionId))
                }

                slot++
                if (slot % 9 == 8) slot += 2
            }
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"), listOf(g("menus.apply_perks.back.lore")))
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, ApplySectionsMenu(plugin)) }

        val next = icon(
            Material.LIME_WOOL,
            g("menus.apply_perks.next.name"),
            listOf(
                g("menus.apply_perks.next.lore.1"),
                g("menus.apply_perks.next.lore.2")
            )
        )
        inv.setItem(53, next)
        set(53, next) { p, _ -> plugin.gui.open(p, ApplyConfirmMenu(plugin)) }
    }

    companion object {
        const val CUSTOM_SECTION_ID: String = "__custom__"
    }
}
