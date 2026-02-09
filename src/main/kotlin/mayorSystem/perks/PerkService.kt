package mayorSystem.perks

import mayorSystem.MayorPlugin
import mayorSystem.api.events.MayorPerksAppliedEvent
import mayorSystem.api.events.MayorPerksClearedEvent
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import mayorSystem.messaging.MayorBroadcasts
import mayorSystem.economy.SellCategoryIndex
import mayorSystem.config.SystemGateOption
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayDeque
import java.util.UUID

data class PerkDef(
    val id: String,
    val displayNameMm: String,
    val loreMm: List<String>,
    val icon: Material,
    val onStart: List<String>,
    val onEnd: List<String>,
    val sectionId: String,
    val sellMultiplier: Double? = null,
    val appliesTo: String? = null
)

class PerkService(private val plugin: MayorPlugin) {

    private var presetCache: Map<String, PerkDef>? = null
    private val displayNameCacheByTerm: MutableMap<Int, Pair<Long, Map<String, String>>> = mutableMapOf()
    private var sellMultiplierCache: Pair<Int, DoubleArray>? = null
    private var sellMultiplierHasPerkCache: Pair<Int, Boolean>? = null

    fun reloadFromConfig() {
        presetCache = null
        displayNameCacheByTerm.clear()
        invalidateSellMultiplierCache()
    }

    private fun normalizeSectionId(sectionId: String): String =
        if (sectionId.equals("__custom__", true)) "custom" else sectionId

    private fun normalizePluginName(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    fun orderedSectionIds(ids: Collection<String>): List<String> {
        val priority = mapOf(
            "skyblock_style" to 0,
            "public_perks" to 1
        )
        return ids.sortedWith(
            compareBy<String> { priority[it] ?: 5 }.thenBy { it.lowercase() }
        )
    }

    fun isSkyblockStyleAddonAvailable(): Boolean =
        skyblockStyleAddonName() != null

    fun skyblockStyleAddonName(): String? {
        val pm = plugin.server.pluginManager
        val names = listOf(
            "SystemSkyblockStyleAddon",
            "SystemSkyblockStyleSystem",
            "SystemSkyblockStyle"
        )
        for (name in names) {
            val p = pm.getPlugin(name) ?: continue
            if (p.isEnabled) return p.name
        }

        val normalizedTargets = setOf(
            "systemskyblockstyleaddon",
            "systemskyblockstylesystem",
            "systemskyblockstyle"
        )
        for (p in pm.plugins) {
            if (!p.isEnabled) continue
            val norm = normalizePluginName(p.name)
            if (norm in normalizedTargets || norm.contains("skyblockstyle")) {
                return p.name
            }
        }
        return null
    }

    fun isPerkSectionAvailable(sectionId: String): Boolean {
        val key = normalizeSectionId(sectionId).lowercase()
        if (key == SKYBLOCK_SECTION_ID) {
            return isSkyblockStyleAddonAvailable()
        }
        return true
    }

    fun perkSectionBlockReason(sectionId: String): String? {
        val key = normalizeSectionId(sectionId).lowercase()
        if (key == SKYBLOCK_SECTION_ID && !isSkyblockStyleAddonAvailable()) {
            return "Requires SystemSkyblockStyleAddon. Install it to enable."
        }
        return null
    }

    fun sectionPickLimit(sectionId: String): Int? {
        val key = normalizeSectionId(sectionId)
        val raw = plugin.config.getInt("perks.sections.$key.pick_limit", 0)
        return if (raw <= 0) null else raw
    }

    fun sectionIdForPerk(perkId: String): String? {
        if (perkId.startsWith("custom:", ignoreCase = true)) return "custom"
        return presetPerks()[perkId]?.sectionId
    }

    fun countSelectedInSection(perks: Set<String>, sectionId: String): Int {
        val target = normalizeSectionId(sectionId).lowercase()
        return perks.count { perkId ->
            val sec = sectionIdForPerk(perkId) ?: return@count false
            normalizeSectionId(sec).lowercase() == target
        }
    }

    fun sectionLimitViolations(perks: Set<String>): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        for (perkId in perks) {
            val sec = sectionIdForPerk(perkId) ?: continue
            val key = normalizeSectionId(sec)
            counts[key] = (counts[key] ?: 0) + 1
        }

        val violations = mutableListOf<Pair<String, Int>>()
        for ((sec, count) in counts) {
            val limit = sectionPickLimit(sec) ?: continue
            if (count > limit) violations += (sec to limit)
        }
        return violations
    }

