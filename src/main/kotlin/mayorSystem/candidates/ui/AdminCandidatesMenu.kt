package mayorSystem.candidates.ui

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Admin view of candidates for the current election term.
 *
 * Requirements:
 * - LEFT click on a candidate: toggle ACTIVE <-> PROCESS
 * - RIGHT click on a candidate: remove (ONLY if already PROCESS) with confirmation
 * - PROCESS candidates are shown, keep their votes, but are not electable / votable.
 * - REMOVED candidates have their votes refunded (handled in MayorStore).
 */
class AdminCandidatesMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#dd2476>🛠 Candidates</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val candidates = plugin.store.candidates(term, includeRemoved = true)
        val votes = plugin.store.voteCounts(term)

        // Header
        inv.setItem(
            4,
            icon(
                Material.PAPER,
                "<white>Election term:</white> <gold>#${term + 1}</gold>",
                listOf(
                    "<gray>Left click:</gray> <white>ACTIVE ↔ PROCESS</white>",
                    "<gray>Right click:</gray> <white>REMOVE (needs PROCESS)</white>",
                    "<dark_gray>PROCESS keeps votes, but cannot win.</dark_gray>"
                )
            )
        )

        // Candidates list (grid)
        var slot = 10
        for (cand in candidates) {
            if (slot >= 44) break

            val count = votes[cand.uuid] ?: 0
            val statusLabel = when (cand.status) {
                CandidateStatus.ACTIVE -> "<green>ACTIVE</green>"
                CandidateStatus.PROCESS -> "<gold>PROCESS</gold>"
                CandidateStatus.REMOVED -> "<red>REMOVED</red>"
            }

            val ban = plugin.store.activeApplyBan(cand.uuid)
            val banLine = when {
                ban == null -> "<gray>Apply ban:</gray> <green>None</green>"
                ban.permanent -> "<gray>Apply ban:</gray> <red>PERMA</red>"
                else -> "<gray>Apply ban:</gray> <gold>TEMP</gold>"
            }
            val badge = when (cand.status) {
                CandidateStatus.ACTIVE -> "<green>●</green>"
                CandidateStatus.PROCESS -> "<gold>●</gold>"
                CandidateStatus.REMOVED -> "<red>●</red>"
            }

            val hint = when (cand.status) {
                CandidateStatus.ACTIVE -> "<dark_gray>Left:</dark_gray> <gray>mark PROCESS</gray>"
                CandidateStatus.PROCESS -> "<dark_gray>Left:</dark_gray> <gray>mark ACTIVE</gray> <dark_gray>•</dark_gray> <dark_gray>Right:</dark_gray> <gray>remove</gray>"
                CandidateStatus.REMOVED -> "<dark_gray>Left:</dark_gray> <gray>restore</gray>"
            }

            val head = playerHead(
                cand.uuid,
                "$badge <white><bold>${cand.lastKnownName}</bold></white>",
                listOf(
                    "<gray>Status:</gray> $statusLabel <dark_gray>•</dark_gray> <gray>Votes:</gray> <white>$count</white>",
                    banLine,
                    "",
                    hint,
                    *(if (cand.status == CandidateStatus.REMOVED) listOf("<dark_gray>Votes were refunded.</dark_gray>") else emptyList()).toTypedArray()
                )
            )

            inv.setItem(slot, head)
            set(slot, head) { admin, click ->
                when (click) {
                    org.bukkit.event.inventory.ClickType.LEFT,
                    org.bukkit.event.inventory.ClickType.SHIFT_LEFT -> {
                        // LEFT click = toggle ACTIVE ↔ PROCESS (and allow restoring removed)
                        val wantsRestore = cand.status == CandidateStatus.REMOVED
                        val hasPerm = if (wantsRestore) {
                            admin.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE)
                                    || admin.hasPermission(Perms.LEGACY_ADMIN_CANDIDATES)
                                    || admin.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
                        } else {
                            admin.hasPermission(Perms.ADMIN_CANDIDATES_PROCESS)
                                    || admin.hasPermission(Perms.LEGACY_ADMIN_CANDIDATES)
                                    || admin.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
                        }

                        if (!hasPerm) {
                            val key = if (wantsRestore) "admin.candidates.no_permission_restore" else "admin.candidates.no_permission_process"
                            denyMsg(admin, key)
                            plugin.gui.open(admin, AdminCandidatesMenu(plugin))
                            return@set
                        }

                        val next = when (cand.status) {
                            CandidateStatus.ACTIVE -> CandidateStatus.PROCESS
                            CandidateStatus.PROCESS -> CandidateStatus.ACTIVE
                            CandidateStatus.REMOVED -> CandidateStatus.ACTIVE
                        }
                        overrideClickSound(UiClickSound.CONFIRM)
                        plugin.scope.launch(plugin.mainDispatcher) {
                            plugin.adminActions.setCandidateStatus(admin, term, cand.uuid, next)
                            admin.sendMessage("${cand.lastKnownName} set to ${next.name} for term #${term + 1}.")
                            plugin.gui.open(admin, AdminCandidatesMenu(plugin))
                        }
                    }

                    org.bukkit.event.inventory.ClickType.RIGHT,
                    org.bukkit.event.inventory.ClickType.SHIFT_RIGHT -> {
                        // RIGHT click = remove (only if already PROCESS)
                        val hasPerm = admin.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE)
                                || admin.hasPermission(Perms.LEGACY_ADMIN_CANDIDATES)
                                || admin.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
                        if (!hasPerm) {
                            denyMsg(admin, "admin.candidates.no_permission_remove")
                            plugin.gui.open(admin, AdminCandidatesMenu(plugin))
                            return@set
                        }

                        when (cand.status) {
                            CandidateStatus.PROCESS -> plugin.gui.open(admin, ConfirmRemoveCandidateMenu(plugin, term, cand.uuid))
                            CandidateStatus.ACTIVE -> {
                                denyMsg(admin, "admin.candidates.must_process")
                                plugin.gui.open(admin, AdminCandidatesMenu(plugin))
                            }
                            CandidateStatus.REMOVED -> {
                                denyMsg(admin, "admin.candidates.already_removed", mapOf("name" to cand.lastKnownName))
                                plugin.gui.open(admin, AdminCandidatesMenu(plugin))
                            }
                        }
                    }

                    else -> {
                        // ignore other click types
                    }
                }
            }

            slot++
        }

        // Ban section
        val canApplyBan = player.hasPermission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                || player.hasPermission(Perms.LEGACY_ADMIN_CANDIDATES)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        if (canApplyBan) {
            val banButton = icon(
                Material.BARRIER,
                "<red>Ban player from applying</red>",
                listOf(
                    "<gray>Opens a search menu.</gray>",
                    "<dark_gray>Temp or perma ban.</dark_gray>"
                )
            )
            inv.setItem(49, banButton)
            set(49, banButton) { p, _ ->
                if (!canApplyBan) {
                    denyMsg(p, "admin.candidates.no_permission_applyban")
                    plugin.gui.open(p, AdminCandidatesMenu(plugin))
                    return@set
                }
                plugin.gui.open(p, AdminApplyBanSearchMenu(plugin))
            }
        }

        // Back
        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p, _ -> plugin.gui.open(p, AdminMenu(plugin)) }

        // Reload view
        inv.setItem(53, icon(Material.SPYGLASS, "<gray>Refresh</gray>"))
        set(53, inv.getItem(53)!!) { p, _ -> plugin.gui.open(p, AdminCandidatesMenu(plugin)) }
    }
}

