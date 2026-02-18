package mayorSystem.service

import mayorSystem.MayorPlugin
import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies/removes a dedicated LuckPerms group for the elected player.
 *
 * Server owners can manage permissions and meta (including prefix) on this group
 * directly in LuckPerms.
 */
class MayorUsernamePrefixService(private val plugin: MayorPlugin) : Listener {
    private val pendingGroupCreates = ConcurrentHashMap.newKeySet<String>()
    private var warnedMissingLuckPerms = false
    private var warnedInvalidGroup: String? = null
    private var warnedMissingGroup: String? = null
    private var suppressTrackedConfigSave = false

    fun onEnable() {
        syncAllOnline()
    }

    fun onReloadSettings() {
        syncAllOnline()
    }

    fun onDisable() {
        clearAll()
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            syncPlayer(e.player.uniqueId)
        })
    }

    @EventHandler
    fun onServiceRegister(e: ServiceRegisterEvent) {
        if (e.provider.service == LuckPerms::class.java) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncAllOnline() })
        }
    }

    @EventHandler
    fun onPluginEnable(e: PluginEnableEvent) {
        if (!e.plugin.name.equals("LuckPerms", ignoreCase = true)) return
        plugin.server.scheduler.runTask(plugin, Runnable { syncAllOnline() })
    }

    fun syncAllOnline() {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncAllOnline() })
            return
        }

        val tracked = trackedAssignment()
        val group = resolvedGroupName()
        val mayorUuid = currentMayorUuid()

        if (group == null || mayorUuid == null) {
            // Feature disabled/invalid or no elected mayor: remove previously tracked assignment.
            if (tracked.uuid != null) {
                removeFromPlayer(tracked.uuid, tracked.group ?: group, loadIfNeeded = true)
            }
            val cleanupGroups = linkedSetOf<String>()
            tracked.group?.let { cleanupGroups += it }
            group?.let { cleanupGroups += it }
            if (cleanupGroups.isNotEmpty()) {
                Bukkit.getOnlinePlayers().forEach { p ->
                    cleanupGroups.forEach { g -> removeFromPlayer(p.uniqueId, g, loadIfNeeded = false) }
                }
            }
            writeTrackedAssignment(null, null)
            return
        }

        if (tracked.uuid != null && tracked.uuid != mayorUuid) {
            removeFromPlayer(tracked.uuid, tracked.group ?: group, loadIfNeeded = true)
        }
        if (!tracked.group.isNullOrBlank() && !tracked.group.equals(group, ignoreCase = true)) {
            removeFromPlayer(mayorUuid, tracked.group, loadIfNeeded = true)
            Bukkit.getOnlinePlayers().forEach { p ->
                if (p.uniqueId != mayorUuid) {
                    removeFromPlayer(p.uniqueId, tracked.group, loadIfNeeded = false)
                }
            }
        }

        val applied = applyToPlayer(mayorUuid, group)
        if (!applied) return

        Bukkit.getOnlinePlayers().forEach { p ->
            if (p.uniqueId != mayorUuid) {
                removeFromPlayer(p.uniqueId, group, loadIfNeeded = false)
            }
        }
        writeTrackedAssignment(mayorUuid, group)
    }

    fun syncPlayer(uuid: UUID) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncPlayer(uuid) })
            return
        }

        val tracked = trackedAssignment()
        val group = resolvedGroupName()
        if (group == null) {
            tracked.group?.let { removeFromPlayer(uuid, it, loadIfNeeded = false) }
            return
        }

        val mayorUuid = currentMayorUuid()
        if (mayorUuid != null && mayorUuid == uuid) {
            if (applyToPlayer(uuid, group)) {
                writeTrackedAssignment(uuid, group)
            }
        } else {
            removeFromPlayer(uuid, group, loadIfNeeded = false)
            if (!tracked.group.isNullOrBlank() && !tracked.group.equals(group, ignoreCase = true)) {
                removeFromPlayer(uuid, tracked.group, loadIfNeeded = false)
            }
        }
    }

    private fun currentMayorUuid(): UUID? {
        if (!plugin.settings.usernameGroupEnabled) return null
        if (!plugin.isReady()) return null
        if (!plugin.hasTermService()) return null

        val currentTerm = plugin.termService.computeNow().first
        if (currentTerm < 0) return null
        return plugin.store.winner(currentTerm)
    }

    private fun resolvedGroupName(): String? {
        if (!plugin.settings.usernameGroupEnabled) return null
        val group = plugin.settings.usernameGroup.trim()
        if (group.isBlank()) return null

        val valid = GROUP_NAME_REGEX.matches(group)
        if (!valid) {
            if (warnedInvalidGroup != group) {
                plugin.logger.warning(
                    "[MayorSystem] Invalid title.username_group '$group'. " +
                        "Allowed: letters, numbers, _, -, ."
                )
                warnedInvalidGroup = group
            }
            return null
        }
        warnedInvalidGroup = null
        return group
    }

    private fun applyToPlayer(uuid: UUID, group: String): Boolean {
        val lp = luckPerms() ?: return false
        if (!ensureGroupExists(lp, group)) return false
        withUserOrLoad(lp, uuid) { user ->
            removeGroupNodes(user, group)

            val node = InheritanceNode.builder(group).build()
            user.data().add(node)
            lp.userManager.saveUser(user)
        }
        return true
    }

    private fun removeFromPlayer(uuid: UUID, group: String?, loadIfNeeded: Boolean) {
        if (group == null) return
        val lp = luckPerms() ?: return
        val mutate: (User) -> Unit = { user ->
            if (removeGroupNodes(user, group)) {
                lp.userManager.saveUser(user)
            }
        }
        if (loadIfNeeded) {
            withUserOrLoad(lp, uuid, mutate)
        } else {
            withUserIfLoaded(lp, uuid, mutate)
        }
    }

    private fun removeGroupNodes(user: User, group: String): Boolean {
        var changed = false
        val toRemovePersistent = user.data()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .filter { it.groupName.equals(group, ignoreCase = true) }
        if (toRemovePersistent.isNotEmpty()) {
            toRemovePersistent.forEach { user.data().remove(it) }
            changed = true
        }
        // Cleanup old transient nodes too (migration path).
        val toRemoveTransient = user.transientData()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .filter { it.groupName.equals(group, ignoreCase = true) }
        if (toRemoveTransient.isNotEmpty()) {
            toRemoveTransient.forEach { user.transientData().remove(it) }
            changed = true
        }
        return changed
    }

    private fun withUserIfLoaded(lp: LuckPerms, uuid: UUID, block: (User) -> Unit) {
        val user = lp.userManager.getUser(uuid) ?: return
        block(user)
    }

    private fun withUserOrLoad(lp: LuckPerms, uuid: UUID, block: (User) -> Unit) {
        val cached = lp.userManager.getUser(uuid)
        if (cached != null) {
            block(cached)
            return
        }

        lp.userManager.loadUser(uuid).thenAccept { loaded ->
            if (!plugin.isEnabled) return@thenAccept
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!plugin.isEnabled) return@Runnable
                block(loaded)
            })
        }
    }

    private fun ensureGroupExists(lp: LuckPerms, group: String): Boolean {
        if (lp.groupManager.getGroup(group) != null) {
            warnedMissingGroup = null
            return true
        }

        if (warnedMissingGroup != group) {
            plugin.logger.warning(
                "[MayorSystem] LuckPerms group '$group' does not exist. Auto-creating it now."
            )
            warnedMissingGroup = group
        }

        val key = group.lowercase()
        if (!pendingGroupCreates.add(key)) {
            return false
        }

        lp.groupManager.createAndLoadGroup(group).whenComplete { _, err ->
            pendingGroupCreates.remove(key)
            if (err != null) {
                plugin.logger.severe(
                    "[MayorSystem] Failed to auto-create LuckPerms group '$group': ${err.message}"
                )
            } else {
                plugin.logger.info("[MayorSystem] Auto-created LuckPerms group '$group'.")
            }

            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable { syncAllOnline() })
        }
        return false
    }

    private fun clearAll(group: String? = resolvedGroupName()) {
        val tracked = trackedAssignment()
        val cleanupGroups = linkedSetOf<String>()
        tracked.group?.let { cleanupGroups += it }
        group?.let { cleanupGroups += it }

        if (tracked.uuid != null) {
            cleanupGroups.forEach { g ->
                removeFromPlayer(tracked.uuid, g, loadIfNeeded = true)
            }
        }

        Bukkit.getOnlinePlayers().forEach { player ->
            cleanupGroups.forEach { g ->
                removeFromPlayer(player.uniqueId, g, loadIfNeeded = false)
            }
        }
        writeTrackedAssignment(null, null)
    }

    private fun luckPerms(): LuckPerms? {
        val provider = plugin.server.servicesManager
            .getRegistration(LuckPerms::class.java)
            ?.provider
        if (provider == null) {
            if (plugin.settings.usernameGroupEnabled && !warnedMissingLuckPerms) {
                plugin.logger.warning("[MayorSystem] LuckPerms was not found; mayor group feature is inactive.")
                warnedMissingLuckPerms = true
            }
            return null
        }
        warnedMissingLuckPerms = false
        return provider
    }

    private fun trackedAssignment(): TrackedAssignment {
        val uuid = plugin.config.getString(TRACKED_UUID_PATH)
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        val group = plugin.config.getString(TRACKED_GROUP_PATH)?.trim()?.takeIf { it.isNotBlank() }
        return TrackedAssignment(uuid, group)
    }

    private fun writeTrackedAssignment(uuid: UUID?, group: String?) {
        if (suppressTrackedConfigSave) return
        val oldUuid = plugin.config.getString(TRACKED_UUID_PATH)
        val oldGroup = plugin.config.getString(TRACKED_GROUP_PATH)
        val newUuid = uuid?.toString()
        val newGroup = group?.trim()?.takeIf { it.isNotBlank() }
        if (oldUuid == newUuid && oldGroup == newGroup) return

        suppressTrackedConfigSave = true
        try {
            plugin.config.set(TRACKED_UUID_PATH, newUuid)
            plugin.config.set(TRACKED_GROUP_PATH, newGroup)
            plugin.saveConfig()
        } finally {
            suppressTrackedConfigSave = false
        }
    }

    private companion object {
        val GROUP_NAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")
        const val TRACKED_UUID_PATH = "admin.mayor_group.tracked_uuid"
        const val TRACKED_GROUP_PATH = "admin.mayor_group.tracked_group"
    }

    private data class TrackedAssignment(val uuid: UUID?, val group: String?)
}
