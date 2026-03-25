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

class CandidateMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.candidate.title")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        if (!player.hasPermission(mayorSystem.security.Perms.CANDIDATE)) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.candidate.no_permission.name"),
                    listOf(g("menus.candidate.no_permission.lore"))
                )
            )
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(27, back)
            set(27, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)
        val term = electionTerm
        val electionOpen = plugin.termService.isElectionOpen(now, term)

        val candidateEntry = plugin.store.candidateEntry(term, player.uniqueId)
        val isCandidate = candidateEntry != null && candidateEntry.status != CandidateStatus.REMOVED
        val isMayor = currentTerm >= 0 && plugin.store.winner(currentTerm) == player.uniqueId

        val roleLabel = if (electionOpen) {
            if (isCandidate) g("menus.candidate.role.candidate") else g("menus.candidate.role.not_candidate")
        } else {
            if (isMayor) g("menus.candidate.role.mayor") else g("menus.candidate.role.peasant")
        }

        val allowed = plugin.settings.perksAllowed(term)
        val chosen = if (isCandidate) plugin.store.chosenPerks(term, player.uniqueId) else emptySet()
        val votes = if (isCandidate) (plugin.store.voteCounts(term)[player.uniqueId] ?: 0) else 0

        val headLore = mutableListOf<String>()
        headLore += g("menus.candidate.profile.lore.term", mapOf("term" to (term + 1).toString()))
        headLore += g("menus.candidate.profile.lore.status", mapOf("status" to roleLabel))
        if (isCandidate) {
            headLore += g("menus.candidate.profile.lore.votes", mapOf("votes" to votes.toString()))
            headLore += g("menus.candidate.profile.lore.perks", mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString()))
            headLore += ""
            headLore += g("menus.candidate.profile.lore.pick_hint")
        } else {
            headLore += ""
            headLore += if (electionOpen) g("menus.candidate.profile.lore.apply_hint") else g("menus.candidate.profile.lore.election_closed")
        }

        val head = selfHead(player, g("menus.candidate.profile.name", mapOf("player" to player.name)), headLore)
        inv.setItem(13, head)
        set(13, head) { p, _ ->
            if (!requireNotBlocked(p, mayorSystem.config.SystemGateOption.ACTIONS)) return@set
            if (!electionOpen) {
                denyMsg(p, "public.apply_closed")
                return@set
            }
            if (!p.hasPermission("mayor.apply")) {
                denyMsg(p, "errors.no_permission")
                return@set
            }
            if (!isCandidate) {
                p.performCommand("mayor apply")
            } else {
                plugin.gui.open(p, CandidatePerkCatalogMenu(plugin))
            }
        }

        val bioRaw = if (isCandidate) plugin.store.candidateBio(term, player.uniqueId).trim() else ""
        val bioSafe = mmSafe(bioRaw)
        val wrappedBio = wrapLore(bioSafe, 34)
        val bioLore = if (!isCandidate) {
            listOf(
                g("menus.candidate.bio.lore.not_candidate.1"),
                g("menus.candidate.bio.lore.not_candidate.2")
            )
        } else if (bioRaw.isBlank()) {
            listOf(
                g("menus.candidate.bio.lore.empty.1"),
                g("menus.candidate.bio.lore.empty.2")
            )
        } else {
            buildList {
                add(g("menus.candidate.bio.lore.preview"))
                wrappedBio.take(4).forEach { add(g("menus.candidate.bio.lore.preview_line", mapOf("line" to it))) }
                if (wrappedBio.size > 4) add(g("menus.candidate.bio.lore.more"))
                add("")
                add(g("menus.candidate.bio.lore.edit"))
            }
        }

        val bioItem = icon(Material.WRITABLE_BOOK, g("menus.candidate.bio.name"), bioLore)
        inv.setItem(11, bioItem)
        set(11, bioItem) { p, _ ->
            if (!requireNotBlocked(p, mayorSystem.config.SystemGateOption.ACTIONS)) return@set
            if (!isCandidate) {
                denyMsg(p, "public.apply_first_bio")
                return@set
            }
            p.closeInventory()
            plugin.prompts.beginBioEditFlow(p, term)
        }

        val previewItem = icon(
            Material.SPYGLASS,
            g("menus.candidate.preview.name"),
            listOf(
                g("menus.candidate.preview.lore.1"),
                if (!isCandidate) g("menus.candidate.preview.lore.not_candidate") else g("menus.candidate.preview.lore.read_only")
            )
        )
        inv.setItem(15, previewItem)
        set(15, previewItem) { p, _ ->
            if (!isCandidate) {
                denyMsg(p, "public.apply_first_candidate")
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

        val customItem = icon(
            Material.ANVIL,
            g("menus.candidate.custom.name"),
            listOf(
                g("menus.candidate.custom.lore.1"),
                g("menus.candidate.custom.lore.2")
            )
        )
        inv.setItem(20, customItem)
        set(20, customItem) { p, _ ->
            if (!requireNotBlocked(p, mayorSystem.config.SystemGateOption.ACTIONS)) return@set
            plugin.gui.open(p, CandidateCustomPerksMenu(plugin))
        }

        val checklistLines = buildList {
            if (!isCandidate) {
                add(g("menus.candidate.checklist.not_applied"))
                add(if (electionOpen) g("menus.candidate.checklist.apply_hint") else g("menus.candidate.checklist.closed"))
                return@buildList
            }

            val perksLocked = plugin.store.isPerksLocked(term, player.uniqueId)
            val missingPerks = (allowed - chosen.size).coerceAtLeast(0)
            val pendingRequests = plugin.store.listRequests(term, status = RequestStatus.PENDING)
                .count { it.candidate == player.uniqueId }

            add(
                if (chosen.size >= allowed) {
                    g("menus.candidate.checklist.perks_done", mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString()))
                } else {
                    g("menus.candidate.checklist.perks_missing", mapOf("missing" to missingPerks.toString()))
                }
            )
            add(if (perksLocked) g("menus.candidate.checklist.application_done") else g("menus.candidate.checklist.application_pending"))
            add(if (bioRaw.isNotBlank()) g("menus.candidate.checklist.bio_done") else g("menus.candidate.checklist.bio_missing"))
            if (pendingRequests > 0) {
                add(g("menus.candidate.checklist.requests_pending", mapOf("count" to pendingRequests.toString())))
            }
        }

        val checklistItem = icon(Material.PAPER, g("menus.candidate.checklist.name"), checklistLines)
        inv.setItem(22, checklistItem)

        val stepDownLore = buildList {
            add(g("menus.candidate.step_down.lore.1"))
            if (plugin.settings.mayorStepdownPolicy == mayorSystem.config.MayorStepdownPolicy.OFF) add(g("menus.candidate.step_down.lore.policy_off"))
            if (!electionOpen && !isMayor) add(g("menus.candidate.step_down.lore.election_closed"))
            if (!electionOpen && isMayor && plugin.settings.mayorStepdownPolicy != mayorSystem.config.MayorStepdownPolicy.OFF) {
                add(g("menus.candidate.step_down.lore.mayor_can_stepdown"))
            }
            if (!isCandidate && !isMayor) add(g("menus.candidate.step_down.lore.not_in_race"))
            if (plugin.settings.applyCost > 0.0) add(g("menus.candidate.step_down.lore.no_refund"))
            add(g("menus.candidate.step_down.lore.confirm"))
        }
        val stepDownItem = icon(Material.RED_DYE, g("menus.candidate.step_down.name"), stepDownLore)
        inv.setItem(24, stepDownItem)
        set(24, stepDownItem) { p, _ ->
            if (!requireNotBlocked(p, mayorSystem.config.SystemGateOption.ACTIONS)) return@set
            val entry = plugin.store.candidateEntry(term, p.uniqueId)
            if (plugin.settings.mayorStepdownPolicy == mayorSystem.config.MayorStepdownPolicy.OFF) {
                denyMsg(p, "public.stepdown_disabled")
                return@set
            }
            if (!electionOpen) {
                if (isMayor && plugin.settings.mayorStepdownPolicy != mayorSystem.config.MayorStepdownPolicy.OFF) {
                    plugin.gui.open(p, MayorStepDownConfirmMenu(plugin, currentTerm, plugin.settings.mayorStepdownPolicy))
                    return@set
                }
                denyMsg(p, "public.stepdown_closed")
                return@set
            }
            if (entry == null || entry.status == CandidateStatus.REMOVED) {
                denyMsg(p, "public.stepdown_not_candidate")
                return@set
            }
            plugin.gui.open(p, StepDownConfirmMenu(plugin, term, p.uniqueId))
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(27, back)
        set(27, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
    }
}
