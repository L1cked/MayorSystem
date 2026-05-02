package mayorSystem.rewards

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class DeluxeTagsCapabilities(
    val present: Boolean,
    val apiAvailable: Boolean,
    val commandAvailable: Boolean,
    val canCheckTags: Boolean,
    val canSelectTags: Boolean,
    val canClearTags: Boolean,
    val canCreateTags: Boolean,
    val perTagIconSupported: Boolean = false
)

data class DeluxeTagsTagSnapshot(
    val id: String,
    val display: String,
    val description: String,
    val order: Int,
    val permission: String
)

data class DeluxeTagsOperationResult(
    val success: Boolean,
    val verified: Boolean,
    val message: String? = null,
    val deferred: Boolean = false
) {
    companion object {
        val OK = DeluxeTagsOperationResult(success = true, verified = true)
        fun failed(message: String) = DeluxeTagsOperationResult(success = false, verified = false, message = message)
        fun deferred(message: String) = DeluxeTagsOperationResult(success = true, verified = false, message = message, deferred = true)
    }
}

class DeluxeTagsIntegration(private val plugin: MayorPlugin) {
    private val attemptedCreate = linkedSetOf<String>()

    fun capabilities(): DeluxeTagsCapabilities {
        val deluxe = deluxeTagsPlugin()
        val api = api(deluxe)
        val commandAvailable = plugin.server.getPluginCommand("tags") != null ||
            plugin.server.getPluginCommand("deluxetags:tags") != null
        return DeluxeTagsCapabilities(
            present = deluxe != null,
            apiAvailable = api != null,
            commandAvailable = commandAvailable,
            canCheckTags = api?.getLoadedTag != null || api?.getLoadedTags != null,
            canSelectTags = api?.saveTagIdentifier != null && api.getLoadedTag != null,
            canClearTags = api?.removeSavedTag != null &&
                (api.removePlayer != null || api.getDummy != null),
            canCreateTags = api?.constructor != null &&
                api.load != null &&
                api.getCfg != null &&
                (api.saveTag != null || api.saveTagFields != null)
        )
    }

    fun hasTag(tagId: String): Boolean {
        val api = api(deluxeTagsPlugin()) ?: return false
        return tagById(api, tagId) != null
    }

    fun tagSnapshot(tagId: String): DeluxeTagsTagSnapshot? {
        val api = api(deluxeTagsPlugin()) ?: return null
        val tag = tagById(api, tagId) ?: return null
        return snapshot(api, tag)
    }

    fun orderConflict(tagId: String, order: Int): List<String> {
        val api = api(deluxeTagsPlugin()) ?: return emptyList()
        return loadedTags(api)
            .mapNotNull { tag -> snapshot(api, tag) }
            .filter { it.order == order && !it.id.equals(tagId, ignoreCase = true) }
            .map { it.id }
    }

    fun loadedTagIds(): List<String> {
        val api = api(deluxeTagsPlugin()) ?: return emptyList()
        return loadedTags(api)
            .mapNotNull { tag -> invoke(api.getIdentifier, tag) as? String }
            .sortedBy { it.lowercase() }
    }

    fun activeTagId(uuid: UUID): String? {
        val deluxe = deluxeTagsPlugin() ?: return null
        val api = api(deluxe) ?: return null
        val uuidString = uuid.toString()
        val active = invoke(api.getPlayerTagIdentifierString, null, uuidString) as? String
        if (!active.isNullOrBlank()) return active
        return invoke(api.getSavedTagIdentifier, deluxe, uuidString) as? String
    }

    fun ensureTagAsync(settings: TagRewardSettings): CompletableFuture<DeluxeTagsOperationResult> =
        runOnMain { ensureTag(settings) }

