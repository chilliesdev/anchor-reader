package com.anchorreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LegalDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val sections = remember {
        listOf("legal/PDFBOX-NOTICE.txt", "legal/PDFBOX-LICENSE.txt", "legal/LICENSE-APACHE-2.0.txt").flatMap { name ->
            context.assets.open(name).bufferedReader().use { it.readText().chunked(6000) }
        }
    }
    ReaderDialog(onClose) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Text("Open-source licenses", style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
                    item { Text("AndroidX, Jetpack Compose, Kotlin, and PDFBox for Android. Upstream notices follow.", modifier = Modifier.padding(bottom = 16.dp)) }
                    items(sections) { text -> SelectionContainer { Text(text, fontSize = 12.sp, lineHeight = 18.sp) } }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close licenses") }
            }
        }
    }
}
