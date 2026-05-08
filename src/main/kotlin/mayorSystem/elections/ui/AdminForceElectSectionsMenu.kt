package mayorSystem.elections.ui

import mayorSystem.MayorPlugin
import mayorSystem.data.RequestStatus
import mayorSystem.ui.Menu
import mayorSystem.ui.menus.ApplyPerksMenu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminForceElectSectionsMenu(
    plugin: MayorPlugin,
    private val approvedCustomLoaded: Boolean = false,
    private val approvedCustomAvailable: Boolean = false
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#f09819>Force Elect</gradient> <gray>Sections</gray>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val session = AdminForceElectFlow.get(player.uniqueId)
        if (session == null) {
            plugin.gui.open(player, AdminForceElectMenu(plugin))
            return
        }

        val term = session.termIndex
        val allowed = plugin.settings.perksAllowed(term)
        val chosen = session.chosenPerks
        val modeLabel = if (session.mode == AdminForceElectFlow.Mode.SET_FORCED) "SET FORCED" else "ELECT NOW"
        if (!approvedCustomLoaded) {
            plugin.scope.launch(plugin.mainDispatcher) {
                val approved = withContext(Dispatchers.IO) {
                    plugin.store.listRequests(term, RequestStatus.APPROVED)
                        .any { it.candidate == session.target }
                }
                if (player.isOnline) {
                    plugin.gui.open(player, AdminForceElectSectionsMenu(plugin, approvedCustomLoaded = true, approvedCustomAvailable = approved))
                }
            }
        }

        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                "<gold>Pick perks for ${session.targetName}</gold>",
                listOf(
                    "<gray>Term:</gray> <white>#${term + 1}</white>",
                    "<gray>Mode:</gray> <white>$modeLabel</white>",
                    "<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>",
                    "",
                    "<dark_gray>Admin force-elect flow.</dark_gray>"
                )
            )
        )

        val secRoot = plugin.config.getConfigurationSection("perks.sections")
        val slots = contentSlots(inv)
        var slotIndex = 0
        if (secRoot != null) {
            for (sectionId in secRoot.getKeys(false)) {
                if (slotIndex >= slots.size) break

                val base = "perks.sections.$sectionId"
                val enabled = plugin.config.getBoolean("$base.enabled", true)
                if (!enabled) continue
                val slot = slots[slotIndex++]

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
                set(slot, item) { p -> plugin.gui.open(p, AdminForceElectPerksMenu(plugin, sectionId)) }
            }
        }

        if (slotIndex < slots.size) {
            val slot = slots[slotIndex]
            if (!approvedCustomLoaded) {
                val item = icon(
                    Material.CLOCK,
                    "<gray>Custom Perks</gray>",
                    listOf("<yellow>Loading approved custom perks...</yellow>")
                )
                inv.setItem(slot, item)
            } else if (approvedCustomAvailable) {
                val item = icon(
                    Material.ANVIL,
                    "<gradient:#f7971e:#ffd200>Custom Perks</gradient>",
                    listOf(
                        "<gray>Approved custom perks.</gray>",
                        "<dark_gray>(Admins must approve first.)</dark_gray>"
                    )
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, AdminForceElectPerksMenu(plugin, ApplyPerksMenu.CUSTOM_SECTION_ID)) }
            } else {
                val item = icon(
                    Material.GRAY_DYE,
                    "<gray>Custom Perks</gray>",
                    listOf(
                        "<dark_gray>No approved custom perks.</dark_gray>",
                        "<gray>Request from:</gray> <white>Candidate -> Custom Perks</white>"
                    )
                )
                inv.setItem(slot, item)
            }
        }

        val next = icon(
            Material.LIME_WOOL,
            "<green>Next</green>",
            listOf(
                "<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>",
                "<dark_gray>Confirm force-elect on next page.</dark_gray>"
            )
        )
        inv.setItem(53, next)
        set(53, next) { p ->
            val current = AdminForceElectFlow.get(p.uniqueId)
            val selected = current?.chosenPerks?.size ?: 0
            if (selected != allowed) {
                denyMsg(
                    p,
                    "admin.perks.perk_exact",
                    mapOf("limit" to allowed.toString(), "selected" to selected.toString())
                )
                return@set
            }
            plugin.gui.open(p, AdminForceElectConfirmMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>", listOf("<dark_gray>Return to player list.</dark_gray>"))
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminForceElectMenu(plugin)) }

        val cancel = icon(Material.RED_DYE, "<red>Cancel</red>", listOf("<gray>Discard changes.</gray>"))
        inv.setItem(49, cancel)
        setDeny(49, cancel) { p, _ ->
            AdminForceElectFlow.clear(p.uniqueId)
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }
    }
}

