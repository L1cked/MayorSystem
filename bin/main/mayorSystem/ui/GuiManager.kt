package mayorSystem.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import java.util.logging.Level
import java.util.UUID
import java.util.WeakHashMap

class GuiManager(private val plugin: MayorPlugin) : Listener {

    private val mm = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    private data class OpenMenu(val menu: Menu, val viewer: UUID)
    private val open = WeakHashMap<Inventory, OpenMenu>()

    private data class OpenAnvilPrompt(
        val viewer: UUID,
        var completed: Boolean = false,
        val onResult: (Player, String?) -> Unit
    )

    private val openAnvil = WeakHashMap<Inventory, OpenAnvilPrompt>()

    fun track(inv: Inventory, menu: Menu, viewer: UUID) {
        open[inv] = OpenMenu(menu, viewer)
    }

    fun reopenIfViewing(viewer: UUID, menu: Menu) {
        val player = org.bukkit.Bukkit.getPlayer(viewer) ?: return
        if (!isViewing(viewer, menu)) return
        menu.open(player)
    }

    private fun isViewing(viewer: UUID, menu: Menu): Boolean {
        return open.values.any { it.viewer == viewer && it.menu === menu }
    }

    fun open(player: Player, menu: Menu) {
        if (!canOpenMenus(player)) return
        menu.open(player)
    }

    /**
     * Opens a simple anvil prompt that returns a single line of text.
     *
     * - Clicking the output slot (right) confirms.
     * - Closing the inventory cancels.
     */
    fun openAnvilPrompt(player: Player, title: net.kyori.adventure.text.Component, initialText: String, onResult: (Player, String?) -> Unit) {
        if (!canOpenMenus(player)) return
        val inv: Inventory = org.bukkit.Bukkit.createInventory(player, InventoryType.ANVIL, title)

        // Don't allow MiniMessage injection into our prompt label.
        val safe = initialText.replace("<", "").replace(">", "")

        val paper = ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta.apply {
                displayName(mm.deserialize("<gray>${safe.ifBlank { " " }}</gray>"))
            }
        }

        inv.setItem(0, paper)

        openAnvil[inv] = OpenAnvilPrompt(player.uniqueId, completed = false, onResult = onResult)
        // Explicit Inventory type avoids Paper's openInventory overload ambiguity in Kotlin.
        player.openInventory(inv)
    }

    private fun canOpenMenus(player: Player): Boolean {
        if (plugin.settings.isDisabled(mayorSystem.config.SystemGateOption.ACTIONS) && !Perms.isAdmin(player)) {
            plugin.messages.msg(player, "public.disabled")
            return false
        }
        if (!plugin.settings.publicEnabled && !Perms.isAdmin(player)) {
            plugin.messages.msg(player, "public.closed")
            return false
        }
        return true
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val top = e.view.topInventory
        val who = e.whoClicked as? Player ?: return

        // Normal menus
        val data = open[top]
        if (data != null) {
            if (who.uniqueId != data.viewer) return

            e.isCancelled = true
            val slot = e.rawSlot
            if (slot < 0 || slot >= top.size) return

            val btn = data.menu.buttonAt(slot)
            if (btn == null) {
                // Only play sounds when clicking a *clickable* icon.
                // Clicking filler / decorative items should be silent.
                return
            }


            // Menu clicks should never be able to take down the plugin.
            // Admins (especially) will spam-click buttons while testing.
            try {
                btn.onClick(who, e.click)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Menu click crashed: ${data.menu::class.java.simpleName} slot=$slot click=${e.click}", t)
                who.playSound(who.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.9f, 1.0f)
                who.sendMessage(
                    mm.deserialize("<red>That action failed.</red> <gray>Check console for details.</gray>")
                )
            }
            return
        }

        // Anvil prompts
        val prompt = openAnvil[top] ?: return
        if (who.uniqueId != prompt.viewer) return

        e.isCancelled = true

        // Confirm by clicking the output slot.
        if (e.rawSlot != 2) return

        who.playSound(who.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.2f)
        prompt.completed = true
        openAnvil.remove(top)

        val out = e.currentItem
        val comp = out?.itemMeta?.displayName()
        val text = comp?.let(plain::serialize)?.trim()?.takeIf { it.isNotBlank() }

        // Close first to keep inventory state sane.
        who.closeInventory()
        try {
            prompt.onResult(who, text)
        } catch (t: Throwable) {
            plugin.logger.log(Level.SEVERE, "Anvil prompt callback crashed", t)
            who.playSound(who.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.9f, 1.0f)
            who.sendMessage(mm.deserialize("<red>That action failed.</red> <gray>Check console for details.</gray>"))
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        open.remove(e.inventory)

        val prompt = openAnvil.remove(e.inventory) ?: return
        if (prompt.completed) return
        val player = e.player as? Player ?: return
        if (player.uniqueId != prompt.viewer) return

        // Cancel (run next tick so any menu reopen happens safely after close)
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                prompt.onResult(player, null)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Anvil prompt cancel callback crashed", t)
            }
        })
    }
}
