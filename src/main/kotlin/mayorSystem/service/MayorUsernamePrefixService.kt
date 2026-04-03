package mayorSystem.service

import mayorSystem.MayorPlugin
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture
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
        pendingGroupCreates.clear()
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

        val group = resolvedGroupName()
        if (group == null) {
            clearAll(null)
            return
        }
        if (!plugin.isReady() || !plugin.hasTermService()) {
            return
        }

        val context = currentMayorContext()
        syncResolved(group, context.mayorUuid, context.previousMayorCandidates)
    }

    /**
     * Sync using an already-known mayor UUID (e.g. immediate post-finalize path),
     * without recomputing current term from cached schedule state.
     */
    fun syncKnownMayor(mayorUuid: UUID?, previousMayorUuid: UUID? = null) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncKnownMayor(mayorUuid, previousMayorUuid) })
            return
        }

        val group = resolvedGroupName()
        if (group == null) {
            clearAll(null)
            return
        }

        val previousMayors = linkedSetOf<UUID>()
        if (previousMayorUuid != null && previousMayorUuid != mayorUuid) {
            previousMayors += previousMayorUuid
        }
        syncResolved(group, mayorUuid, previousMayors)
    }

    private fun syncResolved(group: String, mayorUuid: UUID?, explicitPreviousMayors: Set<UUID> = emptySet()) {
        val tracked = trackedAssignment()
        val ops = mutableListOf<CompletableFuture<Boolean>>()
        val queuedRemovals = hashSetOf<String>()

        fun queueRemoval(uuid: UUID?, removeGroup: String?, loadIfNeeded: Boolean) {
            if (uuid == null || removeGroup == null) return
            val key = "${uuid}:${removeGroup.lowercase()}"
            if (!queuedRemovals.add(key)) return
            ops += removeFromPlayer(uuid, removeGroup, loadIfNeeded)
        }

        if (mayorUuid == null) {
            queueRemoval(tracked.uuid, tracked.group ?: group, loadIfNeeded = true)
            explicitPreviousMayors.forEach { previousMayor ->
                queueRemoval(previousMayor, group, loadIfNeeded = true)
                if (!tracked.group.isNullOrBlank() && !tracked.group.equals(group, ignoreCase = true)) {
                    queueRemoval(previousMayor, tracked.group, loadIfNeeded = true)
                }
            }
            val cleanupGroups = linkedSetOf<String>()
            tracked.group?.let { cleanupGroups += it }
            cleanupGroups += group
            Bukkit.getOnlinePlayers().forEach { p ->
                cleanupGroups.forEach { g ->
                    queueRemoval(p.uniqueId, g, loadIfNeeded = false)
                }
            }
            runAfterOps(ops) { writeTrackedAssignment(null, null) }
            return
        }

        val oldMayors = linkedSetOf<UUID>()
        if (tracked.uuid != null && tracked.uuid != mayorUuid) {
            oldMayors += tracked.uuid
        }
        explicitPreviousMayors.forEach { previousMayor ->
            if (previousMayor != mayorUuid) {
                oldMayors += previousMayor
            }
        }
        oldMayors.forEach { previousMayor ->
            queueRemoval(previousMayor, group, loadIfNeeded = true)
            if (!tracked.group.isNullOrBlank() && !tracked.group.equals(group, ignoreCase = true)) {
                queueRemoval(previousMayor, tracked.group, loadIfNeeded = true)
            }
        }
        if (!tracked.group.isNullOrBlank() && !tracked.group.equals(group, ignoreCase = true)) {
            queueRemoval(mayorUuid, tracked.group, loadIfNeeded = true)
            Bukkit.getOnlinePlayers().forEach { p ->
                if (p.uniqueId != mayorUuid) {
                    queueRemoval(p.uniqueId, tracked.group, loadIfNeeded = false)
                }
            }
        }

        ops += applyToPlayer(mayorUuid, group)
        Bukkit.getOnlinePlayers().forEach { p ->
            if (p.uniqueId != mayorUuid) {
                queueRemoval(p.uniqueId, group, loadIfNeeded = false)
            }
        }
        runAfterOps(ops) { writeTrackedAssignment(mayorUuid, group) }
    }

    fun syncPlayer(uuid: UUID) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncPlayer(uuid) })
            return
        }

        val tracked = trackedAssignment()
        val group = resolvedGroupName()
        if (group == null) {
            tracked.group?.let { g ->
                removeFromPlayer(uuid, g, loadIfNeeded = false)
            }
            return
        }
        if (!plugin.isReady() || !plugin.hasTermService()) {
            return
        }

        val mayorUuid = currentMayorContext().mayorUuid
        if (mayorUuid != null && mayorUuid == uuid) {
            runAfterOps(listOf(applyToPlayer(uuid, group))) {
                writeTrackedAssignment(uuid, group)
            }
        } else {
            val ops = mutableListOf<CompletableFuture<Boolean>>()
            ops += removeFromPlayer(uuid, group, loadIfNeeded = false)
            if (!tracked.group.isNullOrBlank() && !tracked.group.equals(group, ignoreCase = true)) {
                ops += removeFromPlayer(uuid, tracked.group, loadIfNeeded = false)
            }
            runAfterOps(ops) {}
        }
    }

    private fun runAfterOps(ops: List<CompletableFuture<Boolean>>, onSuccess: () -> Unit) {
        if (ops.isEmpty()) {
            onSuccess()
            return
        }
        CompletableFuture.allOf(*ops.toTypedArray()).whenComplete { _, _ ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!plugin.isEnabled) return@Runnable
                val failed = ops.any { !it.getNow(false) }
                if (failed) {
                    plugin.logger.warning("LuckPerms sync did not fully complete; tracked mayor group state unchanged.")
                    return@Runnable
                }
                onSuccess()
            })
        }
    }

    private fun currentMayorContext(): MayorSyncContext {
        val currentTerm = plugin.termService.computeNow().first
        if (currentTerm < 0) {
            return MayorSyncContext(mayorUuid = null, previousMayorCandidates = emptySet())
        }
        val currentMayor = plugin.store.winner(currentTerm)
        val previousMayorCandidates = linkedSetOf<UUID>()
        if (currentTerm > 0) {
            plugin.store.winner(currentTerm - 1)
                ?.takeIf { it != currentMayor }
                ?.let { previousMayorCandidates += it }
        }
        return MayorSyncContext(
            mayorUuid = currentMayor,
            previousMayorCandidates = previousMayorCandidates
        )
    }

    private fun resolvedGroupName(): String? {
        if (!plugin.settings.usernameGroupEnabled) return null
        val group = plugin.settings.usernameGroup.trim()
        if (group.isBlank()) return null

        val valid = GROUP_NAME_REGEX.matches(group)
        if (!valid) {
            if (warnedInvalidGroup != group) {
                plugin.logger.warning(
                    "Invalid title.username_group '$group'. " +
                        "Allowed: letters, numbers, _, -, ."
                )
                warnedInvalidGroup = group
            }
            return null
        }
        warnedInvalidGroup = null
        return group
    }

    private fun applyToPlayer(uuid: UUID, group: String): CompletableFuture<Boolean> {
        val lp = luckPerms() ?: return completedFalse()
        if (!ensureGroupExists(lp, group)) return completedFalse()

        return lp.userManager.modifyUser(uuid) { user ->
            removeGroupNodes(user, group)
            user.data().add(InheritanceNode.builder(group).build())
        }.thenApply { true }
            .exceptionally { err ->
                plugin.logger.severe("Failed to apply LuckPerms group '$group' to $uuid: ${err.message}")
                false
            }
    }

    private fun removeFromPlayer(uuid: UUID, group: String?, loadIfNeeded: Boolean): CompletableFuture<Boolean> {
        if (group == null) return completedTrue()
        val lp = luckPerms() ?: return completedFalse()

        if (loadIfNeeded) {
            return lp.userManager.modifyUser(uuid) { user ->
                removeGroupNodes(user, group)
            }.thenApply { true }
                .exceptionally { err ->
                    plugin.logger.severe("Failed to remove LuckPerms group '$group' from $uuid: ${err.message}")
                    false
                }
        }

        val loaded = lp.userManager.getUser(uuid) ?: return completedTrue()
        val changed = removeGroupNodes(loaded, group)
        if (!changed) return completedTrue()

        return lp.userManager.saveUser(loaded)
            .thenApply { true }
            .exceptionally { err ->
                plugin.logger.severe("Failed to save LuckPerms user after removing '$group' from $uuid: ${err.message}")
                false
            }
    }

    private fun removeGroupNodes(user: User, group: String): Boolean {
        var changed = false

        val persistent = user.data()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .filter { it.groupName.equals(group, ignoreCase = true) }
        if (persistent.isNotEmpty()) {
            persistent.forEach { user.data().remove(it) }
            changed = true
        }

        val transient = user.transientData()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .filter { it.groupName.equals(group, ignoreCase = true) }
        if (transient.isNotEmpty()) {
            transient.forEach { user.transientData().remove(it) }
            changed = true
        }

        return changed
    }

    private fun ensureGroupExists(lp: LuckPerms, group: String): Boolean {
        if (lp.groupManager.getGroup(group) != null) {
            warnedMissingGroup = null
            return true
        }

        if (warnedMissingGroup != group) {
            plugin.logger.warning(
                "LuckPerms group '$group' does not exist. Auto-creating it now."
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
                    "Failed to auto-create LuckPerms group '$group': ${err.message}"
                )
            } else {
                plugin.logger.info("Auto-created LuckPerms group '$group'.")
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

        val ops = mutableListOf<CompletableFuture<Boolean>>()
        if (tracked.uuid != null) {
            cleanupGroups.forEach { g ->
                ops += removeFromPlayer(tracked.uuid, g, loadIfNeeded = true)
            }
        }

        Bukkit.getOnlinePlayers().forEach { player ->
            cleanupGroups.forEach { g ->
                ops += removeFromPlayer(player.uniqueId, g, loadIfNeeded = false)
            }
        }
        runAfterOps(ops) { writeTrackedAssignment(null, null) }
    }

    private fun luckPerms(): LuckPerms? {
        val lpPlugin = plugin.server.pluginManager.getPlugin("LuckPerms")
        if (lpPlugin == null || !lpPlugin.isEnabled) {
            if (plugin.settings.usernameGroupEnabled && !warnedMissingLuckPerms) {
                plugin.logger.warning("LuckPerms was not found; mayor group feature is inactive.")
                warnedMissingLuckPerms = true
            }
            return null
        }

        val provider = runCatching { LuckPermsProvider.get() }.getOrNull()
        if (provider == null) {
            if (plugin.settings.usernameGroupEnabled && !warnedMissingLuckPerms) {
                plugin.logger.warning("LuckPerms API provider is unavailable; mayor group feature is inactive.")
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

        fun completedTrue(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
        fun completedFalse(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
    }

    private data class TrackedAssignment(val uuid: UUID?, val group: String?)
    private data class MayorSyncContext(val mayorUuid: UUID?, val previousMayorCandidates: Set<UUID>)
}