    /**
     * Active potion effects that are currently in force for the term.
     *
     * Why we track these:
     * - `/effect give` is hard-limited by Mojang...; huge durations (like 2147483647)
     *   crash when dispatched as a command.
     * - We still want "infinite" effects, and we also want players who re-join during the term
     *   to automatically get the effect.
     */
    private val activeGlobalEffects: LinkedHashMap<PotionEffectType, PotionEffect> = linkedMapOf()

    /**
     * Per-player cache of what we believe we have applied.
     *
     * Source of truth is the player's PersistentDataContainer so we can clean up
     * "infinite" effects even if the player was offline when the term ended.
     */
    private val appliedCache: MutableMap<UUID, Map<PotionEffectType, EffectSig>> = mutableMapOf()

    private val pdcKey = NamespacedKey(plugin, "perk_effects_v1")
    private var batchTaskId: Int = -1
    private val batchQueue: ArrayDeque<Player> = ArrayDeque()
    private val batchIds: MutableSet<UUID> = mutableSetOf()
    private var batchForce: Boolean = false

    private data class EffectSig(
        val amplifier: Int,
        val ambient: Boolean,
        val particles: Boolean,
        val icon: Boolean
    )

    private fun sigOf(effect: PotionEffect): EffectSig = EffectSig(
        amplifier = effect.amplifier,
        ambient = effect.isAmbient,
        particles = effect.hasParticles(),
        icon = effect.hasIcon()
    )

    private fun encode(map: Map<PotionEffectType, EffectSig>): String {
        // key,amp,ambient,particles,icon;key,amp,...
        return map.entries.joinToString(";") { (type, sig) ->
            val k = type.key.toString() // e.g., minecraft:speed
            "$k,${sig.amplifier},${sig.ambient},${sig.particles},${sig.icon}"
        }
    }

