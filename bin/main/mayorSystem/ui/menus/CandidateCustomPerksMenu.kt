package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

/**
 * Custom perk menu.
 *
 * Players can:
 * - submit custom perk requests (if they meet the condition and per-term limit)
 * - once approved by admins, select them as a perk (counts against perks-per-term)
 *
 * Note: perks can be *strictly locked* after confirming the application.
 * In that case, selection is disabled, but requesting is still allowed.
 */
class CandidateCustomPerksMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#f7971e:#ffd200>🛠 Custom Perks</gradient>")
    override val rows: Int = 6

    /**
     * Checks the admin-configured condition for who can create custom perk requests.
     */
    private fun canRequestCustomPerk(player: Player): Pair<Boolean, String> {
        return when (plugin.settings.customRequestCondition) {
            mayorSystem.config.CustomRequestCondition.NONE ->
                true to "<green>No restriction</green>"

            mayorSystem.config.CustomRequestCondition.ELECTED_ONCE -> {
                val ok = plugin.store.hasEverBeenMayor(player.uniqueId)
                ok to if (ok) "<green>Requirement met</green>" else "<red>Must have been elected mayor at least once</red>"
            }

            mayorSystem.config.CustomRequestCondition.APPLY_REQUIREMENTS -> {
                val minMinutes = plugin.settings.applyPlaytimeMinutes
                if (minMinutes <= 0) return true to "<green>No playtime requirement</green>"
                val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
                val minTicks = minMinutes * 60 * 20
                val ok = playTicks >= minTicks
                ok to if (ok) "<green>Playtime requirement met</green>" else "<red>Requires ${minMinutes} minutes playtime</red>"
            }
        }
    }

    override fun draw(player: Player, inv: Inventory) {
        border(inv)


        val now = Instant.now()
        val term = plugin.termService.compute(now).second
        val isCandidate = plugin.store.isCandidate(term, player.uniqueId)

        val locked = if (isCandidate) plugin.store.isPerksLocked(term, player.uniqueId) else true
        val allowedPerks = plugin.settings.perksAllowed(term)
        val chosen = if (isCandidate) {
            plugin.store.chosenPerks(term, player.uniqueId).toMutableSet()
        } else {
            mutableSetOf<String>()
        }

        val requests: List<CustomPerkRequest> = plugin.store
            .listRequests(term)
            .filter { it.candidate == player.uniqueId }
            .sortedBy { it.id }

        val used = requests.size
        val limit = plugin.settings.customRequestsLimitPerTerm
        val limitReached = limit > 0 && used >= limit

        // Header

        inv.setItem(
            4,
            icon(
                Material.KNOWLEDGE_BOOK,
                "<gold>How it works</gold>",
                buildList {
                    add("<gray>Submit requests for admins to review.</gray>")
                    add("<gray>Approved requests become selectable perks.</gray>")
                    add("")
                    add("<gray>Requests submitted:</gray> <white>$used/${if (limit > 0) limit else "Unlimited"}</white>")
                    if (isCandidate) {
                        add("<gray>Chosen perks:</gray> <white>${chosen.size}/$allowedPerks</white>")
                        if (locked) {
                            add("")
                            add("<red>Locked:</red> <gray>You already confirmed your application.</gray>")
                            add("<gray>You can still request perks, but you can't select them now.</gray>")
                        }
                    } else {
                        add("<gray>Not a candidate:</gray> <white>apply to select perks.</white>")
                    }
                }
            )
        )


        // Submit request button
        val (meetsCondition, conditionMsg) = canRequestCustomPerk(player)
        val canSubmit = meetsCondition && !limitReached

        val submitLore = buildList {
            add("<gray>Requirement:</gray> $conditionMsg")
            add("")
            if (limitReached) {
                add("<red>Limit reached:</red> <gray>$used/$limit requests used.</gray>")
                return@buildList
            }
            if (!meetsCondition) {
                add("<gray>You don't meet the requirement to request custom perks.</gray>")
                return@buildList
            }
            add("<gray>Click, then type Title and Description in chat.</gray>")
            add("<dark_gray>Type 'cancel' to stop.</dark_gray>")
        }

        val submit = icon(
            if (canSubmit) Material.WRITABLE_BOOK else Material.BARRIER,
            if (canSubmit) "<green>+ Submit request</green>" else "<red>Cannot submit</red>",
            submitLore
        )
        inv.setItem(45, submit)
        set(45, submit) { p ->
            val (ok, msg) = canRequestCustomPerk(p)
            if (!ok) {
                denyMm(p, "<red>You can\'t request custom perks right now.</red>")
                p.sendMessage(mm.deserialize("<gray>Reason:</gray> " + msg))
                // Don't open chat prompt
                return@set
            }
            if (limitReached) {
                deny(p, "You reached the custom perk request limit for this term ($limit).")
                return@set
            }
            p.closeInventory()
            plugin.prompts.beginCustomPerkRequestFlow(p, term)
        }

        // Back button
        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(49, back)
        set(49, back) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }

        // List requests (approved ones become selectable, unless locked)
        var slot = 10
        requests.take(21).forEach { req ->
            if (slot >= inv.size - 9) return@forEach

            val selectable = req.status == RequestStatus.APPROVED
            val canSelect = isCandidate && !locked
            val perkId = "custom:${req.id}"
            val selected = chosen.contains(perkId)

            val mat = when (req.status) {
                RequestStatus.PENDING -> Material.YELLOW_DYE
                RequestStatus.DENIED -> Material.RED_DYE
                RequestStatus.APPROVED -> if (selected) Material.LIME_DYE else Material.GRAY_DYE
            }

            val lore = buildList {
                add("<gray>Status:</gray> <white>${req.status}</white>")
                add("")
                if (req.description.isNotBlank()) add("<gray>${req.description}</gray>")
                add("")


                when {
                    !selectable -> add("<dark_gray>Not selectable until approved.</dark_gray>")
                    !isCandidate -> add("<gray>Apply to select perks.</gray>")
                    locked -> {
                        add("<gray>Perk ID:</gray> <white>$perkId</white>")
                        add("<red>Locked</red> <gray>(cannot select/remove now)</gray>")
                    }
                    else -> {
                        add("<gray>Perk ID:</gray> <white>$perkId</white>")
                        add("<gray>Click to ${if (selected) "remove" else "select"}.</gray>")
                        add("<dark_gray>Counts toward your perks-per-term limit.</dark_gray>")
                    }
                }
            }

            val item = icon(
                mat,
                (if (selected) "<green>✓</green> " else "") + "<yellow>#${req.id}</yellow> <white>${req.title}</white>",
                lore
            )
            inv.setItem(slot, item)

            if (selectable && canSelect) {
                set(slot, item) { p ->
                    val next = chosen.toMutableSet()
                    if (selected) {
                        next.remove(perkId)
                    } else {
                        if (next.size >= allowedPerks) {
                            deny(p, "You already selected the maximum perks ($allowedPerks).")
                            return@set
                        }
                        next.add(perkId)
                    }
                    plugin.store.setChosenPerks(term, p.uniqueId, next)
                    plugin.gui.open(p, CandidateCustomPerksMenu(plugin))
                }
            }

            slot++
            if (slot % 9 == 8) slot++ // skip right border column nicely
        }
    }
}
