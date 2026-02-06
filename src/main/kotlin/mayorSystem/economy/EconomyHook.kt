package mayorSystem.economy

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

class EconomyHook(private val plugin: Plugin) {

    private data class ProviderInfo(
        val provider: Any?,
        val name: String?,
        val supported: Boolean
    )

    // Hold as Any? so Vault classes are never required to load this class
    @Volatile
    private var providerInfo: ProviderInfo = ProviderInfo(null, null, false)
    @Volatile
    private var methodCache: MethodCache? = null

    init {
        refresh()
    }

    fun refresh() {
        val info = findEconomyProviderInfo()
        providerInfo = info
        methodCache = info.provider?.takeIf { info.supported }?.let { buildMethodCache(it.javaClass) }
    }

    private val economyProvider: Any?
        get() = providerInfo.provider?.takeIf { providerInfo.supported }

    private enum class PlayerArgType { OFFLINE, PLAYER, STRING, NONE }

    private data class MethodCache(
        val hasMethod: Method?,
        val hasType: PlayerArgType,
        val withdrawMethod: Method?,
        val withdrawType: PlayerArgType,
        val depositMethod: Method?,
        val depositType: PlayerArgType,
        val balanceMethod: Method?,
        val balanceType: PlayerArgType
    )


    fun isAvailable(): Boolean = economyProvider != null

    fun providerName(): String? {
        return providerInfo.name
    }

    fun has(player: Player, amount: Double): Boolean {
        if (amount <= 0.0) return true
        val econ = economyProvider ?: return false
        return runCatching { invokeHas(econ, player, amount) }.getOrDefault(false)
    }

    fun withdraw(player: Player, amount: Double): Boolean {
        if (amount <= 0.0) return true
        val econ = economyProvider ?: return false
        return runCatching { invokeWithdraw(econ, player, amount) }.getOrDefault(false)
    }

    fun deposit(player: Player, amount: Double): Boolean {
        if (amount <= 0.0) return true
        val econ = economyProvider ?: return false
        return runCatching { invokeDeposit(econ, player, amount) }.getOrDefault(false)
    }

    fun balance(player: Player): Double? {
        val econ = economyProvider ?: return null
        return runCatching { invokeBalance(econ, player) }.getOrNull()
    }

    /**
     * Vault providers commonly implement `has`/`withdrawPlayer` using OfflinePlayer,
     * but some older providers still expose Player or String-based overloads.
     */
    private fun invokeHas(econ: Any, player: Player, amount: Double): Boolean {
        val cache = methodCache ?: return false
        val method = cache.hasMethod ?: return false
        return when (cache.hasType) {
            PlayerArgType.OFFLINE -> method.invoke(econ, player as OfflinePlayer, amount) as Boolean
            PlayerArgType.PLAYER -> method.invoke(econ, player, amount) as Boolean
            PlayerArgType.STRING -> method.invoke(econ, player.name, amount) as Boolean
            PlayerArgType.NONE -> false
        }
    }

    private fun invokeWithdraw(econ: Any, player: Player, amount: Double): Boolean {
        val cache = methodCache ?: return false
        val method = cache.withdrawMethod ?: return false
        val resp = when (cache.withdrawType) {
            PlayerArgType.OFFLINE -> method.invoke(econ, player as OfflinePlayer, amount)
            PlayerArgType.PLAYER -> method.invoke(econ, player, amount)
            PlayerArgType.STRING -> method.invoke(econ, player.name, amount)
            PlayerArgType.NONE -> null
        }
        return responseSuccess(resp)
    }

    private fun invokeDeposit(econ: Any, player: Player, amount: Double): Boolean {
        val cache = methodCache ?: return false
        val method = cache.depositMethod ?: return false
        val resp = when (cache.depositType) {
            PlayerArgType.OFFLINE -> method.invoke(econ, player as OfflinePlayer, amount)
            PlayerArgType.PLAYER -> method.invoke(econ, player, amount)
            PlayerArgType.STRING -> method.invoke(econ, player.name, amount)
            PlayerArgType.NONE -> null
        }
        return responseSuccess(resp)
    }

    private fun invokeBalance(econ: Any, player: Player): Double? {
        val cache = methodCache ?: return null
        val method = cache.balanceMethod ?: return null
        return when (cache.balanceType) {
            PlayerArgType.OFFLINE -> (method.invoke(econ, player as OfflinePlayer) as? Number)?.toDouble()
            PlayerArgType.PLAYER -> (method.invoke(econ, player) as? Number)?.toDouble()
            PlayerArgType.STRING -> (method.invoke(econ, player.name) as? Number)?.toDouble()
            PlayerArgType.NONE -> null
        }
    }

    private fun buildMethodCache(econClass: Class<*>): MethodCache {
        val has = findMethod(econClass, listOf("has"), 2)
        val withdraw = findMethod(econClass, listOf("withdrawPlayer", "withdraw"), 2)
        val deposit = findMethod(econClass, listOf("depositPlayer", "deposit"), 2)
        val balance = findMethod(econClass, listOf("getBalance", "balance"), 1)
        return MethodCache(
            hasMethod = has.first,
            hasType = has.second,
            withdrawMethod = withdraw.first,
            withdrawType = withdraw.second,
            depositMethod = deposit.first,
            depositType = deposit.second,
            balanceMethod = balance.first,
            balanceType = balance.second
        )
    }

    private fun findMethod(econClass: Class<*>, names: List<String>, paramCount: Int): Pair<Method?, PlayerArgType> {
        val methods = econClass.methods.filter { it.name in names && it.parameterCount == paramCount }
        if (methods.isEmpty()) return null to PlayerArgType.NONE

        val match = listOf(
            OfflinePlayer::class.java to PlayerArgType.OFFLINE,
            Player::class.java to PlayerArgType.PLAYER,
            String::class.java to PlayerArgType.STRING
        ).firstNotNullOfOrNull { (clazz, type) ->
            val m = methods.firstOrNull { it.parameterTypes[0].isAssignableFrom(clazz) }
            if (m != null) m to type else null
        }

        return match ?: (null to PlayerArgType.NONE)
    }

    private fun responseSuccess(response: Any?): Boolean {
        if (response == null) return false

        // Vault's EconomyResponse has transactionSuccess()
        response.javaClass.methods.firstOrNull { it.name == "transactionSuccess" && it.parameterCount == 0 }?.let { m ->
            return (m.invoke(response) as? Boolean) ?: false
        }

        // Some shaded/forked responses expose a boolean field
        return runCatching {
            val f = response.javaClass.getField("transactionSuccess")
            (f.get(response) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    private fun findEconomyProviderInfo(): ProviderInfo {
        // If Vault plugin isn't present, bail out safely
        val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return ProviderInfo(null, null, false)
        if (!vault.isEnabled) return ProviderInfo(null, null, false)

        // Use reflection so we never link Vault API at class load time
        val provider = runCatching {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val sm = Bukkit.getServicesManager()
            val getReg = sm.javaClass.getMethod("getRegistration", Class::class.java)
            val rsp = getReg.invoke(sm, economyClass) ?: return@runCatching null
            val providerMethod = rsp.javaClass.getMethod("getProvider")
            providerMethod.invoke(rsp)
        }.getOrNull()

        if (provider == null) return ProviderInfo(null, null, false)

        val name = providerNameFrom(provider)
        return ProviderInfo(provider, name, true)
    }

    private fun providerNameFrom(provider: Any): String? {
        return runCatching {
            provider.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(provider)
                ?.toString()
        }.getOrNull() ?: provider.javaClass.name
    }

    // Any Vault economy provider is accepted.
}

