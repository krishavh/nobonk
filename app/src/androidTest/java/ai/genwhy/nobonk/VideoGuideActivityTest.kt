package ai.genwhy.nobonk

import android.media.MediaMetadataRetriever
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class VideoGuideActivityTest {
    @Test fun bundledGuideRequiresPlayAndPausesWhenLeaving() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.resources.openRawResourceFd(R.raw.nobonk_guide).use { fd ->
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Complete 3:24 guide is bundled", duration in 203900..204100)
            } finally { metadata.release() }
        }
        ActivityScenario.launch(VideoGuideActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse("No autoplay", it.guideVideo.isPlaying); it.guideVideo.start() }
            var playing = false
            repeat(100) {
                if (!playing) {
                    Thread.sleep(100)
                    scenario.onActivity { playing = it.guideVideo.isPlaying }
                }
            }
            assertTrue("Bundled video plays without network access", playing)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertFalse("Returning does not autoplay", it.guideVideo.isPlaying) }
        }
    }
}
