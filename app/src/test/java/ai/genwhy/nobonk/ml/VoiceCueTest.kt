package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCueTest {
    @Test fun highSpeaksNounAndSide() {
        assertEquals("Person on your left. Look up.", VoiceCue.phrase(AlertLevel.HIGH, "person", AlertCue.Side.LEFT))
        assertEquals("Vehicle ahead. Look up.", VoiceCue.phrase(AlertLevel.HIGH, "bus", AlertCue.Side.AHEAD))
        assertEquals("Obstacle on your right. Look up.", VoiceCue.phrase(AlertLevel.HIGH, "fire hydrant", AlertCue.Side.RIGHT))
        assertEquals("Obstacle ahead. Look up.", VoiceCue.phrase(AlertLevel.HIGH, null, AlertCue.Side.AHEAD))
    }
    @Test fun lowerLevelsAreSilent() {
        assertNull(VoiceCue.phrase(AlertLevel.MEDIUM, "person", AlertCue.Side.AHEAD))
        assertNull(VoiceCue.phrase(AlertLevel.LOW, "person", AlertCue.Side.AHEAD))
        assertNull(VoiceCue.phrase(AlertLevel.NONE, "person", AlertCue.Side.AHEAD))
    }
}
