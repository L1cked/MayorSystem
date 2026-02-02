package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Apply Wizard — Confirmation
 *
 * This is the *only* place we write to elections.yml:
 * - candidate entry is created
 * - chosen perks are saved
 * - perks are strictly locked (not editable)
 * - cost is charged (if apply.cost > 0)
 */
class ApplyConfirmMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>📜 Apply</gradient> <gray>• Confirm</gray>")
    // Small confirmation menus feel snappy and reduce "UI fatigue".
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, "<red>Applications unavailable</red>", listOf(blocked)))
            val back = icon(Material.ARROW, "<gray>â¬… Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        // If the election closed while they were in the wizard, bail gracefully.
        if (!plugin.termService.isElectionOpen(now, term)) {
            inv.setItem(22, icon(Material.BARRIER, "<red>Applications are closed</red>"))
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        // Session is required to know what they selected.
        val session = plugin.applyFlow.get(player.uniqueId)
        val chosen = session?.chosenPerks ?: linkedSetOf()

        val allowed = plugin.settings.perksAllowed(term)
        val cost = plugin.settings.applyCost

        val perkNames = chosen.map { plugin.perks.displayNameFor(term, it) }

        // Summary card
        val summaryLore = buildList {
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>")
            if (cost > 0.0) add("<gray>Cost:</gray> <gold>$cost</gold>")
            add("")
            if (perkNames.isEmpty()) {
                add("<red>No perks selected yet.</red>")
                add("<gray>Go back and pick your perks.</gray>")
            } else {
                add("<gray>Your perks:</gray>")
                perkNames.take(10).forEach { n -> add("<gray>•</gray> $n") }
                if (perkNames.size > 10) add("<dark_gray>+${perkNames.size - 10} more…</dark_gray>")
            }
            add("")
            add("<dark_gray>After confirming, perks are permanently locked for this election.</dark_gray>")
        }

        inv.setItem(13, icon(Material.BOOK, "<gold>Review your application</gold>", summaryLore))

        // Back to sections (navigation click)
        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>", listOf("<gray>Adjust your perks.</gray>"))
        inv.setItem(18, back)
        set(18, back) { p, _ -> plugin.gui.open(p, ApplySectionsMenu(plugin)) }

        // Cancel (left) / Confirm (right) — consistent confirmation layout
        val cancel = icon(
            Material.RED_DYE,
            "<red>Cancel</red>",
            listOf("<gray>Stops the apply wizard.</gray>")
        )
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ ->
            plugin.applyFlow.clear(p.uniqueId)
            p.closeInventory()
        }

        // Confirm & apply
        val confirmLore = buildList {
            add("<gray>This will:</gray>")
            add("<gray>•</gray> <white>Lock your perks</white>")
            if (cost > 0.0) add("<gray>•</gray> <white>Charge</white> <gold>$cost</gold>")
            add("<gray>•</gray> <white>Register you as a candidate</white>")
            add("")
            add("<yellow>Make sure you're happy with your picks.</yellow>")
        }
        val confirm = icon(Material.LIME_DYE, "<green>Confirm</green>", confirmLore)
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p -> confirmApply(p, term) }
    }

    private fun confirmApply(player: Player, term: Int) {
        plugin.scope.launch(plugin.mainDispatcher) {
            val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
            if (blocked != null) {
                denyMm(player, blocked)
                plugin.gui.open(player, MainMenu(plugin))
                return@launch
            }
            // Re-check eligibility (can't trust client-side UI)
            val now = Instant.now()
            if (!plugin.termService.isElectionOpen(now, term)) {
                denyMsg(player, "public.apply_closed")
                plugin.gui.open(player, MainMenu(plugin))
                return@launch
            }

            // Global apply bans (temp/perma) — admin-proof (expired bans auto-clear)
            val ban = plugin.store.activeApplyBan(player.uniqueId)
            if (ban != null) {
                if (ban.permanent) {
                    denyMsg(player, "public.apply_ban_permanent")
                } else {
                    val remaining = java.time.Duration.between(java.time.OffsetDateTime.now(), ban.until)
                    val mins = remaining.toMinutes().coerceAtLeast(0)
                    denyMsg(player, "public.apply_ban_temporary", mapOf("minutes" to mins.toString()))
                }
                plugin.gui.open(player, MainMenu(plugin))
                return@launch
            }

            val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
            val minTicks = plugin.settings.applyPlaytimeMinutes * 60 * 20
            if (playTicks < minTicks) {
                denyMsg(player, "public.apply_playtime", mapOf("minutes" to plugin.settings.applyPlaytimeMinutes.toString()))
                plugin.gui.open(player, MainMenu(plugin))
                return@launch
            }

            // Already applied? If staff fully REMOVED you, you can't re-apply this term.
            val existing = plugin.store.candidateEntry(term, player.uniqueId)
            if (existing != null) {
                if (existing.status == mayorSystem.data.CandidateStatus.REMOVED) {
                    val canReapply = plugin.settings.stepdownAllowReapply &&
                        plugin.store.candidateSteppedDown(term, player.uniqueId)
                    if (!canReapply) {
                        denyMsg(player, "public.apply_removed")
                        plugin.gui.open(player, MainMenu(plugin))
                        return@launch
                    }
                } else {
                    denyMsg(player, "public.apply_already", mapOf("term" to (term + 1).toString()))
                    plugin.gui.open(player, CandidateMenu(plugin))
                    return@launch
                }
            }

            val allowed = plugin.settings.perksAllowed(term)
            val session = plugin.applyFlow.get(player.uniqueId)
            val chosen = session?.chosenPerks ?: linkedSetOf()

            if (chosen.size != allowed) {
                denyMsg(
                    player,
                    "public.apply_perk_exact",
                    mapOf("limit" to allowed.toString(), "selected" to chosen.size.toString())
                )
                plugin.gui.open(player, ApplySectionsMenu(plugin))
                return@launch
            }

            val violations = plugin.perks.sectionLimitViolations(chosen)
            if (violations.isNotEmpty()) {
                val summary = violations.joinToString(", ") { "${it.first} (${it.second})" }
                denyMsg(player, "public.perk_section_violation", mapOf("sections" to summary))
                plugin.gui.open(player, ApplySectionsMenu(plugin))
                return@launch
            }

            val cost = plugin.settings.applyCost
            if (cost > 0.0) {
                if (!plugin.economy.isAvailable()) {
                    denyMsg(player, "public.economy_missing")
                    return@launch
                }
                if (!plugin.economy.has(player, cost)) {
                    denyMsg(player, "public.apply_insufficient_funds")
                    return@launch
                }
                if (!plugin.economy.withdraw(player, cost)) {
                    denyMsg(player, "public.apply_payment_failed")
                    return@launch
                }
            }

            // ✅ Write everything in one go (candidate + perks + strict lock)
            withContext(Dispatchers.IO) {
                plugin.store.setCandidate(term, player.uniqueId, player.name)
                plugin.store.setChosenPerks(term, player.uniqueId, chosen)
                plugin.store.setPerksLocked(term, player.uniqueId, true)
            }

            plugin.applyFlow.clear(player.uniqueId)

            player.sendMessage("You applied for term #${term + 1}. Good luck!")
            plugin.gui.open(player, CandidateMenu(plugin))
        }
    }
}


