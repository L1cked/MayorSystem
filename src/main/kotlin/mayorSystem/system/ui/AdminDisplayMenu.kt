package mayorSystem.system.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.showcase.ShowcaseMode
import mayorSystem.showcase.ShowcaseTarget
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminDisplayMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#3a7bd5>Display Controls</gradient>")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canMode = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canNpc = player.hasPermission(Perms.ADMIN_NPC_MAYOR)
        val canHologram = player.hasPermission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)

        val mode = plugin.showcase.mode()
        val electionOpen = plugin.showcase.electionOpenNow()
        val desiredTarget = plugin.showcase.desiredTarget(electionOpen)
        val activeTarget = plugin.showcase.activeTarget()
        val modeLore = mutableListOf("<gray>Current:</gray> <white>${mode.name}</white>")
        if (mode == ShowcaseMode.SWITCHING) {
            val state = if (electionOpen) "Election open" else "Election closed"
            modeLore += "<gray>Target:</gray> <white>${desiredTarget.name}</white> <dark_gray>($state)</dark_gray>"
            if (activeTarget != null && activeTarget != desiredTarget) {
                modeLore += "<gray>Active:</gray> <white>${activeTarget.name}</white> <dark_gray>(fallback)</dark_gray>"
            }
        }
        modeLore += ""
        modeLore += "<gray>SWITCHING:</gray> auto swap by election state."
        modeLore += "<gray>INDIVIDUAL:</gray> show both (if enabled)."
        modeLore += ""
        modeLore += if (canMode) "<dark_gray>Click to toggle.</dark_gray>" else "<dark_gray>Requires settings permission.</dark_gray>"

        val modeItem = icon(
            Material.DAYLIGHT_DETECTOR,
            "<yellow>Display Mode</yellow>",
            modeLore
        )
        inv.setItem(13, modeItem)
        set(13, modeItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_SETTINGS_EDIT)) return@set
            val next = if (mode == ShowcaseMode.SWITCHING) ShowcaseMode.INDIVIDUAL else ShowcaseMode.SWITCHING
            plugin.scope.launch(plugin.mainDispatcher) {
                val result = plugin.adminActions.updateConfig(
                    p,
                    "showcase.mode",
                    next.name,
                    reload = false,
                    successKey = "admin.showcase.mode_set",
                    successPlaceholders = mapOf("mode" to next.name)
                )
                if (result.isSuccess) {
                    plugin.showcase.sync()
                }
                dispatchResult(p, result, denyOnNonSuccess = true)
                plugin.gui.open(p, AdminDisplayMenu(plugin))
            }
        }

        val npcEnabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        val npcBackend = plugin.config.getString("npc.mayor.backend") ?: "<unknown>"

        val holoEnabled = plugin.config.getBoolean("hologram.leaderboard.enabled", false)
        val holoName = plugin.config.getString("hologram.leaderboard.name") ?: "mayorsystem_leaderboard"
        if (mode == ShowcaseMode.SWITCHING) {
            val slots = intArrayOf(21, 22, 23)
            when (desiredTarget) {
                ShowcaseTarget.NPC -> drawNpcControls(
                    inv,
                    npcEnabled,
                    npcBackend,
                    slots
                )
                ShowcaseTarget.HOLOGRAM -> drawHologramControls(
                    inv,
                    holoEnabled,
                    holoName,
                    slots
                )
            }
        } else {
            drawNpcControls(
                inv,
                npcEnabled,
                npcBackend,
                intArrayOf(19, 20, 21)
            )
            drawHologramControls(
                inv,
                holoEnabled,
                holoName,
                intArrayOf(23, 24, 25)
            )
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(27, back)
        set(27, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private fun drawNpcControls(
        inv: Inventory,
        enabled: Boolean,
        backend: String,
        slots: IntArray
    ) {
        val npcSpawn = icon(
            Material.ARMOR_STAND,
            "<yellow>Spawn/Move NPC</yellow>",
            listOf(
                "<gray>Enabled:</gray> <white>$enabled</white>",
                "<gray>Backend:</gray> <white>$backend</white>",
                "",
                "<dark_gray>Spawn or move the Mayor NPC here.</dark_gray>"
            )
        )
        val npcUpdate = icon(
            Material.NAME_TAG,
            "<yellow>Update NPC</yellow>",
            listOf("<dark_gray>Refresh the NPC skin/name.</dark_gray>")
        )
        val npcRemove = icon(
            Material.BARRIER,
            "<red>Remove NPC</red>",
            listOf("<dark_gray>Despawn the Mayor NPC.</dark_gray>")
        )

        inv.setItem(slots[0], npcSpawn)
        set(slots[0], npcSpawn) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_NPC_MAYOR)) return@set
            plugin.mayorNpc.spawnHere(p)
            plugin.gui.open(p, AdminDisplayMenu(plugin))
        }

        inv.setItem(slots[1], npcUpdate)
        set(slots[1], npcUpdate) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_NPC_MAYOR)) return@set
            plugin.mayorNpc.forceUpdate(p)
            plugin.gui.open(p, AdminDisplayMenu(plugin))
        }

        inv.setItem(slots[2], npcRemove)
        setConfirm(slots[2], npcRemove) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_NPC_MAYOR)) return@setConfirm
            plugin.mayorNpc.remove(p)
            plugin.gui.open(p, AdminDisplayMenu(plugin))
        }
    }

    private fun drawHologramControls(
        inv: Inventory,
        enabled: Boolean,
        name: String,
        slots: IntArray
    ) {
        val holoSpawn = icon(
            Material.END_CRYSTAL,
            "<yellow>Spawn/Move Hologram</yellow>",
            listOf(
                "<gray>Enabled:</gray> <white>$enabled</white>",
                "<gray>Name:</gray> <white>$name</white>",
                "",
                "<dark_gray>Spawn or move the leaderboard hologram here.</dark_gray>"
            )
        )
        val holoUpdate = icon(
            Material.PAPER,
            "<yellow>Update Hologram</yellow>",
            listOf("<dark_gray>Refresh hologram lines now.</dark_gray>")
        )
        val holoRemove = icon(
            Material.BARRIER,
            "<red>Remove Hologram</red>",
            listOf("<dark_gray>Despawn the leaderboard hologram.</dark_gray>")
        )

        inv.setItem(slots[0], holoSpawn)
        set(slots[0], holoSpawn) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_HOLOGRAM_LEADERBOARD)) return@set
            plugin.leaderboardHologram.spawnHere(p)
            plugin.gui.open(p, AdminDisplayMenu(plugin))
        }

        inv.setItem(slots[1], holoUpdate)
        set(slots[1], holoUpdate) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_HOLOGRAM_LEADERBOARD)) return@set
            plugin.leaderboardHologram.forceUpdate(p)
            plugin.gui.open(p, AdminDisplayMenu(plugin))
        }

        inv.setItem(slots[2], holoRemove)
        setConfirm(slots[2], holoRemove) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_HOLOGRAM_LEADERBOARD)) return@setConfirm
            plugin.leaderboardHologram.remove(p)
            plugin.gui.open(p, AdminDisplayMenu(plugin))
        }
    }
}
