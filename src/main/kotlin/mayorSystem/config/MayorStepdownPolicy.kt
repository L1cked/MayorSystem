package mayorSystem.config

enum class MayorStepdownPolicy {
    OFF,
    NO_MAYOR,
    KEEP_MAYOR;

    fun next(): MayorStepdownPolicy {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }
}

