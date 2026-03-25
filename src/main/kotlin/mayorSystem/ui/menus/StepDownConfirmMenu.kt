package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StepDownConfirmMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID
) : Menu(plugin) {

    override val title: Component = gc("menus.step_down_confirm.title")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(13, icon(Material.BARRIER, g("menus.step_down_confirm.unavailable.name"), listOf(blocked)))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }
            return
        }

        if (plugin.settings.mayorStepdownPolicy == mayorSystem.config.MayorStepdownPolicy.OFF) {
            inv.setItem(13, icon(Material.BARRIER, g("menus.step_down_confirm.disabled.name")))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }
            return
        }

        val entry = plugin.store.candidateEntry(term, candidate)
        val status = entry?.status ?: CandidateStatus.REMOVED
        val votes = plugin.store.voteCounts(term)[candidate] ?: 0
        val bioRaw = plugin.store.candidateBio(term, candidate).trim()
        val bioSafe = mmSafe(bioRaw)
        val chosen = plugin.store.chosenPerks(term, candidate).toList()

        val lore = buildList {
            add(g("menus.step_down_confirm.profile.lore.term", mapOf("term" to (term + 1).toString())))
            add(g("menus.step_down_confirm.profile.lore.status", mapOf("status" to status.name)))
            add(g("menus.step_down_confirm.profile.lore.votes", mapOf("votes" to votes.toString())))
            add("")
            if (bioRaw.isBlank()) {
                add(g("menus.step_down_confirm.profile.lore.bio_none"))
            } else {
                add(g("menus.step_down_confirm.profile.lore.bio_header"))
                val lines = wrapLore(bioSafe, 32)
                lines.take(4).forEach { add(g("menus.step_down_confirm.profile.lore.bio_line", mapOf("line" to it))) }
                if (lines.size > 4) add(g("menus.step_down_confirm.profile.lore.bio_more"))
            }

            add("")
            if (chosen.isEmpty()) {
                add(g("menus.step_down_confirm.profile.lore.perks_none"))
            } else {
                add(g("menus.step_down_confirm.profile.lore.perks_header"))
                chosen.take(6).forEach { perkId ->
                    val perkName = plugin.perks.displayNameFor(term, perkId, player)
                    add(g("menus.step_down_confirm.profile.lore.perk_entry", mapOf("perk" to perkName)))
                }
                if (chosen.size > 6) add(g("menus.step_down_confirm.profile.lore.perks_more", mapOf("count" to (chosen.size - 6).toString())))
            }

            if (plugin.settings.applyCost > 0.0) {
                add("")
                add(g("menus.step_down_confirm.profile.lore.no_refund"))
            }
        }

        val head = selfHead(player, g("menus.step_down_confirm.profile.name", mapOf("player" to player.name)), lore)
        inv.setItem(13, head)

        val cancel = icon(Material.RED_DYE, g("menus.step_down_confirm.cancel.name"), listOf(g("menus.step_down_confirm.cancel.lore")))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }

        val confirm = icon(Material.LIME_DYE, g("menus.step_down_confirm.confirm.name"), listOf(g("menus.step_down_confirm.confirm.lore")))
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ ->
            plugin.scope.launch(plugin.mainDispatcher) {
                if (!p.hasPermission(Perms.CANDIDATE)) {
                    denyMsg(p, "errors.no_permission")
                    plugin.gui.open(p, MainMenu(plugin))
                    return@launch
                }
                val blockedConfirm = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
                if (blockedConfirm != null) {
                    denyMm(p, blockedConfirm)
                    plugin.gui.open(p, CandidateMenu(plugin))
                    return@launch
                }
                val now = Instant.now()
                val electionTerm = plugin.termService.computeCached(now).second
                if (plugin.settings.mayorStepdownPolicy == mayorSystem.config.MayorStepdownPolicy.OFF) {
                    denyMsg(p, "public.stepdown_disabled")
                    plugin.gui.open(p, CandidateMenu(plugin))
                    return@launch
                }
                if (!plugin.termService.isElectionOpen(now, electionTerm)) {
                    denyMsg(p, "public.stepdown_closed")
                    plugin.gui.open(p, CandidateMenu(plugin))
                    return@launch
                }

                val current = plugin.store.candidateEntry(electionTerm, candidate)
                if (current == null || current.status == CandidateStatus.REMOVED) {
                    denyMsg(p, "public.stepdown_not_candidate")
                    plugin.gui.open(p, CandidateMenu(plugin))
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    plugin.store.setCandidateStepdown(electionTerm, candidate)
                }
                plugin.messages.msg(p, "public.stepdown_done")
                plugin.gui.open(p, CandidateMenu(plugin))
            }
        }
    }
}
