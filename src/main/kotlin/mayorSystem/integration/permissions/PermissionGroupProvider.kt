package mayorSystem.integration.permissions

import java.util.UUID
import java.util.concurrent.CompletableFuture

interface PermissionGroupProvider {
    val available: Boolean

    fun refresh()
    fun groupExists(group: String): Boolean
    fun trackExists(track: String): Boolean
    fun groupNames(): List<String>
    fun trackNames(): List<String>
    fun grantGroup(uuid: UUID, lastKnownName: String?, group: String): CompletableFuture<PermissionMutationResult>
    fun removeGroup(uuid: UUID, lastKnownName: String?, group: String): CompletableFuture<PermissionMutationResult>
    fun grantPermission(uuid: UUID, lastKnownName: String?, permission: String): CompletableFuture<PermissionMutationResult>
    fun removePermission(uuid: UUID, lastKnownName: String?, permission: String): CompletableFuture<PermissionMutationResult>
}

data class PermissionMutationResult(
    val success: Boolean,
    val verified: Boolean,
    val message: String? = null
)

object NoopPermissionGroupProvider : PermissionGroupProvider {
    override val available: Boolean = false

    override fun refresh() = Unit
    override fun groupExists(group: String): Boolean = false
    override fun trackExists(track: String): Boolean = false
    override fun groupNames(): List<String> = emptyList()
    override fun trackNames(): List<String> = emptyList()
    override fun grantGroup(uuid: UUID, lastKnownName: String?, group: String): CompletableFuture<PermissionMutationResult> =
        CompletableFuture.completedFuture(PermissionMutationResult(false, false, "LuckPerms is unavailable."))

    override fun removeGroup(uuid: UUID, lastKnownName: String?, group: String): CompletableFuture<PermissionMutationResult> =
        CompletableFuture.completedFuture(PermissionMutationResult(true, false, "LuckPerms is unavailable; nothing was removed."))

    override fun grantPermission(uuid: UUID, lastKnownName: String?, permission: String): CompletableFuture<PermissionMutationResult> =
        CompletableFuture.completedFuture(PermissionMutationResult(false, false, "LuckPerms is unavailable."))

    override fun removePermission(uuid: UUID, lastKnownName: String?, permission: String): CompletableFuture<PermissionMutationResult> =
        CompletableFuture.completedFuture(PermissionMutationResult(true, false, "LuckPerms is unavailable; nothing was removed."))
}

