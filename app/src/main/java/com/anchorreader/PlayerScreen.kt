package com.anchorreader

import android.graphics.BitmapFactory
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.anchorreader.data.Book
import com.anchorreader.data.Progress
import com.anchorreader.ui.ConceptArt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val PlayerAccent = Color(0xffc4d84e)

@Composable
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
) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val poster = remember { BitmapFactory.decodeResource(context.resources, R.raw.ocean_poster).asImageBitmap() }
    val concept = book.units[progress.unitIndex]
    var videoFailed by remember { mutableStateOf(false) }
    val pager = key(book.id) { rememberPagerState(initialPage = progress.unitIndex) { book.units.size } }
    val fling = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1))
    val modelUnit by rememberUpdatedState(progress.unitIndex)
    val commitConcept by rememberUpdatedState(onConceptSettled)
    var mirroredUnit by remember(book.id) { mutableIntStateOf(progress.unitIndex) }
    var synchronizing by remember(book.id) { mutableStateOf(false) }
    var touching by remember(book.id) { mutableStateOf(false) }
    var cancelledPage by remember(book.id) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(48.dp) }
    var footerHeight by remember { mutableStateOf(152.dp) }
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
        // One native scroll owner also accepts drags over the fixed controls. Sentence scroll
        // consumes movement first; its remaining movement reaches this same pager state.
        .scrollable(pager, Orientation.Vertical, enabled = !synchronizing && cancelledPage == null, reverseDirection = true, flingBehavior = fling)) {
        Image(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (!still && !videoFailed) BackgroundVideo(voice.playing, Modifier.fillMaxSize()) { videoFailed = true }
        Box(Modifier.fillMaxSize().background(Color(0xff09181e).copy(alpha = .72f)))
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().testTag("concept_pager"),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            pageNestedScrollConnection = remember { object : NestedScrollConnection {} },
            key = { book.units[it].id },
        ) { page ->
            val active = page == progress.unitIndex
            val sentence = if (active) progress.sentenceIndex else 0
            val scroll = rememberScrollState()
            LaunchedEffect(book.id, active, sentence) { scroll.scrollTo(0) }
            Box(Modifier.fillMaxSize().testTag("concept_page_$page").semantics { if (!active) invisibleToUser() }
                .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(top = headerHeight, bottom = footerHeight, end = if (landscape) 0.dp else 56.dp),
                contentAlignment = Alignment.CenterStart) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll, enabled = scroll.maxValue > 0).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (book.isSample) ConceptArt(page, Modifier.fillMaxWidth().height(150.dp).clipToBounds())
                    else Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = PlayerAccent, modifier = Modifier.size(52.dp))
                    Text(book.units[page].title, modifier = Modifier.testTag("concept_title_$page"), color = Color(0xffd6e0e1), fontSize = 13.sp, lineHeight = 19.sp)
                    Text(book.units[page].sentences[sentence], modifier = Modifier.testTag(if (active) "current_sentence" else "preview_sentence_$page"), color = Color(0xfff5f8f8), fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 38.sp)
                }
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PlayerIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to lesson outline", onBack)
                Text("CONCEPT ${progress.unitIndex + 1} OF ${book.units.size}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Color(0xffd6e0e1), fontSize = 12.sp, letterSpacing = 1.sp)
                Row {
                    if (landscape) {
                        PlayerIcon(Icons.Outlined.KeyboardArrowUp, "Previous concept", onPrevious, enabled = progress.unitIndex > 0)
                        PlayerIcon(Icons.Outlined.KeyboardArrowDown, "Next concept", onNext)
                        PlayerIcon(if (still) Icons.Outlined.MotionPhotosOff else Icons.Outlined.MotionPhotosOn, if (still) "Turn off still mode" else "Turn on still mode", onStill)
                        PlayerIcon(Icons.Outlined.Image, "Open source", onSource)
                    }
                    PlayerIcon(Icons.AutoMirrored.Outlined.Article, "Read full transcript", onTranscript)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                val controlsScroll = rememberScrollState()
                if (!landscape) Column(Modifier.verticalScroll(controlsScroll, enabled = controlsScroll.maxValue > 0), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerIcon(Icons.Outlined.KeyboardArrowUp, "Previous concept", onPrevious, enabled = progress.unitIndex > 0)
                    PlayerIcon(Icons.Outlined.KeyboardArrowDown, "Next concept", onNext)
                    PlayerIcon(if (still) Icons.Outlined.MotionPhotosOff else Icons.Outlined.MotionPhotosOn, if (still) "Turn off still mode" else "Turn on still mode", onStill)
                    PlayerIcon(Icons.Outlined.Image, "Open source", onSource)
                }
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } }) {
                if (!voice.ready) {
                    Text(voice.status, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                    TextButton(onClick = onVoiceSettings, colors = ButtonDefaults.textButtonColors(contentColor = PlayerAccent)) { Text("Voice settings") }
                }
                if (videoFailed) Text("Video unavailable. Still mode is active.", color = Color.White, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.Outlined.ChevronLeft, "Previous sentence", onPreviousSentence, enabled = progress.sentenceIndex > 0)
                    PlayerIcon(Icons.Outlined.ChevronRight, "Next sentence", onNextSentence, enabled = progress.sentenceIndex + 1 < concept.sentences.size)
                    if (onCheck != null) TextButton(onClick = onCheck, colors = ButtonDefaults.textButtonColors(contentColor = PlayerAccent)) { Text("Quick check") }
                }
                LinearProgressIndicator(
                    progress = { (progress.sentenceIndex + 1).toFloat() / concept.sentences.size },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(4.dp).testTag("sentence_progress"),
                    color = PlayerAccent, trackColor = Color.White.copy(alpha = .28f), drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledIconButton(onClick = onPlay, enabled = voice.ready || autoplayPending, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayerAccent, contentColor = Color(0xff093e47))) {
                            Icon(if (voice.playing || autoplayPending) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (voice.playing || autoplayPending) "Pause narration" else "Play narration", modifier = Modifier.size(28.dp))
                        }
                        Text("${progress.sentenceIndex + 1} / ${concept.sentences.size}", color = Color.White, modifier = Modifier.testTag("sentence_count").semantics { contentDescription = "Sentence ${progress.sentenceIndex + 1} of ${concept.sentences.size}" })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(Icons.Outlined.Replay, "Replay narration", onReplay, enabled = voice.ready)
                        TextButton(onClick = onSpeed, modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("${voice.speed.toString().removeSuffix(".0")}×", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .25f))) { Icon(icon, label, modifier = Modifier.size(24.dp)) }
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
                            setOnErrorListener { _, _, _ -> ready = false; failure(); true }
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
