package mayorSystem.messaging

/**
 * Stable StarForged rank-glyph compatibility for MayorSystem display text.
 *
 * Nexo resolves these tags for Java clients through the `nexo:default` font.
 * Inventory titles opened for Bedrock players use readable labels instead,
 * because the Java resource-pack font is not available there.
 */
internal object NexoGlyphText {
    private val namedRankGlyph = Regex("<glyph:(rank_[a-z0-9_]+)>", RegexOption.IGNORE_CASE)
    private val nexoFontSegment = Regex(
        "<font:nexo:default>(.*?)</font>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun javaMini(raw: String): String =
        replaceNamed(raw) { rank ->
            "<font:nexo:default><white>${String(Character.toChars(rank.codepoint))}</white></font>"
        }

    fun bedrockMini(raw: String): String {
        val named = replaceNamed(raw, ::bedrockLabel)
        val withoutKnownFontSegments = nexoFontSegment.replace(named) { match ->
            val inner = match.groupValues[1]
            val replaced = replaceCodepoints(inner, ::bedrockLabel)
            if (replaced == inner) match.value else replaced
        }
        return replaceCodepoints(withoutKnownFontSegments, ::bedrockLabel)
    }

    fun readablePlain(raw: String): String =
        replaceCodepoints(raw) { rank -> "[${rank.smallCapsLabel}]" }

    private fun bedrockLabel(rank: RankStyle): String =
        "<gradient:${rank.gradientStart}:${rank.gradientEnd}>[${rank.smallCapsLabel}]</gradient>"

    private fun replaceNamed(raw: String, replacement: (RankStyle) -> String): String =
        namedRankGlyph.replace(raw) { match ->
            rankById[match.groupValues[1].lowercase()]?.let(replacement) ?: match.value
        }

    private fun replaceCodepoints(raw: String, replacement: (RankStyle) -> String): String {
        if (raw.isEmpty()) return raw
        val output = StringBuilder(raw.length)
        var offset = 0
        while (offset < raw.length) {
            val codepoint = raw.codePointAt(offset)
            val rank = rankByCodepoint[codepoint]
            if (rank == null) output.appendCodePoint(codepoint) else output.append(replacement(rank))
            offset += Character.charCount(codepoint)
        }
        return output.toString()
    }

    private data class RankStyle(
        val id: String,
        val codepoint: Int,
        val smallCapsLabel: String,
        val gradientStart: String,
        val gradientEnd: String
    )

    private val rankById = listOf(
        RankStyle("rank_starstruck", 0xE2B0, "ꜱᴛᴀʀꜱᴛʀᴜᴄᴋ", "#38BDF8", "#3B82F6"),
        RankStyle("rank_orbit", 0xE2B1, "ᴏʀʙɪᴛ", "#67E8F9", "#06B6D4"),
        RankStyle("rank_cosmic", 0xE2B2, "ᴄᴏꜱᴍɪᴄ", "#C084FC", "#7C3AED"),
        RankStyle("rank_supernova", 0xE2B3, "ꜱᴜᴘᴇʀɴᴏᴠᴀ", "#FDE047", "#F97316"),
        RankStyle("rank_astral", 0xE2B4, "ᴀꜱᴛʀᴀʟ", "#F8FAFC", "#94A3B8"),
        RankStyle("rank_starbound", 0xE2B5, "ꜱᴛᴀʀʙᴏᴜɴᴅ", "#F472B6", "#8B5CF6"),
        RankStyle("rank_owner", 0xE2B6, "ᴏᴡɴᴇʀ", "#67E8F9", "#06B6D4"),
        RankStyle("rank_founder", 0xE2B7, "ꜰᴏᴜɴᴅᴇʀ", "#FEF3C7", "#D6A655"),
        RankStyle("rank_admin", 0xE2B8, "ᴀᴅᴍɪɴ", "#F0ABFC", "#A21CAF"),
        RankStyle("rank_dev", 0xE2B9, "ᴅᴇᴠ", "#5EEAD4", "#14B8A6"),
        RankStyle("rank_jrdev", 0xE2BA, "ᴊʀ ᴅᴇᴠ", "#CCFBF1", "#5EEAD4"),
        RankStyle("rank_manager_g", 0xE2BB, "ᴍᴀɴᴀɢᴇʀ", "#FB7185", "#EC4899"),
        RankStyle("rank_manager_m", 0xE2BC, "ᴍᴇᴅɪᴀ ᴍᴀɴᴀɢᴇʀ", "#60A5FA", "#A855F7"),
        RankStyle("rank_manager_s", 0xE2BD, "ɢᴇɴᴇʀᴀʟ ᴍᴀɴᴀɢᴇʀ", "#BEF264", "#22C55E"),
        RankStyle("rank_srstaff", 0xE2BE, "ꜱʀ ꜱᴛᴀꜰꜰ", "#C084FC", "#8B5CF6"),
        RankStyle("rank_staff", 0xE2BF, "ꜱᴛᴀꜰꜰ", "#60A5FA", "#2563EB"),
        RankStyle("rank_support", 0xE2C0, "ꜱᴜᴘᴘᴏʀᴛ", "#FDE047", "#F59E0B"),
        RankStyle("rank_system", 0xE2C1, "ꜱʏꜱᴛᴇᴍ", "#67E8F9", "#06B6D4"),
        RankStyle("rank_media", 0xE2C2, "ᴍᴇᴅɪᴀ", "#F472B6", "#DB2777"),
        RankStyle("rank_srmedia", 0xE2C3, "ꜱʀ ᴍᴇᴅɪᴀ", "#FB7185", "#DC2626"),
        RankStyle("rank_player", 0xE2C4, "ᴘʟᴀʏᴇʀ", "#F8FAFC", "#94A3B8"),
        RankStyle("rank_emperor", 0xE2C5, "ᴇᴍᴘᴇʀᴏʀ", "#F8F0FF", "#D946EF"),
        RankStyle("rank_designer", 0xE2C6, "ᴅᴇꜱɪɢɴᴇʀ", "#99F6E4", "#10B981")
    ).associateBy(RankStyle::id)

    private val rankByCodepoint = rankById.values.associateBy(RankStyle::codepoint)
}