    fun ensureTag(settings: TagRewardSettings): DeluxeTagsOperationResult {
        val tagId = settings.deluxeTagId.trim()
        if (!settings.enabled) return DeluxeTagsOperationResult.OK
        if (!DisplayRewardTagId.isValid(tagId)) {
            return DeluxeTagsOperationResult.failed("Tag '$tagId' is not a valid DeluxeTags identifier.")
        }

        val deluxe = deluxeTagsPlugin()
            ?: return DeluxeTagsOperationResult.failed("DeluxeTags is not enabled.")
        val api = api(deluxe)
            ?: return DeluxeTagsOperationResult.failed(
                "DeluxeTags is enabled, but MayorSystem cannot manage tags for this version. " +
                    manualTagSetup(settings)
            )

        val display = settings.display.trim().takeIf { it.isNotBlank() } ?: TagRewardSettings.DEFAULT_TAG_DISPLAY
        val description = settings.description.trim().takeIf { it.isNotBlank() } ?: TagRewardSettings.DEFAULT_TAG_DESCRIPTION
        val permission = settings.permissionNode()
        val existing = tagById(api, tagId)
        if (existing != null) {
            return updateExistingTag(api, existing, settings, display, description, permission)
        }

        if (!settings.autoCreateIfSupported) {
            return DeluxeTagsOperationResult.failed(manualTagSetup(settings))
        }

        val key = tagId.lowercase()
        if (!attemptedCreate.add(key) && tagById(api, tagId) == null) {
            return DeluxeTagsOperationResult.failed(manualTagSetup(settings))
        }

        return createTag(api, settings, display, description, permission)
    }

    fun selectTag(uuid: UUID, playerName: String?, tagId: String): CompletableFuture<DeluxeTagsOperationResult> =
        runOnMain {
            val deluxe = deluxeTagsPlugin()
                ?: return@runOnMain DeluxeTagsOperationResult.failed("DeluxeTags is not enabled.")
            val api = api(deluxe)
                ?: return@runOnMain DeluxeTagsOperationResult.failed(
                    "DeluxeTags cannot be updated automatically on this server. Select tag '$tagId' for ${playerLabel(uuid, playerName)} in DeluxeTags."
                )
            val tag = tagById(api, tagId)
                ?: return@runOnMain DeluxeTagsOperationResult.failed("Tag '$tagId' is not ready in DeluxeTags. Create or sync the tag, then refresh Display Reward.")

            val uuidString = uuid.toString()
            val online = Bukkit.getPlayer(uuid)
            val saved = invokeSuccess(api.saveTagIdentifier, deluxe, uuidString, tagId)
            val selected = when {
                online != null && api.setPlayerTagPlayer != null -> invoke(api.setPlayerTagPlayer, tag, online) as? Boolean ?: true
                api.setPlayerTagString != null -> invoke(api.setPlayerTagString, tag, uuidString) as? Boolean ?: true
                else -> true
            }

            val active = activeTagId(uuid)
            val verified = active.equals(tagId, ignoreCase = true)
            if (!verified && (!saved || !selected)) {
                return@runOnMain DeluxeTagsOperationResult.failed("Could not select tag '$tagId' for ${playerLabel(uuid, playerName)}.")
            }
            DeluxeTagsOperationResult(
                success = true,
                verified = verified,
                message = if (verified) null else "Tag '$tagId' was selected, but DeluxeTags did not confirm it yet."
            )
        }

