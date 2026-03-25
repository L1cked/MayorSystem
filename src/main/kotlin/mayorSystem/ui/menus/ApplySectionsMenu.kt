package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class ApplySectionsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.apply_sections.title")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.apply_sections.unavailable.name"),
                    listOf(blocked, g("menus.apply_sections.unavailable.lore"))
                )
            )
            backToMain(inv)
            return
        }

        if (!plugin.termService.isElectionOpen(now, term)) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.apply_sections.closed.name"),
                    listOf(g("menus.apply_sections.closed.lore"))
                )
            )
            backToMain(inv)
            return
        }

        val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
        val minTicks = plugin.settings.applyPlaytimeMinutes * 60 * 20
        if (playTicks < minTicks) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.apply_sections.playtime.name"),
                    listOf(
                        g("menus.apply_sections.playtime.lore.required", mapOf("minutes" to plugin.settings.applyPlaytimeMinutes.toString())),
                        g("menus.apply_sections.playtime.lore.retry")
                    )
                )
            )
            backToMain(inv)
            return
        }

        val existing = plugin.store.candidateEntry(term, player.uniqueId)
        if (existing != null) {
            if (existing.status != CandidateStatus.REMOVED) {
                inv.setItem(
                    22,
                    icon(
                        Material.BOOK,
                        g("menus.apply_sections.already_applied.name"),
                        listOf(
                            g("menus.apply_sections.already_applied.lore.term", mapOf("term" to (term + 1).toString())),
                            g("menus.apply_sections.already_applied.lore.view")
                        )
                    )
                )
                set(22, inv.getItem(22)!!) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }
                backToMain(inv)
                return
            }

            val steppedDown = plugin.store.candidateSteppedDown(term, player.uniqueId)
            val canReapply = plugin.settings.stepdownAllowReapply && steppedDown
            if (!canReapply) {
                val lore = if (steppedDown) {
                    listOf(
                        g("menus.apply_sections.reapply_stepdown.lore.1"),
                        g("menus.apply_sections.reapply_stepdown.lore.2")
                    )
                } else {
                    listOf(
                        g("menus.apply_sections.reapply_removed.lore.1"),
                        g("menus.apply_sections.reapply_removed.lore.2")
                    )
                }
                inv.setItem(22, icon(Material.BARRIER, g("menus.apply_sections.reapply_denied.name"), lore))
                backToMain(inv)
                return
            }
        }

        val allowed = plugin.settings.perksAllowed(term)
        val session = plugin.applyFlow.getOrStart(player, term)
        val chosen = session.chosenPerks

        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                g("menus.apply_sections.header.name"),
                buildList {
                    add(g("menus.apply_sections.header.lore.term", mapOf("term" to (term + 1).toString())))
                    add(g("menus.apply_sections.header.lore.allowed", mapOf("allowed" to allowed.toString())))
                    add(
                        g(
                            "menus.apply_sections.header.lore.selected",
                            mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString())
                        )
                    )
                    add("")
                    add(g("menus.apply_sections.header.lore.hint"))
                }
            )
        )

        val secRoot = plugin.config.getConfigurationSection("perks.sections")
        if (secRoot == null || secRoot.getKeys(false).isEmpty()) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.apply_sections.no_sections.name"),
                    listOf(
                        g("menus.apply_sections.no_sections.lore.1"),
                        g("menus.apply_sections.no_sections.lore.2")
                    )
                )
            )
            backToMain(inv)
            return
        }

        var slot = 10
        val orderedSections = plugin.perks.orderedSectionIds(secRoot.getKeys(false))
        for (sectionId in orderedSections) {
            if (slot >= inv.size - 10) break

            val base = "perks.sections.$sectionId"
            val enabled = plugin.config.getBoolean("$base.enabled", true)
            if (!enabled) continue

            val display = plugin.config.getString("$base.display_name") ?: "<white>$sectionId</white>"
            val iconMat = runCatching {
                Material.valueOf((plugin.config.getString("$base.icon") ?: "CHEST").uppercase())
            }.getOrDefault(Material.CHEST)
            val blockReason = plugin.perks.perkSectionBlockReason(sectionId)

            val item = if (blockReason == null) {
                icon(
                    iconMat,
                    display,
                    listOf(
                        g("menus.apply_sections.section_open.lore.click"),
                        g("menus.apply_sections.section_open.lore.section", mapOf("section" to sectionId))
                    )
                )
            } else {
                icon(
                    Material.BARRIER,
                    g("menus.apply_sections.section_locked.name", mapOf("display" to display)),
                    listOf(g("menus.apply_sections.section_locked.lore.reason", mapOf("reason" to blockReason)))
                )
            }

            inv.setItem(slot, item)
            if (blockReason == null) {
                set(slot, item) { p -> plugin.gui.open(p, ApplyPerksMenu(plugin, sectionId)) }
            }

            slot++
            if (slot % 9 == 8) slot += 2
        }

        val approvedCustom = plugin.store.listRequests(term)
            .any { it.candidate == player.uniqueId && it.status == RequestStatus.APPROVED }

        if (slot < inv.size - 10) {
            if (approvedCustom) {
                val item = icon(
                    Material.ANVIL,
                    g("menus.apply_sections.custom_open.name"),
                    listOf(
                        g("menus.apply_sections.custom_open.lore.1"),
                        g("menus.apply_sections.custom_open.lore.2")
                    )
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, ApplyPerksMenu(plugin, ApplyPerksMenu.CUSTOM_SECTION_ID)) }
            } else {
                val item = icon(
                    Material.GRAY_DYE,
                    g("menus.apply_sections.custom_closed.name"),
                    listOf(
                        g("menus.apply_sections.custom_closed.lore.1"),
                        g("menus.apply_sections.custom_closed.lore.2")
                    )
                )
                inv.setItem(slot, item)
            }
        }

        val next = icon(
            Material.LIME_WOOL,
            g("menus.apply_sections.next.name"),
            buildList {
                add(
                    g(
                        "menus.apply_sections.next.lore.selected",
                        mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString())
                    )
                )
                if (plugin.settings.applyCost > 0.0) {
                    add(g("menus.apply_sections.next.lore.cost", mapOf("cost" to plugin.settings.applyCost.toString())))
                }
                add(g("menus.apply_sections.next.lore.hint"))
            }
        )
        inv.setItem(53, next)
        set(53, next) { p, _ -> plugin.gui.open(p, ApplyConfirmMenu(plugin)) }

        backToMain(inv)
    }

    private fun backToMain(inv: Inventory) {
        val back = icon(Material.ARROW, g("menus.common.back.name"), listOf(g("menus.apply_sections.back.lore")))
        inv.setItem(45, back)
        set(45, back) { p ->
            plugin.applyFlow.clear(p.uniqueId)
            plugin.gui.open(p, MainMenu(plugin))
        }
    }
}
