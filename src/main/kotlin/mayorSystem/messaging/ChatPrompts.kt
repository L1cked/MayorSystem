@file:Suppress("DEPRECATION")

package mayorSystem.messaging

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ChatPrompts is now ONLY for player-side flows (no admin editing here).
 *
 * Current flows:
 * - Custom perk request (player types title, then description)
 *
 * Admin flows removed:
 * - Settings editing
 * - Perk approval command entry
 * - Anything else admin-related
 */
class ChatPrompts(private val plugin: MayorPlugin) : Listener {
    private sealed interface Flow {
        data class CustomReq(
            val term: Int,
            val step: Int,
            val title: String? = null,
            val createdAtMs: Long = System.currentTimeMillis(),
            val lastActivityMs: Long = System.currentTimeMillis()
        ) : Flow

        data class BioEdit(
            val term: Int,
            val createdAtMs: Long = System.currentTimeMillis(),
            val lastActivityMs: Long = System.currentTimeMillis()
        ) : Flow
    }

    private val flows = ConcurrentHashMap<UUID, Flow>()

    private data class CancelStreak(var count: Int, var lastCancelMs: Long)
    private val cancelStreaks = ConcurrentHashMap<UUID, CancelStreak>()

    fun beginCustomPerkRequestFlow(player: Player, term: Int) {
        if (blockIfActionsPaused(player)) return
        if (!plugin.termService.isElectionOpen(java.time.Instant.now(), term)) {
            plugin.messages.msg(player, "public.apply_closed")
            return
        }
        if (!isActiveCandidateForTerm(player, term)) {
            plugin.messages.msg(player, "public.apply_first_candidate")
            return
        }
        if (plugin.settings.customRequestCondition == mayorSystem.config.CustomRequestCondition.DISABLED) {
            plugin.messages.msg(player, "public.custom_requests_closed")
            return
        }
        flows[player.uniqueId] = Flow.CustomReq(term, step = 0)
        val maxTitle = plugin.settings.chatPromptMaxTitleChars
        plugin.messages.msg(player, "prompts.custom.title_prompt", mapOf("max" to maxTitle.toString()))
    }

    fun beginBioEditFlow(player: Player, term: Int) {
        if (blockIfActionsPaused(player)) return
        flows[player.uniqueId] = Flow.BioEdit(term)
        val maxBio = plugin.settings.chatPromptMaxBioChars
        plugin.messages.msg(player, "prompts.bio.start", mapOf("max" to maxBio.toString()))
    }

    fun cancel(player: Player) {
        flows.remove(player.uniqueId)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        flows.remove(e.player.uniqueId)
        cancelStreaks.remove(e.player.uniqueId)
    }

    @EventHandler
    fun onChat(e: AsyncPlayerChatEvent) {
        val player = e.player
        val flow = flows[player.uniqueId] ?: return

        if (isExpired(flow)) {
            flows.remove(player.uniqueId)
            plugin.scope.launch(plugin.mainDispatcher) {
                plugin.messages.msg(player, "prompts.expired")
            }
            return
        }

        val raw = e.message.trim()
        if (raw.isBlank()) return

        e.isCancelled = true
        plugin.scope.launch(plugin.mainDispatcher) {
            handlePromptMessage(player, raw)
        }
    }

