package ai.genwhy.nobonk

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

/** Refuse physical phones before granting permissions; do not silently skip CI's AOSP emulator. */
internal fun requireIsolatedEmulator() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val flag = automation.executeShellCommand("getprop ro.kernel.qemu").use {
        java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8).trim()
    }
    assertEquals("This test requires an isolated Android emulator", "1", flag)
}
