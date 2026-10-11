package com.anchorreader.ui

import android.net.Uri
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anchorreader.ReaderDialog
import com.anchorreader.data.Book
import com.anchorreader.data.Outcome
import com.anchorreader.data.Progress
import com.anchorreader.data.RecallQuestion
import com.anchorreader.data.PracticeRecord
import com.anchorreader.data.QuestionTaskStatus
import com.anchorreader.data.SectionQuestionStatus
import com.anchorreader.chatgpt.ChatGptAccountState
import kotlin.math.ceil

@Composable
fun AppHeader(onHome: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(Palette.Canvas).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onHome) { AnchorMark(Modifier.size(28.dp).semantics { contentDescription = "AnchorReader home" }) }
        IconButton(onSettings) { Icon(Icons.Outlined.Settings, "Open settings", tint = Palette.Ink) }
    }
}

@Composable
fun AppTabs(isNew: Boolean, onNew: () -> Unit, onLibrary: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Canvas)) {
        HorizontalDivider(color = Palette.Line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabItem("New", Icons.AutoMirrored.Outlined.NoteAdd, isNew, onNew, Modifier.weight(1f))
            TabItem("Library", Icons.AutoMirrored.Outlined.LibraryBooks, !isNew, onLibrary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(10.dp))
        .background(if (selected) Palette.TealSoft else Palette.Canvas)
        .selectable(selected, onClick = onClick, role = Role.Tab).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = if (selected) Palette.Deep else Palette.Muted, modifier = Modifier.size(24.dp))
        Text(label, color = if (selected) Palette.Deep else Palette.Muted, fontSize = 12.sp)
    }
}

@Composable
fun LibraryScreen(books: List<Book>, progress: Map<String, Progress>, onStart: (Book) -> Unit, onDetails: (Book) -> Unit,
    practice: Map<String, PracticeRecord> = emptyMap()) {
    LazyColumn(Modifier.fillMaxSize().testTag("library_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "heading") { Heading("Library", Modifier.semantics { heading() }) }
        val ownBooks = books.filterNot { it.isSample }
        if (ownBooks.isNotEmpty()) {
            item(key = "own-books") { Text("Your books", fontWeight = FontWeight.SemiBold) }
        }
        items(ownBooks + books.filter { it.isSample }, key = Book::id) { book ->
            BookCard(book, progress[book.id], { onStart(book) }, { onDetails(book) }, practice[book.id]?.questions?.size)
        }
        if (books.isEmpty()) item(key = "empty") { Text("No books yet. Use New to add a book.", color = Palette.Muted) }
    }
}

@Composable
private fun BookCard(book: Book, progress: Progress?, onStart: () -> Unit, onDetails: () -> Unit, questionCount: Int? = null) {
    Surface(onStart, Modifier.fillMaxWidth().testTag("book_card_${book.id}").semantics { contentDescription = "Open ${book.title}" },
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Palette.Line), color = Palette.Surface) {
        Column {
            Box(Modifier.fillMaxWidth().background(Palette.TealSoft).padding(horizontal = 20.dp, vertical = 16.dp)) {
                val coverModifier = Modifier.fillMaxWidth().aspectRatio(2f).testTag("book_cover_${book.id}")
                if (book.pdfPath != null) PdfCover(book.pdfPath, book.title, coverModifier)
                else CoverArt(coverModifier)
            }
            HorizontalDivider(color = Palette.Line)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                if (book.author.isNotBlank()) Text(book.author, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                BookFacts(book, questionCount)
                BookProgress(book, progress)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onStart, Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "${startLabel(progress)} ${book.title}" },
                        shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(startLabel(progress))
                    }
                    OutlinedIconButton(onDetails, modifier = Modifier.size(48.dp).testTag("book_details_${book.id}"), border = BorderStroke(1.dp, Palette.Line)) {
                        Icon(Icons.Outlined.Description, "Details for ${book.title}", tint = Palette.Ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private fun startLabel(progress: Progress?): String = when {
    progress?.completed == true -> "Review"
    progress != null && (progress.visited.isNotEmpty() || progress.sentenceIndex > 0) -> "Continue"
    else -> "Start"
}

private class BookDraftViewModel : ViewModel() {
    var body by mutableStateOf("")
}

@Composable
private fun BookProgress(book: Book, progress: Progress?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = Palette.Line)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.BarChart, null, tint = Palette.Deep, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    progress?.completed == true -> "Complete · ${progress.answers.size} checks"
                    progress != null && progress.visited.isNotEmpty() -> "Concept ${progress.unitIndex + 1} of ${book.units.size}"
                    else -> "Ready"
                }, fontSize = 14.sp)
                LinearProgressIndicator(progress = { if (book.units.isEmpty()) 0f else (progress?.visited?.size ?: 0).toFloat() / book.units.size },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Palette.Teal,
                    trackColor = Palette.Soft, drawStopIndicator = {})
            }
        }
    }
}