    private suspend fun handlePromptMessage(player: Player, raw: String) {
        val flow = flows[player.uniqueId] ?: return

        if (blockIfActionsPaused(player)) {
            flows.remove(player.uniqueId)
            return
        }

        // Lazy timeouts: if the prompt is stale, drop it and let the message go to normal chat.
        if (isExpired(flow)) {
            flows.remove(player.uniqueId)
            plugin.messages.msg(player, "prompts.expired")
            return
        }

        if (raw.equals("cancel", ignoreCase = true)) {
            flows.remove(player.uniqueId)
            recordCancel(player)
            plugin.messages.msg(player, "prompts.cancelled")
            return
        }

        when (flow) {
            is Flow.CustomReq -> {
                val maxTitle = plugin.settings.chatPromptMaxTitleChars
                val maxDesc = plugin.settings.chatPromptMaxDescChars
                if (flow.step == 0) {
                    if (raw.length > maxTitle) {
                        plugin.messages.msg(player, "prompts.custom.title_too_long", mapOf("max" to maxTitle.toString()))
                        plugin.messages.msg(player, "prompts.custom.title_prompt", mapOf("max" to maxTitle.toString()))
                        flows[player.uniqueId] = flow.copy(lastActivityMs = System.currentTimeMillis())
                        return
                    }
                    flows[player.uniqueId] = flow.copy(step = 1, title = raw, lastActivityMs = System.currentTimeMillis())
                    plugin.messages.msg(player, "prompts.custom.description_prompt", mapOf("max" to maxDesc.toString()))
                } else {
                    if (!plugin.termService.isElectionOpen(java.time.Instant.now(), flow.term)) {
                        flows.remove(player.uniqueId)
                        plugin.messages.msg(player, "prompts.custom.closed")
                        return
                    }
                    if (!isActiveCandidateForTerm(player, flow.term)) {
                        flows.remove(player.uniqueId)
                        plugin.messages.msg(player, "prompts.custom.must_candidate")
                        return
                    }
                    if (raw.length > maxDesc) {
                        plugin.messages.msg(player, "prompts.custom.description_too_long", mapOf("max" to maxDesc.toString()))
                        plugin.messages.msg(player, "prompts.custom.description_prompt", mapOf("max" to maxDesc.toString()))
                        flows[player.uniqueId] = flow.copy(lastActivityMs = System.currentTimeMillis())
                        return
                    }
                    val limit = plugin.settings.customRequestsLimitPerTerm
                    if (limit > 0) {
                        val existing = withContext(Dispatchers.IO) {
                            plugin.store.requestCountForCandidate(flow.term, player.uniqueId)
                        }
                        if (existing >= limit) {
                            flows.remove(player.uniqueId)
                            plugin.messages.msg(player, "prompts.custom.limit_reached")
                            plugin.messages.msg(player, "prompts.custom.limit_value", mapOf("limit" to limit.toString()))
                            return
                        }
                    }

                    val id = withContext(Dispatchers.IO) {
                        plugin.store.addRequest(
                            flow.term,
                            player.uniqueId,
                            flow.title ?: "Untitled",
                            raw
                        )
                    }
                    flows.remove(player.uniqueId)
                    // Successfully completed a prompt flow: reset cancel streak.
                    cancelStreaks.remove(player.uniqueId)
                    plugin.messages.msg(player, "prompts.custom.submitted", mapOf("id" to id.toString()))
                    plugin.messages.msg(player, "prompts.custom.admin_review")
                }
            }

            is Flow.BioEdit -> {
                val trimmed = raw.trim()
                val maxBio = plugin.settings.chatPromptMaxBioChars

                if (trimmed.length > maxBio) {
                    plugin.messages.msg(player, "prompts.bio.too_long", mapOf("max" to maxBio.toString()))
                    plugin.messages.msg(player, "prompts.bio.start", mapOf("max" to maxBio.toString()))
                    flows[player.uniqueId] = flow.copy(lastActivityMs = System.currentTimeMillis())
                    return
                }

                withContext(Dispatchers.IO) {
                    plugin.store.setCandidateBio(flow.term, player.uniqueId, trimmed)
                }
                flows.remove(player.uniqueId)
                cancelStreaks.remove(player.uniqueId)
                plugin.messages.msg(player, "prompts.bio.saved")
                plugin.gui.open(player, mayorSystem.ui.menus.CandidateMenu(plugin))
            }
        }
    }

    private fun isExpired(flow: Flow): Boolean {
        val timeoutMs = max(30, plugin.settings.chatPromptTimeoutSeconds) * 1000L
        val last = when (flow) {
            is Flow.CustomReq -> flow.lastActivityMs
            is Flow.BioEdit -> flow.lastActivityMs
        }
        return System.currentTimeMillis() - last > timeoutMs
    }

    private fun blockIfActionsPaused(player: Player): Boolean {
        if (plugin.settings.isDisabled(mayorSystem.config.SystemGateOption.ACTIONS)) {
            plugin.messages.msg(player, "public.disabled")
            return true
        }
        if (plugin.settings.isPaused(mayorSystem.config.SystemGateOption.ACTIONS)) {
            plugin.messages.msg(player, "public.paused")
            return true
        }
        return false
    }

    private fun isActiveCandidateForTerm(player: Player, term: Int): Boolean {
        val entry = plugin.store.candidateEntry(term, player.uniqueId) ?: return false
        return entry.status != CandidateStatus.REMOVED
    }

    private fun sendSync(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block() else Bukkit.getScheduler().runTask(plugin, Runnable { block() })
    }

    private fun recordCancel(player: Player) {
        val now = System.currentTimeMillis()
        val streak = cancelStreaks.compute(player.uniqueId) { _, existing ->
            val s = existing ?: CancelStreak(0, 0)
            // If they've been calm for a while, don't punish them for old cancels.
            if (now - s.lastCancelMs > CANCEL_WINDOW_MS) s.count = 0
            s.count += 1
            s.lastCancelMs = now
            s
        } ?: return

        if (streak.count >= 5) {
            // Reset so this doesn't spam them.
            streak.count = 0
            sendSync {
                plugin.messages.msg(player, "prompts.tip_cancel_spam")
            }
        }
    }

    private companion object {
        private const val CANCEL_WINDOW_MS: Long = 10 * 60 * 1000L
    }
}

