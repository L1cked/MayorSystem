package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

/**
 * Candidate Hub (campaign page).
 *
 * A candidate-facing dashboard that focuses on:
 * - picking perks
 * - editing the candidate bio/profile
 * - previewing what voters see
 * - showing a quick checklist of “what’s missing”
 */
class CandidateMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#dd2476>👑 Candidate Hub</gradient>")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.compute(now)
        val term = electionTerm
        val electionOpen = plugin.termService.isElectionOpen(now, term)

        val isCandidate = plugin.store.isCandidate(term, player.uniqueId)
        val isMayor = currentTerm >= 0 && plugin.store.winner(currentTerm) == player.uniqueId

        // -----------------------------------------------------------------
        // Profile / status card
        // -----------------------------------------------------------------
        val roleLabel = if (electionOpen) {
            if (isCandidate) "<green>Candidate</green>" else "<red>Not a candidate</red>"
        } else {
            if (isMayor) "<gold>Mayor</gold>" else "<gray>Peasant</gray>"
        }

        val allowed = plugin.settings.perksAllowed(term)
        val chosen = if (isCandidate) plugin.store.chosenPerks(term, player.uniqueId) else emptySet()
        val votes = if (isCandidate) (plugin.store.voteCounts(term)[player.uniqueId] ?: 0) else 0

        val headLore = mutableListOf<String>()
        headLore += "<gray>Election term:</gray> <white>#${term + 1}</white>"
        headLore += "<gray>Status:</gray> $roleLabel"
        if (isCandidate) {
            headLore += "<gray>Votes:</gray> <white>$votes</white>"
            headLore += "<gray>Perks picked:</gray> <white>${chosen.size}/$allowed</white>"
            headLore += ""
            headLore += "<dark_gray>Click to pick perks.</dark_gray>"
        } else {
            headLore += ""
            headLore += if (electionOpen) "<green>Click to apply.</green>" else "<red>Election closed.</red>"
        }

        val head = selfHead(player, "<gold>${player.name}</gold>", headLore)
        inv.setItem(13, head)
        set(13, head) { p, _ ->
            if (!electionOpen) {
                deny(p, "Election is closed.")
                return@set
            }
            if (!p.hasPermission("mayor.apply")) {
                deny(p, "No permission to apply.")
                return@set
            }
            if (!isCandidate) {
                p.performCommand("mayor apply")
            } else {
                plugin.gui.open(p, CandidatePerkCatalogMenu(plugin))
            }
        }

        // -----------------------------------------------------------------
        // Bio editor
        // -----------------------------------------------------------------
        val bioRaw = if (isCandidate) plugin.store.candidateBio(term, player.uniqueId).trim() else ""
        val bioLore = if (!isCandidate) {
            listOf(
                "<gray>Set a short bio that voters see.</gray>",
                "<dark_gray>(Apply first.)</dark_gray>"
            )
        } else if (bioRaw.isBlank()) {
            listOf(
                "<gray>No bio set.</gray>",
                "<dark_gray>Click to write one.</dark_gray>"
            )
        } else {
            buildList {
                add("<gray>Preview:</gray>")
                wrapLore(bioRaw, 34).take(4).forEach { add("<white>$it</white>") }
                if (wrapLore(bioRaw, 34).size > 4) add("<dark_gray>+ more…</dark_gray>")
                add("")
                add("<dark_gray>Click to edit.</dark_gray>")
            }
        }

        val bioItem = icon(Material.WRITABLE_BOOK, "<gold>✍ Bio / Profile</gold>", bioLore)
        inv.setItem(11, bioItem)
        set(11, bioItem) { p, _ ->
            if (!isCandidate) {
                deny(p, "Apply first, then you can set your bio.")
                return@set
            }
            // Close the menu so the player can type in chat without the GUI covering it.
            p.closeInventory()
            plugin.prompts.beginBioEditFlow(p, term)
        }

        // -----------------------------------------------------------------
        // Preview what voters see
        // -----------------------------------------------------------------
        val previewItem = icon(
            Material.SPYGLASS,
            "<aqua>Preview Voter View</aqua>",
            listOf(
                "<gray>See your perks + bio as voters see them.</gray>",
                if (!isCandidate) "<dark_gray>(Apply first.)</dark_gray>" else "<dark_gray>Read-only preview.</dark_gray>"
            )
        )
        inv.setItem(15, previewItem)
        set(15, previewItem) { p, _ ->
            if (!isCandidate) {
                deny(p, "Apply first to become a candidate.")
                return@set
            }
            plugin.gui.open(
                p,
                CandidatePerksViewMenu(
                    plugin,
                    term = term,
                    candidate = p.uniqueId,
                    candidateName = p.name,
                    backToConfirm = null,
                    backToList = { CandidateMenu(plugin) }
                )
            )
        }

        // -----------------------------------------------------------------
        // Custom perks
        // -----------------------------------------------------------------
        val customItem = icon(
            Material.ANVIL,
            "<gold>Custom Perks</gold>",
            listOf(
                "<gray>Request custom perks here.</gray>",
                "<dark_gray>Admins must approve first.</dark_gray>"
            )
        )
        inv.setItem(20, customItem)
        set(20, customItem) { p, _ ->
            plugin.gui.open(p, CandidateCustomPerksMenu(plugin))
        }

        // -----------------------------------------------------------------
        // Checklist
        // -----------------------------------------------------------------
        val checklistLines = buildList {
            if (!isCandidate) {
                add("<gray>Not applied yet.</gray>")
                add(if (electionOpen) "<green>Apply to join the election.</green>" else "<red>Election is closed.</red>")
                return@buildList
            }

            val perksLocked = plugin.store.isPerksLocked(term, player.uniqueId)
            val missingPerks = (allowed - chosen.size).coerceAtLeast(0)
            val pendingRequests = plugin.store.listRequests(term, status = RequestStatus.PENDING)
                .count { it.candidate == player.uniqueId }

            add(if (chosen.size >= allowed) "<green>✔</green> <gray>Perks chosen:</gray> <white>${chosen.size}/$allowed</white>" else "<yellow>⚠</yellow> <gray>Pick</gray> <white>$missingPerks</white> <gray>more perk(s)</gray>")
            add(if (perksLocked) "<green>✔</green> <gray>Application confirmed</gray>" else "<yellow>⚠</yellow> <gray>Application not confirmed yet</gray>")
            add(if (bioRaw.isNotBlank()) "<green>✔</green> <gray>Bio set</gray>" else "<yellow>⚠</yellow> <gray>Bio not set</gray>")
            if (pendingRequests > 0) add("<yellow>⚠</yellow> <gray>Custom requests pending:</gray> <white>$pendingRequests</white>")
        }

        val checklistItem = icon(Material.PAPER, "<yellow>Checklist</yellow>", checklistLines)
        inv.setItem(22, checklistItem)

        // -----------------------------------------------------------------
        // Step down
        // -----------------------------------------------------------------
        val stepDownLore = buildList {
            add("<gray>Withdraw from the current election.</gray>")
            if (!plugin.settings.stepdownEnabled) add("<dark_gray>Step down is disabled.</dark_gray>")
            if (!electionOpen) add("<dark_gray>Election is closed.</dark_gray>")
            if (!isCandidate) add("<dark_gray>Not currently a candidate.</dark_gray>")
            if (plugin.settings.applyCost > 0.0) add("<red>You won't get your money back.</red>")
            add("<dark_gray>Click to confirm.</dark_gray>")
        }
        val stepDownItem = icon(Material.RED_DYE, "<red>Step Down</red>", stepDownLore)
        inv.setItem(24, stepDownItem)
        set(24, stepDownItem) { p, _ ->
            val entry = plugin.store.candidateEntry(term, p.uniqueId)
            if (!plugin.settings.stepdownEnabled) {
                deny(p)
                plugin.messages.msg(p, "public.stepdown_disabled")
                return@set
            }
            if (!electionOpen) {
                deny(p)
                plugin.messages.msg(p, "public.stepdown_closed")
                return@set
            }
            if (entry == null || entry.status == CandidateStatus.REMOVED) {
                deny(p)
                plugin.messages.msg(p, "public.stepdown_not_candidate")
                return@set
            }
            plugin.gui.open(p, StepDownConfirmMenu(plugin, term, p.uniqueId))
        }

        // Back
        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(27, back)
        set(27, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
    }
}
