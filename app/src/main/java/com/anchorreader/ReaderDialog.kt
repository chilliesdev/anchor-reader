package com.anchorreader

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Give the content the real window height, including on newer Android releases. */
@Composable
fun ReaderDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    // Keep platform measurement. The window size below supplies full width.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            // WRAP_CONTENT can measure beyond the visible window on API 35+.
            if (window != null && (window.attributes.width != WindowManager.LayoutParams.MATCH_PARENT ||
                    window.attributes.height != WindowManager.LayoutParams.MATCH_PARENT)) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }
        content()
    }
}