    fun clearTag(uuid: UUID, playerName: String?, tagId: String?): CompletableFuture<DeluxeTagsOperationResult> =
        runOnMain {
            val deluxe = deluxeTagsPlugin() ?: return@runOnMain DeluxeTagsOperationResult.OK
            val api = api(deluxe)
                ?: return@runOnMain DeluxeTagsOperationResult.deferred(
                    "Clear tag${tagId?.let { " '$it'" } ?: ""} for ${playerLabel(uuid, playerName)} in DeluxeTags, then refresh Display Reward."
                )
            val uuidString = uuid.toString()
            val online = Bukkit.getPlayer(uuid)

            val savedRemoved = invokeSuccess(api.removeSavedTag, deluxe, uuidString)
            if (online != null) {
                val dummy = invoke(api.getDummy, deluxe)
                if (dummy != null && api.setPlayerTagPlayer != null) {
                    invoke(api.setPlayerTagPlayer, dummy, online)
                }
            }
            if (api.removePlayer != null) {
                invoke(api.removePlayer, null, uuidString)
            }

            val active = activeTagId(uuid)
            val cleared = active.isNullOrBlank() || (tagId != null && !active.equals(tagId, ignoreCase = true))
            if (savedRemoved || cleared) {
                DeluxeTagsOperationResult(success = true, verified = cleared)
            } else {
                DeluxeTagsOperationResult.failed("Could not clear the DeluxeTags tag for ${playerLabel(uuid, playerName)}.")
            }
        }

    private fun updateExistingTag(
        api: DeluxeTagsApi,
        tag: Any,
        settings: TagRewardSettings,
        display: String,
        description: String,
        permission: String
    ): DeluxeTagsOperationResult {
        val tagId = settings.deluxeTagId.trim()
        val current = snapshot(api, tag) ?: return DeluxeTagsOperationResult.failed("Tag '$tagId' could not be read from DeluxeTags.")
        val orderOwners = orderConflict(tagId, settings.order)
        val order = if (orderOwners.isEmpty()) settings.order else current.order
        val changed = current.display != display ||
            current.description != description ||
            current.order != order ||
            !current.permission.equals(permission, ignoreCase = true)

        if (!changed) return DeluxeTagsOperationResult.OK

        invoke(api.setDisplayTag, tag, display)
        invoke(api.setDescription, tag, description)
        invoke(api.setPriority, tag, order)
        invoke(api.setPermission, tag, permission)
        if (!saveTag(api, tag, order, tagId, display, description, permission)) {
            return DeluxeTagsOperationResult.failed("Tag '$tagId' was updated in memory, but DeluxeTags did not save it.")
        }

        val updated = tagSnapshot(tagId)
        val verified = updated != null &&
            updated.display == display &&
            updated.description == description &&
            updated.permission.equals(permission, ignoreCase = true)
        return DeluxeTagsOperationResult(
            success = true,
            verified = verified,
            message = if (verified) null else "Tag '$tagId' was updated, but DeluxeTags did not confirm every field yet."
        )
    }

    private fun createTag(
        api: DeluxeTagsApi,
        settings: TagRewardSettings,
        display: String,
        description: String,
        permission: String
    ): DeluxeTagsOperationResult {
        val tagId = settings.deluxeTagId.trim()
        val order = availableOrder(api, settings.order, tagId)
        if (order != settings.order) {
            val owners = orderConflict(tagId, settings.order).joinToString(", ")
            plugin.logger.warning(
                "DeluxeTags order ${settings.order} is already used by $owners; creating '$tagId' at order $order."
            )
        }

        val tag = runCatching {
            api.constructor!!.newInstance(order, tagId, display, description)
        }.getOrElse { err ->
            return DeluxeTagsOperationResult.failed("Could not create DeluxeTags tag '$tagId': ${err.message}")
        }
        invoke(api.setPermission, tag, permission)
        if (!invokeSuccess(api.load, tag)) {
            return DeluxeTagsOperationResult.failed("Could not load DeluxeTags tag '$tagId'.")
        }
        if (!saveTag(api, tag, order, tagId, display, description, permission)) {
            return DeluxeTagsOperationResult.failed("Could not save DeluxeTags tag '$tagId'.")
        }
        val loaded = tagById(api, tagId) != null
        return if (loaded) {
            DeluxeTagsOperationResult.OK
        } else {
            DeluxeTagsOperationResult.failed("Tag '$tagId' was saved, but DeluxeTags did not load it.")
        }
    }

