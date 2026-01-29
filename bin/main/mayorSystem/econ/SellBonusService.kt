package mayorSystem.econ

import mayorSystem.MayorPlugin
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * /sell bonus integration.
 *
 * Goals:
 * - Prefer real sell-plugin APIs when they exist (reliable, works for GUI sells).
 * - Otherwise, fall back to the Vault balance-delta method.
 * - Avoid double-bonusing when both an API hook and the command fallback fire.
 */
class SellBonusService(private val plugin: MayorPlugin) {

    data class IntegrationStatus(
        val id: String,
        val detectedPlugin: String,
        val active: Boolean,
        val details: String
    )

    private val skipFallbackUntilMs = ConcurrentHashMap<UUID, Long>()
    private val statuses = mutableListOf<IntegrationStatus>()
    private var enabled = false

    fun enable() {
        if (enabled) return
        enabled = true

        // Always register the generic fallback listener.
        plugin.server.pluginManager.registerEvents(SellBonusListener(plugin, this), plugin)

        // Register API-based hooks (best effort).
        registerShopGuiPlusHook()
        registerEconomyShopGuiHook()
        registerDonutSellHook()

        // Clean up skip map on plugin disable
        plugin.server.pluginManager.registerEvents(object : Listener {
            @org.bukkit.event.EventHandler
            fun onPluginEnable(e: org.bukkit.event.server.PluginEnableEvent) {
                val name = e.plugin.name.lowercase()
                when {
                    name == "shopguiplus" -> registerShopGuiPlusHook()
                    name.contains("economyshopgui") -> registerEconomyShopGuiHook()
                    name == "donutsell" -> registerDonutSellHook()
                }
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

    fun notifySellBonus(player: Player, bonus: Double, multiplier: Double) {
        if (bonus <= 0.0) return
        val prettyBonus = String.format(java.util.Locale.US, "%.2f", bonus)
        plugin.messages.msg(player, "public.sell_bonus", mapOf("bonus" to prettyBonus, "multiplier" to String.format(java.util.Locale.US, "%.2f", multiplier)))
    }

    fun integrationStatuses(): List<IntegrationStatus> = statuses.toList()

    private fun registerShopGuiPlusHook() {
        if (hasActiveStatus("shopguiplus")) return
        val pm = plugin.server.pluginManager
        val installed = pm.getPlugin("ShopGUIPlus")?.takeIf { it.isEnabled } != null

        if (!installed) {
            recordStatus(IntegrationStatus(
                id = "shopguiplus",
                detectedPlugin = "ShopGUIPlus",
                active = false,
                details = "Not installed"
            ))
            return
        }

        val ok = registerDynamicEvent(
            pluginNameForStatus = "ShopGUIPlus",
            id = "shopguiplus",
            eventClassName = "net.brcdev.shopgui.event.ShopPreTransactionEvent",
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
        ) { event ->
            val action = invokeNoThrow(event, "getShopAction")?.toString()?.uppercase() ?: return@registerDynamicEvent
            if (!action.contains("SELL")) return@registerDynamicEvent

            val player = invokeNoThrow(event, "getPlayer") as? Player ?: return@registerDynamicEvent
            val term = plugin.termService.compute(java.time.Instant.now()).first
            val category = extractCategoryToken(event) ?: extractMaterial(event)?.let { categoryForMaterial(it) }
            val multiplier = plugin.perks.sellMultiplierForTerm(term, category)
            if (multiplier <= 1.000001) return@registerDynamicEvent

            val price = invokeDoubleNoThrow(event, listOf("getPrice")) ?: return@registerDynamicEvent
            val newPrice = price * multiplier

            if (!invokeSetterDoubleNoThrow(event, listOf("setPrice"), newPrice)) {
                return@registerDynamicEvent
            }

            notifySellBonus(player, price * (multiplier - 1.0), multiplier)
            markHandledByApi(player.uniqueId)
        }

        recordStatus(IntegrationStatus(
            id = "shopguiplus",
            detectedPlugin = "ShopGUIPlus",
            active = ok,
            details = if (ok) "Using ShopGUI+ transaction event hook" else "Installed, but API hook registration failed (using fallback)"
        ))
    }

    private fun registerEconomyShopGuiHook() {
        if (hasActiveStatus("economyshopgui")) return
        val pm = plugin.server.pluginManager
        val installed = (pm.getPlugin("EconomyShopGUI")?.takeIf { it.isEnabled }
            ?: pm.getPlugin("EconomyShopGUI-Premium")?.takeIf { it.isEnabled }) != null

        if (!installed) {
            recordStatus(IntegrationStatus(
                id = "economyshopgui",
                detectedPlugin = "EconomyShopGUI",
                active = false,
                details = "Not installed"
            ))
            return
        }

        val ok = registerDynamicEvent(
            pluginNameForStatus = "EconomyShopGUI",
            id = "economyshopgui",
            eventClassName = "me.gypopo.economyshopgui.api.events.PreTransactionEvent",
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
        ) { event ->
            // Determine if this is a SELL transaction.
            val typeStr = (
                invokeNoThrow(event, "getTransactionType")
                    ?: invokeNoThrow(invokeNoThrow(event, "getTransaction"), "getTransactionType")
                    ?: invokeNoThrow(invokeNoThrow(event, "getTransaction"), "getType")
                    ?: invokeNoThrow(event, "getType")
                )?.toString()?.uppercase()
                ?: return@registerDynamicEvent

            if (!typeStr.contains("SELL")) return@registerDynamicEvent

            val player = (invokeNoThrow(event, "getPlayer") as? Player)
                ?: (invokeNoThrow(event, "getBuyer") as? Player)
                ?: return@registerDynamicEvent

            // EconomyShopGUI supports non-Vault eco types. We only adjust if this transaction looks Vault-based.
            val ecoType = (
                invokeNoThrow(event, "getEcoType")
                    ?: invokeNoThrow(invokeNoThrow(event, "getShopItem"), "getEcoType")
                    ?: invokeNoThrow(invokeNoThrow(event, "getTransaction"), "getEcoType")
                )?.toString()?.uppercase()

            if (ecoType != null && !ecoType.contains("VAULT")) {
                // Let the fallback handle it (it will only trigger on Vault balance changes anyway).
                return@registerDynamicEvent
            }

            val term = plugin.termService.compute(java.time.Instant.now()).first
            val category = extractCategoryToken(event) ?: extractMaterial(event)?.let { categoryForMaterial(it) }
            val multiplier = plugin.perks.sellMultiplierForTerm(term, category)
            if (multiplier <= 1.000001) return@registerDynamicEvent

            val price = invokeDoubleNoThrow(event, listOf("getPrice", "getTotalPrice", "getTotalCost")) ?: return@registerDynamicEvent
            val newPrice = price * multiplier

            val changed = invokeSetterDoubleNoThrow(event, listOf("setPrice", "setTotalPrice", "setTotalCost"), newPrice)
            if (!changed) return@registerDynamicEvent

            notifySellBonus(player, price * (multiplier - 1.0), multiplier)
            markHandledByApi(player.uniqueId)
        }

        recordStatus(IntegrationStatus(
            id = "economyshopgui",
            detectedPlugin = "EconomyShopGUI",
            active = ok,
            details = if (ok) "Using EconomyShopGUI transaction event hook (Vault eco only)" else "Installed, but API hook registration failed (using fallback)"
        ))
    }

    private fun registerDonutSellHook() {
        if (hasActiveStatus("donutsell")) return
        val pm = plugin.server.pluginManager
        val installed = pm.getPlugin("DonutSell")?.takeIf { it.isEnabled } != null

        if (!installed) {
            recordStatus(IntegrationStatus(
                id = "donutsell",
                detectedPlugin = "DonutSell",
                active = false,
                details = "Not installed"
            ))
            return
        }

        val ok = registerDynamicEvent(
            pluginNameForStatus = "DonutSell",
            id = "donutsell",
            eventClassName = "com.jovanstar.donutsell.api.event.SellCategoryPayoutEvent",
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
        ) { event ->
            if (!plugin.economy.isAvailable()) return@registerDynamicEvent

            val player = invokeNoThrow(event, "getPlayer") as? Player ?: return@registerDynamicEvent
            val totals = readCategoryPaidTotals(event)
            val totalPaid = invokeDoubleNoThrow(event, listOf("getTotalPaid")) ?: totals.values.sum()
            if (totalPaid <= 0.0) return@registerDynamicEvent

            val term = plugin.termService.compute(java.time.Instant.now()).first
            var extra = 0.0

            if (totals.isNotEmpty()) {
                for ((key, paid) in totals) {
                    if (paid <= 0.0) continue
                    val m = plugin.perks.sellMultiplierForTerm(term, key)
                    if (m > 1.000001) {
                        extra += paid * (m - 1.0)
                    }
                }
            } else {
                val m = plugin.perks.sellMultiplierForTerm(term, null)
                if (m > 1.000001) {
                    extra = totalPaid * (m - 1.0)
                }
            }

            if (extra <= 0.0) return@registerDynamicEvent
            val okDeposit = plugin.economy.deposit(player, extra)
            if (!okDeposit) return@registerDynamicEvent

            val effective = if (totalPaid > 0.0) (totalPaid + extra) / totalPaid else 1.0
            notifySellBonus(player, extra, effective)
            markHandledByApi(player.uniqueId)
        }

        recordStatus(IntegrationStatus(
            id = "donutsell",
            detectedPlugin = "DonutSell",
            active = ok,
            details = if (ok) "Using DonutSell SellCategoryPayoutEvent hook" else "Installed, but API hook registration failed (using fallback)"
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

    private fun invokeNoThrow(target: Any?, method: String): Any? {
        if (target == null) return null
        return runCatching { target.javaClass.getMethod(method).invoke(target) }.getOrNull()
    }

    private fun invokeDoubleNoThrow(target: Any?, methods: List<String>): Double? {
        if (target == null) return null
        for (m in methods) {
            val v = runCatching { target.javaClass.getMethod(m).invoke(target) }.getOrNull()
            when (v) {
                is Number -> return v.toDouble()
            }
        }
        return null
    }

    private fun invokeSetterDoubleNoThrow(target: Any?, methods: List<String>, value: Double): Boolean {
        if (target == null) return false
        for (m in methods) {
            val ok = runCatching {
                val method = target.javaClass.getMethod(m, Double::class.javaPrimitiveType)
                method.invoke(target, value)
                true
            }.getOrElse { false }
            if (ok) return true
        }
        return false
    }

    private fun readCategoryPaidTotals(event: Any): Map<String, Double> {
        val raw = invokeNoThrow(event, "getCategoryPaidTotals") as? Map<*, *> ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()

        val out = linkedMapOf<String, Double>()
        for ((k, v) in raw) {
            val key = when (k) {
                is String -> k.trim()
                is Enum<*> -> k.name
                else -> k?.toString()?.trim().orEmpty()
            }.uppercase()
            if (key.isBlank()) continue
            val value = when (v) {
                is Number -> v.toDouble()
                else -> v?.toString()?.toDoubleOrNull()
            } ?: continue
            out[key] = value
        }
        return out
    }

    private fun extractCategoryToken(event: Any): String? {
        val direct = readCategoryToken(event)
        if (direct != null) return direct

        val shopItem = invokeNoThrow(event, "getShopItem")
        val fromItem = readCategoryToken(shopItem)
            ?: readCategoryToken(invokeNoThrow(shopItem, "getCategory"))
            ?: readCategoryToken(invokeNoThrow(shopItem, "getShopCategory"))
            ?: readCategoryToken(invokeNoThrow(shopItem, "getSection"))
            ?: readCategoryToken(invokeNoThrow(shopItem, "getShopSection"))
        if (fromItem != null) return fromItem

        val shop = invokeNoThrow(event, "getShop") ?: invokeNoThrow(shopItem, "getShop")
        val fromShop = readCategoryToken(shop)
            ?: readCategoryToken(invokeNoThrow(shop, "getCategory"))
            ?: readCategoryToken(invokeNoThrow(shop, "getShopCategory"))
            ?: readCategoryToken(invokeNoThrow(shop, "getSection"))
            ?: readCategoryToken(invokeNoThrow(shop, "getShopSection"))
        if (fromShop != null) return fromShop

        val category = invokeNoThrow(event, "getCategory")
            ?: invokeNoThrow(event, "getShopCategory")
            ?: invokeNoThrow(event, "getSection")
            ?: invokeNoThrow(event, "getShopSection")
        return readCategoryToken(category)
    }

    private fun readCategoryToken(obj: Any?): String? {
        if (obj == null) return null
        when (obj) {
            is String -> {
                val raw = obj.trim()
                return if (raw.isNotBlank()) raw.uppercase() else null
            }
            is Enum<*> -> return obj.name.uppercase()
        }

        val getters = listOf("getId", "getKey", "getName", "getIdentifier", "getCategory", "getSection")
        for (m in getters) {
            val v = invokeNoThrow(obj, m)
            when (v) {
                is String -> {
                    val raw = v.trim()
                    if (raw.isNotBlank()) return raw.uppercase()
                }
                is Enum<*> -> return v.name.uppercase()
            }
        }

        val s = obj.toString().trim()
        return if (s.isNotBlank() && !s.contains("@")) s.uppercase() else null
    }

    private fun categoryForMaterial(mat: Material): String? {
        val sec = plugin.config.getConfigurationSection("sell_bonus.categories") ?: return null
        for (key in sec.getKeys(false)) {
            val items = plugin.config.getStringList("sell_bonus.categories.$key")
            if (items.any { it.equals(mat.name, ignoreCase = true) }) return key.uppercase()
        }
        return null
    }

    private fun extractMaterial(event: Any): Material? {
        val direct = extractItemStack(event) ?: run {
            val shopItem = invokeNoThrow(event, "getShopItem")
            extractItemStack(shopItem)
        } ?: run {
            val transaction = invokeNoThrow(event, "getTransaction")
            extractItemStack(transaction)
        }

        return direct?.type
    }

    private fun extractItemStack(obj: Any?): ItemStack? {
        if (obj == null) return null
        if (obj is ItemStack) return obj

        val methods = listOf(
            "getItemStack",
            "getItem",
            "getItemToSell",
            "getItemStackToSell",
            "getOriginalItem",
            "getItemStackOriginal"
        )
        for (m in methods) {
            val v = invokeNoThrow(obj, m)
            if (v is ItemStack) return v
        }
        return null
    }
}
