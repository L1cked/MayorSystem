package mayorSystem.data

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModelsTest {
    @Test
    fun `temporary apply bans require an expiry`() {
        assertFailsWith<IllegalArgumentException> {
            ApplyBan(
                uuid = UUID.fromString("00000000-0000-0000-0000-000000000321"),
                lastKnownName = "Alice",
                permanent = false,
                until = null,
                createdAt = OffsetDateTime.now()
            )
        }
    }
}
