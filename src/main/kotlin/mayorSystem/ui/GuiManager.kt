package mayorSystem.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.menus.ApplyConfirmMenu
import mayorSystem.ui.menus.ApplyPerksMenu
import mayorSystem.ui.menus.ApplySectionsMenu
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
import java.lang.reflect.Modifier
import java.util.logging.Level
import java.util.UUID
import java.util.WeakHashMap

class GuiManager(private val plugin: MayorPlugin) : Listener {

    private val mm = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    private enum class PermFingerprintScope { ADMIN, PUBLIC }

    private data class OpenMenu(
        val menu: Menu,
        val viewer: UUID,
        val permFingerprint: String?,
        val permFingerprintScope: PermFingerprintScope?
    )
    private val open = WeakHashMap<Inventory, OpenMenu>()

    private data class OpenAnvilPrompt(
        val viewer: UUID,
        val permFingerprint: String?,
        val permFingerprintScope: PermFingerprintScope?,
        var completed: Boolean = false,
        val onResult: (Player, String?) -> Unit
    )

    private val openAnvil = WeakHashMap<Inventory, OpenAnvilPrompt>()

    fun track(inv: Inventory, menu: Menu, viewer: UUID) {
        val viewerPlayer = org.bukkit.Bukkit.getPlayer(viewer)
        val scope = when {
            viewerPlayer == null -> null
            isAdminMenu(menu) -> PermFingerprintScope.ADMIN
            isPublicMenu(menu) -> PermFingerprintScope.PUBLIC
            else -> null
        }
        val fingerprint = if (viewerPlayer != null && scope != null) {
            permissionFingerprint(viewerPlayer, scope)
        } else {
            null
        }
        open[inv] = OpenMenu(menu, viewer, fingerprint, scope)
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
        if (isAdminMenu(menu) && !Perms.isAdmin(player)) {
            denyNoPermission(player, close = false)
            return
        }
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

        val sourceMenu = open[player.openInventory.topInventory]
            ?.takeIf { it.viewer == player.uniqueId }
        openAnvil[inv] = OpenAnvilPrompt(
            viewer = player.uniqueId,
            permFingerprint = sourceMenu?.permFingerprint,
            permFingerprintScope = sourceMenu?.permFingerprintScope,
            completed = false,
            onResult = onResult
        )
        // Explicit Inventory type avoids Paper's openInventory overload ambiguity in Kotlin.
        player.openInventory(inv)
    }

    private fun canOpenMenus(player: Player): Boolean {
        if (!plugin.isReady()) {
            player.sendMessage(mm.deserialize("<yellow>${plugin.settings.titleName} system is still loading. Try again in a moment.</yellow>"))
            return false
        }
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
            if (!isFingerprintValid(who, data.permFingerprint, data.permFingerprintScope)) {
                e.isCancelled = true
                open.remove(top)
                denyNoPermission(who, close = true)
                return
            }

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
        if (!isFingerprintValid(who, prompt.permFingerprint, prompt.permFingerprintScope)) {
            e.isCancelled = true
            prompt.completed = true
            openAnvil.remove(top)
            denyNoPermission(who, close = true)
            return
        }

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
        val data = open.remove(e.inventory)

        if (data != null) {
            val player = e.player as? Player
            if (player != null && isApplyMenu(data.menu)) {
                val viewer = player.uniqueId
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (!isViewingApplyMenu(viewer)) {
                        plugin.applyFlow.clear(viewer)
                    }
                })
            }
        }

        val prompt = openAnvil.remove(e.inventory) ?: return
        if (prompt.completed) return
        val player = e.player as? Player ?: return
        if (player.uniqueId != prompt.viewer) return
        if (!isFingerprintValid(player, prompt.permFingerprint, prompt.permFingerprintScope)) return

        // Cancel (run next tick so any menu reopen happens safely after close)
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                prompt.onResult(player, null)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Anvil prompt cancel callback crashed", t)
            }
        })
    }

    private fun isApplyMenu(menu: Menu): Boolean =
        menu is ApplySectionsMenu || menu is ApplyPerksMenu || menu is ApplyConfirmMenu

    private fun isViewingApplyMenu(viewer: UUID): Boolean =
        open.values.any { it.viewer == viewer && isApplyMenu(it.menu) }

    private fun isAdminMenu(menu: Menu): Boolean {
        val qn = menu::class.qualifiedName ?: return false
        return qn.startsWith("mayorSystem.") &&
            (qn.contains(".ui.Admin") || qn.endsWith(".governance.ui.GovernanceSettingsMenu"))
    }

    private fun isPublicMenu(menu: Menu): Boolean {
        val qn = menu::class.qualifiedName ?: return false
        return qn.startsWith("mayorSystem.") && qn.contains(".ui.menus.")
    }

    private fun isFingerprintValid(player: Player, expected: String?, scope: PermFingerprintScope?): Boolean {
        if (expected == null || scope == null) return true
        return expected == permissionFingerprint(player, scope)
    }

    private fun permissionFingerprint(player: Player, scope: PermFingerprintScope): String {
        val nodes = when (scope) {
            PermFingerprintScope.ADMIN -> trackedAdminPermNodes
            PermFingerprintScope.PUBLIC -> trackedPublicPermNodes
        }
        return nodes.joinToString("|") { perm ->
            if (player.hasPermission(perm)) "1" else "0"
        }
    }

    private fun denyNoPermission(player: Player, close: Boolean) {
        plugin.messages.msg(player, "errors.no_permission")
        player.playSound(player.location, org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.9f, 1.0f)
        if (close) {
            player.closeInventory()
        }
    }

    private companion object {
        val trackedAdminPermNodes: List<String> by lazy {
            Perms::class.java.declaredFields
                .filter { field ->
                    Modifier.isStatic(field.modifiers) &&
                        field.type == String::class.java &&
                        field.name.startsWith("ADMIN_")
                }
                .mapNotNull { field -> runCatching { field.get(null) as? String }.getOrNull() }
                .distinct()
                .sorted()
        }

        val trackedPublicPermNodes: List<String> = listOf(
            Perms.USE,
            Perms.APPLY,
            Perms.VOTE,
            Perms.CANDIDATE
        )
    }
}

