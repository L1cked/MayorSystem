package mayorSystem.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mayorSystem.MayorPlugin
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class SpigotUpdateNotifier(private val plugin: MayorPlugin) : Listener {
    private val mini = MiniMessage.miniMessage()
    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var state: UpdateState? = null

    fun onPluginReady() {
        refreshAsync(force = true)
    }

    fun refreshAsync(force: Boolean = false) {
        if (!force && state != null) return
        if (!refreshInFlight.compareAndSet(false, true)) return

        plugin.scope.launch {
            try {
                val installed = withContext(Dispatchers.Default) { plugin.pluginMeta.version.trim() }
                val latest = fetchLatestVersion() ?: return@launch
                val outdated = withContext(Dispatchers.Default) {
                    SpigotVersionComparator.isInstalledOlder(installed, latest)
                }
                state = UpdateState(installed = installed, latest = latest, outdated = outdated)
                if (outdated) {
                    plugin.logger.warning(
                        "Update available: installed=${displayVersion(installed)} -> latest=${displayVersion(latest)} ($SPIGOT_PAGE_URL)"
                    )
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        if (!player.isOp) return

        plugin.scope.launch {
            delay(JOIN_NOTIFY_DELAY_MS)
            val snapshot = withContext(Dispatchers.Default) { state }
            if (snapshot == null || !snapshot.outdated) return@launch
            withContext(plugin.mainDispatcher) {
                if (!player.isOnline || !player.isOp) return@withContext
                sendUpdateMessage(player, snapshot)
            }
        }
    }

    private suspend fun fetchLatestVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URI(SPIGOT_API_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "MayorSystem/${plugin.pluginMeta.version}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                .takeIf { it.isNotBlank() }
        }.onFailure {
            plugin.logger.warning("Failed to check Spigot updates: ${it.message}")
        }.getOrNull()
    }

    private fun sendUpdateMessage(player: Player, snapshot: UpdateState) {
        val installedEscaped = mini.escapeTags(displayVersion(snapshot.installed))
        val latestEscaped = mini.escapeTags(displayVersion(snapshot.latest))
        player.sendMessage(
            mini.deserialize(
                "<yellow><bold>MayorSystem update available</bold></yellow> " +
                    "<gray>(installed <white>$installedEscaped</white>, latest <white>$latestEscaped</white>)</gray>"
            )
        )
        player.sendMessage(
            mini.deserialize(
                "<gray>Download:</gray> " +
                    "<aqua><click:open_url:'$SPIGOT_PAGE_URL'><hover:show_text:'<gray>Open Spigot page</gray>'>$SPIGOT_PAGE_URL</hover></click></aqua>"
            )
        )
    }

    private fun displayVersion(raw: String): String =
        raw.trim().replaceFirst(Regex("^[vV](?=\\d)"), "")

    private data class UpdateState(
        val installed: String,
        val latest: String,
        val outdated: Boolean
    )

    private companion object {
        private const val SPIGOT_RESOURCE_ID = 132472
        private const val SPIGOT_PAGE_URL = "https://www.spigotmc.org/resources/mayorsystem.$SPIGOT_RESOURCE_ID/"
        private const val SPIGOT_API_URL = "https://api.spigotmc.org/legacy/update.php?resource=$SPIGOT_RESOURCE_ID"
        private const val JOIN_NOTIFY_DELAY_MS = 2000L
    }
}

internal object SpigotVersionComparator {
    fun isInstalledOlder(installed: String, latest: String): Boolean {
        val left = tokenize(installed)
        val right = tokenize(latest)
        val max = maxOf(left.size, right.size)
        for (idx in 0 until max) {
            val l = left.getOrNull(idx) ?: Token.Number(0)
            val r = right.getOrNull(idx) ?: Token.Number(0)
            val cmp = compare(l, r)
            if (cmp < 0) return true
            if (cmp > 0) return false
        }
        return false
    }

    private fun tokenize(value: String): List<Token> {
        val matches = TOKEN_REGEX.findAll(value)
        val tokens = matches.map { part ->
            val token = part.value
            token.toIntOrNull()?.let { Token.Number(it) } ?: Token.Text(token.lowercase())
        }.toList()
        val firstNumberIndex = tokens.indexOfFirst { it is Token.Number }
        if (firstNumberIndex > 0) {
            return tokens.subList(firstNumberIndex, tokens.size)
        }
        return tokens
    }

    private fun compare(left: Token, right: Token): Int {
        return when {
            left is Token.Number && right is Token.Number -> left.value.compareTo(right.value)
            left is Token.Number && right is Token.Text -> 1
            left is Token.Text && right is Token.Number -> -1
            left is Token.Text && right is Token.Text -> left.value.compareTo(right.value)
            else -> 0
        }
    }

    private sealed interface Token {
        data class Number(val value: Int) : Token
        data class Text(val value: String) : Token
    }

    private val TOKEN_REGEX = Regex("\\d+|[A-Za-z]+")
}
