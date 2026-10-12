package com.anchorreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anchorreader.data.Book
import com.anchorreader.data.PracticeRecord
import com.anchorreader.data.QuestionTaskStatus
import com.anchorreader.data.SectionQuestionStatus
import com.anchorreader.data.Progress
import com.anchorreader.data.practiceSummary

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
    onPractice: () -> Unit, onManageUsage: () -> Unit, progress: Progress = Progress()) {
    val task = practice.task
    val summary = practiceSummary(book, progress, practice)
    val status = if (practice.working) "Generating" else if (summary.ready > 0) "Ready" else "Not ready"
    Surface(modifier = Modifier.testTag("practice_card"), shape = RoundedCornerShape(16.dp), color = Palette.Surface, border = BorderStroke(1.dp, Palette.Line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Wrap the badge below a long heading at large native font sizes.
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Practice questions", fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp,
                    modifier = Modifier.semantics { heading() })
                Surface(shape = RoundedCornerShape(50), color = if (summary.ready > 0) Palette.TealSoft else Palette.Soft) {
                    Text(status, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Deep,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            if (practice.working) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text("Making questions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("You can keep reading while questions are made.", color = Palette.Muted, fontSize = 14.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("question_generation_progress"), color = Palette.Teal)
                }
            }
            // Partial results remain accessible while an unfinished task runs or fails.
            if (summary.ready > 0) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    PracticeMetric(summary.ready, "questions ready", Modifier.weight(1f).padding(end = 12.dp), "practice_ready_count")
                    VerticalDivider(color = Palette.Line)
                    PracticeMetric(summary.answered, "answered", Modifier.weight(1f).padding(start = 12.dp), "practice_answered_count")
                }
                Text("From ${summary.concepts} of ${book.units.size} concepts", color = Palette.Muted, fontSize = 13.sp,
                    modifier = Modifier.testTag("practice_coverage"))
                PrimaryButton(summary.action, onPractice, Modifier.fillMaxWidth().testTag("overview_practice_action"))
            } else if (!practice.working) {
                Text("No questions yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Add questions with a connected ChatGPT account. Your book remains available.", color = Palette.Muted, fontSize = 14.sp)
            }
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
            if (practice.working) SecondaryButton("Cancel generation", onCancel, Modifier.fillMaxWidth())
            else if (!book.isSample && task?.status != QuestionTaskStatus.COMPLETE) {
                SecondaryButton(if (task == null) "Add questions" else "Retry unfinished sections", onAdd, Modifier.fillMaxWidth())
            }
            if (task?.status in setOf(QuestionTaskStatus.RUNNING, QuestionTaskStatus.LIMIT)) SecondaryButton("Manage usage", onManageUsage, Modifier.fillMaxWidth())
            Text("Saved questions work offline. Check each answer against its source.", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun PracticeMetric(count: Int, label: String, modifier: Modifier, tag: String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(count.toString(), fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, modifier = Modifier.testTag(tag))
        Text(label, fontSize = 13.sp, color = Palette.Muted)
    }
}
