package rikka.shizuku.nightdog

import android.os.SystemClock
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NightDogTest {

    @After
    fun tearDown() {
        NightDog.stop()
    }

    @Test
    fun `start and stop manage lifecycle, beat counter, and elapsedRealtime uptime`() {
        assertFalse(NightDog.isStarted())
        assertEquals(0L, NightDog.getUptimeMs())

        NightDog.start(timeoutMs = 10_000L, pollIntervalMs = 10_000L)
        assertTrue(NightDog.isStarted())
        assertEquals(0L, NightDog.getBeatCount())

        NightDog.beat()
        NightDog.beat()
        assertEquals(2L, NightDog.getBeatCount())

        ShadowSystemClock.advanceBy(Duration.ofMillis(3_500))
        assertTrue(NightDog.getUptimeMs() >= 3_500L)

        // Calling start() while already started is idempotent
        NightDog.start(timeoutMs = 20_000L, pollIntervalMs = 20_000L)
        assertEquals(2L, NightDog.getBeatCount())

        NightDog.stop()
        assertFalse(NightDog.isStarted())
        assertEquals(0L, NightDog.getUptimeMs())
        assertEquals(0, NightDog.getBoundServiceCount())
    }
}
