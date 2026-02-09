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

/**
 * Apply Wizard — Page 1/2
 *
 * Players first pick a *section* (Effects / Economy / Cool Stuff / etc.).
 * Clicking a section opens ApplyPerksMenu for that section.
 *
 * When they are ready, they click "Next" to proceed to confirmation + payment.
 */
class ApplySectionsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>📜 Apply</gradient> <gray>• Sections</gray>")
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
                    "<red>Applications unavailable</red>",
                    listOf(blocked, "<gray>Unpause or re-enable the system to continue.</gray>")
                )
            )
            backToMain(inv)
            return
        }

        // Hard safety checks (if these fail, we don't let them even start the wizard).
        if (!plugin.termService.isElectionOpen(now, term)) {
            inv.setItem(
                22,
                icon(Material.BARRIER, "<red>Applications are closed</red>", listOf("<gray>Come back during the vote window.</gray>"))
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
                    "<red>Not enough playtime</red>",
                    listOf(
                        "<gray>You need:</gray> <white>${plugin.settings.applyPlaytimeMinutes} minutes</white>",
                        "<gray>Keep playing and try again!</gray>"
                    )
                )
            )
            backToMain(inv)
            return
        }

        // If they already applied for this term, we route them to CandidateMenu instead of the wizard.
        val existing = plugin.store.candidateEntry(term, player.uniqueId)
        if (existing != null) {
            if (existing.status != CandidateStatus.REMOVED) {
                inv.setItem(
                    22,
                    icon(
                        Material.BOOK,
                        "<yellow>You already applied</yellow>",
                        listOf(
                            "<gray>Your perks are locked for term:</gray> <white>#${term + 1}</white>",
                            "<gray>Click to view your application.</gray>"
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
                        "<gray>Re-applying after step down</gray>",
                        "<gray>is disabled for this term.</gray>"
                    )
                } else {
                    listOf(
                        "<gray>You were removed from this election</gray>",
                        "<gray>and cannot re-apply this term.</gray>"
                    )
                }
                inv.setItem(22, icon(Material.BARRIER, "<red>Cannot re-apply</red>", lore))
                backToMain(inv)
                return
            }
        }

        val allowed = plugin.settings.perksAllowed(term)
        val session = plugin.applyFlow.getOrStart(player, term)
        val chosen = session.chosenPerks

        // Header
        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                "<gold>Pick your perks</gold>",
                buildList {
                    add("<gray>Term:</gray> <white>#${term + 1}</white>")
                    add("<gray>Perks allowed:</gray> <white>$allowed</white>")
                    add("<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>")
                    add("")
                    add("<dark_gray>You’ll confirm + pay on the last page.</dark_gray>")
                }
            )
        )

        // Build section list from config
        val secRoot = plugin.config.getConfigurationSection("perks.sections")

        var slot = 10
        if (secRoot != null) {
            val orderedSections = plugin.perks.orderedSectionIds(secRoot.getKeys(false))
            for (sectionId in orderedSections) {
                if (slot >= inv.size - 10) break

                val base = "perks.sections.$sectionId"
                val enabled = plugin.config.getBoolean("$base.enabled", true)
                if (!enabled) continue
                if (!plugin.perks.isPerkSectionAvailable(sectionId)) continue

                val display = plugin.config.getString("$base.display_name") ?: "<white>$sectionId</white>"
                val iconMat = runCatching {
                    Material.valueOf((plugin.config.getString("$base.icon") ?: "CHEST").uppercase())
                }.getOrDefault(Material.CHEST)

                val item = icon(
                    iconMat,
                    display,
                    listOf(
                        "<gray>Click to browse perks.</gray>",
                        "<dark_gray>Section:</dark_gray> <white>$sectionId</white>"
                    )
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, ApplyPerksMenu(plugin, sectionId)) }

                slot++
                if (slot % 9 == 8) slot += 2 // skip right border + next row left border
            }
        }

        // Virtual section: approved custom perks
        val approvedCustom = plugin.store.listRequests(term)
            .any { it.candidate == player.uniqueId && it.status == RequestStatus.APPROVED }

        if (slot < inv.size - 10) {
            if (approvedCustom) {
                val item = icon(
                    Material.ANVIL,
                    "<gradient:#f7971e:#ffd200>🛠 Custom Perks</gradient>",
                    listOf(
                        "<gray>Your approved custom perks.</gray>",
                        "<dark_gray>(Admins must approve before you can pick them.)</dark_gray>"
                    )
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, ApplyPerksMenu(plugin, ApplyPerksMenu.CUSTOM_SECTION_ID)) }
            } else {
                val item = icon(
                    Material.GRAY_DYE,
                    "<gray>🛠 Custom Perks</gray>",
                    listOf(
                        "<dark_gray>No approved custom perks yet.</dark_gray>",
                        "<gray>Request one from:</gray> <white>Candidate → Custom Perks</white>"
                    )
                )
                inv.setItem(slot, item)
            }
        }

        // ✅ Next -> confirm menu -> confirm menu
        val next = icon(
            Material.LIME_WOOL,
            "<green>Next</green>",
            buildList {
                add("<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>")
                if (plugin.settings.applyCost > 0.0) add("<gray>Cost:</gray> <gold>${plugin.settings.applyCost}</gold>")
                add("<dark_gray>Confirm & pay on the next page.</dark_gray>")
            }
        )
        inv.setItem(53, next)
        set(53, next) { p, _ -> plugin.gui.open(p, ApplyConfirmMenu(plugin)) }

        // Back
        backToMain(inv)
    }

    private fun backToMain(inv: Inventory) {
        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>", listOf("<dark_gray>Return to the main menu.</dark_gray>"))
        inv.setItem(45, back)
        set(45, back) { p ->
            plugin.applyFlow.clear(p.uniqueId)
            plugin.gui.open(p, MainMenu(plugin))
        }
    }
}






