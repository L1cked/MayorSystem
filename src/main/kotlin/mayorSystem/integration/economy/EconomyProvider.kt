package mayorSystem.integration.economy

import org.bukkit.entity.Player

interface EconomyProvider {
    val available: Boolean
    val providerName: String?

    fun refresh()
    fun has(player: Player, amount: Double): Boolean
    fun withdraw(player: Player, amount: Double): EconomyTransactionResult
    fun deposit(player: Player, amount: Double): EconomyTransactionResult
    fun balance(player: Player): Double?
}

data class EconomyTransactionResult(
    val success: Boolean,
    val message: String? = null
)

object NoopEconomyProvider : EconomyProvider {
    override val available: Boolean = false
    override val providerName: String? = null

    override fun refresh() = Unit
    override fun has(player: Player, amount: Double): Boolean = amount <= 0.0
    override fun withdraw(player: Player, amount: Double): EconomyTransactionResult =
        EconomyTransactionResult(success = amount <= 0.0, message = if (amount > 0.0) "Economy provider unavailable." else null)

    override fun deposit(player: Player, amount: Double): EconomyTransactionResult =
        EconomyTransactionResult(success = amount <= 0.0, message = if (amount > 0.0) "Economy provider unavailable." else null)

    override fun balance(player: Player): Double? = null
}