@Composable
private fun BookFacts(book: Book, questionCount: Int? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Fact(Icons.AutoMirrored.Outlined.Article, "${book.units.size}", "${book.units.size} concepts")
        val minutes = remember(book) {
            val words = book.units.sumOf { unit -> unit.sentences.sumOf { it.trim().split(Regex("\\s+")).size } }
            ceil(words / 150.0).toInt().coerceAtLeast(1)
        }
        Fact(Icons.Outlined.Schedule, "${minutes}m", "$minutes minutes estimated")
        val checks = questionCount ?: if (book.isSample) 3 else 0
        Fact(Icons.Outlined.Description, "$checks", "$checks recall checks")
    }
}

@Composable
private fun Fact(icon: ImageVector, value: String, description: String) {
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Teal, modifier = Modifier.size(18.dp))
        Text(value, fontSize = 14.sp)
    }
}

@Composable
fun NewBookScreen(onSaveText: (String, String, String) -> Unit, onImportPdf: (Uri, String, String, Boolean) -> Unit,
    busy: Boolean, error: String?, onBack: () -> Unit) {
    var flow by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable(flow) { mutableStateOf("") }
    var author by rememberSaveable(flow) { mutableStateOf("") }
    var questions by rememberSaveable(flow) { mutableStateOf(false) }
    val draft: BookDraftViewModel = viewModel(key = "new-book-draft", factory = remember {
        viewModelFactory { initializer { BookDraftViewModel() } }
    })
    var uriText by rememberSaveable { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            uriText = uri.toString()
            fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "Selected PDF"
            if (title.isBlank()) title = fileName!!.removeSuffix(".pdf").removeSuffix(".PDF").take(120)
            localError = null
        }
    }
    BackHandler(enabled = !busy) { if (flow.isEmpty()) onBack() else { flow = ""; localError = null } }
    PageColumn(ime = true) {
        Heading("New", Modifier.semantics { heading() })
        if (flow.isEmpty()) {
            NewChoice("Import PDF", Icons.Outlined.FileUpload) { flow = "pdf"; localError = null }
            NewChoice("Create book", Icons.Outlined.Edit) { draft.body = ""; flow = "text"; localError = null }
        } else {
            Surface(shape = RoundedCornerShape(20.dp), color = Palette.Surface, border = BorderStroke(1.dp, Palette.Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextButton({ flow = ""; localError = null }, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp)); Text("Back")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (flow == "pdf") IconBadge(Icons.Outlined.PictureAsPdf)
                        Text(if (flow == "pdf") "Import PDF" else "Create book", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                    }
                    if (flow == "pdf") {
                        Text("PDF file", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Surface(shape = RoundedCornerShape(12.dp), color = Palette.Soft, border = BorderStroke(1.dp, Palette.Line)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PrimaryButton("Choose PDF", { launcher.launch(arrayOf("application/pdf")) }, enabled = !busy)
                                Text(fileName ?: "No file selected", fontSize = 13.sp, color = Palette.Muted)
                            }
                        }
                        Text("Text PDFs only. Scanned files need OCR.", fontSize = 12.sp, color = Palette.Muted)
                    }
                    BookField("Title", title, { title = it.take(120); localError = null }, enabled = !busy, tag = if (flow == "pdf") "pdf_title" else "create_title")
                    BookField("Author (optional)", author, { author = it.take(120) }, enabled = !busy, tag = if (flow == "pdf") "pdf_author" else "create_author")
                    if (flow == "pdf") {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                            .toggleable(value = questions, enabled = !busy, role = Role.Switch, onValueChange = { questions = it })
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Add practice questions", fontWeight = FontWeight.SemiBold)
                                Text("Optional. Needs internet. You can add questions later.", fontSize = 13.sp, color = Palette.Muted)
                            }
                            Switch(questions, null, enabled = !busy)
                        }
                        if (questions) QuestionTransferNotice()
                    }
                    if (flow == "text") BookField("Book text", draft.body, { draft.body = it; localError = null }, multiline = true, enabled = !busy, tag = "create_text")
                    (localError ?: error)?.let { ErrorMessage(it) }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Saving book…", fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    PrimaryButton(if (flow == "pdf" && questions) "Save and add questions" else "Save book", {
                        localError = when {
                            title.isBlank() -> "Enter a title."
                            flow == "pdf" && uriText == null -> "Choose a PDF file."
                            flow == "text" && draft.body.isBlank() -> "Enter book text."
                            else -> null
                        }
                        if (localError == null) {
                            if (flow == "pdf") onImportPdf(Uri.parse(uriText!!), title.trim(), author.trim(), questions)
                            else onSaveText(title.trim(), author.trim(), draft.body.trim())
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun BookField(label: String, value: String, onChange: (String) -> Unit, multiline: Boolean = false, enabled: Boolean = true, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value, onChange,
            enabled = enabled, singleLine = !multiline, minLines = if (multiline) 6 else 1,
            shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 176.dp) else Modifier).testTag(tag).semantics { contentDescription = label },
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Line))
    }
}

