package mayorSystem.perks

import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mayorSystem.MayorPlugin
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.potion.PotionEffect

class PerkServiceTest {

    @Test
    fun `resolveText returns raw text when no viewer is supplied`() {
        val service = PerkService(mockk(relaxed = true))
        setPapiMethod(service, placeholderMethod())

        assertEquals("<gold>%unsafe%</gold>", service.resolveText(null, "<gold>%unsafe%</gold>"))
    }

    @Test
    fun `resolveText sanitizes placeholder output for explicit viewer`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val viewer = mockk<Player>(relaxed = true)
        every { viewer.name } returns "Alice"
        setPapiMethod(service, placeholderMethod())

        val resolved = service.resolveText(viewer, "<gold>%unsafe% %viewer%</gold>")

        assertEquals(
            "<gold>\\<click:run_command:'/op me'>\\<red>boom\\</red>\\</click> \\<green>Alice\\</green></gold>",
            resolved
        )
    }

    @Test
    fun `term effect duration parser supports explicit and legacy infinite durations`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))

        assertEquals(PotionEffect.INFINITE_DURATION, durationTicksForTermEffect(service, "infinite"))
        assertEquals(PotionEffect.INFINITE_DURATION, durationTicksForTermEffect(service, "permanent"))
        assertEquals(PotionEffect.INFINITE_DURATION, durationTicksForTermEffect(service, "1000000"))
        assertEquals(600, durationTicksForTermEffect(service, "30"))
    }

    @Test
    fun `long lived classifier ignores short potion effects`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val shortEffect = potionEffect(duration = 600, infinite = false)
        val infiniteEffect = potionEffect(duration = PotionEffect.INFINITE_DURATION, infinite = true)
        val legacyLongEffect = potionEffect(duration = 20 * 60 * 60 * 24 * 7, infinite = false)

        assertFalse(isLongLived(service, shortEffect))
        assertTrue(isLongLived(service, infiniteEffect))
        assertTrue(isLongLived(service, legacyLongEffect))
    }

    @Test
    fun `hidden potion effect classifier detects Paper hidden chains`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val visibleOnly = potionEffect(duration = PotionEffect.INFINITE_DURATION, infinite = true)
        val hidden = potionEffect(duration = 600, infinite = false)
        val withHidden = potionEffect(duration = PotionEffect.INFINITE_DURATION, infinite = true, hidden = hidden)

        assertFalse(hasHiddenPotionEffect(service, visibleOnly))
        assertTrue(hasHiddenPotionEffect(service, withHidden))
    }

    @Test
    fun `tracked infinite mayor effect clears same strength covered potion`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val mayor = potionEffect(
            duration = PotionEffect.INFINITE_DURATION,
            infinite = true,
            amplifier = 0,
            particles = false,
            icon = false
        )
        val potion = potionEffect(
            duration = 3_600,
            infinite = false,
            amplifier = 0,
            particles = true,
            icon = true
        )

        assertTrue(shouldClearTrackedCurrent(service, mayor, potion))
    }

    @Test
    fun `tracked mayor effect preserves stronger potion`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val mayor = potionEffect(
            duration = PotionEffect.INFINITE_DURATION,
            infinite = true,
            amplifier = 0
        )
        val strongerPotion = potionEffect(
            duration = 3_600,
            infinite = false,
            amplifier = 1
        )

        assertFalse(shouldClearTrackedCurrent(service, mayor, strongerPotion))
    }

    @Test
    fun `active mayor effect yields to stronger current effect`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val mayor = potionEffect(
            duration = PotionEffect.INFINITE_DURATION,
            infinite = true,
            amplifier = 0
        )
        val strongerPotion = potionEffect(
            duration = 3_600,
            infinite = false,
            amplifier = 1
        )
        val sameStrengthPotion = potionEffect(
            duration = 3_600,
            infinite = false,
            amplifier = 0
        )

        assertTrue(currentShouldOverrideMayor(service, mayor, strongerPotion))
        assertFalse(currentShouldOverrideMayor(service, mayor, sameStrengthPotion))
    }

    @Test
    fun `expired active mayor effects are eligible for reapply`() {
        assertTrue(shouldReapplyMayorEffectRemoval(
            EntityPotionEffectEvent.Action.REMOVED,
            EntityPotionEffectEvent.Cause.EXPIRATION,
            hasReplacement = false
        ))
        assertTrue(shouldReapplyMayorEffectRemoval(
            EntityPotionEffectEvent.Action.REMOVED,
            EntityPotionEffectEvent.Cause.PLUGIN,
            hasReplacement = false
        ))
        assertTrue(shouldReapplyMayorEffectRemoval(
            EntityPotionEffectEvent.Action.CLEARED,
            EntityPotionEffectEvent.Cause.MILK,
            hasReplacement = false
        ))
        assertFalse(shouldReapplyMayorEffectRemoval(
            EntityPotionEffectEvent.Action.REMOVED,
            EntityPotionEffectEvent.Cause.EXPIRATION,
            hasReplacement = true
        ))
        assertFalse(shouldReapplyMayorEffectRemoval(
            EntityPotionEffectEvent.Action.ADDED,
            EntityPotionEffectEvent.Cause.PLUGIN,
            hasReplacement = false
        ))
    }

    private fun setPapiMethod(service: PerkService, method: Method) {
        val field: Field = PerkService::class.java.getDeclaredField("papiSetPlaceholders")
        field.isAccessible = true
        field.set(service, method)
    }

    private fun placeholderMethod(): Method =
        TestPlaceholderApi::class.java.getMethod("setPlaceholders", Player::class.java, String::class.java)

    private fun durationTicksForTermEffect(service: PerkService, raw: String): Int {
        val method = PerkService::class.java.getDeclaredMethod("durationTicksForTermEffect", String::class.java)
        method.isAccessible = true
        return method.invoke(service, raw) as Int
    }

    private fun isLongLived(service: PerkService, effect: PotionEffect): Boolean {
        val method = PerkService::class.java.getDeclaredMethod("isLongLived", PotionEffect::class.java)
        method.isAccessible = true
        return method.invoke(service, effect) as Boolean
    }

    private fun hasHiddenPotionEffect(service: PerkService, effect: PotionEffect): Boolean {
        val method = PerkService::class.java.getDeclaredMethod("hasHiddenPotionEffect", PotionEffect::class.java)
        method.isAccessible = true
        return method.invoke(service, effect) as Boolean
    }

    private fun shouldClearTrackedCurrent(
        service: PerkService,
        trackedMayor: PotionEffect,
        current: PotionEffect
    ): Boolean {
        val sigMethod = PerkService::class.java.getDeclaredMethod("sigOf", PotionEffect::class.java)
        sigMethod.isAccessible = true
        val sig = sigMethod.invoke(service, trackedMayor)

        val method = PerkService::class.java.declaredMethods.first { it.name == "shouldClearTrackedCurrent" }
        method.isAccessible = true
        return method.invoke(service, sig, current) as Boolean
    }

    private fun currentShouldOverrideMayor(
        service: PerkService,
        mayor: PotionEffect,
        current: PotionEffect
    ): Boolean {
        val sigMethod = PerkService::class.java.getDeclaredMethod("sigOf", PotionEffect::class.java)
        sigMethod.isAccessible = true
        val sig = sigMethod.invoke(service, mayor)

        val method = PerkService::class.java.declaredMethods.first { it.name == "currentShouldOverrideMayor" }
        method.isAccessible = true
        return method.invoke(service, current, sig) as Boolean
    }

    private fun potionEffect(
        duration: Int,
        infinite: Boolean,
        hidden: PotionEffect? = null,
        amplifier: Int = 0,
        ambient: Boolean = false,
        particles: Boolean = false,
        icon: Boolean = false
    ): PotionEffect {
        val effect = mockk<PotionEffect>()
        every { effect.amplifier } returns amplifier
        every { effect.duration } returns duration
        every { effect.isInfinite } returns infinite
        every { effect.isAmbient } returns ambient
        every { effect.hasParticles() } returns particles
        every { effect.hasIcon() } returns icon
        every { effect.hiddenPotionEffect } returns hidden
        return effect
    }

    private object TestPlaceholderApi {
        @JvmStatic
        fun setPlaceholders(player: Player, raw: String): String {
            return raw
                .replace("%unsafe%", "<click:run_command:'/op me'><red>boom</red></click>")
                .replace("%viewer%", "<green>${player.name}</green>")
        }
    }
}
