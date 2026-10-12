package com.anchorreader

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anchorreader.data.Book
import com.anchorreader.data.Progress
import com.anchorreader.ui.ConceptArt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val PlayerAccent = Color(0xffc4d84e)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PlayerScreen(
    book: Book,
    progress: Progress,
    voice: VoiceState,
    still: Boolean,
    autoplayPending: Boolean,
    onBack: () -> Unit,
    onTranscript: () -> Unit,
    onPlay: () -> Unit,
    onReplay: () -> Unit,
    onSpeed: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onConceptSettled: (Int) -> Unit,
    onCheck: (() -> Unit)?,
    onStill: () -> Unit,
    onSource: () -> Unit,
    onVoiceSettings: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    checkConcepts: Set<Int> = if (onCheck == null) emptySet() else setOf(progress.unitIndex),
) {
    val context = LocalContext.current
    val poster = remember { BitmapFactory.decodeResource(context.resources, R.raw.ocean_poster).asImageBitmap() }
    val pager = key(book.id) { rememberPagerState(initialPage = progress.unitIndex) { book.units.size } }
    val fling = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1))
    val modelUnit by rememberUpdatedState(progress.unitIndex)
    val commitConcept by rememberUpdatedState(onConceptSettled)
    var mirroredUnit by remember(book.id) { mutableIntStateOf(progress.unitIndex) }
    var synchronizing by remember(book.id) { mutableStateOf(false) }
    var touching by remember(book.id) { mutableStateOf(false) }
    var cancelledPage by remember(book.id) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    // Explicit navigation owns the model. Its pager synchronization must not autoplay again.
    LaunchedEffect(book.id, progress.unitIndex) {
        if (mirroredUnit != progress.unitIndex) {
            synchronizing = true
            mirroredUnit = progress.unitIndex
            try {
                pager.stopScroll(MutatePriority.PreventUserInput)
                pager.scrollToPage(progress.unitIndex)
            } finally {
                synchronizing = false
            }
        }
    }
    LaunchedEffect(pager, cancelledPage) {
        if (cancelledPage != null) {
            try {
                pager.stopScroll(MutatePriority.PreventUserInput)
                pager.animateScrollToPage(modelUnit)
            }
            finally { cancelledPage = null }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow {
            if (!touching && !pager.isScrollInProgress && !synchronizing && cancelledPage == null && mirroredUnit == modelUnit) pager.settledPage else null
        }.collect { page ->
            if (page != null && page != modelUnit) {
                mirroredUnit = page
                commitConcept(page)
            }
        }
    }
    val touchState = Modifier.pointerInput(pager) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            val origin = modelUnit
            var released = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.none { it.pressed }) released = event.changes.any { it.changedToUp() }
                } while (event.changes.any { it.pressed })
            } finally {
                // Clickables consume their normal up too. Restore only an actual page drag;
                // a tap at the committed position must leave the next gesture available.
                if (!released && (pager.currentPage != origin || pager.currentPageOffsetFraction != 0f)) cancelledPage = origin
                touching = false
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xff09181e)).testTag("concept_video").then(touchState)
        // One native scroll owner accepts drags over sentence text and each page's controls.
        .scrollable(pager, Orientation.Vertical, enabled = !synchronizing && cancelledPage == null, reverseDirection = true, flingBehavior = fling)) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().testTag("concept_pager"),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            pageNestedScrollConnection = remember { object : NestedScrollConnection {} },
            key = { book.units[it].id },
        ) { page ->
            val active = page == progress.unitIndex
            val interactive = active && pager.settledPage == page && pager.currentPageOffsetFraction == 0f && !pager.isScrollInProgress && !synchronizing && cancelledPage == null
            val concept = book.units[page]
            val sentence = if (active) progress.sentenceIndex else 0
            var videoFailed by remember { mutableStateOf(false) }
            var titleHeight by remember { mutableStateOf(40.dp) }
            var headerHeight by remember { mutableStateOf(48.dp) }
            var requestedStoryHeight by remember(book.id, concept.id, sentence) { mutableStateOf(0.dp) }
            val controlSemantics = if (active) Modifier else Modifier.clearAndSetSemantics {}
            val titleScroll = rememberScrollState()
            BoxWithConstraints(Modifier.fillMaxSize().testTag("concept_page_$page").semantics { if (!active) invisibleToUser() }) {
                val compact = maxHeight < 400.dp
                Image(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("concept_background_$page"))
                if (!still && !videoFailed) BackgroundVideo(active && voice.playing, Modifier.fillMaxSize().testTag("concept_background_video_$page")) { videoFailed = true }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color(0xff09181e).copy(alpha = .45f), .2f to Color.Transparent,
                    .6f to Color.Transparent, .82f to Color(0xff09181e).copy(alpha = .24f),
                    1f to Color(0xff09181e).copy(alpha = .7f),
                )))
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp)) {
                    val measurer = rememberTextMeasurer()
                    val partStyle = partControlStyle()
                    // Reserve the narrow story width first; a wider story can only need less space.
                    val minimumWidth = with(density) { (maxWidth - 64.dp - 4.dp).roundToPx() }.coerceAtLeast(1)
                    val minimumControls = measurePartControls(concept.sentences[sentence], minimumWidth, density, measurer, partStyle)
                    val minimumStoryHeight = maxOf(requestedStoryHeight,
                        measureMinimumStoryHeight(concept.sentences[sentence], density, measurer, minimumControls))
                    val titlePadding = if (compact) 4.dp else 8.dp
                    val minimumTitleHeight = with(density) {
                        measurer.measure(concept.title, TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                            softWrap = false, maxLines = 1).size.height.toDp()
                    } + titlePadding
                    val footerBudget = (maxHeight - headerHeight - minimumStoryHeight - minimumTitleHeight).coerceAtLeast(48.dp)
                    val footerHeight = if (compact) minOf(64.dp, footerBudget) else footerBudget
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                            .testTag("concept_header_$page"), verticalAlignment = Alignment.CenterVertically) {
                            PlayerIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to lesson outline", onBack, interactive = interactive, modifier = controlSemantics)
                            Text("CONCEPT ${page + 1} OF ${book.units.size}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Color(0xffd6e0e1), fontSize = 12.sp, letterSpacing = 1.sp)
                            PlayerIcon(Icons.AutoMirrored.Outlined.Article, "Read full transcript", onTranscript, interactive = interactive, modifier = controlSemantics)
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val availableHeight = maxHeight
                            val railPadding = if (availableHeight >= 480.dp) 0.dp else 64.dp
                            val availableTitleSpace = (availableHeight - minimumStoryHeight).coerceAtLeast(0.dp)
                            val titleSpace = minOf(availableTitleSpace, maxOf(minimumTitleHeight,
                                if (compact) availableTitleSpace else availableHeight * .35f))
                            val desiredBottomSpace = if (availableHeight >= 480.dp) maxOf(280.dp, titleHeight + titlePadding + 8.dp)
                                else titleHeight + titlePadding + if (compact) 0.dp else 8.dp
                            val bottomSpace = minOf(desiredBottomSpace, availableTitleSpace)
                            Box(Modifier.fillMaxSize().padding(end = railPadding, bottom = bottomSpace), contentAlignment = Alignment.CenterStart) {
                                FittedSentence(concept.sentences[sentence], "${book.id}:${concept.id}:$sentence", page, active,
                                    interactive, book.isSample, minOf(150.dp, availableHeight * .4f).value.toInt(),
                                    Modifier.fillMaxSize().padding(end = 4.dp), controlSemantics,
                                    onRequireSpace = { requestedStoryHeight = maxOf(requestedStoryHeight, it) })
                            }
                            Text(concept.title, modifier = Modifier.align(Alignment.BottomStart).padding(end = 64.dp, bottom = titlePadding)
                                .heightIn(max = (titleSpace - titlePadding).coerceAtLeast(0.dp)).verticalScroll(titleScroll, enabled = titleScroll.maxValue > 0)
                                .onSizeChanged { titleHeight = with(density) { it.height.toDp() } }.testTag("concept_title_$page"),
                                color = Color(0xfff5f8f8), fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                                style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 2f), 5f)))
                            val controlsScroll = rememberScrollState()
                            Column(Modifier.align(Alignment.BottomEnd).heightIn(max = availableHeight).testTag("concept_actions_$page")
                                .verticalScroll(controlsScroll, enabled = controlsScroll.maxValue > 0), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (availableHeight < 272.dp) 0.dp else 8.dp)) {
                                PlayerIcon(Icons.Outlined.KeyboardArrowUp, "Previous concept", onPrevious, enabled = page > 0, interactive = interactive, modifier = controlSemantics)
                                PlayerIcon(Icons.Outlined.KeyboardArrowDown, "Next concept", onNext, interactive = interactive, modifier = controlSemantics)
                                PlayerIcon(if (still) Icons.Outlined.MotionPhotosOff else Icons.Outlined.MotionPhotosOn, if (still) "Turn off still mode" else "Turn on still mode", onStill, interactive = interactive, modifier = controlSemantics)
                                PlayerIcon(Icons.Outlined.Image, "Open source", onSource, interactive = interactive, modifier = controlSemantics)
                                PlayerIcon(Icons.Outlined.CheckCircle, "Quick check", { onCheck?.invoke() }, enabled = page in checkConcepts && onCheck != null, interactive = interactive, modifier = controlSemantics)
                            }
                        }
                        val footerScroll = rememberScrollState()
                        Column(Modifier.fillMaxWidth().testTag("concept_footer_$page")
                            .heightIn(max = footerHeight).verticalScroll(footerScroll, enabled = footerScroll.maxValue > 0),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                            if (!voice.ready) {
                                Text(voice.status, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                                TextButton(onClick = { if (interactive) onVoiceSettings() }, enabled = interactive, modifier = controlSemantics, colors = ButtonDefaults.textButtonColors(contentColor = PlayerAccent, disabledContentColor = PlayerAccent)) { Text("Voice settings") }
                            }
                            if (videoFailed) Text("Video unavailable. Still mode is active.", color = Color.White, fontSize = 12.sp)
                            SentenceProgress(sentence, concept.sentences.size, Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp).height(4.dp).testTag(if (active) "sentence_progress" else "preview_sentence_progress_$page"))
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val playing = active && (voice.playing || autoplayPending)
                                    val playAvailable = voice.ready || autoplayPending
                                    FilledIconButton(onClick = { if (interactive && playAvailable) onPlay() }, enabled = interactive && playAvailable, modifier = Modifier.size(if (compact) 48.dp else 56.dp).then(controlSemantics), shape = CircleShape,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayerAccent, contentColor = Color(0xff093e47), disabledContainerColor = if (playAvailable) PlayerAccent else Color.Unspecified, disabledContentColor = if (playAvailable) Color(0xff093e47) else Color.Unspecified)) {
                                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause narration" else "Play narration", modifier = Modifier.size(28.dp))
                                    }
                                    Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
                                    PlayerIcon(Icons.Outlined.ChevronLeft, "Previous sentence", onPreviousSentence, enabled = sentence > 0, interactive = interactive, modifier = controlSemantics)
                                    Text("${sentence + 1} / ${concept.sentences.size}", color = Color.White, fontSize = 16.sp,
                                        modifier = Modifier.widthIn(max = 96.dp).testTag(if (active) "sentence_count" else "preview_sentence_count_$page").semantics { contentDescription = "Sentence ${sentence + 1} of ${concept.sentences.size}" })
                                    PlayerIcon(Icons.Outlined.ChevronRight, "Next sentence", onNextSentence, enabled = sentence + 1 < concept.sentences.size, interactive = interactive, modifier = controlSemantics)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PlayerIcon(Icons.Outlined.Replay, "Replay narration", onReplay, enabled = voice.ready, interactive = interactive, modifier = controlSemantics)
                                    TextButton(onClick = { if (interactive) onSpeed() }, enabled = interactive, modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).then(controlSemantics).semantics { contentDescription = "Change speed" }, contentPadding = PaddingValues(4.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White)) { Text("${voice.speed.toString().removeSuffix(".0")}×", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FittedSentence(text: String, identity: String, page: Int, active: Boolean, interactive: Boolean,
    hasDiagram: Boolean, preferredDiagramHeight: Int, modifier: Modifier, controlSemantics: Modifier, onRequireSpace: (Dp) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    var selectedPart by remember(identity, active) { mutableIntStateOf(0) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = with(density) { maxHeight.roundToPx() }
        val preferredSize = if (maxWidth < 360.dp) 27 else 30
        val baseStyle = playerSentenceStyle()
        val partStyle = partControlStyle()
        val controls = remember(text, width, density.density, density.fontScale, measurer, partStyle) {
            measurePartControls(text, width, density, measurer, partStyle)
        }
        val display = remember(text, width, height, preferredSize, hasDiagram, preferredDiagramHeight, density.density, density.fontScale, measurer, controls) {
            fitSentenceDisplay(text, preferredSize, if (hasDiagram) preferredDiagramHeight else 0) { candidate, size, diagram, gap, parts ->
                val layout = measurer.measure(candidate, baseStyle.copy(fontSize = size.sp, lineHeight = (size * 1.27f).sp),
                    constraints = Constraints(minWidth = width, maxWidth = width))
                val decoration = with(density) { (diagram.dp + if (diagram > 0) gap.dp else 0.dp).roundToPx() }
                val controlsHeight = if (parts) controls.height + with(density) { gap.dp.roundToPx() } else 0
                !layout.hasVisualOverflow && layout.size.height + decoration + controlsHeight <= height
            }
        }
        if (display == null) {
            // Insets/font/viewport changes can briefly precede the parent's next layout.
            // Retain the complete source and selected part while requesting its measured space.
            LaunchedEffect(text, width, height, controls) {
                onRequireSpace(measureMinimumStoryHeight(text, density, measurer, controls) + 1.dp)
            }
            return@BoxWithConstraints
        }
        val part = selectedPart.coerceIn(display.parts.indices)
        LaunchedEffect(display) { selectedPart = selectedPart.coerceIn(display.parts.indices) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(display.gap.dp)) {
            if (display.diagramHeight > 0) ConceptArt(page, Modifier.fillMaxWidth().height(display.diagramHeight.dp)
                .clipToBounds().testTag("concept_art_$page"))
            Text(display.parts[part], modifier = Modifier.fillMaxWidth().testTag(if (active) "current_sentence" else "preview_sentence_$page"),
                style = baseStyle.copy(fontSize = display.fontSize.sp, lineHeight = (display.fontSize * 1.27f).sp))
            if (display.parts.size > 1) {
                Column(Modifier.fillMaxWidth().heightIn(min = with(density) { controls.height.toDp() })
                    .testTag(if (active) "sentence_parts" else "preview_sentence_parts_$page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val colors = ButtonDefaults.textButtonColors(contentColor = PlayerAccent, disabledContentColor = PlayerAccent.copy(alpha = .35f))
                    val countModifier = Modifier.testTag(if (active) "sentence_part_count" else "preview_part_count_$page")
                        .semantics { liveRegion = LiveRegionMode.Polite }
                    if (controls.stacked) Text("Part ${part + 1} of ${display.parts.size}", style = partStyle, color = Color.White,
                        modifier = Modifier.fillMaxWidth().then(countModifier))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ if (interactive && part > 0) selectedPart = part - 1 }, enabled = interactive && part > 0,
                            modifier = Modifier.width(with(density) { controls.backWidth.toDp() }).heightIn(min = 48.dp).then(controlSemantics)
                                .testTag(if (active) "previous_sentence_part" else "preview_previous_part_$page"),
                            contentPadding = PaddingValues(horizontal = 8.dp), colors = colors) { Text("Back", style = partStyle) }
                        if (controls.stacked) Spacer(Modifier.weight(1f))
                        else Text("Part ${part + 1} of ${display.parts.size}", style = partStyle, color = Color.White,
                            modifier = Modifier.weight(1f).then(countModifier))
                        TextButton({ if (interactive && part < display.parts.lastIndex) selectedPart = part + 1 }, enabled = interactive && part < display.parts.lastIndex,
                            modifier = Modifier.width(with(density) { controls.nextWidth.toDp() }).heightIn(min = 48.dp).then(controlSemantics)
                                .testTag(if (active) "next_sentence_part" else "preview_next_part_$page"),
                            contentPadding = PaddingValues(horizontal = 8.dp), colors = colors) { Text("Next", style = partStyle) }
                    }
                }
            }
        }
    }
}

