package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.data.RequestStatus
import mayorSystem.ux.MayorBroadcasts
import mayorSystem.econ.SellCategoryIndex
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.persistence.PersistentDataType
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

    private fun normalizeSectionId(sectionId: String): String =
        if (sectionId.equals("__custom__", true)) "custom" else sectionId

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
        Bukkit.getOnlinePlayers().forEach { p -> applyActiveEffects(p) }
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
        for (p in players) applyActiveEffects(p, force = true)
        return players.size
    }

    /** Forces a re-application of currently active perk potion effects for a single player. */
    fun refreshPlayer(player: Player) {
        applyActiveEffects(player, force = true)
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
            val allowed = appliesTo == null || appliesTo == "ALL" || (categoryNorm != null && appliesTo == categoryNorm)
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
        return (pm.getPlugin("ShopGUIPlus")?.isEnabled == true)
            || (pm.getPlugin("EconomyShopGUI")?.isEnabled == true)
            || (pm.getPlugin("EconomyShopGUI-Premium")?.isEnabled == true)
            || (pm.getPlugin("DSellSystem")?.isEnabled == true)
    }

    fun canEnableSellCategory(appliesTo: String?): Boolean {
        val applies = appliesTo?.uppercase() ?: return true
        if (applies == "ALL") return true
        return isSellPluginAvailable()
    }

    fun enforceSellCategoryPerkAvailability(): Int {
        if (isSellPluginAvailable()) return 0
        val sec = plugin.config.getConfigurationSection("perks.sections") ?: return 0
        var disabled = 0
        for (sectionId in sec.getKeys(false)) {
            val base = "perks.sections.$sectionId.perks"
            val perksSec = plugin.config.getConfigurationSection(base) ?: continue
            for (perkId in perksSec.getKeys(false)) {
                val pBase = "$base.$perkId"
                val hasMultiplier = plugin.config.contains("$pBase.sell_multiplier")
                val appliesTo = plugin.config.getString("$pBase.applies_to")?.uppercase()
                if (!hasMultiplier) continue
                if (appliesTo == null || appliesTo == "ALL") continue
                val enabled = plugin.config.getBoolean("$pBase.enabled", true)
                if (enabled) {
                    plugin.config.set("$pBase.enabled", false)
                    disabled++
                }
            }
        }
        if (disabled > 0) {
            plugin.saveConfig()
        }
        return disabled
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
    }

    fun applyPerks(term: Int, suppressSayBroadcast: Boolean = false) {
        if (!plugin.config.getBoolean("perks.enabled", true)) return

        // Make sure we don't keep any old tracked effects around (e.g., after a forced election).
        clearAllGlobalEffects()
        invalidateSellMultiplierCache()

        val mayor = plugin.store.winner(term) ?: return
        val chosen = plugin.store.chosenPerks(term, mayor)

        val preset = presetPerks()
        val sellSayLines = mutableListOf<String>()

        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = plugin.store.listRequests(term).firstOrNull { it.id == reqId } ?: continue
                if (req.status != RequestStatus.APPROVED) continue
                runCommands(req.onStart, suppressSayBroadcast)
                continue
            }

            val def = preset[perkId] ?: continue
            if (def.sellMultiplier != null) {
                for (cmd in def.onStart) {
                    val lines = extractSayLines(cmd)
                    if (lines != null) {
                        sellSayLines += lines
                    } else {
                        runCommands(listOf(cmd), suppressSayBroadcast)
                    }
                }
                continue
            }

            runCommands(def.onStart, suppressSayBroadcast)
        }

        if (sellSayLines.isNotEmpty() && !suppressSayBroadcast) {
            MayorBroadcasts.broadcastChat(sellSayLines)
        }
    }

    fun clearPerks(term: Int) {
        if (!plugin.config.getBoolean("perks.enabled", true)) return
        invalidateSellMultiplierCache()

        val mayor = plugin.store.winner(term) ?: return
        val chosen = plugin.store.chosenPerks(term, mayor)

        val preset = presetPerks()

        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = plugin.store.listRequests(term).firstOrNull { it.id == reqId } ?: continue
                if (req.status != RequestStatus.APPROVED) continue
                runCommands(req.onEnd)
                continue
            }

            val def = preset[perkId] ?: continue
            runCommands(def.onEnd)
        }

        // Safety: if a perk forgot to include an onEnd effect clear, don't leave leftovers.
        clearAllGlobalEffects()
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

        for (perkId in chosen) {
            val commands = if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
                val req = plugin.store.listRequests(term)
                    .firstOrNull { it.id == reqId && it.status == RequestStatus.APPROVED }
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

    private fun extractSayLines(cmd: String): List<String>? {
        if (!cmd.startsWith("say ", ignoreCase = true)) return null
        var msg = cmd.substringAfter("say ").trim()
        if (msg.isBlank()) return emptyList()

        if ((msg.startsWith('"') && msg.endsWith('"') && msg.length >= 2) ||
            (msg.startsWith('\'') && msg.endsWith('\'') && msg.length >= 2)
        ) {
            msg = msg.substring(1, msg.length - 1)
        }

        val lines = msg.replace("\\n", "\n").split('\n')
        return lines.filter { it.isNotBlank() }
    }

    private fun runCommands(cmds: List<String>, suppressSayBroadcast: Boolean = false) {
        for (cmd in cmds) {
            val trimmed = cmd.trim()
            if (trimmed.isBlank()) continue

            // Avoid Brigadier limits and give us join-sync for effect perks.
            if (handlePotionEffectCommand(trimmed)) continue

            // "say ..." does NOT parse MiniMessage, so default configs like:
            //   say <gold>The Mayor declared...</gold>
            // would show raw tags in chat. We intercept it and broadcast properly.
            if (handleFormattedSayBroadcast(trimmed, suppressSayBroadcast)) continue

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), trimmed)
            } catch (t: Throwable) {
                plugin.logger.warning("Command failed (ignored): $trimmed")
                plugin.logger.warning("Reason: ${t::class.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Intercept console "say" from perk commands and broadcast it with MayorSystem formatting.
     *
     * - Supports MiniMessage (<gold> etc) and legacy (& / §) via [MayorBroadcasts].
     * - Applies PlaceholderAPI per-player if installed.
     */
    private fun handleFormattedSayBroadcast(cmd: String, suppress: Boolean): Boolean {
        if (!cmd.startsWith("say ", ignoreCase = true)) return false

        var msg = cmd.substringAfter("say ").trim()
        if (msg.isBlank()) return true

        // Trim surrounding quotes for nicer config ergonomics: say "..."
        if ((msg.startsWith('"') && msg.endsWith('"') && msg.length >= 2) ||
            (msg.startsWith('\'') && msg.endsWith('\'') && msg.length >= 2)
        ) {
            msg = msg.substring(1, msg.length - 1)
        }

        if (suppress) return true

        // Allow explicit multi-line broadcasts: "line1\\nline2"
        val lines = msg.replace("\\\\n", "\n").split('\n')
        MayorBroadcasts.broadcastChat(lines)
        return true
    }
}
