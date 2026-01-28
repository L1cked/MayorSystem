package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

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
        val term = plugin.termService.compute(now).second

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
        // Re-check eligibility (can't trust client-side UI)
        val now = Instant.now()
        if (!plugin.termService.isElectionOpen(now, term)) {
            deny(player, "Applications are closed right now.")
            plugin.gui.open(player, MainMenu(plugin))
            return
        }

        // Global apply bans (temp/perma) — admin-proof (expired bans auto-clear)
        val ban = plugin.store.activeApplyBan(player.uniqueId)
        if (ban != null) {
            if (ban.permanent) {
                deny(player, "You are permanently banned from applying to mayor elections.")
            } else {
                val remaining = java.time.Duration.between(java.time.OffsetDateTime.now(), ban.until)
                val mins = remaining.toMinutes().coerceAtLeast(0)
                deny(player, "You are temporarily banned from applying. Try again in ${mins} minutes.")
            }
            plugin.gui.open(player, MainMenu(plugin))
            return
        }

        val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
        val minTicks = plugin.settings.applyPlaytimeMinutes * 60 * 20
        if (playTicks < minTicks) {
            deny(player, "Not enough playtime to apply. Need ${plugin.settings.applyPlaytimeMinutes} minutes.")
            plugin.gui.open(player, MainMenu(plugin))
            return
        }

        // Already applied? If staff fully REMOVED you, you can't re-apply this term.
        val existing = plugin.store.candidateEntry(term, player.uniqueId)
        if (existing != null) {
            if (existing.status == mayorSystem.data.CandidateStatus.REMOVED) {
                val canReapply = plugin.settings.stepdownAllowReapply &&
                    plugin.store.candidateSteppedDown(term, player.uniqueId)
                if (!canReapply) {
                    deny(player, "You were removed from this election and cannot re-apply this term.")
                    plugin.gui.open(player, MainMenu(plugin))
                    return
                }
            } else {
                deny(player, "You already applied for term #${term + 1}.")
                plugin.gui.open(player, CandidateMenu(plugin))
                return
            }
        }

        val allowed = plugin.settings.perksAllowed(term)
        val session = plugin.applyFlow.get(player.uniqueId)
        val chosen = session?.chosenPerks ?: linkedSetOf()

        if (chosen.size != allowed) {
            deny(player, "You must select exactly $allowed perks before applying. (Selected: ${chosen.size})")
            plugin.gui.open(player, ApplySectionsMenu(plugin))
            return
        }

        val violations = plugin.perks.sectionLimitViolations(chosen)
        if (violations.isNotEmpty()) {
            val summary = violations.joinToString(", ") { "${it.first} (${it.second})" }
            deny(player)
            plugin.messages.msg(player, "public.perk_section_violation", mapOf("sections" to summary))
            plugin.gui.open(player, ApplySectionsMenu(plugin))
            return
        }

        val cost = plugin.settings.applyCost
        if (cost > 0.0) {
            if (!plugin.economy.isAvailable()) {
                deny(player, "Economy not available (Vault missing).")
                return
            }
            if (!plugin.economy.has(player, cost)) {
                deny(player, "Not enough money to apply.")
                return
            }
            if (!plugin.economy.withdraw(player, cost)) {
                deny(player, "Payment failed.")
                return
            }
        }

        // ✅ Write everything in one go (candidate + perks + strict lock)
        plugin.store.setCandidate(term, player.uniqueId, player.name)
        plugin.store.setChosenPerks(term, player.uniqueId, chosen)
        plugin.store.setPerksLocked(term, player.uniqueId, true)

        plugin.applyFlow.clear(player.uniqueId)

        player.sendMessage("You applied for term #${term + 1}. Good luck!")
        plugin.gui.open(player, CandidateMenu(plugin))
    }
}
