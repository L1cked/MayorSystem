package mayorSystem.economy

import mayorSystem.MayorPlugin
import mayorSystem.economy.SellCategoryIndex.SIZE
import mayorSystem.economy.SellCategoryIndex.TOTAL
import mayorSystem.config.SystemGateOption
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.EventExecutor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Method

/**
 * /sell bonus integration.
 *
 * Goals:
 * - Prefer SystemSellAddon API when available (reliable, works for GUI sells).
 * - Otherwise, fall back to the Vault balance-delta method.
 * - Avoid double-bonusing when both an API hook and the command fallback fire.
 */
class SellBonusService(private val plugin: MayorPlugin) : MayorSellCallback {

    data class IntegrationStatus(
        val id: String,
        val detectedPlugin: String,
        val active: Boolean,
        val details: String
    )

    private val skipFallbackUntilMs = ConcurrentHashMap<UUID, Long>()
    private val noArgMethodCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Method?>>()
    private val lastTxByPlayer = ConcurrentHashMap<UUID, String>()
    private val lastAtByPlayer = ConcurrentHashMap<UUID, Long>()
    private val statuses = mutableListOf<IntegrationStatus>()
    private var enabled = false
    fun enable() {
        if (enabled) return
        enabled = true

        // Expose callback for SystemSellAddon direct calls.
        plugin.server.servicesManager.register(
            MayorSellCallback::class.java,
            this,
            plugin,
            org.bukkit.plugin.ServicePriority.Normal
        )

        // Always register the generic fallback listener.
        plugin.server.pluginManager.registerEvents(SellBonusListener(plugin, this), plugin)

        // Register API-based hooks (best effort).
        registerDsellHook()

        // Clean up skip map on plugin disable
        plugin.server.pluginManager.registerEvents(object : Listener {
            @org.bukkit.event.EventHandler
            fun onPluginEnable(e: org.bukkit.event.server.PluginEnableEvent) {
                val name = e.plugin.name.lowercase()
                if (name == "systemselladdon") registerDsellHook()
            }

            @org.bukkit.event.EventHandler
            fun onDisable(e: PluginDisableEvent) {
                if (e.plugin.name.equals(plugin.name, ignoreCase = true)) {
                    skipFallbackUntilMs.clear()
                }
            }
        }, plugin)
    }

    fun isFallbackSuppressed(playerId: UUID): Boolean {
        val until = skipFallbackUntilMs[playerId] ?: return false
        return System.currentTimeMillis() < until
    }

    fun markHandledByApi(playerId: UUID, suppressMs: Long = 2500L) {
        skipFallbackUntilMs[playerId] = System.currentTimeMillis() + suppressMs
    }

    fun notifySellBonus(player: Player, bonus: Double, multiplier: Double, name: String) {
        if (bonus <= 0.0) return
        val prettyBonus = String.format(java.util.Locale.US, "%.2f", bonus)
        plugin.messages.msg(
            player,
            "public.sell_bonus",
            mapOf(
                "bonus" to prettyBonus,
                "multiplier" to String.format(java.util.Locale.US, "%.2f", multiplier),
                "name" to name
            )
        )
    }

    fun integrationStatuses(): List<IntegrationStatus> = statuses.toList()
    fun isDsellActive(): Boolean = hasActiveStatus("systemselladdon")

