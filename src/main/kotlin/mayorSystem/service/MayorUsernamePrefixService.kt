package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.rewards.DeluxeTagsIntegration
import mayorSystem.rewards.DeluxeTagsOperationResult
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardPlanner
import mayorSystem.rewards.DisplayRewardSettings
import mayorSystem.rewards.DisplayRewardSubject
import mayorSystem.rewards.DisplayRewardTagId
import mayorSystem.rewards.TrackedDisplayReward
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.PermissionNode
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies and removes the configured mayor display reward.
 *
 * The original LuckPerms group path is still supported when display_reward is
 * absent, and its tracked cleanup keys are still read before writing the newer
 * tracked reward state.
 */
class MayorUsernamePrefixService(private val plugin: MayorPlugin) : Listener {
    private val pendingGroupCreates = ConcurrentHashMap.newKeySet<String>()
    private val deluxeTags = DeluxeTagsIntegration(plugin)
    private var warnedMissingLuckPerms = false
    private var warnedInvalidGroup: String? = null
    private var warnedMissingGroup: String? = null
    private var warnedInvalidTagId: String? = null
    private var suppressTrackedConfigSave = false
    private var syncQueued = false
    private var lastAutomaticSyncAtMs = 0L
    private val queuedSyncNotifiers = linkedSetOf<CommandSender>()
    private val warnedDeferredTagCleanup = ConcurrentHashMap.newKeySet<String>()
    private val deluxeTagsWarningTimes = ConcurrentHashMap<String, Long>()

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
        syncPlayer(e.player.uniqueId)
    }

    @EventHandler
    fun onServiceRegister(e: ServiceRegisterEvent) {
        if (e.provider.service == LuckPerms::class.java) {
            syncAllOnline()
        }
    }

    @EventHandler
    fun onPluginEnable(e: PluginEnableEvent) {
        val name = e.plugin.name
        if (!name.equals("LuckPerms", ignoreCase = true) && !name.equals("DeluxeTags", ignoreCase = true)) return
        syncAllOnline()
    }

    fun syncAllOnline(notify: CommandSender? = null) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncAllOnline(notify) })
            return
        }
        val now = System.currentTimeMillis()
        if (notify == null && syncQueued) return
        if (notify == null && now - lastAutomaticSyncAtMs < 750L) return
        notify?.let { queuedSyncNotifiers += it }
        if (syncQueued) return
        syncQueued = true
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!plugin.isEnabled) return@Runnable
            syncQueued = false
            val notifiers = queuedSyncNotifiers.toList()
            queuedSyncNotifiers.clear()
            if (syncAllOnlineNow(notifiers)) {
                lastAutomaticSyncAtMs = System.currentTimeMillis()
            }
        })
    }

    private fun syncAllOnlineNow(notifiers: List<CommandSender>): Boolean {
        val settings = plugin.settings.displayReward
        if (!settings.enabled) {
            clearAll(settings, notifiers)
            return true
        }
        if (!plugin.isReady() || !plugin.hasTermService()) {
            return false
        }

        val context = currentMayorContext()
        syncResolved(settings, context.mayorUuid, context.previousMayorCandidates, notifiers)
        return true
    }

    /**
     * Sync using an already-known mayor UUID (e.g. immediate post-finalize path),
     * without recomputing current term from cached schedule state.
     */
    fun syncKnownMayor(mayorUuid: UUID?, previousMayorUuid: UUID? = null, afterSync: () -> Unit = {}) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncKnownMayor(mayorUuid, previousMayorUuid, afterSync) })
            return
        }

        val settings = plugin.settings.displayReward
        if (!settings.enabled) {
            clearAll(settings, emptyList(), afterSync)
            return
        }

        val previousMayors = linkedSetOf<UUID>()
        if (previousMayorUuid != null && previousMayorUuid != mayorUuid) {
            previousMayors += previousMayorUuid
        }
        syncResolved(settings, mayorUuid, previousMayors, emptyList(), afterSync)
    }

    private fun syncResolved(
        settings: DisplayRewardSettings,
        mayorUuid: UUID?,
        explicitPreviousMayors: Set<UUID> = emptySet(),
        notifiers: List<CommandSender> = emptyList(),
        afterSync: () -> Unit = {}
    ) {
        val tracked = trackedAssignment()
        val currentReward = currentConfiguredReward(settings)
        val ops = mutableListOf<CompletableFuture<Boolean>>()
        val queuedRemovals = hashSetOf<String>()
        var appliedAssignment: TrackedAssignment? = null

        fun queueRemoval(uuid: UUID?, reward: TrackedDisplayReward, loadIfNeeded: Boolean) {
            if (uuid == null || reward.isEmpty()) return
            val key = listOf(
                uuid.toString(),
                reward.rankGroup?.lowercase().orEmpty(),
                reward.tagPermission?.lowercase().orEmpty(),
                reward.tagId?.lowercase().orEmpty()
            ).joinToString(":")
            if (!queuedRemovals.add(key)) return
            ops += removeRewardFromPlayer(uuid, reward, loadIfNeeded, settings)
        }

        if (mayorUuid == null) {
            queueRemoval(tracked.uuid, tracked.toReward(), loadIfNeeded = true)
            explicitPreviousMayors.forEach { previousMayor ->
                queueRemoval(previousMayor, currentReward, loadIfNeeded = true)
                queueRemoval(previousMayor, tracked.toReward(), loadIfNeeded = true)
            }
            Bukkit.getOnlinePlayers().forEach { p ->
                queueRemoval(p.uniqueId, currentReward, loadIfNeeded = false)
                queueRemoval(p.uniqueId, tracked.toReward(), loadIfNeeded = false)
            }
            runAfterOps(ops, notifiers) {
                writeTrackedAssignment(null)
                afterSync()
            }
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
            queueRemoval(previousMayor, currentReward, loadIfNeeded = true)
            queueRemoval(previousMayor, tracked.toReward(), loadIfNeeded = true)
        }

        ops += applyExpectedToMayor(mayorUuid, settings, tracked)
            .thenApply { assignment ->
                if (assignment == null) return@thenApply false
                appliedAssignment = assignment
                true
            }

        Bukkit.getOnlinePlayers().forEach { p ->
            if (p.uniqueId != mayorUuid) {
                queueRemoval(p.uniqueId, currentReward, loadIfNeeded = false)
                queueRemoval(p.uniqueId, tracked.toReward(), loadIfNeeded = false)
            }
        }
        runAfterOps(ops, notifiers) {
            writeTrackedAssignment(appliedAssignment)
            afterSync()
        }
    }

    fun syncPlayer(uuid: UUID) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { syncPlayer(uuid) })
            return
        }

        val settings = plugin.settings.displayReward
        val tracked = trackedAssignment()
        val currentReward = currentConfiguredReward(settings)
        if (!settings.enabled) {
            runAfterOps(
                listOf(
                    removeRewardFromPlayer(uuid, tracked.toReward(), false, settings),
                    removeRewardFromPlayer(uuid, currentReward, false, settings)
                )
            ) {}
            return
        }
        if (!plugin.isReady() || !plugin.hasTermService()) {
            return
        }

        val mayorUuid = currentMayorContext().mayorUuid
        if (mayorUuid != null && mayorUuid == uuid) {
            var appliedAssignment: TrackedAssignment? = null
            val ops = listOf(
                applyExpectedToMayor(uuid, settings, tracked)
                    .thenApply { assignment ->
                        if (assignment == null) return@thenApply false
                        appliedAssignment = assignment
                        true
                    }
            )
            runAfterOps(ops) { writeTrackedAssignment(appliedAssignment) }
        } else {
            runAfterOps(
                listOf(
                    removeRewardFromPlayer(uuid, tracked.toReward(), loadIfNeeded = false, settings),
                    removeRewardFromPlayer(uuid, currentReward, loadIfNeeded = false, settings)
                )
            ) {}
        }
    }

    private fun applyExpectedToMayor(
        uuid: UUID,
        settings: DisplayRewardSettings,
        tracked: TrackedAssignment
    ): CompletableFuture<TrackedAssignment?> {
        return resolveSubject(uuid)
            .thenCompose { subject ->
                val mode = settings.modeFor(subject)
                val plan = DisplayRewardPlanner.forMayor(mode, settings, tracked.toReward())
                val ops = mutableListOf<CompletableFuture<Boolean>>()

                plan.removeRankGroups.forEach { group ->
                    ops += removeRankGroup(uuid, subject.name, group, loadIfNeeded = true)
                }
                plan.removeTagPermissions.forEach { permission ->
                    ops += removePermission(uuid, subject.name, permission, loadIfNeeded = true)
                }
                if (settings.tag.clearWhenRemoved) {
                    plan.clearTagIds.forEach { tagId ->
                        ops += clearDeluxeTag(uuid, subject.name, tagId, tracked.toReward())
                    }
                }

                plan.applyRankGroup?.let { group ->
                    ops += applyRankGroup(uuid, subject.name, group, settings.rank.autoCreateGroup)
                }
                plan.applyTagPermission?.let { permission ->
                    ops += applyPermission(uuid, subject.name, permission)
                }
                plan.applyTagId?.let { tagId ->
                    var tagOp = deluxeTags.ensureTagAsync(
                        settings.tag.copy(
                            display = plugin.settings.applyTitleTokens(settings.tag.display),
                            description = plugin.settings.applyTitleTokens(settings.tag.description)
                        )
                    ).thenApply { recordDeluxeTagsResult("prepare", uuid, tagId, it) }
                    if (settings.tag.selectWhenApplied) {
                        tagOp = tagOp.thenCompose { prepared ->
                            if (!prepared) {
                                completedFalse()
                            } else {
                                deluxeTags.selectTag(uuid, subject.name, tagId)
                                    .thenApply { recordDeluxeTagsResult("select", uuid, tagId, it) }
                            }
                        }
                    }
                    ops += tagOp
                }

                allOps(ops).thenApply { ok ->
                    if (!ok) {
                        null
                    } else {
                        TrackedAssignment(
                            uuid = uuid,
                            lastKnownName = subject.name,
                            rankGroup = plan.applyRankGroup,
                            tagPermission = plan.applyTagPermission,
                            tagId = plan.applyTagId,
                            mode = mode
                        )
                    }
                }
            }
    }

    private fun removeRewardFromPlayer(
        uuid: UUID,
        reward: TrackedDisplayReward,
        loadIfNeeded: Boolean,
        settings: DisplayRewardSettings
    ): CompletableFuture<Boolean> {
        val ops = mutableListOf<CompletableFuture<Boolean>>()
        reward.rankGroup?.let { ops += removeRankGroup(uuid, reward.lastKnownName, it, loadIfNeeded) }
        reward.tagPermission?.let { ops += removePermission(uuid, reward.lastKnownName, it, loadIfNeeded) }
        if (settings.tag.clearWhenRemoved) {
            reward.tagId?.let { ops += clearDeluxeTag(uuid, reward.lastKnownName, it, reward) }
        }
        return allOps(ops)
    }

    private fun runAfterOps(
        ops: List<CompletableFuture<Boolean>>,
        notifiers: List<CommandSender> = emptyList(),
        onSuccess: () -> Unit
    ) {
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
                    plugin.logger.warning("Display reward sync did not fully complete; tracked reward state unchanged.")
                    notifiers.forEach { plugin.messages.msg(it, "admin.settings.display_reward_sync_failed") }
                    if (notifiers.isEmpty()) {
                        notifyOperators("admin.settings.display_reward_sync_failed")
                    }
                    return@Runnable
                }
                onSuccess()
            })
        }
    }

    private fun notifyOperators(messageKey: String) {
        Bukkit.getOnlinePlayers()
            .filter { it.isOp || it.hasPermission(plugin.settings.displayReward.adminEditPermission) }
            .forEach { plugin.messages.msg(it, messageKey) }
    }

    private fun allOps(ops: List<CompletableFuture<Boolean>>): CompletableFuture<Boolean> {
        if (ops.isEmpty()) return completedTrue()
        return CompletableFuture.allOf(*ops.toTypedArray())
            .thenApply { ops.all { it.getNow(false) } }
            .exceptionally { false }
    }

    private fun clearDeluxeTag(
        uuid: UUID,
        storedName: String?,
        tagId: String,
        reward: TrackedDisplayReward
    ): CompletableFuture<Boolean> {
        return resolveCleanupName(uuid, storedName)
            .thenCompose { name -> deluxeTags.clearTag(uuid, name, tagId) }
            .thenApply { result -> recordDeluxeTagsResult("clear", uuid, tagId, result, reward) }
    }

    private fun resolveCleanupName(uuid: UUID, storedName: String?): CompletableFuture<String?> {
        storedName?.trim()?.takeIf { it.isNotBlank() }?.let {
            return CompletableFuture.completedFuture(it)
        }
        offlineName(uuid)?.let { return CompletableFuture.completedFuture(it) }

        val lp = luckPerms(warnIfMissing = false)
        if (lp != null) {
            return lp.userManager.lookupUsername(uuid)
                .thenApply { name ->
                    name?.trim()?.takeIf { it.isNotBlank() }
                        ?: Bukkit.getPlayer(uuid)?.name
                }
                .exceptionally { Bukkit.getPlayer(uuid)?.name }
        }

        return CompletableFuture.completedFuture(Bukkit.getPlayer(uuid)?.name)
    }

    private fun recordDeluxeTagsResult(
        action: String,
        uuid: UUID,
        tagId: String?,
        result: DeluxeTagsOperationResult,
        reward: TrackedDisplayReward = TrackedDisplayReward()
    ): Boolean {
        if (result.success && !result.deferred) {
            if (action == "clear" || action == "select") clearDeferredTagCleanup(uuid)
            if (plugin.hasDisplayRewardTags()) {
                plugin.displayRewardTags.clear(uuid)
            }
            clearDeluxeTagsFailure()
            return true
        }

        val message = result.message ?: "DeluxeTags action did not complete."
        if (result.deferred) {
            writeDeferredTagCleanup(uuid, reward.lastKnownName, tagId, reward)
            val warnKey = "$action:${uuid}:${tagId.orEmpty()}"
            if (warnedDeferredTagCleanup.add(warnKey)) {
                plugin.logger.warning(message)
            }
            return true
        }

        writeDeluxeTagsFailure(action, uuid, tagId, message, result.verified)
        return false
    }

    private fun writeDeluxeTagsFailure(action: String, uuid: UUID, tagId: String?, message: String, verified: Boolean) {
        val failureKey = "$action:${uuid}:${tagId.orEmpty()}:$message"
        val now = System.currentTimeMillis()
        val last = deluxeTagsWarningTimes[failureKey] ?: 0L
        if (now - last >= 60_000L) {
            plugin.logger.warning("Display reward DeluxeTags $action failed for $uuid: $message")
            deluxeTagsWarningTimes[failureKey] = now
        }
        plugin.config.set("admin.display_reward.last_deluxetags_failure.action", action)
        plugin.config.set("admin.display_reward.last_deluxetags_failure.player_uuid", uuid.toString())
        plugin.config.set("admin.display_reward.last_deluxetags_failure.tag_id", tagId)
        plugin.config.set("admin.display_reward.last_deluxetags_failure.message", message)
        plugin.config.set("admin.display_reward.last_deluxetags_failure.verified", verified)
        plugin.config.set("admin.display_reward.last_deluxetags_failure.at", Instant.now().toString())
        plugin.saveConfig()
    }

    private fun clearDeluxeTagsFailure() {
        if (!plugin.config.isConfigurationSection("admin.display_reward.last_deluxetags_failure")) return
        plugin.config.set("admin.display_reward.last_deluxetags_failure", null)
        plugin.saveConfig()
    }

    private fun writeDeferredTagCleanup(
        uuid: UUID,
        lastKnownName: String?,
        tagId: String?,
        reward: TrackedDisplayReward
    ) {
        val base = "admin.display_reward.deferred_tag_cleanup.${uuid}"
        plugin.config.set("$base.uuid", uuid.toString())
        plugin.config.set("$base.last_known_name", lastKnownName?.trim()?.takeIf { it.isNotBlank() })
        plugin.config.set("$base.tag_id", tagId)
        plugin.config.set("$base.mode", reward.mode?.name)
        plugin.config.set("$base.rank_group", reward.rankGroup)
        plugin.config.set("$base.reason", "Player name is needed before the tag can be cleared.")
        plugin.config.set("$base.updated_at", Instant.now().toString())
        plugin.saveConfig()
    }

    private fun clearDeferredTagCleanup(uuid: UUID) {
        val base = "admin.display_reward.deferred_tag_cleanup.${uuid}"
        if (!plugin.config.isConfigurationSection(base)) return
        plugin.config.set(base, null)
        plugin.saveConfig()
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

    private fun resolveSubject(uuid: UUID): CompletableFuture<DisplayRewardSubject> {
        val lp = luckPerms(warnIfMissing = false)
        if (lp == null) {
            return CompletableFuture.completedFuture(
                DisplayRewardSubject(uuid = uuid, name = offlineName(uuid), tracks = emptySet(), groups = emptySet())
            )
        }

        val knownName = Bukkit.getPlayer(uuid)?.name ?: offlineName(uuid)
        val loaded = if (knownName.isNullOrBlank()) {
            lp.userManager.loadUser(uuid)
        } else {
            lp.userManager.loadUser(uuid, knownName)
        }
        return loaded
            .thenApply { user -> subjectFromUser(uuid, user, lp) }
            .exceptionally {
                DisplayRewardSubject(uuid = uuid, name = knownName, tracks = emptySet(), groups = emptySet())
            }
    }

    private fun subjectFromUser(uuid: UUID, user: User, lp: LuckPerms): DisplayRewardSubject {
        val groups = linkedSetOf<String>()
        user.data()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .mapTo(groups) { it.groupName }
        user.transientData()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .mapTo(groups) { it.groupName }
        val normalizedGroups = groups.map { it.lowercase() }.toSet()
        val tracks = lp.trackManager.loadedTracks
            .filter { track ->
                track.groups.any { group -> normalizedGroups.contains(group.lowercase()) }
            }
            .mapTo(linkedSetOf()) { it.name }
        return DisplayRewardSubject(
            uuid = uuid,
            name = Bukkit.getPlayer(uuid)?.name ?: offlineName(uuid),
            tracks = tracks,
            groups = groups
        )
    }

    private fun currentConfiguredReward(settings: DisplayRewardSettings): TrackedDisplayReward {
        val group = settings.rank.luckPermsGroup.trim()
            .takeIf { settings.rank.enabled }
            ?.takeIf { DisplayRewardSettings.GROUP_NAME_REGEX.matches(it) }
        val tagId = settings.tag.deluxeTagId.trim()
            .takeIf { settings.tag.enabled }
            ?.takeIf { DisplayRewardTagId.isValid(it) }
        return TrackedDisplayReward(
            rankGroup = group,
            tagPermission = tagId?.let { settings.tag.permissionNode() },
            tagId = tagId
        )
    }

    private fun applyRankGroup(uuid: UUID, knownName: String?, group: String, autoCreate: Boolean): CompletableFuture<Boolean> {
        val lp = luckPerms(warnIfMissing = true) ?: return completedFalse()
        if (!ensureGroupExists(lp, group, autoCreate)) return completedFalse()

        return mutateLuckPermsUser(lp, uuid, knownName, "apply LuckPerms group '$group'") { user ->
            var changed = removeGroupNodes(user, group)
            val hasGroup = user.data()
                .toCollection()
                .filterIsInstance<InheritanceNode>()
                .any { it.groupName.equals(group, ignoreCase = true) }
            if (!hasGroup) {
                user.data().add(InheritanceNode.builder(group).build())
                changed = true
            }
            changed
        }
    }

    private fun removeRankGroup(uuid: UUID, knownName: String?, group: String?, loadIfNeeded: Boolean): CompletableFuture<Boolean> {
        if (group == null) return completedTrue()
        val lp = luckPerms(warnIfMissing = true) ?: return completedFalse()

        if (loadIfNeeded) {
            return mutateLuckPermsUser(lp, uuid, knownName, "remove LuckPerms group '$group'") { user ->
                removeGroupNodes(user, group)
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

    private fun removePermission(uuid: UUID, knownName: String?, permission: String?, loadIfNeeded: Boolean): CompletableFuture<Boolean> {
        if (permission == null) return completedTrue()
        val lp = luckPerms(warnIfMissing = false) ?: return completedFalse()

        if (loadIfNeeded) {
            return mutateLuckPermsUser(lp, uuid, knownName, "remove LuckPerms permission '$permission'") { user ->
                removePermissionNodes(user, permission)
            }
        }

        val loaded = lp.userManager.getUser(uuid) ?: return completedTrue()
        val changed = removePermissionNodes(loaded, permission)
        if (!changed) return completedTrue()

        return lp.userManager.saveUser(loaded)
            .thenApply { true }
            .exceptionally { err ->
                plugin.logger.severe("Failed to save LuckPerms user after removing '$permission' from $uuid: ${err.message}")
                false
            }
    }

    private fun applyPermission(uuid: UUID, knownName: String?, permission: String): CompletableFuture<Boolean> {
        val lp = luckPerms(warnIfMissing = false) ?: run {
            plugin.logger.warning("LuckPerms API provider is unavailable; tag permission '$permission' was not granted to $uuid.")
            return completedFalse()
        }

        return mutateLuckPermsUser(lp, uuid, knownName, "apply LuckPerms permission '$permission'") { user ->
            var changed = removePermissionNodes(user, permission)
            val hasPermission = user.data()
                .toCollection()
                .filterIsInstance<PermissionNode>()
                .any { it.permission.equals(permission, ignoreCase = true) }
            if (!hasPermission) {
                user.data().add(PermissionNode.builder(permission).build())
                changed = true
            }
            changed
        }
    }

    private fun mutateLuckPermsUser(
        lp: LuckPerms,
        uuid: UUID,
        knownName: String?,
        action: String,
        mutator: (User) -> Boolean
    ): CompletableFuture<Boolean> {
        val load = knownName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { lp.userManager.loadUser(uuid, it) }
            ?: lp.userManager.loadUser(uuid)
        return load.thenCompose { user ->
            val changed = mutator(user)
            if (!changed) {
                CompletableFuture.completedFuture(true)
            } else {
                lp.userManager.saveUser(user).thenApply { true }
            }
        }.exceptionally { err ->
            plugin.logger.severe("Failed to $action for $uuid: ${err.message}")
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

    private fun removePermissionNodes(user: User, permission: String): Boolean {
        var changed = false

        val persistent = user.data()
            .toCollection()
            .filterIsInstance<PermissionNode>()
            .filter { it.permission.equals(permission, ignoreCase = true) }
        if (persistent.isNotEmpty()) {
            persistent.forEach { user.data().remove(it) }
            changed = true
        }

        val transient = user.transientData()
            .toCollection()
            .filterIsInstance<PermissionNode>()
            .filter { it.permission.equals(permission, ignoreCase = true) }
        if (transient.isNotEmpty()) {
            transient.forEach { user.transientData().remove(it) }
            changed = true
        }

        return changed
    }

    private fun ensureGroupExists(lp: LuckPerms, group: String, autoCreate: Boolean): Boolean {
        if (lp.groupManager.getGroup(group) != null) {
            warnedMissingGroup = null
            return true
        }

        if (!autoCreate) {
            if (warnedMissingGroup != group) {
                plugin.logger.warning("LuckPerms group '$group' does not exist.")
                warnedMissingGroup = group
            }
            return false
        }

        if (warnedMissingGroup != group) {
            plugin.logger.warning(
                "LuckPerms group '$group' does not exist. Creating it now."
            )
            warnedMissingGroup = group
        }

        val key = group.lowercase()
        if (!pendingGroupCreates.add(key)) {
            return false
        }

        lp.groupManager.loadGroup(group)
            .thenCompose { loaded ->
                val existing = loaded.orElse(null)
                val groupFuture = if (existing != null) {
                    CompletableFuture.completedFuture(existing)
                } else {
                    lp.groupManager.createAndLoadGroup(group)
                }
                groupFuture.thenCompose { created -> lp.groupManager.saveGroup(created).thenApply { created } }
            }
            .whenComplete { _, err ->
                pendingGroupCreates.remove(key)
                if (err != null) {
                    plugin.logger.severe(
                        "Failed to create LuckPerms group '$group': ${err.message}"
                    )
                } else {
                    plugin.logger.info("Created LuckPerms group '$group'.")
                }

                if (!plugin.isEnabled) return@whenComplete
                plugin.server.scheduler.runTask(plugin, Runnable { syncAllOnline() })
            }
        return false
    }

    private fun clearAll(
        settings: DisplayRewardSettings,
        notifiers: List<CommandSender> = emptyList(),
        afterSync: () -> Unit = {}
    ) {
        val tracked = trackedAssignment()
        val currentReward = currentConfiguredReward(settings)

        val ops = mutableListOf<CompletableFuture<Boolean>>()
        if (tracked.uuid != null) {
            ops += removeRewardFromPlayer(tracked.uuid, tracked.toReward(), loadIfNeeded = true, settings)
            ops += removeRewardFromPlayer(tracked.uuid, currentReward, loadIfNeeded = true, settings)
        }

        Bukkit.getOnlinePlayers().forEach { player ->
            ops += removeRewardFromPlayer(player.uniqueId, tracked.toReward(), loadIfNeeded = false, settings)
            ops += removeRewardFromPlayer(player.uniqueId, currentReward, loadIfNeeded = false, settings)
        }
        runAfterOps(ops, notifiers) {
            writeTrackedAssignment(null)
            afterSync()
        }
    }

    private fun luckPerms(warnIfMissing: Boolean): LuckPerms? {
        val lpPlugin = plugin.server.pluginManager.getPlugin("LuckPerms")
        if (lpPlugin == null || !lpPlugin.isEnabled) {
            if (warnIfMissing && !warnedMissingLuckPerms) {
                plugin.logger.warning("LuckPerms was not found; display reward rank updates are inactive.")
                warnedMissingLuckPerms = true
            }
            return null
        }

        val provider = runCatching { LuckPermsProvider.get() }.getOrNull()
        if (provider == null) {
            if (warnIfMissing && !warnedMissingLuckPerms) {
                plugin.logger.warning("LuckPerms API provider is unavailable; display reward rank updates are inactive.")
                warnedMissingLuckPerms = true
            }
            return null
        }

        warnedMissingLuckPerms = false
        return provider
    }

    fun validRankGroupOrNull(settings: DisplayRewardSettings = plugin.settings.displayReward): String? {
        val group = settings.rank.luckPermsGroup.trim()
        if (!settings.rank.enabled || group.isBlank()) return null

        val valid = DisplayRewardSettings.GROUP_NAME_REGEX.matches(group)
        if (!valid) {
            if (warnedInvalidGroup != group) {
                plugin.logger.warning(
                    "Invalid display_reward rank group '$group'. Allowed: letters, numbers, _, -, ."
                )
                warnedInvalidGroup = group
            }
            return null
        }
        warnedInvalidGroup = null
        return group
    }

    fun validTagIdOrNull(settings: DisplayRewardSettings = plugin.settings.displayReward): String? {
        val tagId = settings.tag.deluxeTagId.trim()
        if (!settings.tag.enabled || tagId.isBlank()) return null
        if (!DisplayRewardTagId.isValid(tagId)) {
            if (warnedInvalidTagId != tagId) {
                plugin.logger.warning("Invalid DeluxeTags tag id '$tagId'. Allowed: letters, numbers, _, -.")
                warnedInvalidTagId = tagId
            }
            return null
        }
        warnedInvalidTagId = null
        return tagId
    }

    private fun trackedAssignment(): TrackedAssignment {
        val uuid = plugin.config.getString(TRACKED_UUID_PATH)
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: plugin.config.getString(LEGACY_TRACKED_UUID_PATH)
                ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        val rankGroup = plugin.config.getString(TRACKED_RANK_GROUP_PATH)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: plugin.config.getString(LEGACY_TRACKED_GROUP_PATH)?.trim()?.takeIf { it.isNotBlank() }
        val lastKnownName = plugin.config.getString(TRACKED_LAST_KNOWN_NAME_PATH)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val tagPermission = plugin.config.getString(TRACKED_TAG_PERMISSION_PATH)?.trim()?.takeIf { it.isNotBlank() }
        val tagId = plugin.config.getString(TRACKED_TAG_ID_PATH)?.trim()?.takeIf { it.isNotBlank() }
        val mode = DisplayRewardMode.parse(plugin.config.getString(TRACKED_MODE_PATH))
        return TrackedAssignment(uuid, lastKnownName, rankGroup, tagPermission, tagId, mode)
    }

    private fun writeTrackedAssignment(assignment: TrackedAssignment?) {
        if (suppressTrackedConfigSave) return
        val oldUuid = plugin.config.getString(TRACKED_UUID_PATH)
        val oldLastKnownName = plugin.config.getString(TRACKED_LAST_KNOWN_NAME_PATH)
        val oldRankGroup = plugin.config.getString(TRACKED_RANK_GROUP_PATH)
        val oldTagPermission = plugin.config.getString(TRACKED_TAG_PERMISSION_PATH)
        val oldTagId = plugin.config.getString(TRACKED_TAG_ID_PATH)
        val oldMode = plugin.config.getString(TRACKED_MODE_PATH)

        val newUuid = assignment?.uuid?.toString()
        val newLastKnownName = assignment?.lastKnownName?.trim()?.takeIf { it.isNotBlank() }
        val newRankGroup = assignment?.rankGroup?.trim()?.takeIf { it.isNotBlank() }
        val newTagPermission = assignment?.tagPermission?.trim()?.takeIf { it.isNotBlank() }
        val newTagId = assignment?.tagId?.trim()?.takeIf { it.isNotBlank() }
        val newMode = assignment?.mode?.name

        if (
            oldUuid == newUuid &&
            oldLastKnownName == newLastKnownName &&
            oldRankGroup == newRankGroup &&
            oldTagPermission == newTagPermission &&
            oldTagId == newTagId &&
            oldMode == newMode
        ) {
            return
        }

        suppressTrackedConfigSave = true
        try {
            plugin.config.set(TRACKED_UUID_PATH, newUuid)
            plugin.config.set(TRACKED_LAST_KNOWN_NAME_PATH, newLastKnownName)
            plugin.config.set(TRACKED_RANK_GROUP_PATH, newRankGroup)
            plugin.config.set(TRACKED_TAG_PERMISSION_PATH, newTagPermission)
            plugin.config.set(TRACKED_TAG_ID_PATH, newTagId)
            plugin.config.set(TRACKED_MODE_PATH, newMode)
            plugin.config.set(LEGACY_TRACKED_UUID_PATH, if (newRankGroup == null) null else newUuid)
            plugin.config.set(LEGACY_TRACKED_GROUP_PATH, newRankGroup)
            plugin.saveConfig()
        } finally {
            suppressTrackedConfigSave = false
        }
    }

    private fun offlineName(uuid: UUID): String? =
        runCatching { Bukkit.getOfflinePlayer(uuid).name }.getOrNull()

    private fun TrackedDisplayReward.isEmpty(): Boolean =
        rankGroup == null && tagPermission == null && tagId == null

    private fun TrackedAssignment.toReward(): TrackedDisplayReward =
        TrackedDisplayReward(
            uuid = uuid,
            lastKnownName = lastKnownName,
            rankGroup = rankGroup,
            tagPermission = tagPermission,
            tagId = tagId,
            mode = mode
        )

    private companion object {
        const val LEGACY_TRACKED_UUID_PATH = "admin.mayor_group.tracked_uuid"
        const val LEGACY_TRACKED_GROUP_PATH = "admin.mayor_group.tracked_group"
        const val TRACKED_UUID_PATH = "admin.display_reward.tracked_uuid"
        const val TRACKED_LAST_KNOWN_NAME_PATH = "admin.display_reward.tracked_last_known_name"
        const val TRACKED_RANK_GROUP_PATH = "admin.display_reward.tracked_rank_group"
        const val TRACKED_TAG_PERMISSION_PATH = "admin.display_reward.tracked_tag_permission"
        const val TRACKED_TAG_ID_PATH = "admin.display_reward.tracked_tag_id"
        const val TRACKED_MODE_PATH = "admin.display_reward.tracked_mode"

        fun completedTrue(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
        fun completedFalse(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
    }

    private data class TrackedAssignment(
        val uuid: UUID?,
        val lastKnownName: String?,
        val rankGroup: String?,
        val tagPermission: String?,
        val tagId: String?,
        val mode: DisplayRewardMode?
    )

    private data class MayorSyncContext(val mayorUuid: UUID?, val previousMayorCandidates: Set<UUID>)
}
