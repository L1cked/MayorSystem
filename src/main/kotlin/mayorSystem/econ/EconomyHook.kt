package mayorSystem.econ

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class EconomyHook(private val plugin: Plugin) {

    // Hold as Any? so Vault classes are never required to load this class
    private val economyProvider: Any? by lazy { findEconomyProvider() }

    fun isAvailable(): Boolean = economyProvider != null

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

    /**
     * Vault providers commonly implement `has`/`withdrawPlayer` using OfflinePlayer,
     * but some older providers still expose Player or String-based overloads.
     *
     * EzEconomy (via Vault) works through the OfflinePlayer overload.
     */
    private fun invokeHas(econ: Any, player: Player, amount: Double): Boolean {
        val amountType = Double::class.javaPrimitiveType

        // Preferred: has(OfflinePlayer, double)
        econ.javaClass.methods.firstOrNull { m ->
            m.name == "has" && m.parameterTypes.contentEquals(arrayOf(OfflinePlayer::class.java, amountType))
        }?.let { m ->
            return m.invoke(econ, player as OfflinePlayer, amount) as Boolean
        }

        // Fallback: has(Player, double)
        econ.javaClass.methods.firstOrNull { m ->
            m.name == "has" && m.parameterTypes.contentEquals(arrayOf(Player::class.java, amountType))
        }?.let { m ->
            return m.invoke(econ, player, amount) as Boolean
        }

        // Fallback: has(String, double)
        econ.javaClass.methods.firstOrNull { m ->
            m.name == "has" && m.parameterTypes.contentEquals(arrayOf(String::class.java, amountType))
        }?.let { m ->
            return m.invoke(econ, player.name, amount) as Boolean
        }

        return false
    }

    private fun invokeWithdraw(econ: Any, player: Player, amount: Double): Boolean {
        val amountType = Double::class.javaPrimitiveType

        // Preferred: withdrawPlayer(OfflinePlayer, double)
        econ.javaClass.methods.firstOrNull { m ->
            m.name == "withdrawPlayer" && m.parameterTypes.contentEquals(arrayOf(OfflinePlayer::class.java, amountType))
        }?.let { m ->
            val resp = m.invoke(econ, player as OfflinePlayer, amount)
            return responseSuccess(resp)
        }

        // Fallback: withdrawPlayer(Player, double)
        econ.javaClass.methods.firstOrNull { m ->
            m.name == "withdrawPlayer" && m.parameterTypes.contentEquals(arrayOf(Player::class.java, amountType))
        }?.let { m ->
            val resp = m.invoke(econ, player, amount)
            return responseSuccess(resp)
        }

        // Fallback: withdrawPlayer(String, double)
        econ.javaClass.methods.firstOrNull { m ->
            m.name == "withdrawPlayer" && m.parameterTypes.contentEquals(arrayOf(String::class.java, amountType))
        }?.let { m ->
            val resp = m.invoke(econ, player.name, amount)
            return responseSuccess(resp)
        }

        return false
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

    private fun findEconomyProvider(): Any? {
        // If Vault plugin isn't present, bail out safely
        val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return null
        if (!vault.isEnabled) return null

        // Use reflection so we never link Vault API at class load time
        return runCatching {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val sm = Bukkit.getServicesManager()
            val getReg = sm.javaClass.getMethod("getRegistration", Class::class.java)
            val rsp = getReg.invoke(sm, economyClass) ?: return null
            val providerMethod = rsp.javaClass.getMethod("getProvider")
            providerMethod.invoke(rsp)
        }.getOrNull()
    }
}