    override fun onSellPayout(snapshot: DSellPayoutSnapshot) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { handleSellPayout(snapshot) })
            return
        }
        handleSellPayout(snapshot)
    }

    private fun handleSellPayout(snapshot: DSellPayoutSnapshot) {
        if (!bonusesActive()) return

        val player = plugin.server.getPlayer(snapshot.playerId) ?: return
        if (!player.isOnline) return

        // Anti-double: prefer transaction id, otherwise short time window.
        val txId = snapshot.transactionId
        if (txId != null) {
            val last = lastTxByPlayer[snapshot.playerId]
            if (txId == last) return
            lastTxByPlayer[snapshot.playerId] = txId
        } else {
            val now = System.currentTimeMillis()
            val lastAt = lastAtByPlayer[snapshot.playerId] ?: 0L
            if (now - lastAt < 500L) return
            lastAtByPlayer[snapshot.playerId] = now
        }

        val paid = snapshot.paidByCategory
        if (paid.isEmpty()) return
        val totalPaid = if (paid.size > TOTAL) paid[TOTAL] else paid.sum()
        if (totalPaid <= 0.0) return

        val term = plugin.termService.computeNow().first
        if (!plugin.perks.sellPerksActiveForTermCached(term)) return
        val mults = plugin.perks.sellMultipliersForTermCached(term)

        if (plugin.config.getBoolean("sell_bonus.async_bonus_calc", false)) {
            val paidCopy = paid.copyOf()
            val multsCopy = mults.copyOf()
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                val entries = buildBonusEntriesFromSnapshot(paidCopy, totalPaid, multsCopy)
                val extraAsync = entries.sumOf { it.bonus }
                if (extraAsync <= 0.0) return@Runnable
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (!player.isOnline) return@Runnable
                    if (!bonusesActive()) return@Runnable
                    val okDeposit = plugin.economy.deposit(player, extraAsync)
                    if (!okDeposit) return@Runnable
                    for (entry in entries) {
                        notifySellBonus(player, entry.bonus, entry.multiplier, entry.name)
                    }
                    markHandledByApi(player.uniqueId)
                })
            })
            return
        }

        val entries = buildBonusEntriesFromSnapshot(paid, totalPaid, mults)
        val extra = entries.sumOf { it.bonus }
        if (extra <= 0.0) return
        val okDeposit = plugin.economy.deposit(player, extra)
        if (!okDeposit) return

        for (entry in entries) {
            notifySellBonus(player, entry.bonus, entry.multiplier, entry.name)
        }
        markHandledByApi(player.uniqueId)
    }

    private fun bonusesActive(): Boolean =
        plugin.isReady() &&
            !plugin.settings.isBlocked(SystemGateOption.PERKS) &&
            plugin.config.getBoolean("sell_bonus.enabled", true) &&
            plugin.economy.isAvailable()

    private fun registerDsellHook() {
        if (hasActiveStatus("systemselladdon")) return
        val pm = plugin.server.pluginManager
        val installed = pm.getPlugin("SystemSellAddon")?.takeIf { it.isEnabled } != null

        if (!installed) {
            recordStatus(IntegrationStatus(
                id = "systemselladdon",
                detectedPlugin = "SystemSellAddon",
                active = false,
                details = "Not installed"
            ))
            return
        }

        val okDetail = registerDynamicEvent(
            pluginNameForStatus = "SystemSellAddon",
            id = "systemselladdon",
            eventClassName = "ca.l1cked.systemselladdon.api.event.SystemSellAddonPayoutEvent",
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
        ) { event ->
            if (!bonusesActive()) return@registerDynamicEvent

            val player = invokeNoThrow(event, "getPlayer") as? Player ?: return@registerDynamicEvent
            val totalPaid = invokeDoubleNoThrow(event, listOf("getTotalPaid")) ?: return@registerDynamicEvent
            if (totalPaid <= 0.0) return@registerDynamicEvent

            val txId = invokeNoThrow(event, "getTransactionId")?.toString()
            if (!markDsellHandledIfNew(player.uniqueId, txId)) return@registerDynamicEvent

            val term = plugin.termService.computeNow().first
        val ids = invokeIntArrayNoThrow(
            event,
            listOf("categoryIds", "getCategoryIds", "getCategoryIdList", "getCategories")
        )
        val paid = invokeDoubleArrayNoThrow(
            event,
            listOf("paidByCategory", "getPaidByCategory", "getPaidPerCategory", "getPaidByCategories")
        )

            val entries = buildBonusEntriesFromDsell(ids, paid, term, totalPaid)
            val extra = entries.sumOf { it.bonus }
            if (extra <= 0.0) return@registerDynamicEvent
            val okDeposit = plugin.economy.deposit(player, extra)
            if (!okDeposit) return@registerDynamicEvent

            for (entry in entries) {
                notifySellBonus(player, entry.bonus, entry.multiplier, entry.name)
            }
            markHandledByApi(player.uniqueId)
        }

        val okSummary = if (okDetail) true else registerDynamicEvent(
            pluginNameForStatus = "SystemSellAddon",
            id = "systemselladdon",
            eventClassName = "ca.l1cked.systemselladdon.api.event.SystemSellAddonPayoutSummaryEvent",
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
        ) { event ->
            if (!bonusesActive()) return@registerDynamicEvent
            val player = invokeNoThrow(event, "getPlayer") as? Player ?: return@registerDynamicEvent
            val totalPaid = invokeDoubleNoThrow(event, listOf("getTotalPaid")) ?: return@registerDynamicEvent
            if (totalPaid <= 0.0) return@registerDynamicEvent

            val txId = invokeNoThrow(event, "getTransactionId")?.toString()
            if (!markDsellHandledIfNew(player.uniqueId, txId)) return@registerDynamicEvent

            val term = plugin.termService.computeNow().first
            val entries = buildBonusEntriesFromGlobal(totalPaid, term)
            val extra = entries.sumOf { it.bonus }
            if (extra <= 0.0) return@registerDynamicEvent
            val okDeposit = plugin.economy.deposit(player, extra)
            if (!okDeposit) return@registerDynamicEvent

            for (entry in entries) {
                notifySellBonus(player, entry.bonus, entry.multiplier, entry.name)
            }
            markHandledByApi(player.uniqueId)
        }

        recordStatus(IntegrationStatus(
            id = "systemselladdon",
            detectedPlugin = "SystemSellAddon",
            active = okDetail || okSummary,
            details = if (okDetail || okSummary) "Using SystemSellAddon payout event hook" else "Installed, but API hook registration failed (using fallback)"
        ))
    }

    private fun registerDynamicEvent(
        pluginNameForStatus: String,
        id: String,
        eventClassName: String,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        handler: (Any) -> Unit
    ): Boolean {
        val eventClass: Class<out Event> = try {
            @Suppress("UNCHECKED_CAST")
            Class.forName(eventClassName) as Class<out Event>
        } catch (_: Throwable) {
            return false
        }

        val listener = object : Listener {}
        val executor = EventExecutor { _, event ->
            try {
                handler(event)
            } catch (e: Throwable) {
                // Do not break the sell plugin; log once per error type.
                plugin.logger.warning("Sell hook '$id' (${pluginNameForStatus}) handler error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return try {
            plugin.server.pluginManager.registerEvent(eventClass, listener, priority, executor, plugin, ignoreCancelled)
            true
        } catch (_: EventException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasActiveStatus(id: String): Boolean =
        statuses.any { it.id == id && it.active }

    private fun recordStatus(status: IntegrationStatus) {
        val idx = statuses.indexOfFirst { it.id == status.id }
        if (idx >= 0) {
            statuses[idx] = status
        } else {
            statuses += status
        }
    }

    private data class BonusEntry(
        val name: String,
        val bonus: Double,
        val multiplier: Double)

    private fun buildBonusEntriesFromSnapshot(
        paid: DoubleArray,
        totalPaid: Double,
        mults: DoubleArray): List<BonusEntry> {
        val out = mutableListOf<BonusEntry>()
        var paidWithCategoryBonus = 0.0
        val limit = minOf(paid.size, SIZE)
        for (i in 0 until minOf(limit, TOTAL)) {
            val amount = paid[i]
            if (amount <= 0.0) continue
            val m = mults.getOrNull(i) ?: 1.0
            if (m > 1.000001) {
                out += BonusEntry(multiplierNameForCategoryId(i), amount * (m - 1.0), m)
                paidWithCategoryBonus += amount
            }
        }

        val allMult = mults.getOrNull(TOTAL) ?: 1.0
        if (allMult > 1.000001 && totalPaid > 0.0) {
            val base = if (plugin.settings.sellAllBonusStacks) {
                totalPaid
            } else {
                (totalPaid - paidWithCategoryBonus).coerceAtLeast(0.0)
            }
            if (base > 0.0) {
                out += BonusEntry("All", base * (allMult - 1.0), allMult)
            }
        }
        return out
    }

    private fun buildBonusEntriesFromDsell(
        ids: IntArray?,
        paid: DoubleArray?,
        term: Int,
        totalPaid: Double): List<BonusEntry> {
        val out = mutableListOf<BonusEntry>()
        var paidWithCategoryBonus = 0.0
        if (paid != null && paid.isNotEmpty()) {
            val limit = minOf(paid.size, TOTAL)
            val effectiveIds = if (ids != null && ids.size >= limit) ids else IntArray(limit) { it }
            for (i in 0 until limit) {
                val amount = paid[i]
                if (amount <= 0.0) continue
                val id = effectiveIds[i]
                val m = plugin.perks.sellMultiplierForTerm(term, id.toString())
                if (m > 1.000001) {
                    out += BonusEntry(multiplierNameForCategoryId(id), amount * (m - 1.0), m)
                    paidWithCategoryBonus += amount
                }
            }
        }

        val allMult = plugin.perks.sellMultiplierForTerm(term, null)
        if (allMult > 1.000001 && totalPaid > 0.0) {
            val base = if (plugin.settings.sellAllBonusStacks || paidWithCategoryBonus <= 0.0) {
                totalPaid
            } else {
                (totalPaid - paidWithCategoryBonus).coerceAtLeast(0.0)
            }
            if (base > 0.0) {
                out += BonusEntry("All", base * (allMult - 1.0), allMult)
            }
        }
        return out
    }

    private fun buildBonusEntriesFromGlobal(totalPaid: Double, term: Int): List<BonusEntry> {
        if (totalPaid <= 0.0) return emptyList()
        val m = plugin.perks.sellMultiplierForTerm(term, null)
        if (m <= 1.000001) return emptyList()
        return listOf(BonusEntry("All", totalPaid * (m - 1.0), m))
    }

    private fun multiplierNameForCategoryId(id: Int): String = when (id) {
        0 -> "Crops"
        1 -> "Ores"
        2 -> "Mobs"
        3 -> "Natural"
        4 -> "Armor & Tools"
        5 -> "Fish"
        6 -> "Books"
        7 -> "Potions"
        8 -> "Blocks"
        else -> "All"
    }

    private fun getNoArgMethod(cls: Class<*>, name: String): Method? {
        val methods = noArgMethodCache.computeIfAbsent(cls) { ConcurrentHashMap() }
        return methods.computeIfAbsent(name) {
            runCatching { cls.getMethod(name) }.getOrNull()
        }
    }

    private fun invokeNoThrow(target: Any?, method: String): Any? {
        if (target == null) return null
        val m = getNoArgMethod(target.javaClass, method) ?: return null
        return runCatching { m.invoke(target) }.getOrNull()
    }

    private fun markDsellHandledIfNew(playerId: java.util.UUID, txId: String?): Boolean {
        if (txId != null) {
            val last = lastTxByPlayer[playerId]
            if (txId == last) return false
            lastTxByPlayer[playerId] = txId
            return true
        }
        val now = System.currentTimeMillis()
        val lastAt = lastAtByPlayer[playerId] ?: 0L
        if (now - lastAt < 500L) return false
        lastAtByPlayer[playerId] = now
        return true
    }



    private fun invokeDoubleNoThrow(target: Any?, methods: List<String>): Double? {
        if (target == null) return null
        for (m in methods) {
            val method = getNoArgMethod(target.javaClass, m) ?: continue
            val v = runCatching { method.invoke(target) }.getOrNull()
            when (v) {
                is Number -> return v.toDouble()
            }
        }
        return null
    }


    private fun invokeIntArrayNoThrow(target: Any?, methods: List<String>): IntArray? {
        if (target == null) return null
        for (m in methods) {
            val method = getNoArgMethod(target.javaClass, m) ?: continue
            val v = runCatching { method.invoke(target) }.getOrNull() ?: continue
            when (v) {
                is IntArray -> return v
                is Array<*> -> {
                    val nums = v.mapNotNull { (it as? Number)?.toInt() }
                    if (nums.isNotEmpty()) return nums.toIntArray()
                }
                is Iterable<*> -> {
                    val nums = v.mapNotNull { (it as? Number)?.toInt() }
                    if (nums.isNotEmpty()) return nums.toIntArray()
                }
            }
        }
        return null
    }

    private fun invokeDoubleArrayNoThrow(target: Any?, methods: List<String>): DoubleArray? {
        if (target == null) return null
        for (m in methods) {
            val method = getNoArgMethod(target.javaClass, m) ?: continue
            val v = runCatching { method.invoke(target) }.getOrNull() ?: continue
            when (v) {
                is DoubleArray -> return v
                is Array<*> -> {
                    val nums = v.mapNotNull { (it as? Number)?.toDouble() }
                    if (nums.isNotEmpty()) return nums.toDoubleArray()
                }
                is Iterable<*> -> {
                    val nums = v.mapNotNull { (it as? Number)?.toDouble() }
                    if (nums.isNotEmpty()) return nums.toDoubleArray()
                }
            }
        }
        return null
    }


}

