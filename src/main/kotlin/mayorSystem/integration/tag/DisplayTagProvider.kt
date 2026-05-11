package mayorSystem.integration.tag

import java.util.UUID
import java.util.concurrent.CompletableFuture

data class DisplayTagSnapshot(
    val id: String,
    val display: String,
    val description: String,
    val order: Int,
    val permission: String
)

data class DisplayTagCapabilities(
    val available: Boolean,
    val canCreateTags: Boolean,
    val canUpdateTags: Boolean,
    val canSelectTags: Boolean,
    val canClearTags: Boolean
)

data class DisplayTagMutationResult(
    val success: Boolean,
    val verified: Boolean,
    val deferred: Boolean = false,
    val message: String? = null
)

interface DisplayTagProvider {
    val capabilities: DisplayTagCapabilities

    fun loadedTagIds(): List<String>
    fun tagSnapshot(tagId: String): DisplayTagSnapshot?
    fun ensureTag(tagId: String, display: String, description: String, order: Int): CompletableFuture<DisplayTagMutationResult>
    fun selectTag(uuid: UUID, playerName: String?, tagId: String): CompletableFuture<DisplayTagMutationResult>
    fun clearTag(uuid: UUID, playerName: String?, tagId: String?): CompletableFuture<DisplayTagMutationResult>
}

object NoopDisplayTagProvider : DisplayTagProvider {
    override val capabilities: DisplayTagCapabilities =
        DisplayTagCapabilities(
            available = false,
            canCreateTags = false,
            canUpdateTags = false,
            canSelectTags = false,
            canClearTags = false
        )

    override fun loadedTagIds(): List<String> = emptyList()

    override fun tagSnapshot(tagId: String): DisplayTagSnapshot? = null

    override fun ensureTag(
        tagId: String,
        display: String,
        description: String,
        order: Int
    ): CompletableFuture<DisplayTagMutationResult> =
        CompletableFuture.completedFuture(
            DisplayTagMutationResult(false, false, message = "Display tag provider is unavailable.")
        )

    override fun selectTag(uuid: UUID, playerName: String?, tagId: String): CompletableFuture<DisplayTagMutationResult> =
        CompletableFuture.completedFuture(
            DisplayTagMutationResult(false, false, message = "Display tag provider is unavailable.")
        )

    override fun clearTag(uuid: UUID, playerName: String?, tagId: String?): CompletableFuture<DisplayTagMutationResult> =
        CompletableFuture.completedFuture(
            DisplayTagMutationResult(true, false, deferred = true, message = "Display tag provider is unavailable; nothing was cleared.")
        )
}

