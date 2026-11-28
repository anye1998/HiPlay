package com.shilapi.xcertplay

import android.content.pm.PackageManager
import androidx.compose.runtime.MutableState
import android.os.Looper
import androidx.core.content.FileProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * End-to-end check of the export button on a head-unit-like configuration: the report must reach a
 * file on disk whose path is shown to the driver, not merely sit in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class HiCarExportUiTest {
    @Test
    fun exportingTheLogWritesAFileAndExposesAShareableUri() {
        val controller = Robolectric.buildActivity(HiCarHostActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, FileProvider().apply { attachInfo(context, info) })

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "exportLog")

        val reports = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
        // Wait for a *new* file: a previous run leaves reports behind in the same sandbox, and
        // "there is a file" then stops the wait before `exportLog` has written anything.
        val before = reports.listFiles().orEmpty().map { it.name }.toSet()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (reports.listFiles().orEmpty().none { it.name !in before } && System.nanoTime() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()

        // The newest file, not "the only file": a re-run reuses the same app sandbox.
        val file = reports.listFiles()!!.maxByOrNull { it.lastModified() }!!
        val report = file.readText()
        assertTrue(file.name.startsWith("hicar-log-"))
        assertTrue(file.name.endsWith(".txt"))
        // The connection code is what the phone is asked for, so support can tell a rejected code
        // from a protocol failure without asking the driver to photograph the screen.
        val pin = stateOf<String>(activity, "pin")
        assertTrue(report, report.contains("connection code: $pin"))
        assertTrue(report, report.contains("--- end of log ---"))
        assertNotNull("the saved report must stay shareable", stateOf<Any>(activity, "exportUri"))
        val notice = stateOf<String>(activity, "exportNotice")
        assertTrue(notice, notice.contains(file.absolutePath))
    }

    /**
     * An activity property written as `by mutableStateOf(...)` has no field of its own: the value
     * lives in the generated `name$delegate`, so tests must follow the delegate.
     */
    private fun <T> stateOf(activity: Any, name: String): T {
        val delegate = ReflectionHelpers.getField<Any>(activity, "$name\$delegate")
        @Suppress("UNCHECKED_CAST")
        return (delegate as MutableState<T>).value
    }
}