@Composable
private fun NewChoice(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick, shape = RoundedCornerShape(12.dp), color = Palette.Surface, border = BorderStroke(1.dp, Palette.Line)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon)
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(Icons.Outlined.ChevronRight, null, tint = Palette.Ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(44.dp).background(Palette.TealSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Palette.Deep, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun OverviewScreen(book: Book, progress: Progress?, onBack: () -> Unit, onStart: (Int?) -> Unit,
    onSource: () -> Unit, onTranscript: () -> Unit, onDelete: () -> Unit,
    practice: PracticeRecord = PracticeRecord(book.documentVersion), onAddQuestions: () -> Unit = {},
    onCancelQuestions: () -> Unit = {}, onPractice: (Int?) -> Unit = {}, onManageUsage: () -> Unit = {}) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmTransfer by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
                Text(book.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                if (book.author.isNotBlank()) Text(book.author, color = Palette.Muted)
                BookFacts(book, if (book.isSample) null else practice.questions.size)
                PrimaryButton(if (!book.isSample && progress?.visited.isNullOrEmpty()) "Read now" else startLabel(progress), { onStart(null) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onTranscript) { Icon(Icons.AutoMirrored.Outlined.Notes, null); Spacer(Modifier.width(6.dp)); Text("Full text") }
                    TextButton(onSource) { Icon(Icons.AutoMirrored.Outlined.Article, null); Spacer(Modifier.width(6.dp)); Text("Source") }
                }
                if (book.pdfPath != null) PracticeCard(book, practice, { confirmTransfer = true }, onCancelQuestions, { onPractice(null) }, onManageUsage)
                Text("Concepts", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                HorizontalDivider(color = Palette.Line)
            }
        }
        itemsIndexed(book.units, key = { index, _ -> "concept-$index" }) { index, unit ->
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onStart(index) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(Palette.TealSoft, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (index in (progress?.visited ?: emptySet())) Icons.Outlined.Check else Icons.Outlined.PlayArrow,
                            null, tint = Palette.Deep, modifier = Modifier.size(18.dp))
                    }
                    Text(unit.title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
                }
                if (book.pdfPath != null) {
                    val count = practice.questions.count { it.unitIndex == index }
                    if (count > 0) TextButton({ onPractice(index) }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "Practice ${unit.title}, $count questions" }) { Text("Practice · $count questions") }
                    else Text(when (practice.task?.sections?.get(unit.id)) {
                        SectionQuestionStatus.INSUFFICIENT -> "Not enough source text for questions"
                        SectionQuestionStatus.FAILED -> "Questions need another try"
                        else -> "No questions yet"
                    }, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                }
                HorizontalDivider(color = Palette.Line)
            }
        }
        item(key = "source") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (book.isSample) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, border = BorderStroke(1.dp, Palette.Line)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Request path", fontFamily = FontFamily.Serif, fontSize = 18.sp)
                            SourceDiagram(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Palette.Soft, RoundedCornerShape(8.dp)))
                            SecondaryButton("Diagram", onSource, Modifier.fillMaxWidth())
                        }
                    }
                    Text("Prepared example. Original AnchorReader lesson and diagram. Narration uses your device voice.", color = Palette.Muted, fontSize = 12.sp)
                } else {
                    Text("Text is split into reading sections. Read the source to check context. Narration uses your device voice.", color = Palette.Muted, fontSize = 12.sp)
                    TextButton({ confirmDelete = true }, modifier = Modifier.semantics { contentDescription = "Delete ${book.title}" }) { Icon(Icons.Outlined.DeleteOutline, null, tint = Palette.ErrorInk); Spacer(Modifier.width(6.dp)); Text("Delete book", color = Palette.ErrorInk) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this book?") },
        text = { Text("This removes the local book, source file, and progress.") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete book", color = Palette.ErrorInk) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep book") } })
    if (confirmTransfer) AlertDialog(onDismissRequest = { confirmTransfer = false }, title = { Text("Add practice questions") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuestionTransferNotice()
            Text("Up to 3 questions per source section. Your book remains available while questions are made.")
        } },
        confirmButton = { TextButton({ confirmTransfer = false; onAddQuestions() }) { Text("Add questions") } },
        dismissButton = { TextButton({ confirmTransfer = false }) { Text("Keep reading") } })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CheckScreen(question: RecallQuestion, number: Int, outcome: Outcome?, selected: Int?, onSelect: (Int) -> Unit,
    onSubmit: () -> Unit, onSkip: () -> Unit, onContinue: () -> Unit, onReview: () -> Unit, onSource: () -> Unit,
    total: Int = 0, sectionTitle: String = "", onRetry: () -> Unit = {}) {
    PageColumn(gap = 20) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.align(Alignment.CenterVertically)) {
                Eyebrow(if (total > 0) "QUESTION $number OF $total" else "CHECK / ${number.toString().padStart(2, '0')}")
            }
            TextButton(onReview, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.CenterVertically)) { Text(if (total > 0) "Continue reading" else "Review") }
        }
        Heading("Quick check", Modifier.semantics { heading() })
        if (sectionTitle.isNotBlank()) Text(sectionTitle, fontWeight = FontWeight.SemiBold)
        Text(question.prompt, color = Palette.Muted)
        Text("Choose one answer", fontWeight = FontWeight.Bold)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            question.options.forEachIndexed { index, option ->
                val checked = selected == index
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (checked) Palette.TealSoft else Palette.Surface)
                    .border(1.dp, if (checked) Palette.Teal else Palette.Line, RoundedCornerShape(12.dp))
                    .selectable(checked, enabled = outcome == null, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(checked, null, enabled = outcome == null, modifier = Modifier.size(24.dp))
                    Text(option, modifier = Modifier.weight(1f), fontSize = 15.sp, lineHeight = 23.sp)
                }
            }
        }
        if (outcome == null) {
            PrimaryButton("Check answer", onSubmit, Modifier.fillMaxWidth(), enabled = selected != null)
            SecondaryButton(if (total > 0) "Skip" else "I do not know", onSkip, Modifier.fillMaxWidth())
        } else {
            val correct = outcome == Outcome.CORRECT
            Surface(color = if (correct) Palette.Success else Palette.Error, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (outcome) { Outcome.CORRECT -> "Correct"; Outcome.WRONG -> "Review this idea"; Outcome.SKIPPED -> "Skipped" },
                        color = if (correct) Palette.SuccessInk else Palette.ErrorInk, fontWeight = FontWeight.SemiBold)
                    Text("Answer: ${question.options[question.correctIndex]}", fontWeight = FontWeight.Bold)
                    Text(question.explanation, fontSize = 14.sp, lineHeight = 22.sp)
                    if (question.sourceExcerpt.isNotBlank()) Text("Source: “${question.sourceExcerpt}”", fontSize = 13.sp, lineHeight = 20.sp)
                    TextButton(onSource, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(question.sourcePage?.let { "View source · page $it" } ?: "View source") }
                }
            }
            if (total > 0 && outcome == Outcome.WRONG) SecondaryButton("Try again", onRetry, Modifier.fillMaxWidth())
            PrimaryButton(if (total == 0) "Continue now" else if (number < total) "Next question" else "Back to reading", onContinue, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun FinishScreen(book: Book, progress: Progress, questions: List<RecallQuestion>, onReview: () -> Unit, onLibrary: () -> Unit, onSource: () -> Unit) {
    PageColumn(gap = 20) {
        Eyebrow("LESSON COMPLETE")
        Heading(if (book.isSample) "You reached the end of this sample." else "You reached the end of this book.", Modifier.semantics { heading() })
        Text("Answer record only. This is not a mastery score.", color = Palette.Muted)
        Column {
            HorizontalDivider(color = Palette.Line)
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinishStat("${progress.visited.size} / ${book.units.size}", "concepts visited", Modifier.weight(1f))
                FinishStat("${progress.answers.values.count { it == Outcome.CORRECT }}", "checks correct", Modifier.weight(1f))
                FinishStat("${progress.answers.values.count { it == Outcome.SKIPPED }}", "checks skipped", Modifier.weight(1f))
            }
            HorizontalDivider(color = Palette.Line)
        }
        questions.forEachIndexed { index, question ->
            val result = progress.answers[index]
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(when (result) { Outcome.CORRECT -> Icons.Outlined.CheckCircle; Outcome.WRONG -> Icons.Outlined.ErrorOutline; else -> Icons.Outlined.RemoveCircleOutline },
                    null, tint = if (result == Outcome.CORRECT) Palette.SuccessInk else Palette.Muted, modifier = Modifier.size(22.dp))
                Column {
                    Text(book.units.getOrNull(question.unitIndex)?.title ?: question.prompt, fontSize = 14.sp)
                    Text(when (result) { Outcome.CORRECT -> "Correct"; Outcome.WRONG -> "Wrong · Review suggested"; Outcome.SKIPPED -> "Skipped"; null -> "Not answered" },
                        fontSize = 12.sp, color = Palette.Muted)
                }
            }
        }
        PrimaryButton("Review lesson", onReview, Modifier.fillMaxWidth())
        SecondaryButton("Back to library", onLibrary, Modifier.fillMaxWidth())
        TextButton(onSource) { Text("Read source") }
    }
}

