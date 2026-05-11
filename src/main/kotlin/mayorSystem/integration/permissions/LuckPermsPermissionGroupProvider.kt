package mayorSystem.integration.permissions

import mayorSystem.MayorPlugin
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.PermissionNode
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckPermsPermissionGroupProvider(private val plugin: MayorPlugin) : PermissionGroupProvider {
    override val available: Boolean
        get() = luckPermsOrNull() != null

    override fun refresh() = Unit

    override fun groupExists(group: String): Boolean =
        luckPermsOrNull()?.groupManager?.getGroup(group) != null

    override fun trackExists(track: String): Boolean =
        luckPermsOrNull()?.trackManager?.getTrack(track) != null

    override fun groupNames(): List<String> =
        luckPermsOrNull()?.groupManager?.loadedGroups?.map { it.name } ?: emptyList()

    override fun trackNames(): List<String> =
        luckPermsOrNull()?.trackManager?.loadedTracks?.map { it.name } ?: emptyList()

    override fun grantGroup(uuid: UUID, lastKnownName: String?, group: String): CompletableFuture<PermissionMutationResult> =
        mutateUserNode(uuid, InheritanceNode.builder(group).build(), add = true)

    override fun removeGroup(uuid: UUID, lastKnownName: String?, group: String): CompletableFuture<PermissionMutationResult> =
        mutateUserNode(uuid, InheritanceNode.builder(group).build(), add = false)

    override fun grantPermission(uuid: UUID, lastKnownName: String?, permission: String): CompletableFuture<PermissionMutationResult> =
        mutateUserNode(uuid, PermissionNode.builder(permission).build(), add = true)

    override fun removePermission(uuid: UUID, lastKnownName: String?, permission: String): CompletableFuture<PermissionMutationResult> =
        mutateUserNode(uuid, PermissionNode.builder(permission).build(), add = false)

    private fun mutateUserNode(uuid: UUID, node: Node, add: Boolean): CompletableFuture<PermissionMutationResult> {
        val lp = luckPermsOrNull()
            ?: return CompletableFuture.completedFuture(PermissionMutationResult(false, false, "LuckPerms is unavailable."))

        return lp.userManager.loadUser(uuid)
            .thenCompose { user ->
                if (add) {
                    user.data().add(node)
                } else {
                    user.data().remove(node)
                }
                lp.userManager.saveUser(user).thenApply {
                    PermissionMutationResult(success = true, verified = false)
                }
            }
            .exceptionally { ex ->
                PermissionMutationResult(success = false, verified = false, message = ex.message)
            }
    }

    private fun luckPermsOrNull(): LuckPerms? {
        val lpPlugin = plugin.server.pluginManager.getPlugin("LuckPerms")
        if (lpPlugin == null || !lpPlugin.isEnabled) return null
        return runCatching { LuckPermsProvider.get() }.getOrNull()
    }
}
