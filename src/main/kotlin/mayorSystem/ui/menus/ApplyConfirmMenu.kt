package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import java.util.logging.Level
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApplyConfirmMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.apply_confirm.title")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, g("menus.apply_confirm.unavailable.name"), listOf(blocked)))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        if (!plugin.termService.isElectionOpen(now, term)) {
            inv.setItem(22, icon(Material.BARRIER, g("menus.apply_confirm.closed.name")))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        val session = plugin.applyFlow.get(player.uniqueId)
        val chosen = session?.chosenPerks ?: linkedSetOf()

        val allowed = plugin.settings.perksAllowed(term)
        val cost = plugin.settings.applyCost

        val perkNames = chosen.map { plugin.perks.displayNameFor(term, it, player) }

        val summaryLore = buildList {
            add(g("menus.apply_confirm.summary.term", mapOf("term" to (term + 1).toString())))
            add(
                g(
                    "menus.apply_confirm.summary.selected",
                    mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString())
                )
            )
            if (cost > 0.0) {
                add(g("menus.apply_confirm.summary.cost", mapOf("cost" to cost.toString())))
            }
            add("")
            if (perkNames.isEmpty()) {
                add(g("menus.apply_confirm.summary.none"))
                add(g("menus.apply_confirm.summary.none_hint"))
            } else {
                add(g("menus.apply_confirm.summary.perks_header"))
                perkNames.take(10).forEach { n ->
                    add(g("menus.apply_confirm.summary.perk_entry", mapOf("perk" to n)))
                }
                if (perkNames.size > 10) {
                    add(g("menus.apply_confirm.summary.more", mapOf("count" to (perkNames.size - 10).toString())))
                }
            }
            add("")
            add(g("menus.apply_confirm.summary.locked_hint"))
        }

        inv.setItem(13, icon(Material.BOOK, g("menus.apply_confirm.summary.name"), summaryLore))

        val back = icon(Material.ARROW, g("menus.common.back.name"), listOf(g("menus.apply_confirm.back.lore")))
        inv.setItem(18, back)
        set(18, back) { p, _ -> plugin.gui.open(p, ApplySectionsMenu(plugin)) }

        val cancel = icon(
            Material.RED_DYE,
            g("menus.apply_confirm.cancel.name"),
            listOf(g("menus.apply_confirm.cancel.lore"))
        )
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ ->
            plugin.applyFlow.clear(p.uniqueId)
            p.closeInventory()
        }

        val confirmLore = buildList {
            add(g("menus.apply_confirm.confirm.lore.header"))
            add(g("menus.apply_confirm.confirm.lore.lock"))
            if (cost > 0.0) {
                add(g("menus.apply_confirm.confirm.lore.charge", mapOf("cost" to cost.toString())))
            }
            add(g("menus.apply_confirm.confirm.lore.register"))
            add("")
            add(g("menus.apply_confirm.confirm.lore.hint"))
        }
        val confirm = icon(Material.LIME_DYE, g("menus.apply_confirm.confirm.name"), confirmLore)
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ -> confirmApply(p, term) }
    }

    private fun confirmApply(player: Player, term: Int) {
        plugin.scope.launch(plugin.mainDispatcher) {
            val completed = plugin.actionCoordinator.trySerialized("apply:${player.uniqueId}:$term") {
                if (!player.hasPermission(Perms.APPLY)) {
                    denyMsg(player, "errors.no_permission")
                    plugin.gui.open(player, MainMenu(plugin))
                    return@trySerialized
                }
                val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
                if (blocked != null) {
                    denyMm(player, blocked)
                    plugin.gui.open(player, MainMenu(plugin))
                    return@trySerialized
                }
                val now = Instant.now()
                if (!plugin.termService.isElectionOpen(now, term)) {
                    denyMsg(player, "public.apply_closed")
                    plugin.gui.open(player, MainMenu(plugin))
                    return@trySerialized
                }

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
                    return@trySerialized
                }

                val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
                val minTicks = plugin.settings.applyPlaytimeMinutes * 60 * 20
                if (playTicks < minTicks) {
                    denyMsg(player, "public.apply_playtime", mapOf("minutes" to plugin.settings.applyPlaytimeMinutes.toString()))
                    plugin.gui.open(player, MainMenu(plugin))
                    return@trySerialized
                }

                val existing = plugin.store.candidateEntry(term, player.uniqueId)
                if (existing != null) {
                    if (existing.status == mayorSystem.data.CandidateStatus.REMOVED) {
                        val canReapply = plugin.settings.stepdownAllowReapply &&
                            plugin.store.candidateSteppedDown(term, player.uniqueId)
                        if (!canReapply) {
                            denyMsg(player, "public.apply_removed")
                            plugin.gui.open(player, MainMenu(plugin))
                            return@trySerialized
                        }
                    } else {
                        denyMsg(player, "public.apply_already", mapOf("term" to (term + 1).toString()))
                        plugin.gui.open(player, CandidateMenu(plugin))
                        return@trySerialized
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
                    return@trySerialized
                }

                val violations = plugin.perks.sectionLimitViolations(chosen)
                if (violations.isNotEmpty()) {
                    val summary = violations.joinToString(", ") { "${it.first} (${it.second})" }
                    denyMsg(player, "public.perk_section_violation", mapOf("sections" to summary))
                    plugin.gui.open(player, ApplySectionsMenu(plugin))
                    return@trySerialized
                }

                val cost = plugin.settings.applyCost
                var withdrew = false
                if (cost > 0.0) {
                    if (!plugin.economy.isAvailable()) {
                        denyMsg(player, "public.economy_missing")
                        return@trySerialized
                    }
                    if (!plugin.economy.has(player, cost)) {
                        denyMsg(player, "public.apply_insufficient_funds")
                        return@trySerialized
                    }
                    if (!plugin.economy.withdraw(player, cost)) {
                        denyMsg(player, "public.apply_payment_failed")
                        return@trySerialized
                    }
                    withdrew = true
                }

                try {
                    withContext(Dispatchers.IO) {
                        plugin.store.setCandidate(term, player.uniqueId, player.name)
                        plugin.store.setChosenPerks(term, player.uniqueId, chosen)
                        plugin.store.setPerksLocked(term, player.uniqueId, true)
                    }
                } catch (t: Throwable) {
                    if (withdrew) {
                        runCatching { plugin.economy.deposit(player, cost) }
                    }
                    plugin.logger.log(Level.SEVERE, "Apply failed after payment; refunded=$withdrew", t)
                    denyMsg(player, "public.apply_failed_refunded")
                    return@trySerialized
                }

                plugin.applyFlow.clear(player.uniqueId)

                plugin.messages.msg(player, "public.apply_submitted", mapOf("term" to (term + 1).toString()))
                if (plugin.hasLeaderboardHologram()) {
                    plugin.leaderboardHologram.refreshIfActive()
                }
                plugin.gui.open(player, CandidateMenu(plugin))
            }
            if (completed == null) {
                denyMsg(player, "errors.action_in_progress")
                plugin.gui.open(player, CandidateMenu(plugin))
            }
        }
    }
}