@Composable
private fun FinishStat(value: String, caption: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontFamily = FontFamily.Serif, fontSize = 24.sp)
        Text(caption, fontSize = 11.sp, color = Palette.Muted, lineHeight = 16.sp)
    }
}

@Composable
fun SettingsScreen(stillMode: Boolean, onStill: (Boolean) -> Unit, storedBytes: Long, voiceStatus: String,
    onVoiceSettings: () -> Unit, onReset: () -> Unit, onClose: () -> Unit, onLicenses: () -> Unit = {},
    account: ChatGptAccountState = ChatGptAccountState(), onConnect: () -> Unit = {}, onSignOut: () -> Unit = {}, onCancelSignIn: () -> Unit = {}, onManageUsage: () -> Unit = {},
    onResetConnection: () -> Unit = {}) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmConnectionReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    PageColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("Settings", Modifier.semantics { heading() })
            IconButton(onClose) { Icon(Icons.Outlined.Close, "Close settings") }
        }
        SettingRow("Still mode", "Stop background motion.") {
            Switch(stillMode, onStill, Modifier.semantics { contentDescription = "Still mode" })
        }
        SettingRow("Local storage", formatBytes(storedBytes)) { Icon(Icons.Outlined.Storage, null, tint = Palette.Teal) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ChatGPT account", fontWeight = FontWeight.SemiBold)
            Text(account.label ?: "Connect to make optional practice questions.", color = Palette.Muted, fontSize = 13.sp)
            account.message?.let { Text(it, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (account.storageNeedsReset) SecondaryButton("Reset connection", { confirmConnectionReset = true })
            else if (account.signingIn) SecondaryButton("Cancel sign-in", onCancelSignIn)
            else if (account.connected) {
                Text(if (account.planEnabled) "Uses your ChatGPT plan when you add questions." else "Plan use was not granted. Reconnect to check access.", color = Palette.Muted, fontSize = 13.sp)
                SecondaryButton("Manage usage", onManageUsage)
                SecondaryButton("Sign out", onSignOut)
            } else SecondaryButton("Continue with ChatGPT", onConnect)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device narration", fontWeight = FontWeight.SemiBold)
            Text(voiceStatus, color = Palette.Muted, fontSize = 13.sp)
            SecondaryButton("Voice settings", onVoiceSettings)
        }
        HorizontalDivider(color = Palette.Line)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local progress", fontWeight = FontWeight.SemiBold)
            Text("Saved on this device.", color = Palette.Muted, fontSize = 13.sp)
            SecondaryButton("Reset progress", { confirmReset = true })
        }
        HorizontalDivider(color = Palette.Line)
        Text("Reading and saved questions work on this device. Adding questions sends selected source text to OpenAI. Narration needs an installed offline English voice.", color = Palette.Muted, fontSize = 13.sp)
        Text("Background video: GOES-16 imagery by CSU/CIRA and NOAA. Public domain.", color = Palette.Muted, fontSize = 12.sp)
        TextButton({
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://commons.wikimedia.org/wiki/File:Ocean_waves_northeast_of_northern_South_America_(CIRA_2021-09-10).webm"))) }
        }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            Text("Video credit on Wikimedia Commons", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
        }
        SecondaryButton("Open-source licenses", onLicenses)
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Clear all progress?") },
        text = { Text("This clears saved positions and answers. Books and source files stay.") },
        confirmButton = { TextButton({ confirmReset = false; onReset() }) { Text("Clear progress", color = Palette.ErrorInk) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Keep progress") } })
    if (confirmConnectionReset) AlertDialog(onDismissRequest = { confirmConnectionReset = false }, title = { Text("Reset ChatGPT connection?") },
        text = { Text("This clears damaged sign-in data on this device. Your books, reading position, and saved questions stay. Connect again when you want to add questions.") },
        confirmButton = { TextButton({ confirmConnectionReset = false; onResetConnection() }) { Text("Reset connection") } },
        dismissButton = { TextButton({ confirmConnectionReset = false }) { Text("Keep reading") } })
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 13.sp, color = Palette.Muted) }
            control()
        }
        HorizontalDivider(color = Palette.Line)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB stored".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB stored".format(bytes / 1024.0)
    else -> "$bytes bytes stored"
}

@Composable
fun TranscriptDialog(book: Book, onClose: () -> Unit) {
    ReaderDialog(onClose) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(20.dp), color = Palette.Canvas) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("FULL TEXT")
                        Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClose) { Icon(Icons.Outlined.Close, "Close transcript") }
                }
                LazyColumn(Modifier.weight(1f).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(book.units, key = { index, _ -> index }) { _, unit ->
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(unit.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            SelectionContainer { Text(unit.sentences.joinToString(" "), lineHeight = 27.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Eyebrow(text: String) { Text(text, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted) }

@Composable
private fun ErrorMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().background(Palette.Error, RoundedCornerShape(8.dp)).padding(12.dp)
        .semantics { liveRegion = LiveRegionMode.Assertive }, color = Palette.ErrorInk, fontSize = 14.sp)
}

@Composable
private fun PageColumn(gap: Int = 16, ime: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().then(if (ime) Modifier.imePadding() else Modifier).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(gap.dp), content = content)
}