    private fun decode(raw: String?): Map<PotionEffectType, EffectSig> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<PotionEffectType, EffectSig>()
        for (part in raw.split(';')) {
            val p = part.trim()
            if (p.isBlank()) continue
            val bits = p.split(',')
            if (bits.size < 5) continue
            val keyStr = bits[0].trim()
            val key = runCatching { NamespacedKey.fromString(keyStr) }.getOrNull() ?: continue
            val type = Registry.EFFECT.get(key) ?: continue
            val amp = bits[1].trim().toIntOrNull() ?: continue
            val ambient = bits[2].trim().toBooleanStrictOrNull() ?: false
            val particles = bits[3].trim().toBooleanStrictOrNull() ?: false
            val icon = bits[4].trim().toBooleanStrictOrNull() ?: false
            out[type] = EffectSig(amp, ambient, particles, icon)
        }
        return out
    }

    private fun loadApplied(player: Player): Map<PotionEffectType, EffectSig> {
        // in-memory fast path
        appliedCache[player.uniqueId]?.let { return it }

        val raw = player.persistentDataContainer.get(pdcKey, PersistentDataType.STRING)
        val decoded = decode(raw)
        appliedCache[player.uniqueId] = decoded
        return decoded
    }

    private fun saveApplied(player: Player, map: Map<PotionEffectType, EffectSig>) {
        appliedCache[player.uniqueId] = map
        if (map.isEmpty()) {
            player.persistentDataContainer.remove(pdcKey)
        } else {
            player.persistentDataContainer.set(pdcKey, PersistentDataType.STRING, encode(map))
        }
    }

    fun cleanupPlayerCache(uuid: UUID) {
        appliedCache.remove(uuid)
    }

    /**
     * MiniMessage safety: never let user-provided text inject tags.
     * (We keep it simple: strip angle brackets.)
     */
    private fun mmSafe(input: String): String = input.replace("<", "").replace(">", "")

    private fun syncActiveEffectsToOnlinePlayers() {
        enqueueBatch(Bukkit.getOnlinePlayers().toList(), force = false)
    }

    private fun potionEffectType(effectId: String): PotionEffectType? {
        // Accept both "speed" and "minecraft:speed".
        val id = effectId.substringAfter("minecraft:").lowercase()
        return Registry.EFFECT.get(NamespacedKey.minecraft(id))
    }

    private fun activateGlobalEffect(effectId: String, amplifier: Int, hideParticles: Boolean) {
        val type = potionEffectType(effectId) ?: return

        // Bukkit API uses ticks and does not have the Brigadier 1_000_000 duration limit.
        // Int.MAX_VALUE ticks ~ 3.4 years. Good enough for "infinite" server terms.
        val durationTicks = Int.MAX_VALUE
        val particles = !hideParticles
        val icon = !hideParticles

        val effect = PotionEffect(type, durationTicks, amplifier.coerceAtLeast(0), false, particles, icon)
        activeGlobalEffects[type] = effect
        syncActiveEffectsToOnlinePlayers()
    }

    private fun deactivateGlobalEffect(effectId: String) {
        val type = potionEffectType(effectId) ?: return
        activeGlobalEffects.remove(type)
        Bukkit.getOnlinePlayers().forEach { p ->
            val prev = loadApplied(p)
            val sig = prev[type]
            val current = p.getPotionEffect(type)
            if (sig != null && current != null && sigOf(current) == sig) {
                p.removePotionEffect(type)
            }
            if (sig != null) saveApplied(p, prev - type)
        }
    }

    private fun clearAllGlobalEffects() {
        clearBatch()
        activeGlobalEffects.clear()

        // Remove only the effects we previously applied (tracked in player PDC).
        Bukkit.getOnlinePlayers().forEach { p ->
            val prev = loadApplied(p)
            for ((type, sig) in prev) {
                val current = p.getPotionEffect(type)
                if (current != null && sigOf(current) == sig) {
                    p.removePotionEffect(type)
                }
            }
            saveApplied(p, emptyMap())
        }
    }

    /**
     * True if this potion effect type is currently active via MayorSystem perks.
     */
    fun isActiveGlobalEffect(type: PotionEffectType): Boolean = activeGlobalEffects.containsKey(type)

    /**
     * Intercepts the two commands we care about so they don't crash:
     * - effect give @a minecraft:<id> <duration> <amp> <hideParticles>
     * - effect clear @a [minecraft:<id>]
     */
    private fun handlePotionEffectCommand(cmd: String): Boolean {
        val parts = cmd.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        if (!parts[0].equals("effect", ignoreCase = true)) return false
        if (parts.size < 3) return false

        val sub = parts[1].lowercase()
        val target = parts[2]
        if (target != "@a") return false // we only special-case global perks

        when (sub) {
            "give" -> {
                val effectId = parts.getOrNull(3) ?: return true
                val amplifier = parts.getOrNull(5)?.toIntOrNull() ?: 0
                val hideParticles = parts.getOrNull(6)?.toBooleanStrictOrNull() ?: false
                activateGlobalEffect(effectId, amplifier, hideParticles)
                return true
            }

            "clear" -> {
                val effectId = parts.getOrNull(3)
                if (effectId == null) clearAllGlobalEffects() else deactivateGlobalEffect(effectId)
                return true
            }
        }

        return false
    }

    /**
     * Apply the active global effects to a specific player.
     * Called on player join so offline players get the current term effects as soon as they connect.
     */
    fun applyActiveEffects(player: Player) {
        applyActiveEffects(player, force = false)
    }

    /**
     * Applies/removes MayorSystem potion effects using a diff against what we last applied.
     *
     * This is intentionally conservative:
     * - It won't spam re-application if nothing changed.
     * - It can clean up old "infinite" effects for players who were offline when a term ended.
     */
    fun applyActiveEffects(player: Player, force: Boolean) {
        val desired: Map<PotionEffectType, PotionEffect> = activeGlobalEffects.toMap()

        val prevApplied = loadApplied(player)

        // Legacy cleanup (pre-0.4.36): older versions didn't persist what they applied.
        // If a player comes online with a ridiculously long effect that we do NOT want anymore,
        // we assume it's a leftover MayorSystem "infinite" effect and remove it.
        // This is deliberately strict to avoid stomping normal potion effects.
        if (!player.persistentDataContainer.has(pdcKey, PersistentDataType.STRING) && desired.isEmpty()) {
            for (eff in player.activePotionEffects) {
                if (eff.duration >= LEGACY_INFINITE_THRESHOLD_TICKS) {
                    player.removePotionEffect(eff.type)
                }
            }
        }

        // 1) Remove effects we previously applied that are no longer desired.
        if (prevApplied.isNotEmpty()) {
            val toRemove = prevApplied.keys - desired.keys
            if (toRemove.isNotEmpty()) {
                for (type in toRemove) {
                    val sig = prevApplied[type] ?: continue
                    val current = player.getPotionEffect(type)
                    if (current != null && sigOf(current) == sig) {
                        player.removePotionEffect(type)
                    }
                }
            }
        }

        // 2) Ensure desired effects exist and match.
        var newApplied = prevApplied
        for ((type, effect) in desired) {
            val want = sigOf(effect)
            val current = player.getPotionEffect(type)

            val matches = current != null && sigOf(current) == want
            if (!force && matches) {
                // Already correct; track it.
                if (newApplied[type] != want) newApplied = newApplied + (type to want)
                continue
            }

            if (!matches) {
                // Replace only when necessary.
                // (We avoid using deprecated force=true; explicit replace is clearer.)
                player.removePotionEffect(type)
                player.addPotionEffect(effect)
            } else if (force) {
                // Force-refresh even if it matches (admin button use-case).
                player.addPotionEffect(effect)
            }

            newApplied = newApplied + (type to want)
        }

        // 3) Persist the applied set. (Only keep what is currently desired.)
        val normalized = newApplied.filterKeys { it in desired.keys }
        saveApplied(player, normalized)
    }

    /**
     * Forces a re-application of the currently active perk potion effects.
     * Returns number of online players refreshed.
     */
    fun refreshAllOnlinePlayers(): Int {
        val players = Bukkit.getOnlinePlayers().toList()
        enqueueBatch(players, force = true)
        return players.size
    }

    /** Forces a re-application of currently active perk potion effects for a single player. */
    fun refreshPlayer(player: Player) {
        applyActiveEffects(player, force = true)
    }

    private fun enqueueBatch(players: Collection<Player>, force: Boolean) {
        if (plugin.settings.isBlocked(SystemGateOption.PERKS)) return
        if (players.isEmpty()) return
        if (force) batchForce = true

        for (p in players) {
            if (batchIds.add(p.uniqueId)) {
                batchQueue.add(p)
            }
        }

        if (batchTaskId != -1) return

        val perTick = plugin.config.getInt("perks.refresh_batch_size", 20).coerceAtLeast(1)
        batchTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            if (!plugin.isEnabled) {
                clearBatch()
                return@Runnable
            }
            if (plugin.settings.isBlocked(SystemGateOption.PERKS)) {
                clearBatch()
                return@Runnable
            }

            var processed = 0
            while (processed < perTick && batchQueue.isNotEmpty()) {
                val p = batchQueue.removeFirst()
                batchIds.remove(p.uniqueId)
                if (p.isOnline) {
                    applyActiveEffects(p, force = batchForce)
                }
                processed++
            }

            if (batchQueue.isEmpty()) {
                batchForce = false
                cancelBatch()
            }
        }, 1L, 1L)
    }

    private fun cancelBatch() {
        if (batchTaskId != -1) {
            runCatching { Bukkit.getScheduler().cancelTask(batchTaskId) }
            batchTaskId = -1
        }
    }

    private fun clearBatch() {
        batchQueue.clear()
        batchIds.clear()
        batchForce = false
        cancelBatch()
    }

    /**
     * Computes the sell multiplier currently active for this term based on the elected mayor's chosen perks.
     *
     * Notes:
     * - This is intentionally generic: we don't depend on any specific /sell plugin API.
     * - How multipliers stack can be configured via `sell_bonus.stack_mode` (MAX or PRODUCT).
     */
    fun sellMultiplierForTerm(termIndex: Int): Double {
        return sellMultiplierForTerm(termIndex, null)
    }

    fun sellMultiplierForTerm(termIndex: Int, category: String?): Double {
        if (termIndex < 0) return 1.0
        val mayor = plugin.store.winner(termIndex) ?: return 1.0
        val chosen = plugin.store.chosenPerks(termIndex, mayor)
        if (chosen.isEmpty()) return 1.0

        val categoryNorm = category?.uppercase()
        val multipliers = chosen.mapNotNull { id ->
            val def = presetPerks()[id] ?: return@mapNotNull null
            val multiplier = def.sellMultiplier ?: return@mapNotNull null
            val appliesTo = def.appliesTo?.uppercase()
            val allowed = if (categoryNorm == null) {
                appliesTo == null || appliesTo == "ALL"
            } else {
                appliesTo != null && appliesTo == categoryNorm
            }
            if (!allowed) return@mapNotNull null
            multiplier
        }
            .filter { it.isFinite() && it > 1.0 }

        if (multipliers.isEmpty()) return 1.0

        val mode = plugin.config.getString("sell_bonus.stack_mode", "MAX") ?: "MAX"
        return if (mode.equals("PRODUCT", true)) {
            multipliers.fold(1.0) { acc, m -> acc * m }
        } else {
            multipliers.maxOrNull() ?: 1.0
        }
    }

    fun sellMultipliersForTermCached(termIndex: Int): DoubleArray {
        if (termIndex < 0) return DoubleArray(SellCategoryIndex.SIZE) { 1.0 }
        val cached = sellMultiplierCache
        if (cached != null && cached.first == termIndex) return cached.second
        val built = computeSellMultipliersForTerm(termIndex)
        sellMultiplierCache = termIndex to built
        sellMultiplierHasPerkCache = termIndex to built.any { it > 1.000001 }
        return built
    }

    fun sellPerksActiveForTermCached(termIndex: Int): Boolean {
        if (termIndex < 0) return false
        val cached = sellMultiplierHasPerkCache
        if (cached != null && cached.first == termIndex) return cached.second
        val built = computeSellMultipliersForTerm(termIndex)
        sellMultiplierCache = termIndex to built
        val active = built.any { it > 1.000001 }
        sellMultiplierHasPerkCache = termIndex to active
        return active
    }

    fun invalidateSellMultiplierCache() {
        sellMultiplierCache = null
        sellMultiplierHasPerkCache = null
    }

    private fun computeSellMultipliersForTerm(termIndex: Int): DoubleArray {
        val out = DoubleArray(SellCategoryIndex.SIZE) { 1.0 }
        if (termIndex < 0) return out
        val mayor = plugin.store.winner(termIndex) ?: return out
        val chosen = plugin.store.chosenPerks(termIndex, mayor)
        if (chosen.isEmpty()) return out

        val preset = presetPerks()
        val byCategory = mutableMapOf<String, MutableList<Double>>()
        val global = mutableListOf<Double>()

        for (id in chosen) {
            val def = preset[id] ?: continue
            val m = def.sellMultiplier ?: continue
            if (!m.isFinite() || m <= 1.0) continue
            val appliesTo = def.appliesTo?.uppercase()
            if (appliesTo == null || appliesTo == "ALL") {
                global += m
            } else {
                byCategory.getOrPut(appliesTo) { mutableListOf() } += m
            }
        }

        val mode = plugin.config.getString("sell_bonus.stack_mode", "MAX") ?: "MAX"
        val useProduct = mode.equals("PRODUCT", true)
        fun stack(list: List<Double>): Double {
            if (list.isEmpty()) return 1.0
            return if (useProduct) list.fold(1.0) { acc, m -> acc * m } else list.maxOrNull() ?: 1.0
        }

        for (i in 0 until SellCategoryIndex.TOTAL) {
            out[i] = stack(byCategory[i.toString()] ?: emptyList())
        }
        out[SellCategoryIndex.TOTAL] = stack(global)
        return out
    }

    fun isSellPluginAvailable(): Boolean {
        val pm = plugin.server.pluginManager
        return (pm.getPlugin("SystemSellAddon")?.isEnabled == true)
    }

    fun canEnableSellCategory(appliesTo: String?): Boolean {
        if (!isSellPluginAvailable()) return false
        return true
    }

    fun enforceSellCategoryPerkAvailability(): Int {
        if (isSellPluginAvailable()) return 0
        val sec = plugin.config.getConfigurationSection("perks.sections") ?: return 0
        var disabledSections = 0
        for (sectionId in sec.getKeys(false)) {
            val sectionBase = "perks.sections.$sectionId"
            val perksSec = plugin.config.getConfigurationSection("$sectionBase.perks") ?: continue
            val hasSellPerk = perksSec.getKeys(false).any { perkId ->
                plugin.config.contains("$sectionBase.perks.$perkId.sell_multiplier")
            }
            if (!hasSellPerk) continue
            val enabled = plugin.config.getBoolean("$sectionBase.enabled", true)
            if (enabled) {
                plugin.config.set("$sectionBase.enabled", false)
                disabledSections++
            }
        }
        if (disabledSections > 0) {
            plugin.saveConfig()
        }
        return disabledSections
    }

    fun enforceSkyblockStyleSectionAvailability(): Int {
        if (isSkyblockStyleAddonAvailable()) return 0

        val base = "perks.sections.$SKYBLOCK_SECTION_ID"
        if (!plugin.config.contains(base)) return 0

        val enabled = plugin.config.getBoolean("$base.enabled", true)
        if (!enabled) return 0

        plugin.config.set("$base.enabled", false)
        plugin.saveConfig()
        return 1
    }

    /**
     * Read all enabled perks from config:
     * perks.enabled
     * perks.sections.<section>.enabled
     * perks.sections.<section>.perks.<perk>.enabled
     */
    fun presetPerks(): Map<String, PerkDef> {
        val cached = presetCache
        if (cached != null) return cached

        val loaded = loadPresetPerks()
        presetCache = loaded
        return loaded
    }

    private fun loadPresetPerks(): Map<String, PerkDef> {
        if (!plugin.config.getBoolean("perks.enabled", true)) return emptyMap()

        val sec = plugin.config.getConfigurationSection("perks.sections") ?: return emptyMap()
        val out = linkedMapOf<String, PerkDef>()

        for (sectionId in sec.getKeys(false)) {
            val sectionBase = "perks.sections.$sectionId"
            if (!isPerkSectionAvailable(sectionId)) continue
            if (!plugin.config.getBoolean("$sectionBase.enabled", true)) continue

            val perksSec = plugin.config.getConfigurationSection("$sectionBase.perks") ?: continue
            for (perkId in perksSec.getKeys(false)) {
                val base = "$sectionBase.perks.$perkId"
                if (!plugin.config.getBoolean("$base.enabled", true)) continue

                val display = plugin.config.getString("$base.display_name") ?: "<white>$perkId</white>"
                val lore = plugin.config.getStringList("$base.lore")
                val iconKey = (plugin.config.getString("$base.icon")
                    ?: plugin.config.getString("$sectionBase.icon")
                    ?: "CHEST").uppercase()
                val iconMat = runCatching { Material.valueOf(iconKey) }.getOrDefault(Material.CHEST)
                val onStart = plugin.config.getStringList("$base.on_start")
                val onEnd = plugin.config.getStringList("$base.on_end")

                val sellMultiplier = if (plugin.config.contains("$base.sell_multiplier")) {
                    plugin.config.getDouble("$base.sell_multiplier")
                } else null
                val appliesTo = plugin.config.getString("$base.applies_to")?.uppercase()

                out[perkId] = PerkDef(
                    id = perkId,
                    displayNameMm = display,
                    loreMm = lore,
                    icon = iconMat,
                    onStart = onStart,
                    onEnd = onEnd,
                    sectionId = sectionId,
                    sellMultiplier = sellMultiplier,
                    appliesTo = appliesTo
                )
            }
        }

        return out
    }

    /**
     * Returns the perk list a specific candidate can pick from.
     *
     * This is where we merge:
     * - preset perks from config.yml
     * - approved custom perk requests for THIS candidate (as virtual perks with id `custom:<id>`)
     */
    fun availablePerksForCandidate(term: Int, uuid: java.util.UUID): List<PerkDef> {
        val out = presetPerks().values.toMutableList()

        // Approved custom perks for this candidate only.
        // These are shown as perks so they can be picked like normal ones.
        val approved = plugin.store.listRequests(term, RequestStatus.APPROVED)
            .filter { it.candidate == uuid }

        for (req in approved) {
            out += PerkDef(
                id = "custom:${req.id}",
                displayNameMm = "<gold>${mmSafe(req.title)}</gold>",
                loreMm = listOf(
                    "<gray>Custom perk (approved).</gray>",
                    "<dark_gray>${mmSafe(req.description)}</dark_gray>",
                    "",
                    "<gray>Chosen perks will apply to <white>everyone</white> during your term.</gray>"
                ),
                icon = Material.WRITABLE_BOOK,
                onStart = req.onStart,
                onEnd = req.onEnd,
                sectionId = "custom"
            )
        }

        // Deterministic ordering: sections first, then name.
        return out.sortedWith(
            compareBy<PerkDef> { it.sectionId.lowercase() }
                .thenBy { it.displayNameMm.lowercase() }
                .thenBy { it.id.lowercase() }
        )
    }

    fun displayNameFor(term: Int, perkId: String): String {
        val cache = displayNameCache(term)
        return cache[perkId] ?: "<white>$perkId</white>"
    }

    private fun displayNameCache(term: Int): Map<String, String> {
        val now = System.currentTimeMillis()
        val cached = displayNameCacheByTerm[term]
        if (cached != null && now - cached.first < DISPLAY_NAME_TTL_MS) return cached.second

        val out = linkedMapOf<String, String>()

        // Preset perks from config (cached by PerkService instance).
        for ((id, def) in presetPerks()) {
            out[id] = def.displayNameMm
        }

        // Custom requests can change without a full reload (admin approvals), so this cache is short-lived.
        for (req in plugin.store.listRequests(term)) {
            out["custom:${req.id}"] = "<gold>${mmSafe(req.title)}</gold>"
        }

        displayNameCacheByTerm[term] = now to out
        return out
    }

    private companion object {
        private const val DISPLAY_NAME_TTL_MS: Long = 10_000L
        private const val LEGACY_INFINITE_THRESHOLD_TICKS: Int = 20 * 60 * 60 * 24 * 7 // 7 days
        private const val SKYBLOCK_SECTION_ID: String = "skyblock_style"
    }

    private fun computeAppliedPerkIds(
        chosen: Set<String>,
        preset: Map<String, PerkDef>,
        requestsById: Map<Int, CustomPerkRequest>
    ): Set<String> {
        if (chosen.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = requestsById[reqId] ?: continue
                if (req.status != RequestStatus.APPROVED) continue
                out += "custom:$reqId"
                continue
            }
            if (preset.containsKey(perkId)) {
                out += perkId
            }
        }
        return out
    }

    fun applyPerks(term: Int, suppressSayBroadcast: Boolean = false) {
        if (!plugin.config.getBoolean("perks.enabled", true)) return

        // Make sure we don't keep any old tracked effects around (e.g., after a forced election).
        clearAllGlobalEffects()
        invalidateSellMultiplierCache()

        val mayor = plugin.store.winner(term) ?: return
        val chosen = plugin.store.chosenPerks(term, mayor)

        val preset = presetPerks()

        val requestsById = plugin.store.listRequests(term).associateBy { it.id }
        val appliedPerkIds = computeAppliedPerkIds(chosen, preset, requestsById)

        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = requestsById[reqId] ?: continue
                if (req.status != RequestStatus.APPROVED) continue
                runCommands(req.onStart, suppressSayBroadcast = true)
                continue
            }

            val def = preset[perkId] ?: continue
            runCommands(def.onStart, suppressSayBroadcast = true)
        }

        if (!suppressSayBroadcast) {
            val announceLines = buildPerkAnnouncementLines(term, appliedPerkIds)
            if (announceLines.isNotEmpty()) {
                MayorBroadcasts.broadcastChat(announceLines)
            }
        }

        Bukkit.getPluginManager().callEvent(
            MayorPerksAppliedEvent(term, mayor, appliedPerkIds)
        )
    }

    fun clearPerks(term: Int) {
        if (!plugin.config.getBoolean("perks.enabled", true)) return
        invalidateSellMultiplierCache()

        val mayor = plugin.store.winner(term) ?: return
        val chosen = plugin.store.chosenPerks(term, mayor)

        val preset = presetPerks()

        val requestsById = plugin.store.listRequests(term).associateBy { it.id }
        val clearedPerkIds = computeAppliedPerkIds(chosen, preset, requestsById)

        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = requestsById[reqId] ?: continue
                if (req.status != RequestStatus.APPROVED) continue
                runCommands(req.onEnd, suppressSayBroadcast = true)
                continue
            }

            val def = preset[perkId] ?: continue
            runCommands(def.onEnd, suppressSayBroadcast = true)
        }

        // Safety: if a perk forgot to include an onEnd effect clear, don't leave leftovers.
        clearAllGlobalEffects()

        Bukkit.getPluginManager().callEvent(
            MayorPerksClearedEvent(term, mayor, clearedPerkIds)
        )
    }

    private fun buildPerkAnnouncementLines(term: Int, perkIds: Set<String>): List<String> {
        if (perkIds.isEmpty()) return emptyList()
        val ordered = perkIds
            .map { id -> id to displayNameFor(term, id) }
            .sortedBy { (_, name) -> mmSafe(name).lowercase() }

        val lines = ArrayList<String>(ordered.size + 1)
        lines += "<gold>Mayor has declared:</gold>"
        for ((_, name) in ordered) {
            lines += "<gray>-</gray> $name"
        }
        return lines
    }

    /**
     * Rebuilds ONLY the active potion effects for the current term.
     *
     * This is used on reloads / restarts to keep MayorSystem effects persistent
     * without re-running other perk commands.
     */
    fun rebuildActiveEffectsForTerm(term: Int) {
        // If the system is disabled or term hasn't started, ensure effects are cleared.
        if (!plugin.config.getBoolean("perks.enabled", true) || term < 0) {
            clearAllGlobalEffects()
            return
        }

        clearAllGlobalEffects()

        val mayor = plugin.store.winner(term) ?: return
        val chosen = plugin.store.chosenPerks(term, mayor)
        if (chosen.isEmpty()) return

        val preset = presetPerks()

        val approvedById = plugin.store.listRequests(term, RequestStatus.APPROVED).associateBy { it.id }

        for (perkId in chosen) {
            val commands = if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = approvedById[reqId]
                req?.onStart ?: emptyList()
            } else {
                preset[perkId]?.onStart ?: emptyList()
            }

            for (cmd in commands) {
                handlePotionEffectCommand(cmd)
            }
        }

        syncActiveEffectsToOnlinePlayers()
    }

    private fun runCommands(cmds: List<String>, suppressSayBroadcast: Boolean = false) {
        for (cmd in cmds) {
            val trimmed = cmd.trim()
            if (trimmed.isBlank()) continue

            // Avoid Brigadier limits and give us join-sync for effect perks.
            if (handlePotionEffectCommand(trimmed)) continue

            if (suppressSayBroadcast && trimmed.startsWith("say ", ignoreCase = true)) {
                continue
            }

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), trimmed)
            } catch (t: Throwable) {
                plugin.logger.warning("Command failed (ignored): $trimmed")
                plugin.logger.warning("Reason: ${t::class.simpleName}: ${t.message}")
            }
        }
    }
}

