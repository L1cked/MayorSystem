package mayorSystem.perks

import mayorSystem.MayorPlugin
import mayorSystem.api.events.MayorPerksAppliedEvent
import mayorSystem.api.events.MayorPerksClearedEvent
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import mayorSystem.messaging.MayorBroadcasts
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
import java.lang.reflect.Method

data class PerkDef(
    val id: String,
    val displayNameMm: String,
    val loreMm: List<String>,
    val adminLoreMm: List<String> = emptyList(),
    val icon: Material,
    val onStart: List<String>,
    val onEnd: List<String>,
    val sectionId: String,
    val origin: PerkOrigin,
    val enabled: Boolean = true
)

enum class PerkOrigin {
    INTERNAL,
    EXTERNAL
}

class PerkService(private val plugin: MayorPlugin) {
    @Volatile private var papiSetPlaceholders: Method? = null
    private val warnedBlockedCommandRoots: MutableSet<String> = mutableSetOf()

    fun reloadFromConfig() {
        // No cached perk definitions; read config on demand.
    }

    fun resolveText(player: Player?, raw: String): String {
        val m = papiSetPlaceholders ?: loadPapiMethod() ?: return raw
        val p = player ?: plugin.server.onlinePlayers.firstOrNull() ?: return raw
        return runCatching { m.invoke(null, p, raw) as? String }.getOrNull() ?: raw
    }

    fun resolveLore(player: Player?, lore: List<String>): List<String> =
        if (lore.isEmpty()) lore else lore.map { resolveText(player, it) }

    private fun loadPapiMethod(): Method? {
        if (plugin.server.pluginManager.getPlugin("PlaceholderAPI")?.isEnabled != true) return null
        if (papiSetPlaceholders != null) return papiSetPlaceholders
        val method = runCatching {
            val cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
            runCatching {
                cls.getMethod("setPlaceholders", Player::class.java, String::class.java)
            }.getOrElse {
                cls.getMethod("setPlaceholders", org.bukkit.OfflinePlayer::class.java, String::class.java)
            }
        }.getOrNull()
        if (method != null) {
            papiSetPlaceholders = method
        }
        return method
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

    fun isSellAddonAvailable(): Boolean {
        val pm = plugin.server.pluginManager
        val sell = pm.getPlugin("SystemSellAddon") ?: return false
        return sell.isEnabled
    }

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
        return when (key) {
            "economy" -> isSellAddonAvailable()
            SKYBLOCK_SECTION_ID -> isSkyblockStyleAddonAvailable()
            else -> true
        }
    }

    fun perkSectionBlockReason(sectionId: String): String? {
        val key = normalizeSectionId(sectionId).lowercase()
        return when (key) {
            "economy" -> if (!isSellAddonAvailable()) "Requires SystemSellAddon. Install it to enable." else null
            SKYBLOCK_SECTION_ID -> if (!isSkyblockStyleAddonAvailable()) "Requires SystemSkyblockStyleAddon. Install it to enable." else null
            else -> null
        }
    }