private data class PartControls(val backWidth: Int, val nextWidth: Int, val stacked: Boolean, val height: Int)

private fun playerSentenceStyle() = TextStyle(color = Color(0xfff5f8f8), fontFamily = FontFamily.Serif,
    shadow = Shadow(Color.Black, Offset(0f, 2f), 5f))

@Composable
private fun partControlStyle() = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.SansSerif,
    fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)

private fun measureMinimumStoryHeight(text: String, density: Density, measurer: TextMeasurer, controls: PartControls): Dp {
    // Include source fallback fonts on one measured line without changing its displayed text.
    val line = measurer.measure("Ag😀" + text.replace('\n', ' ').replace('\r', ' '),
        playerSentenceStyle().copy(fontSize = 16.sp, lineHeight = 20.32.sp), softWrap = false, maxLines = 1).size.height
    // Independent parent padding and child constraints can round in opposite directions.
    return with(density) { (line + controls.height + 8.dp.roundToPx() + 1).toDp() }
}

private fun measurePartControls(text: String, width: Int, density: Density, measurer: TextMeasurer, style: TextStyle): PartControls {
    val back = measurer.measure("Back", style, softWrap = false)
    val next = measurer.measure("Next", style, softWrap = false)
    val padding = with(density) { 16.dp.roundToPx() }
    val minimumWidth = with(density) { 64.dp.roundToPx() }
    val backWidth = maxOf(minimumWidth, back.size.width + padding)
    val nextWidth = maxOf(minimumWidth, next.size.width + padding)
    val gap = with(density) { 8.dp.roundToPx() }
    val countText = "Part ${text.length} of ${text.length}"
    val countWidth = measurer.measure(countText, style, softWrap = false).size.width
    val stacked = backWidth + countWidth + nextWidth + gap * 2 > width
    val countHeight = measurer.measure(countText, style,
        constraints = Constraints(maxWidth = if (stacked) width else (width - backWidth - nextWidth - gap * 2).coerceAtLeast(1))).size.height
    val buttonHeight = maxOf(with(density) { 48.dp.roundToPx() }, maxOf(back.size.height, next.size.height) + padding)
    return PartControls(backWidth, nextWidth, stacked, if (stacked) countHeight + gap + buttonHeight else maxOf(countHeight, buttonHeight))
}

