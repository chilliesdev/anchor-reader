package com.anchorreader

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anchorreader.chatgpt.ChatGptAccountState
import com.anchorreader.ui.AnchorTheme
import com.anchorreader.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AccountRecoveryUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun damagedConnectionResetNeedsConfirmationAndKeepsReadingAvailable() {
        val resets = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                AnchorTheme {
                    SettingsScreen(false, {}, 0, "Voice ready", {}, {}, {},
                        account = ChatGptAccountState(storageNeedsReset = true,
                            message = "Saved sign-in data could not open. Reset the connection to sign in again."),
                        onResetConnection = { resets.incrementAndGet() })
                }
            } }
            compose.onNodeWithText("Continue with ChatGPT").assertDoesNotExist()
            compose.onNodeWithText("Reset connection").performScrollTo().performClick()
            compose.onNodeWithText("Reset ChatGPT connection?").assertExists()
            compose.onNodeWithText("This clears damaged sign-in data on this device. Your books, reading position, and saved questions stay. Connect again when you want to add questions.").assertExists()
            assertEquals(0, resets.get())
            compose.onNodeWithText("Keep reading").performClick()
            compose.onNodeWithText("Reset ChatGPT connection?").assertDoesNotExist()
            assertEquals(0, resets.get())
            compose.onNodeWithText("Reset connection").performScrollTo().performClick()
            compose.onNode(hasText("Reset connection") and hasAnyAncestor(isDialog())).performClick()
            assertEquals(1, resets.get())
        }
    }
}
