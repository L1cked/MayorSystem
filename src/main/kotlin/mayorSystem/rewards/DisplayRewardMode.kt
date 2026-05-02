package mayorSystem.rewards

enum class DisplayRewardMode {
    RANK,
    TAG,
    BOTH;

    fun includesRank(): Boolean = this == RANK || this == BOTH

    fun includesTag(): Boolean = this == TAG || this == BOTH

    fun label(): String = when (this) {
        RANK -> "Rank"
        TAG -> "Tag"
        BOTH -> "Rank + Tag"
    }

    fun next(): DisplayRewardMode = when (this) {
        RANK -> TAG
        TAG -> BOTH
        BOTH -> RANK
    }

    companion object {
        fun parse(raw: String?): DisplayRewardMode? {
            val normalized = raw
                ?.trim()
                ?.uppercase()
                ?.replace('-', '_')
                ?: return null
            return entries.firstOrNull { it.name == normalized }
        }

        fun parseOrDefault(raw: String?, default: DisplayRewardMode = RANK): DisplayRewardMode =
            parse(raw) ?: default
    }
}
