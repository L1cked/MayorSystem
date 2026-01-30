package mayorSystem.econ

import java.util.UUID

interface MayorSellCallback {
    fun onSellPayout(snapshot: DSellPayoutSnapshot)
}

data class DSellPayoutSnapshot(
    val playerId: UUID,
    val paidByCategory: DoubleArray, // size 10: 0..8 categories, 9 = total
    val timestampMillis: Long,
    val transactionId: String? = null
)

object SellCategoryIndex {
    const val CROPS = 0
    const val ORES = 1
    const val MOBS = 2
    const val NATURAL = 3
    const val ARMOR_TOOLS = 4
    const val FISH = 5
    const val BOOK = 6
    const val POTIONS = 7
    const val BLOCKS = 8
    const val TOTAL = 9
    const val SIZE = 10
}