    fun sectionEmptyReason(sectionId: String): String? {
        val key = normalizeSectionId(sectionId)
        val perksSec = plugin.config.getConfigurationSection("perks.sections.$key.perks")
        return if (perksSec == null || perksSec.getKeys(false).isEmpty()) {
            "No perks configured for this section."
        } else {
            null
        }
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
     * Read all enabled perks from config:
     * perks.enabled
     * perks.sections.<section>.enabled
     * perks.sections.<section>.perks.<perk>.enabled
     */
    fun presetPerks(): Map<String, PerkDef> {
        return loadPresetPerks(includeDisabled = false)
    }

    private fun presetPerksAll(): Map<String, PerkDef> {
        return loadPresetPerks(includeDisabled = true)
    }

    private fun loadPresetPerks(includeDisabled: Boolean): Map<String, PerkDef> {
        if (!plugin.config.getBoolean("perks.enabled", true)) return emptyMap()

        val sec = plugin.config.getConfigurationSection("perks.sections") ?: return emptyMap()
        val out = linkedMapOf<String, PerkDef>()

        for (sectionId in sec.getKeys(false)) {
            val sectionBase = "perks.sections.$sectionId"
            if (!isPerkSectionAvailable(sectionId)) continue
            if (!plugin.config.getBoolean("$sectionBase.enabled", true)) continue

            val perksSec = plugin.config.getConfigurationSection("$sectionBase.perks") ?: continue
            if (perksSec.getKeys(false).isEmpty()) continue
            for (perkId in perksSec.getKeys(false)) {
                val base = "$sectionBase.perks.$perkId"
                val enabled = plugin.config.getBoolean("$base.enabled", true)
                if (!includeDisabled && !enabled) continue

                val display = plugin.config.getString("$base.display_name") ?: "<white>$perkId</white>"
                val lore = plugin.config.getStringList("$base.lore")
                val adminLore = plugin.config.getStringList("$base.admin_lore")
                val iconKey = (plugin.config.getString("$base.icon")
                    ?: plugin.config.getString("$sectionBase.icon")
                    ?: "CHEST").uppercase()
                val iconMat = runCatching { Material.valueOf(iconKey) }.getOrDefault(Material.CHEST)
                val onStart = plugin.config.getStringList("$base.on_start")
                val onEnd = plugin.config.getStringList("$base.on_end")

                out[perkId] = PerkDef(
                    id = perkId,
                    displayNameMm = display,
                    loreMm = lore,
                    adminLoreMm = adminLore,
                    icon = iconMat,
                    onStart = onStart,
                    onEnd = onEnd,
                    sectionId = sectionId,
                    origin = PerkOrigin.INTERNAL,
                    enabled = enabled
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
                adminLoreMm = emptyList(),
                icon = Material.WRITABLE_BOOK,
                onStart = req.onStart,
                onEnd = req.onEnd,
                sectionId = "custom",
                origin = PerkOrigin.INTERNAL,
                enabled = true
            )
        }

        // Deterministic ordering: sections first, then name.
        return out.sortedWith(
            compareBy<PerkDef> { it.sectionId.lowercase() }
                .thenBy { it.displayNameMm.lowercase() }
                .thenBy { it.id.lowercase() }
        )
    }

    fun perksForSection(sectionId: String, includeDisabled: Boolean): List<PerkDef> {
        val target = normalizeSectionId(sectionId).lowercase()
        val source = if (includeDisabled) presetPerksAll() else presetPerks()
        return source.values
            .filter { normalizeSectionId(it.sectionId).lowercase() == target }
            .sortedBy { it.displayNameMm.lowercase() }
    }

    fun displayNameFor(term: Int, perkId: String): String {
        if (perkId.startsWith("custom:", ignoreCase = true)) {
            val reqId = perkId.substringAfter("custom:").toIntOrNull()
            if (reqId != null) {
                val req = plugin.store.listRequests(term).firstOrNull { it.id == reqId }
                if (req != null) return "<gold>${mmSafe(req.title)}</gold>"
            }
            return "<white>$perkId</white>"
        }
        return presetPerks()[perkId]?.displayNameMm ?: "<white>$perkId</white>"
    }

    fun displayNameFor(term: Int, perkId: String, viewer: Player?): String =
        resolveText(viewer, displayNameFor(term, perkId))

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
            .map { id -> id to displayNameFor(term, id, null) }
            .sortedBy { (_, name) -> mmSafe(name).lowercase() }

        val lines = ArrayList<String>(ordered.size + 1)
        lines += "<gold>${plugin.settings.titleName} has declared:</gold>"
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
        val consoleCommandsEnabled = plugin.config.getBoolean("perks.command_execution.enable_console_commands", true)
        val allowedDangerousRoots = plugin.config.getStringList("perks.command_execution.allow_roots")
            .mapNotNull { commandRoot(it) }
            .toSet()
        for (cmd in cmds) {
            val trimmed = cmd.trim()
            if (trimmed.isBlank()) continue

            // Avoid Brigadier limits and give us join-sync for effect perks.
            if (handlePotionEffectCommand(trimmed)) continue

            if (suppressSayBroadcast && trimmed.startsWith("say ", ignoreCase = true)) {
                continue
            }

            if (!consoleCommandsEnabled) {
                continue
            }

            val root = commandRoot(trimmed)
            if (root != null && root in BLOCKED_DANGEROUS_COMMAND_ROOTS && root !in allowedDangerousRoots) {
                warnBlockedCommandRoot(root)
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

    private fun commandRoot(command: String): String? {
        val token = command.trim()
            .removePrefix("/")
            .substringBefore(' ')
            .lowercase()
        if (token.isBlank()) return null
        return token.substringAfter(':')
    }

    private fun warnBlockedCommandRoot(root: String) {
        if (!warnedBlockedCommandRoots.add(root)) return
        plugin.logger.warning(
            "Blocked perk command root '$root'. " +
                "Add it to perks.command_execution.allow_roots only if you trust this configuration."
        )
    }

    private companion object {
        private const val LEGACY_INFINITE_THRESHOLD_TICKS: Int = 20 * 60 * 60 * 24 * 7 // 7 days
        private const val SKYBLOCK_SECTION_ID: String = "skyblock_style"
        private val BLOCKED_DANGEROUS_COMMAND_ROOTS: Set<String> = setOf(
            "op",
            "deop",
            "stop",
            "reload",
            "pl",
            "plugins",
            "lp",
            "luckperms",
            "pex",
            "permissionsex",
            "manuadd",
            "manudel",
            "whitelist",
            "ban",
            "pardon",
            "kick",
            "sudo"
        )
    }
}

