package ai.genwhy.nobonk.service

import android.content.Intent

/** Bring the existing screen forward without clearing its Activity/ViewModel or stacking a copy. */
object OpenAppIntent {
    const val FLAGS: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP

    // Intent extras/flags do not distinguish PendingIntents. Avoid both the old flag-less
    // return identity (0) and walking reminder (2), whose extra would open the walking prompt.
    const val NOTIFICATION_REQUEST_CODE: Int = 4
}
