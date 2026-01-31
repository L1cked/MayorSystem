package mayorSystem.ui

import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.SkullMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

typealias ClickAction = (Player, ClickType) -> Unit

data class Button(val item: ItemStack, val onClick: ClickAction)

enum class UiClickSound {
    NONE,
    NAV,
    CONFIRM,
    DENY,
    NOT_ALLOWED
}

abstract class Menu(protected val plugin: MayorPlugin) {
    protected val mm = MiniMessage.miniMessage()

    abstract val title: Component
    abstract val rows: Int

    private val buttons = mutableMapOf<Int, Button>()
    private var currentViewer: UUID? = null

    private val clickSoundOverride = ThreadLocal<UiClickSound?>()

    protected open fun titleFor(player: Player): Component = title

    fun open(player: Player) {
        // Menus are often re-opened (paging, sorting, refreshing). Never keep stale click handlers.
        buttons.clear()
        currentViewer = player.uniqueId
        val inv = Bukkit.createInventory(null, rows * 9, titleFor(player))
        draw(player, inv)
        player.openInventory(inv)
        plugin.gui.track(inv, this, player.uniqueId)
    }

    private fun set(slot: Int, item: ItemStack, sound: UiClickSound, onClick: ClickAction? = null) {
        if (onClick == null) {
            buttons.remove(slot)
            return
        }

        val wrapped: ClickAction = { p, click ->
            // Decide click sound *after* running handler so "denied" actions can override.
            clickSoundOverride.set(null)
            onClick(p, click)
            val decided = clickSoundOverride.get() ?: sound
            clickSoundOverride.set(null)

            when (decided) {
                UiClickSound.NAV -> soundNav(p)
                UiClickSound.CONFIRM -> soundConfirm(p)
                UiClickSound.DENY -> soundDeny(p)
                UiClickSound.NOT_ALLOWED -> soundNotAllowed(p)
                UiClickSound.NONE -> {}
            }
        }
        buttons[slot] = Button(item, wrapped)
    }

    /** Default: navigation click sound. */
    protected fun set(slot: Int, item: ItemStack, onClick: ClickAction? = null) {
        set(slot, item, UiClickSound.NAV, onClick)
    }

    /** Confirmation: plays a stronger sound than normal navigation. */
    protected fun setConfirm(slot: Int, item: ItemStack, onClick: ClickAction) {
        set(slot, item, UiClickSound.CONFIRM, onClick)
    }

    /** Convenience: confirm handler that doesn't care about click type. */
    protected fun setConfirm(slot: Int, item: ItemStack, onClick: (Player) -> Unit) {
        setConfirm(slot, item) { p, _ -> onClick(p) }
    }

    /** Deny / cancel action: plays the deny sound (useful for confirmation menus). */
    protected fun setDeny(slot: Int, item: ItemStack, onClick: ClickAction) {
        set(slot, item, UiClickSound.DENY, onClick)
    }

    /** Convenience: deny handler that doesn't care about click type. */
    protected fun setDeny(slot: Int, item: ItemStack, onClick: (Player) -> Unit) {
        setDeny(slot, item) { p, _ -> onClick(p) }
    }

    // Convenience: old style click handlers still work
    protected fun set(slot: Int, item: ItemStack, onClick: (Player) -> Unit) {
        set(slot, item) { p, _ -> onClick(p) }
    }

    fun buttonAt(slot: Int): Button? = buttons[slot]

    // ---------------------------------------------------------------------
    // UI sounds
    // ---------------------------------------------------------------------

