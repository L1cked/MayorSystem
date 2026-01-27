package mayorSystem.ux

import mayorSystem.MayorPlugin
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

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
    private val mm = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

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
        flows[player.uniqueId] = Flow.CustomReq(term, step = 0)
        player.sendMessage(
            mm.deserialize(
                "<gold>Custom perk request:</gold> Type the <white>title</white> in chat. " +
                        "<dark_gray>(max ${MAX_REQ_TITLE_CHARS} chars)</dark_gray> <gray>(type 'cancel' to abort)</gray>"
            )
        )
    }

    fun beginBioEditFlow(player: Player, term: Int) {
        flows[player.uniqueId] = Flow.BioEdit(term)
        player.sendMessage(
            mm.deserialize(
                "<gold>Candidate bio:</gold> Type your bio in chat. " +
                        "<dark_gray>(max ${MAX_BIO_CHARS} chars)</dark_gray> <gray>(type 'cancel' to abort)</gray>\n" +
                        "<dark_gray>Tip: keep it short. You can edit again any time.</dark_gray>"
            )
        )
    }

    fun cancel(player: Player) {
        flows.remove(player.uniqueId)
    }

    @EventHandler
    fun onChat(e: AsyncChatEvent) {
        val player = e.player
        val flow = flows[player.uniqueId] ?: return

        // Lazy timeouts: if the prompt is stale, drop it and let the message go to normal chat.
        if (isExpired(flow)) {
            flows.remove(player.uniqueId)
	            sendSync {
                player.sendMessage(mm.deserialize("<gray>Your prompt expired (timeout). Start again from the menu.</gray>"))
            }
            return
        }

        val raw = plain.serialize(e.message()).trim()
        if (raw.isBlank()) return

        if (raw.equals("cancel", ignoreCase = true)) {
            flows.remove(player.uniqueId)
            e.isCancelled = true
            recordCancel(player)
	            sendSync { player.sendMessage(mm.deserialize("<gray>Cancelled.</gray>")) }
            return
        }

        when (flow) {
            is Flow.CustomReq -> {
                e.isCancelled = true
                if (flow.step == 0) {
                    if (raw.length > MAX_REQ_TITLE_CHARS) {
                        sendSync {
                            player.sendMessage(
                                mm.deserialize(
                                    "<red>Title too long.</red> <gray>Max ${MAX_REQ_TITLE_CHARS} characters.</gray>"
                                )
                            )
                            player.sendMessage(
                                mm.deserialize(
                                    "<gold>Custom perk request:</gold> Type the <white>title</white> in chat. " +
                                            "<dark_gray>(max ${MAX_REQ_TITLE_CHARS} chars)</dark_gray> <gray>(type 'cancel' to abort)</gray>"
                                )
                            )
                        }
                        flows[player.uniqueId] = flow.copy(lastActivityMs = System.currentTimeMillis())
                        return
                    }
                    flows[player.uniqueId] = flow.copy(step = 1, title = raw, lastActivityMs = System.currentTimeMillis())
	                    sendSync {
                        player.sendMessage(
                            mm.deserialize(
                                "<gold>Now type the <white>description</white>.</gold> " +
                                        "<dark_gray>(max ${MAX_REQ_DESC_CHARS} chars)</dark_gray> <gray>(type 'cancel' to abort)</gray>"
                            )
                        )
                    }
                } else {
                    if (raw.length > MAX_REQ_DESC_CHARS) {
                        sendSync {
                            player.sendMessage(
                                mm.deserialize(
                                    "<red>Description too long.</red> <gray>Max ${MAX_REQ_DESC_CHARS} characters.</gray>"
                                )
                            )
                            player.sendMessage(
                                mm.deserialize(
                                    "<gold>Now type the <white>description</white>.</gold> " +
                                            "<dark_gray>(max ${MAX_REQ_DESC_CHARS} chars)</dark_gray> <gray>(type 'cancel' to abort)</gray>"
                                )
                            )
                        }
                        flows[player.uniqueId] = flow.copy(lastActivityMs = System.currentTimeMillis())
                        return
                    }
                    val limit = plugin.settings.customRequestsLimitPerTerm
                    if (limit > 0) {
                        val existing = plugin.store.requestCountForCandidate(flow.term, player.uniqueId)
                        if (existing >= limit) {
                            flows.remove(player.uniqueId)
	                            sendSync {
                                player.sendMessage(mm.deserialize("<red>Request limit reached.</red>"))
                                player.sendMessage(mm.deserialize("<gray>Limit:</gray> <white>$limit</white>"))
                            }
                            return
                        }
                    }

                    val id = plugin.store.addRequest(
                        flow.term,
                        player.uniqueId,
                        flow.title ?: "Untitled",
                        raw
                    )
                    flows.remove(player.uniqueId)
                    // Successfully completed a prompt flow: reset cancel streak.
                    cancelStreaks.remove(player.uniqueId)
	                    sendSync {
                        player.sendMessage(
                            mm.deserialize(
                                "<green>Submitted request</green> <yellow>#$id</yellow><green>.</green>"
                            )
                        )
                        player.sendMessage(mm.deserialize("<gray>Admins will approve/deny it from the UI.</gray>"))
                    }
                }
            }

            is Flow.BioEdit -> {
                e.isCancelled = true
                val trimmed = raw.trim()

                if (trimmed.length > MAX_BIO_CHARS) {
                    sendSync {
                        player.sendMessage(mm.deserialize("<red>Bio too long.</red> <gray>Max ${MAX_BIO_CHARS} characters.</gray>"))
                        player.sendMessage(
                            mm.deserialize(
                                "<gold>Candidate bio:</gold> Type your bio in chat. " +
                                        "<dark_gray>(max ${MAX_BIO_CHARS} chars)</dark_gray> <gray>(type 'cancel' to abort)</gray>"
                            )
                        )
                    }
                    flows[player.uniqueId] = flow.copy(lastActivityMs = System.currentTimeMillis())
                    return
                }

                plugin.store.setCandidateBio(flow.term, player.uniqueId, trimmed)
                flows.remove(player.uniqueId)
                cancelStreaks.remove(player.uniqueId)
	                sendSync {
                    player.sendMessage(mm.deserialize("<green>Bio saved.</green>"))
                    plugin.gui.open(player, mayorSystem.ui.menus.CandidateMenu(plugin))
                }
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
                player.sendMessage(
                    mm.deserialize(
                        "<yellow>Tip:</yellow> You cancelled prompts a bunch of times. " +
                                "<gray>Using the menu button again is usually easier than typing.</gray>"
                    )
                )
            }
        }
    }

    private companion object {
        private const val CANCEL_WINDOW_MS: Long = 10 * 60 * 1000L

        // Chat prompt length limits (hard caps to prevent chat spam / huge config strings)
        private const val MAX_REQ_TITLE_CHARS: Int = 48
        private const val MAX_REQ_DESC_CHARS: Int = 400
        private const val MAX_BIO_CHARS: Int = 240
    }
}
