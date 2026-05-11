package mayorSystem.integration.economy

import mayorSystem.economy.EconomyHook
import org.bukkit.entity.Player

class VaultEconomyProvider(private val hook: EconomyHook) : EconomyProvider {
    override val available: Boolean
        get() = hook.isAvailable()

    override val providerName: String?
        get() = hook.providerName()

    override fun refresh() {
        hook.refresh()
    }

    override fun has(player: Player, amount: Double): Boolean =
        hook.has(player, amount)

    override fun withdraw(player: Player, amount: Double): EconomyTransactionResult =
        hook.withdraw(player, amount).toResult("Economy withdrawal failed.")

    override fun deposit(player: Player, amount: Double): EconomyTransactionResult =
        hook.deposit(player, amount).toResult("Economy deposit failed.")

    override fun balance(player: Player): Double? =
        hook.balance(player)

    private fun Boolean.toResult(failureMessage: String): EconomyTransactionResult =
        EconomyTransactionResult(success = this, message = failureMessage.takeUnless { this })
}
