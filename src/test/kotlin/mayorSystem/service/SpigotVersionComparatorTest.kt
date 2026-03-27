package mayorSystem.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpigotVersionComparatorTest {
    @Test
    fun `detects when installed is older`() {
        assertTrue(SpigotVersionComparator.isInstalledOlder("1.0.4", "1.0.5"))
        assertTrue(SpigotVersionComparator.isInstalledOlder("1.0.9", "1.0.10"))
        assertTrue(SpigotVersionComparator.isInstalledOlder("1.0.3", "v1.0.4"))
    }

    @Test
    fun `does not mark equal versions as older`() {
        assertFalse(SpigotVersionComparator.isInstalledOlder("1.0.4", "1.0.4"))
    }

    @Test
    fun `treats pre-release text as older than release`() {
        assertTrue(SpigotVersionComparator.isInstalledOlder("1.0.4-SNAPSHOT", "1.0.4"))
        assertFalse(SpigotVersionComparator.isInstalledOlder("1.0.4", "1.0.4-SNAPSHOT"))
    }
}