    protected fun soundNav(player: Player) {
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.0f)
    }

    protected fun soundConfirm(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.2f)
    }

    protected fun soundDeny(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f)
    }

    protected fun soundNotAllowed(player: Player) {
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.9f, 1.0f)
    }

    /**
     * Mark the current click as denied (plays deny sound instead of default) and optionally message the player.
     */
    protected fun deny(player: Player, message: String? = null) {
        clickSoundOverride.set(UiClickSound.NOT_ALLOWED)
        if (message != null) player.sendMessage(message)
    }

    /**
     * Deny with MiniMessage formatted text.
     */
    protected fun denyMm(player: Player, messageMm: String) {
        clickSoundOverride.set(UiClickSound.NOT_ALLOWED)
        player.sendMessage(mm.deserialize(messageMm))
    }

    /**
     * Override the sound played for this click (useful for special cases).
     */
    protected fun overrideClickSound(sound: UiClickSound) {
        clickSoundOverride.set(sound)
    }

    protected fun isBlocked(option: SystemGateOption): Boolean =
        plugin.settings.isBlocked(option)

    protected fun blockedReason(option: SystemGateOption): String? {
        return when {
            plugin.settings.isDisabled(option) -> "<red>The Mayor system is disabled.</red>"
            plugin.settings.isPaused(option) -> "<yellow>The Mayor system is paused.</yellow>"
            else -> null
        }
    }

    protected fun filler(name: Component = Component.empty()): ItemStack =
        ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta.apply { displayName(name) }
        }

    protected fun icon(mat: Material, nameMm: String, loreMm: List<String> = emptyList()): ItemStack =
        ItemStack(mat).apply {
            itemMeta = itemMeta.apply {
                displayName(mm.deserialize(nameMm))
                lore(loreMm.map(mm::deserialize))
            }
        }

    protected fun glow(item: ItemStack): ItemStack {
        val meta = item.itemMeta ?: return item
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        return item
    }

    /**
     * Player-head icon that uses the real skin.
     *
     * - If the skin isn't cached yet, Paper will usually fetch it over time.
     * - For offline / never-joined players, you typically won't have a skin to show.
     */
    protected fun playerHead(player: OfflinePlayer, nameMm: String, loreMm: List<String> = emptyList()): ItemStack =
        playerHead(player.uniqueId, player.name, nameMm, loreMm)

    /** Shortcut for known UUIDs. */
    protected fun playerHead(uuid: UUID, nameMm: String, loreMm: List<String> = emptyList()): ItemStack =
        playerHead(Bukkit.getOfflinePlayer(uuid), nameMm, loreMm)

    /**
     * Player-head icon for a stored UUID + last-known name.
     *
     * Why this exists:
     * - Bukkit.getOfflinePlayer(uuid) does not always carry a name (especially on fresh caches),
     *   which can prevent Paper from resolving the correct skin/profile.
     * - Paper's PlayerProfile lets us provide both UUID and name, improving offline head rendering.
     */
    protected fun playerHead(uuid: UUID, lastKnownName: String?, nameMm: String, loreMm: List<String> = emptyList()): ItemStack {
        val item = icon(Material.PLAYER_HEAD, nameMm, loreMm)
        val meta = item.itemMeta as? SkullMeta ?: return item

        val online = Bukkit.getPlayer(uuid)
        if (online != null) {
            meta.owningPlayer = online
            item.itemMeta = meta
            return item
        }

        // Prefer cached textures for offline players (use public API to avoid reflection access issues).
        val profile: Any = if (lastKnownName.isNullOrBlank()) {
            Bukkit.createProfile(uuid)
        } else {
            Bukkit.createProfile(uuid, lastKnownName)
        }
        val applied = plugin.skins.applyToProfile(profile, uuid)
        if (!setProfile(meta, profile)) {
            meta.owningPlayer = Bukkit.getOfflinePlayer(uuid)
        }
        item.itemMeta = meta
        if (!applied) {
            plugin.skins.request(uuid, lastKnownName, currentViewer, this)
        }
        return item

    }

    /** Convenience for "this player's head". */
    protected fun selfHead(player: Player, nameMm: String, loreMm: List<String> = emptyList()): ItemStack =
        playerHead(player.uniqueId, nameMm, loreMm)

    protected fun border(inv: Inventory) {
        val glass = filler()
        val size = inv.size
        for (i in 0 until size) {
            val isTop = i < 9
            val isBottom = i >= size - 9
            val isLeft = i % 9 == 0
            val isRight = i % 9 == 8
            if (isTop || isBottom || isLeft || isRight) inv.setItem(i, glass)
        }
    }

    /**
     * Word-wrap a plain-text blob into lore-sized lines.
     *
     * This intentionally does NOT try to parse MiniMessage tags; treat input as plain text.
     */
    protected fun wrapLore(text: String, maxLineLen: Int = 36): List<String> {
        val cleaned = text.replace("\n", " ").replace("\r", " ").trim()
        if (cleaned.isEmpty()) return emptyList()

        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = ArrayList<String>()
        var current = StringBuilder()

        for (w in words) {
            if (current.isEmpty()) {
                current.append(w)
                continue
            }

            if (current.length + 1 + w.length <= maxLineLen) {
                current.append(' ').append(w)
            } else {
                lines += current.toString()
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /**
     * Formats an [Instant] for UI display.
     *
     * We keep this in the UI base class so menus don't duplicate date formatting logic.
     * Uses the server's default timezone.
     */
    protected fun timeFmt(instant: Instant): String {
        val zdt = instant.atZone(ZoneId.systemDefault())
        return DATE_FMT.format(zdt)
    }

    private companion object {
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }

    abstract fun draw(player: Player, inv: Inventory)

    private fun setProfile(meta: SkullMeta, profile: Any): Boolean {
        val candidates = listOf(
            "org.bukkit.profile.PlayerProfile",
            "com.destroystokyo.paper.profile.PlayerProfile"
        )
        for (name in candidates) {
            val type = runCatching { Class.forName(name) }.getOrNull() ?: continue
            if (!type.isAssignableFrom(profile.javaClass)) continue
            val setter = SkullMeta::class.java.methods.firstOrNull {
                (it.name == "setPlayerProfile" || it.name == "setOwnerProfile") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == type
            }
            if (setter != null) {
                setter.invoke(meta, profile)
                return true
            }
        }
        return false
    }
}
