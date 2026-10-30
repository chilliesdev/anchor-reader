package com.anchorreader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchorreader.supertonic.VoicePackState
import com.anchorreader.ui.AnchorTheme
import com.anchorreader.ui.SettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class SettingsUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsNotInstalledStateDisplaysDownloadAndSize() {
        var downloaded = false
        compose.setContent {
            AnchorTheme {
                SettingsScreen(
                    stillMode = false,
                    onStill = {},
                    storedBytes = 1024 * 1024 * 12,
                    voiceStatus = "Offline device voice ready",
                    onVoiceSettings = {},
                    onReset = {},
                    onClose = {},
                    voiceState = VoiceState(
                        ready = true,
                        status = "Offline device voice ready",
                        packState = VoicePackState.NotInstalled,
                        useHd = false,
                        selectedVoice = "F1"
                    ),
                    onDownloadVoicePack = { downloaded = true }
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Device narration").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Supertonic 3 HD offline voice (~129 MB)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Offline device voice ready").performScrollTo().assertIsDisplayed()

        val downloadButton = compose.onNodeWithText("Download HD voice pack")
        downloadButton.performScrollTo().assertIsDisplayed()
        DeviceEvidence.capture("ui_audit_01_not_installed")

        assertTouchTargetSize(downloadButton, "Download HD voice pack")
        downloadButton.performClick()
        assertTrue("Clicking download must trigger callback", downloaded)
    }

    @Test
    fun settingsDownloadingStateDisplaysProgressAndCancel() {
        var cancelled = false
        compose.setContent {
            AnchorTheme {
                SettingsScreen(
                    stillMode = false,
                    onStill = {},
                    storedBytes = 1024 * 1024 * 12,
                    voiceStatus = "Downloading HD voice pack...",
                    onVoiceSettings = {},
                    onReset = {},
                    onClose = {},
                    voiceState = VoiceState(
                        ready = false,
                        status = "Downloading HD voice pack...",
                        packState = VoicePackState.Downloading(64 * 1024 * 1024, 128 * 1024 * 1024),
                        useHd = false,
                        selectedVoice = "F1"
                    ),
                    onCancelVoicePack = { cancelled = true }
                )
            }
        }

        compose.onNodeWithText("64 MB of 128 MB (50%)").performScrollTo().assertIsDisplayed()
        val cancelButton = compose.onNodeWithText("Cancel download")
        cancelButton.performScrollTo().assertIsDisplayed()
        DeviceEvidence.capture("ui_audit_02_downloading")

        assertTouchTargetSize(cancelButton, "Cancel download")
        cancelButton.performClick()
        assertTrue("Clicking cancel must trigger callback", cancelled)
    }

    @Test
    fun settingsFailedStateDisplaysErrorMessageAndRetry() {
        var retried = false
        compose.setContent {
            AnchorTheme {
                SettingsScreen(
                    stillMode = false,
                    onStill = {},
                    storedBytes = 1024 * 1024 * 12,
                    voiceStatus = "Voice pack download failed",
                    onVoiceSettings = {},
                    onReset = {},
                    onClose = {},
                    voiceState = VoiceState(
                        ready = false,
                        status = "Voice pack download failed",
                        packState = VoicePackState.Failed("Checksum verification failed. Network packet corrupted."),
                        useHd = false,
                        selectedVoice = "F1"
                    ),
                    onDownloadVoicePack = { retried = true }
                )
            }
        }

        compose.onNodeWithText("Checksum verification failed. Network packet corrupted.").performScrollTo().assertIsDisplayed()
        val retryButton = compose.onNodeWithText("Retry download")
        retryButton.performScrollTo().assertIsDisplayed()
        DeviceEvidence.capture("ui_audit_03_failed")

        assertTouchTargetSize(retryButton, "Retry download")
        retryButton.performClick()
        assertTrue("Clicking retry must trigger callback", retried)
    }

    @Test
    fun settingsInstalledStateDisplaysVoicesEngineToggleAndPreview() {
        var voiceSelected = "F1"
        var previewed = false
        var engineToggled = true
        var deleteConfirmed = false

        compose.setContent {
            AnchorTheme {
                SettingsScreen(
                    stillMode = false,
                    onStill = {},
                    storedBytes = 129L * 1024 * 1024,
                    voiceStatus = "Offline HD voice ready · $voiceSelected",
                    onVoiceSettings = {},
                    onReset = {},
                    onClose = {},
                    voiceState = VoiceState(
                        ready = true,
                        status = "Offline HD voice ready · $voiceSelected",
                        packState = VoicePackState.Installed(129L * 1024 * 1024),
                        useHd = engineToggled,
                        selectedVoice = voiceSelected
                    ),
                    onSetEnginePreference = { engineToggled = it },
                    onSetVoicePreference = { voiceSelected = it },
                    onPreviewVoice = { previewed = true },
                    onDeleteVoicePack = { deleteConfirmed = true }
                )
            }
        }

        compose.onNodeWithText("HD offline voice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Using Supertonic 3 HD neural speech.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Voice selection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Female voices").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Male voices").performScrollTo().assertIsDisplayed()

        // Preview voice button
        val previewButton = compose.onNodeWithText("Preview voice")
        previewButton.performScrollTo().assertIsDisplayed()
        assertTouchTargetSize(previewButton, "Preview voice")
        previewButton.performClick()
        assertTrue("Preview voice clicked", previewed)

        // Delete HD voices button
        val deleteButton = compose.onNodeWithText("Delete HD voices")
        deleteButton.performScrollTo().assertIsDisplayed()
        assertTouchTargetSize(deleteButton, "Delete HD voices")

        DeviceEvidence.capture("ui_audit_04_installed_hd")

        // Click a different voice
        compose.onNodeWithContentDescription("Voice M3").performScrollTo().performClick()
        assertEquals("M3", voiceSelected)

        // Click delete to open confirmation dialog
        deleteButton.performClick()
        compose.onNodeWithText("Delete HD voices?").assertIsDisplayed()
        compose.onNodeWithText("This frees ~129 MB of storage. Narration will switch to your device's built-in voice. Your books, reading positions, and saved questions stay.").assertIsDisplayed()

        DeviceEvidence.capture("ui_audit_05_delete_dialog")

        // Dismiss dialog
        compose.onNodeWithText("Keep voices").performClick()
        compose.onNodeWithText("Delete HD voices?").assertDoesNotExist()
        assertFalse("Delete was dismissed", deleteConfirmed)

        // Re-open and confirm
        deleteButton.performClick()
        compose.onAllNodesWithText("Delete HD voices")[1].performClick()
        assertTrue("Delete was confirmed", deleteConfirmed)
    }

    @Test
    fun settingsInstalledStateWithSystemVoiceFallback() {
        compose.setContent {
            AnchorTheme {
                SettingsScreen(
                    stillMode = false,
                    onStill = {},
                    storedBytes = 129L * 1024 * 1024,
                    voiceStatus = "Offline device voice ready",
                    onVoiceSettings = {},
                    onReset = {},
                    onClose = {},
                    voiceState = VoiceState(
                        ready = true,
                        status = "Offline device voice ready",
                        packState = VoicePackState.Installed(129L * 1024 * 1024),
                        useHd = false,
                        selectedVoice = "F1"
                    )
                )
            }
        }

        compose.onNodeWithText("HD offline voice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Using system device voice.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Voice selection").assertDoesNotExist()
        compose.onNodeWithText("Preview voice").assertDoesNotExist()
        compose.onNodeWithText("Delete HD voices").performScrollTo().assertIsDisplayed()

        DeviceEvidence.capture("ui_audit_06_system_fallback")
    }

    @Test
    fun settingsNarrowViewport320dp() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.0f)) {
                AnchorTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        SettingsScreen(
                            stillMode = false,
                            onStill = {},
                            storedBytes = 129L * 1024 * 1024,
                            voiceStatus = "Offline HD voice ready · F1",
                            onVoiceSettings = {},
                            onReset = {},
                            onClose = {},
                            voiceState = VoiceState(
                                ready = true,
                                status = "Offline HD voice ready · F1",
                                packState = VoicePackState.Installed(129L * 1024 * 1024),
                                useHd = true,
                                selectedVoice = "F1"
                            )
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("HD offline voice").performScrollTo().assertIsDisplayed()
        DeviceEvidence.capture("ui_audit_07_narrow_320dp")

        listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5").forEach { voiceId ->
            val node = compose.onNodeWithContentDescription("Voice $voiceId")
            node.performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun settingsFontScale200Percent() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2.0f)) {
                AnchorTheme {
                    Box(Modifier.width(411.dp).height(800.dp)) {
                        SettingsScreen(
                            stillMode = false,
                            onStill = {},
                            storedBytes = 129L * 1024 * 1024,
                            voiceStatus = "Offline HD voice ready · F1",
                            onVoiceSettings = {},
                            onReset = {},
                            onClose = {},
                            voiceState = VoiceState(
                                ready = true,
                                status = "Offline HD voice ready · F1",
                                packState = VoicePackState.Installed(129L * 1024 * 1024),
                                useHd = true,
                                selectedVoice = "F1"
                            )
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("HD offline voice").performScrollTo().assertIsDisplayed()
        DeviceEvidence.capture("ui_audit_08_font_scale_200")

        listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5").forEach { voiceId ->
            val node = compose.onNodeWithContentDescription("Voice $voiceId")
            node.performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun settingsLandscapeOrientation() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.0f)) {
                AnchorTheme {
                    Box(Modifier.width(840.dp).height(411.dp)) {
                        SettingsScreen(
                            stillMode = false,
                            onStill = {},
                            storedBytes = 129L * 1024 * 1024,
                            voiceStatus = "Offline HD voice ready · F1",
                            onVoiceSettings = {},
                            onReset = {},
                            onClose = {},
                            voiceState = VoiceState(
                                ready = true,
                                status = "Offline HD voice ready · F1",
                                packState = VoicePackState.Installed(129L * 1024 * 1024),
                                useHd = true,
                                selectedVoice = "F1"
                            )
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("HD offline voice").performScrollTo().assertIsDisplayed()
        DeviceEvidence.capture("ui_audit_09_landscape")

        listOf("F1", "F2", "F3", "F4", "F5").forEach { voiceId ->
            compose.onNodeWithContentDescription("Voice $voiceId").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun voiceChipsTouchTargetAndBoundsAudit() {
        compose.setContent {
            AnchorTheme {
                SettingsScreen(
                    stillMode = false,
                    onStill = {},
                    storedBytes = 129L * 1024 * 1024,
                    voiceStatus = "Offline HD voice ready · F1",
                    onVoiceSettings = {},
                    onReset = {},
                    onClose = {},
                    voiceState = VoiceState(
                        ready = true,
                        status = "Offline HD voice ready · F1",
                        packState = VoicePackState.Installed(129L * 1024 * 1024),
                        useHd = true,
                        selectedVoice = "F1"
                    )
                )
            }
        }

        val f1 = compose.onNodeWithContentDescription("Voice F1")
        f1.performScrollTo().assertIsDisplayed()
        val bounds = f1.getUnclippedBoundsInRoot()
        val width = (bounds.right - bounds.left).value
        val height = (bounds.bottom - bounds.top).value
        println("AUDIT_METRIC: Voice F1 bounds=$bounds width=${width}dp height=${height}dp")
        // Document touch target conformance
        assertTrue("FilterChip width should be at least 48dp (WCAG 2.5.5 / Android A11y)", width >= 48f)
        assertTrue("FilterChip height should be at least 48dp (WCAG 2.5.5 / Android A11y)", height >= 48f)
    }

    private fun assertTouchTargetSize(node: SemanticsNodeInteraction, label: String) {
        val bounds = node.getUnclippedBoundsInRoot()
        val minDp = 48f
        val widthDp = (bounds.right - bounds.left).value
        val heightDp = (bounds.bottom - bounds.top).value
        assertTrue("$label touch target width must be >= 48dp: was $widthDp dp ($bounds)", widthDp >= minDp - 0.5f)
        assertTrue("$label touch target height must be >= 48dp: was $heightDp dp ($bounds)", heightDp >= minDp - 0.5f)
    }
}