    private fun availableOrder(api: DeluxeTagsApi, requested: Int, tagId: String): Int {
        val used = loadedTags(api)
            .mapNotNull { snapshot(api, it) }
            .filterNot { it.id.equals(tagId, ignoreCase = true) }
            .mapTo(mutableSetOf()) { it.order }
        if (requested !in used) return requested
        var candidate = requested + 1
        while (candidate in used) candidate++
        return candidate.coerceAtMost(100000)
    }

    private fun saveTag(
        api: DeluxeTagsApi,
        tag: Any,
        order: Int,
        tagId: String,
        display: String,
        description: String,
        permission: String
    ): Boolean {
        val deluxe = deluxeTagsPlugin() ?: return false
        val cfg = invoke(api.getCfg, deluxe) ?: return false
        return when {
            api.saveTagFields != null -> invokeSuccess(api.saveTagFields, cfg, order, tagId, display, description, permission)
            api.saveTag != null -> invokeSuccess(api.saveTag, cfg, tag)
            else -> false
        }
    }

    private fun snapshot(api: DeluxeTagsApi, tag: Any): DeluxeTagsTagSnapshot? {
        val id = invoke(api.getIdentifier, tag) as? String ?: return null
        val display = invoke(api.getDisplayTag, tag) as? String ?: ""
        val description = invoke(api.getDescription, tag) as? String ?: ""
        val order = invoke(api.getPriority, tag) as? Int ?: 0
        val permission = invoke(api.getPermission, tag) as? String ?: "DeluxeTags.Tag.$id"
        return DeluxeTagsTagSnapshot(id, display, description, order, permission)
    }

    private fun loadedTags(api: DeluxeTagsApi): List<Any> {
        val loaded = invoke(api.getLoadedTags, null) as? Collection<*> ?: return emptyList()
        return loaded.filterNotNull()
    }

    private fun tagById(api: DeluxeTagsApi, tagId: String): Any? =
        invoke(api.getLoadedTag, null, tagId)
            ?: loadedTags(api).firstOrNull { tag ->
                (invoke(api.getIdentifier, tag) as? String).equals(tagId, ignoreCase = true)
            }

    private fun manualTagSetup(settings: TagRewardSettings): String =
        "Create tag '${settings.deluxeTagId}' with display '${settings.display}', description '${settings.description}', order ${settings.order}, and permission '${settings.permissionNode()}'."

    private fun playerLabel(uuid: UUID, fallback: String?): String {
        val online = Bukkit.getPlayer(uuid)?.name?.trim()?.takeIf { it.isNotBlank() }
        val name = online ?: fallback?.trim()?.takeIf { it.isNotBlank() }
        return name ?: "Unknown player"
    }

    private fun deluxeTagsPlugin(): org.bukkit.plugin.Plugin? =
        plugin.server.pluginManager.getPlugin("DeluxeTags")?.takeIf { it.isEnabled }

