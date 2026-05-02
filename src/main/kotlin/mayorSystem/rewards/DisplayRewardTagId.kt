package mayorSystem.rewards

object DisplayRewardTagId {
    private val pattern = Regex("^[A-Za-z0-9_-]{1,32}$")

    fun isValid(raw: String): Boolean = pattern.matches(raw)
}
