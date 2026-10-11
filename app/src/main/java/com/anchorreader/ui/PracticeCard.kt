package com.anchorreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchorreader.data.Book
import com.anchorreader.data.PracticeRecord
import com.anchorreader.data.QuestionTaskStatus
import com.anchorreader.data.SectionQuestionStatus

@Composable
fun QuestionTransferNotice() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Use my ChatGPT plan", fontWeight = FontWeight.SemiBold)
        Text("Section text will be sent to OpenAI to make questions. Uses your ChatGPT plan and its limits. Plan access must be granted by your account.",
            fontSize = 13.sp, color = Palette.Muted)
        Text("Up to 3 questions per section. Reading remains available.", fontSize = 13.sp, color = Palette.Muted)
    }
}

@Composable
fun PracticeCard(book: Book, practice: PracticeRecord, onAdd: () -> Unit, onCancel: () -> Unit,
    onPractice: () -> Unit, onManageUsage: () -> Unit) {
    val task = practice.task
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, border = BorderStroke(1.dp, Palette.Line)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Practice questions", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(if (practice.questions.isEmpty()) "No questions ready yet" else "${practice.questions.size} questions · ${practice.answers.size} answered",
                fontSize = 14.sp)
            if (task != null) {
                Text("Questions ready for ${practice.readySections} of ${book.units.size} sections",
                    fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(when (task.status) {
                    QuestionTaskStatus.CONNECTING -> "Complete ChatGPT sign-in. Your book is saved."
                    QuestionTaskStatus.RUNNING -> "Using ChatGPT plan. Making questions…"
                    else -> task.message
                }, fontSize = 13.sp, color = Palette.Muted)
                if (task.sections.values.any { it == SectionQuestionStatus.INSUFFICIENT }) Text("Some sections do not contain enough source text for questions.", fontSize = 13.sp, color = Palette.Muted)
            }
            if (book.units.size == 1 && book.units.first().title == "Full document") Text("No clear section headings found. Questions cover the full document.", fontSize = 13.sp, color = Palette.Muted)
            if (practice.questions.isNotEmpty()) SecondaryButton("Practice questions", onPractice, Modifier.fillMaxWidth())
            if (practice.working) SecondaryButton("Cancel generation", onCancel, Modifier.fillMaxWidth())
            else if (task?.status != QuestionTaskStatus.COMPLETE) {
                SecondaryButton(if (task == null) "Add questions" else "Retry unfinished sections", onAdd, Modifier.fillMaxWidth())
            }
            if (task?.status in setOf(QuestionTaskStatus.RUNNING, QuestionTaskStatus.LIMIT)) SecondaryButton("Manage usage", onManageUsage, Modifier.fillMaxWidth())
            Text("Saved questions work offline. Check each answer against its source.", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}