    private fun api(deluxe: org.bukkit.plugin.Plugin?): DeluxeTagsApi? {
        if (deluxe == null) return null
        return runCatching {
            val pluginClass = deluxe.javaClass
            val tagClass = Class.forName("me.clip.deluxetags.tags.DeluxeTag")
            val tagConfigClass = Class.forName("me.clip.deluxetags.config.TagConfig")
            DeluxeTagsApi(
                constructor = runCatching {
                    tagClass.getConstructor(
                        Integer.TYPE,
                        String::class.java,
                        String::class.java,
                        String::class.java
                    )
                }.getOrNull(),
                getCfg = pluginClass.methodOrNull("getCfg"),
                getDummy = pluginClass.methodOrNull("getDummy"),
                getSavedTagIdentifier = pluginClass.methodOrNull("getSavedTagIdentifier", String::class.java),
                saveTagIdentifier = pluginClass.methodOrNull("saveTagIdentifier", String::class.java, String::class.java),
                removeSavedTag = pluginClass.methodOrNull("removeSavedTag", String::class.java),
                getLoadedTags = tagClass.methodOrNull("getLoadedTags"),
                getLoadedTag = tagClass.methodOrNull("getLoadedTag", String::class.java),
                getPlayerTagIdentifierString = tagClass.methodOrNull("getPlayerTagIdentifier", String::class.java),
                setPlayerTagPlayer = tagClass.methodOrNull("setPlayerTag", Player::class.java),
                setPlayerTagString = tagClass.methodOrNull("setPlayerTag", String::class.java),
                removePlayer = tagClass.methodOrNull("removePlayer", String::class.java),
                load = tagClass.methodOrNull("load"),
                getIdentifier = tagClass.methodOrNull("getIdentifier"),
                getDisplayTag = tagClass.methodOrNull("getDisplayTag"),
                getDescription = tagClass.methodOrNull("getDescription"),
                getPriority = tagClass.methodOrNull("getPriority"),
                getPermission = tagClass.methodOrNull("getPermission"),
                setDisplayTag = tagClass.methodOrNull("setDisplayTag", String::class.java),
                setDescription = tagClass.methodOrNull("setDescription", String::class.java),
                setPriority = tagClass.methodOrNull("setPriority", Integer.TYPE),
                setPermission = tagClass.methodOrNull("setPermission", String::class.java),
                saveTag = tagConfigClass.methodOrNull("saveTag", tagClass),
                saveTagFields = tagConfigClass.methodOrNull(
                    "saveTag",
                    Integer.TYPE,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java
                )
            ).takeIf { it.getLoadedTag != null && it.getIdentifier != null }
        }.getOrNull()
    }

    private fun Class<*>.methodOrNull(name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { getMethod(name, *parameterTypes) }.getOrNull()

    private fun invoke(method: Method?, target: Any?, vararg args: Any?): Any? {
        if (method == null) return null
        return runCatching { method.invoke(target, *args) }.getOrElse { err ->
            val cause = (err as? java.lang.reflect.InvocationTargetException)?.targetException ?: err
            plugin.logger.warning("DeluxeTags API call ${method.name} failed: ${cause.message}")
            null
        }
    }

    private fun invokeSuccess(method: Method?, target: Any?, vararg args: Any?): Boolean {
        if (method == null) return false
        return runCatching {
            method.invoke(target, *args)
            true
        }.getOrElse { err ->
            val cause = (err as? java.lang.reflect.InvocationTargetException)?.targetException ?: err
            plugin.logger.warning("DeluxeTags API call ${method.name} failed: ${cause.message}")
            false
        }
    }

    private fun runOnMain(block: () -> DeluxeTagsOperationResult): CompletableFuture<DeluxeTagsOperationResult> {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(block())
        }

        val future = CompletableFuture<DeluxeTagsOperationResult>()
        plugin.server.scheduler.runTask(plugin, Runnable {
            future.complete(runCatching { block() }.getOrElse {
                plugin.logger.warning("DeluxeTags operation failed: ${it.message}")
                DeluxeTagsOperationResult.failed(it.message ?: "DeluxeTags operation failed.")
            })
        })
        return future
    }

    private data class DeluxeTagsApi(
        val constructor: Constructor<*>?,
        val getCfg: Method?,
        val getDummy: Method?,
        val getSavedTagIdentifier: Method?,
        val saveTagIdentifier: Method?,
        val removeSavedTag: Method?,
        val getLoadedTags: Method?,
        val getLoadedTag: Method?,
        val getPlayerTagIdentifierString: Method?,
        val setPlayerTagPlayer: Method?,
        val setPlayerTagString: Method?,
        val removePlayer: Method?,
        val load: Method?,
        val getIdentifier: Method?,
        val getDisplayTag: Method?,
        val getDescription: Method?,
        val getPriority: Method?,
        val getPermission: Method?,
        val setDisplayTag: Method?,
        val setDescription: Method?,
        val setPriority: Method?,
        val setPermission: Method?,
        val saveTag: Method?,
        val saveTagFields: Method?
    )
}
