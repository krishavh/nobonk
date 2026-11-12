package ai.genwhy.nobonk.service

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.requireIsolatedEmulator
import ai.genwhy.nobonk.safety.SafetyNotice
import ai.genwhy.nobonk.testing.WalkingHarnessActivity
import ai.genwhy.nobonk.testing.WalkingServiceHarness
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real service refusal: no foreground-start obligation, camera work or delayed process crash. */
class StartAdmissionInstrumentedTest {
    @Test fun unacknowledgedVisibleStartIsRejectedWithoutPromotionOrDelayedCrash() {
        requireIsolatedEmulator()
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val prefs = context.getSharedPreferences("nobonk_prefs", 0)
        val previous = prefs.getInt(SafetyNotice.PREF_ACK_VERSION, 0)
        val reply = CountDownLatch(1)
        var code = -1
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        try {
            prefs.edit().putInt(SafetyNotice.PREF_ACK_VERSION, 0).commit()
            scenario.onActivity { activity ->
                activity.startService(Intent(activity, WalkingServiceHarness::class.java).apply {
                    action = DetectionService.ACTION_START
                    putExtra(DetectionService.EXTRA_ARM_RESULT, object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code = resultCode; reply.countDown() }
                    })
                })
            }
            assertTrue("Rejected request must acknowledge failure", reply.await(5, TimeUnit.SECONDS))
            assertEquals(0, code)
            // Covers the delayed FGS deadline which previously could crash after an early stopSelf.
            SystemClock.sleep(6_000)
            i.runOnMainSync { assertNull(WalkingServiceHarness.current) }
            assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == 1 })
            scenario.onActivity { assertFalse(it.isFinishing) }
        } finally {
            context.stopService(Intent(context, WalkingServiceHarness::class.java))
            prefs.edit().putInt(SafetyNotice.PREF_ACK_VERSION, previous).commit()
            scenario.close()
        }
    }
}