@Composable
private fun SentenceProgress(sentence: Int, count: Int, modifier: Modifier) {
    Canvas(modifier.semantics {
        contentDescription = "Sentence progress in concept"
        stateDescription = "${sentence + 1} of $count sentences"
        progressBarRangeInfo = ProgressBarRangeInfo((sentence + 1).toFloat() / count, 0f..1f, count - 1)
    }) {
        val gap = minOf(4.dp.toPx(), size.width / (count * 2))
        val width = (size.width - gap * (count - 1)) / count
        repeat(count) { index ->
            drawRoundRect(if (index <= sentence) PlayerAccent else Color.White.copy(alpha = .28f),
                topLeft = Offset(index * (width + gap), 0f), size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

@Composable
private fun PlayerIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, interactive: Boolean = true) {
    IconButton(onClick = { if (interactive && enabled) onClick() }, enabled = enabled && interactive, modifier = modifier.size(48.dp).background(Color.Black.copy(alpha = .08f), RoundedCornerShape(12.dp)), colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = if (enabled) Color.White else Color.White.copy(alpha = .25f))) { Icon(icon, label, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun BackgroundVideo(active: Boolean, modifier: Modifier, onFailure: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    val latestActive by rememberUpdatedState(active)
    val failure by rememberUpdatedState(onFailure)
    DisposableEffect(Unit) { onDispose { player?.release(); player = null } }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && ready && player?.isPlaying == true) player?.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, ready) {
        player?.let { if (ready) { if (active && !it.isPlaying) it.start() else if (!active && it.isPlaying) it.pause() } }
    }
    AndroidView(modifier = modifier, factory = {
        TextureView(context).apply {
            val textureView = this
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    try {
                        val surface = Surface(texture)
                        player = MediaPlayer.create(context, R.raw.ocean)?.apply {
                            setSurface(surface)
                            isLooping = true
                            setVolume(0f, 0f)
                            setOnErrorListener { _, _, _ ->
                                ready = false
                                failure()
                                true
                            }
                            ready = true
                            adjust(textureView, videoWidth, videoHeight)
                            if (latestActive) start()
                        }
                        surface.release()
                        if (player == null) failure()
                    } catch (_: Exception) { failure() }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { player?.let { adjust(textureView, it.videoWidth, it.videoHeight) } }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { ready = false; player?.release(); player = null; return true }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    })
}

private fun adjust(view: TextureView, videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
    val scale = maxOf(view.width.toFloat() / videoWidth, view.height.toFloat() / videoHeight)
    view.setTransform(Matrix().apply { setScale(videoWidth * scale / view.width, videoHeight * scale / view.height, view.width / 2f, view.height / 2f) })
}
