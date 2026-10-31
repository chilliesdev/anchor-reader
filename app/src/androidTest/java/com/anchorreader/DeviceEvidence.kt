package com.anchorreader

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Real rendered frames from device journeys, retained for the independent design audit. */
internal object DeviceEvidence {
    private const val TAG = "DeviceEvidence"

    fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = runCatching { instrumentation.uiAutomation }.getOrNull()
        if (automation == null) {
            Log.w(TAG, "uiAutomation unavailable; skipping screenshot $name")
            return
        }

        var bitmap: Bitmap? = null
        for (attempt in 1..5) {
            runCatching { instrumentation.waitForIdleSync() }
            bitmap = runCatching { automation.takeScreenshot() }.getOrNull()
            if (bitmap != null) break
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                break
            }
        }

        if (bitmap == null) {
            Log.w(TAG, "Failed to capture screenshot for $name after retries")
            return
        }

        try {
            val baseDir = instrumentation.targetContext.getExternalFilesDir(null)
                ?: instrumentation.targetContext.filesDir
            val folder = File(baseDir, "design-evidence").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write screenshot for $name: ${e.message}")
        } finally {
            bitmap.recycle()
        }
    }
}
