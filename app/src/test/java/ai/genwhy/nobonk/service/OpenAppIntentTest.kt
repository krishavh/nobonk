package ai.genwhy.nobonk.service

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class OpenAppIntentTest {
    @Test fun returnFlagsPreserveLiveActivity() {
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, OpenAppIntent.FLAGS and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, OpenAppIntent.FLAGS and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, OpenAppIntent.FLAGS and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertEquals(0, OpenAppIntent.FLAGS and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    @Test fun returnIdentityAvoidsLegacyReturnAndWalkingPrompt() {
        assertFalse(OpenAppIntent.NOTIFICATION_REQUEST_CODE in 0..3)
    }
}
