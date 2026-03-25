package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import mayorSystem.security.Perms
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

class CandidateCustomPerksMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.candidate_custom.title")
    override val rows: Int = 6

    private fun canRequestCustomPerk(player: Player): Pair<Boolean, String> {
        return when (plugin.settings.customRequestCondition) {
            mayorSystem.config.CustomRequestCondition.DISABLED ->
                false to g("menus.candidate_custom.conditions.disabled")
            mayorSystem.config.CustomRequestCondition.NONE ->
                true to g("menus.candidate_custom.conditions.none")

            mayorSystem.config.CustomRequestCondition.ELECTED_ONCE -> {
                val ok = plugin.store.hasEverBeenMayor(player.uniqueId)
                ok to if (ok) g("menus.candidate_custom.conditions.elected_once_ok") else g("menus.candidate_custom.conditions.elected_once_fail")
            }

            mayorSystem.config.CustomRequestCondition.APPLY_REQUIREMENTS -> {
                val minMinutes = plugin.settings.applyPlaytimeMinutes
                if (minMinutes <= 0) return true to g("menus.candidate_custom.conditions.no_playtime")
                val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
                val minTicks = minMinutes * 60 * 20
                val ok = playTicks >= minTicks
                ok to if (ok) {
                    g("menus.candidate_custom.conditions.playtime_ok")
                } else {
                    g("menus.candidate_custom.conditions.playtime_fail", mapOf("minutes" to minMinutes.toString()))
                }
            }
        }
    }

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second
        val electionOpen = plugin.termService.isElectionOpen(now, term)
        val candidateEntry = plugin.store.candidateEntry(term, player.uniqueId)
        val isCandidate = candidateEntry != null && candidateEntry.status != CandidateStatus.REMOVED

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, g("menus.candidate_custom.unavailable.name"), listOf(blocked)))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }
            return
        }

        val locked = if (isCandidate) plugin.store.isPerksLocked(term, player.uniqueId) else true
        val allowedPerks = plugin.settings.perksAllowed(term)
        val chosen = if (isCandidate) {
            plugin.store.chosenPerks(term, player.uniqueId).toMutableSet()
        } else {
            mutableSetOf<String>()
        }

        val requests: List<CustomPerkRequest> = plugin.store
            .listRequestsForCandidate(term, player.uniqueId)
            .sortedBy { it.id }

        val used = requests.size
        val limit = plugin.settings.customRequestsLimitPerTerm
        val limitReached = limit > 0 && used >= limit

        inv.setItem(
            4,
            icon(
                Material.KNOWLEDGE_BOOK,
                g("menus.candidate_custom.header.name"),
                buildList {
                    add(g("menus.candidate_custom.header.lore.1"))
                    add(g("menus.candidate_custom.header.lore.2"))
                    add("")
                    add(
                        g(
                            "menus.candidate_custom.header.lore.requests",
                            mapOf("used" to used.toString(), "limit" to if (limit > 0) limit.toString() else g("menus.common.unlimited"))
                        )
                    )
                    if (isCandidate) {
                        add(
                            g(
                                "menus.candidate_custom.header.lore.chosen",
                                mapOf("chosen" to chosen.size.toString(), "allowed" to allowedPerks.toString())
                            )
                        )
                        if (locked) {
                            add("")
                            add(g("menus.candidate_custom.header.lore.locked.1"))
                            add(g("menus.candidate_custom.header.lore.locked.2"))
                        }
                    } else {
                        add(g("menus.candidate_custom.header.lore.not_candidate"))
                    }
                }
            )
        )

        val (meetsCondition, conditionMsg) = canRequestCustomPerk(player)
        val canSubmit = isCandidate && electionOpen && meetsCondition && !limitReached

        val submitLore = buildList {
            add(g("menus.candidate_custom.submit.lore.requirement", mapOf("status" to conditionMsg)))
            add("")
            if (!isCandidate) {
                add(g("menus.candidate_custom.submit.lore.must_be_candidate"))
                return@buildList
            }
            if (!electionOpen) {
                add(g("menus.candidate_custom.submit.lore.election_closed"))
                return@buildList
            }
            if (limitReached) {
                add(g("menus.candidate_custom.submit.lore.limit", mapOf("used" to used.toString(), "limit" to limit.toString())))
                return@buildList
            }
            if (!meetsCondition) {
                add(g("menus.candidate_custom.submit.lore.condition_fail"))
                return@buildList
            }
            add(g("menus.candidate_custom.submit.lore.prompt"))
            add(g("menus.candidate_custom.submit.lore.cancel_hint"))
        }

        val submit = icon(
            if (canSubmit) Material.WRITABLE_BOOK else Material.BARRIER,
            if (canSubmit) g("menus.candidate_custom.submit.name_enabled") else g("menus.candidate_custom.submit.name_disabled"),
            submitLore
        )
        inv.setItem(46, submit)
        setConfirm(46, submit) { p, _ ->
            if (!p.hasPermission(Perms.CANDIDATE)) {
                denyMsg(p, "errors.no_permission")
                return@setConfirm
            }
            val nowConfirm = Instant.now()
            val currentTerm = plugin.termService.computeCached(nowConfirm).second
            if (currentTerm != term || !plugin.termService.isElectionOpen(nowConfirm, term)) {
                denyMsg(p, "public.apply_closed")
                return@setConfirm
            }
            val currentEntry = plugin.store.candidateEntry(term, p.uniqueId)
            val currentlyCandidate = currentEntry != null && currentEntry.status != CandidateStatus.REMOVED
            if (!currentlyCandidate) {
                denyMsg(p, "public.apply_first_candidate")
                return@setConfirm
            }
            val (ok, msg) = canRequestCustomPerk(p)
            if (!ok) {
                denyMsg(p, "public.custom_requests_closed")
                plugin.messages.msg(p, "public.custom_requests_reason", mapOf("reason" to msg))
                return@setConfirm
            }
            val usedNow = plugin.store.requestCountForCandidate(term, p.uniqueId)
            if (limit > 0 && usedNow >= limit) {
                denyMsg(p, "public.custom_requests_limit", mapOf("limit" to limit.toString()))
                return@setConfirm
            }
            p.closeInventory()
            plugin.prompts.beginCustomPerkRequestFlow(p, term)
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }

        var slot = 10
        requests.take(21).forEach { req ->
            if (slot >= inv.size - 9) return@forEach

            val selectable = req.status == RequestStatus.APPROVED
            val canSelect = isCandidate && !locked
            val perkId = "custom:${req.id}"
            val selected = chosen.contains(perkId)

            val safeTitle = mmSafe(req.title)
            val safeDesc = mmSafe(req.description)
            val mat = when (req.status) {
                RequestStatus.PENDING -> Material.YELLOW_DYE
                RequestStatus.DENIED -> Material.RED_DYE
                RequestStatus.APPROVED -> if (selected) Material.LIME_DYE else Material.GRAY_DYE
            }

            val lore = buildList {
                add(g("menus.candidate_custom.request.lore.status", mapOf("status" to req.status.name)))
                add("")
                if (safeDesc.isNotBlank()) add(g("menus.candidate_custom.request.lore.description", mapOf("description" to safeDesc)))
                add("")

                when {
                    !selectable -> add(g("menus.candidate_custom.request.lore.not_selectable"))
                    !isCandidate -> add(g("menus.candidate_custom.request.lore.apply_first"))
                    locked -> {
                        add(g("menus.candidate_custom.request.lore.perk_id", mapOf("perk_id" to perkId)))
                        add(g("menus.candidate_custom.request.lore.locked"))
                    }
                    else -> {
                        add(g("menus.candidate_custom.request.lore.perk_id", mapOf("perk_id" to perkId)))
                        add(if (selected) g("menus.candidate_custom.request.lore.toggle_remove") else g("menus.candidate_custom.request.lore.toggle_select"))
                        add(g("menus.candidate_custom.request.lore.limit_hint"))
                    }
                }
            }

            val item = icon(
                mat,
                (if (selected) g("menus.candidate_custom.request.selected_prefix") else "") +
                    g("menus.candidate_custom.request.name", mapOf("id" to req.id.toString(), "title" to safeTitle)),
                lore
            )
            inv.setItem(slot, item)

            if (selectable && canSelect) {
                setConfirm(slot, item) { p, _ ->
                    plugin.scope.launch(plugin.mainDispatcher) {
                        val next = chosen.toMutableSet()
                        if (selected) {
                            next.remove(perkId)
                        } else {
                            if (next.size >= allowedPerks) {
                                denyMsg(p, "public.perk_limit", mapOf("limit" to allowedPerks.toString()))
                                return@launch
                            }
                            next.add(perkId)
                        }
                        withContext(Dispatchers.IO) {
                            plugin.store.setChosenPerks(term, p.uniqueId, next)
                        }
                        plugin.gui.open(p, CandidateCustomPerksMenu(plugin))
                    }
                }
            }

            slot++
            if (slot % 9 == 8) slot++
        }
    }
}
