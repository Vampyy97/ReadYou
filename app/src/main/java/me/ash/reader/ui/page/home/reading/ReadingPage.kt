package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding // Added for padding modifier
import androidx.compose.foundation.lazy.LazyColumn // Added for LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
// import androidx.compose.ui.platform.LocalHapticFeedback // Unused import
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingBoldCharacters
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton
import androidx.compose.ui.unit.dp

// Imports for summarizeArticle
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log // Added for enhanced logging
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState



import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api

import org.json.JSONArray
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

import androidx.compose.material3.Surface

import androidx.compose.material3.TextButton


fun summarizeArticle(articleContent: String, onResult: (String) -> Unit) {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val url    = "https://api.berrify.org/v1/chat/completions"

    // 1️⃣ Build the JSON payload programmatically:
    val payloadObj = JSONObject().apply {
        put("model", "qwen/qwen-8b")
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role",    "system")
                put("content", "Summarize the article briefly.")
            })
            put(JSONObject().apply {
                put("role",    "user")
                put("content", articleContent.take(4000))
            })
        })
    }

    // 2️⃣ Log it so we can copy/paste into Postman for standalone testing:
    Log.d("SummarizeArticle", "Payload =\n${payloadObj.toString(2)}")

    val body = payloadObj
        .toString()
        .toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(url)
        .post(body)
        .header("Accept", "application/json")
        .build()

    CoroutineScope(Dispatchers.IO).launch {
        val summary = runCatching {
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                // Log full HTTP error + body
                val errorBody = resp.body.string()
                Log.e("SummarizeArticle", "HTTP ${resp.code} ${resp.message}")
                Log.e("SummarizeArticle", "Error body: $errorBody")
                throw Exception("HTTP ${resp.code}")
            }
            // Parse the JSON response:
            val jsonRoot = JSONObject(resp.body.string())
            jsonRoot
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrElse { err ->
            Log.e("SummarizeArticle", "Request failed", err)
            "Error: ${err.localizedMessage}"
        }

        withContext(Dispatchers.Main) {
            onResult(summary)
        }
    }
}

private const val UPWARD = 1
private const val DOWNWARD = -1

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun ReadingPage(
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String, Int) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    val boldCharacters = LocalReadingBoldCharacters.current
    val coroutineScope = rememberCoroutineScope()

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var currentImageData by remember { mutableStateOf(ImageData()) }
    val isShowToolBar = if (LocalReadingAutoHideToolbar.current.value) {
        readerState.articleId != null && !isReaderScrollingDown
    } else {
        true
    }
    var showTopDivider by remember { mutableStateOf(false) }
    var bringToTop by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) } // Corrected: Added closing brace and parenthesis

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        content = { paddings ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (readerState.articleId != null) {

                    TopBar(
                        isShow = isShowToolBar,
                        isScrolled = showTopDivider,
                        title = readerState.title,
                        link = readerState.link,
                        onClick = { bringToTop = true },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                        onSummarize = {
                            isLoading = true
                            val rawHtml = readerState.content.text ?: ""
                            val plainText = Jsoup.parse(rawHtml).text()
                            summarizeArticle(plainText.take(4000)) { summaryText ->
                                summary = summaryText
                                isLoading = false
                            }
                        }
                    )

                }

                val isNextArticleAvailable = readerState.nextArticle != null
                val isPreviousArticleAvailable = readerState.previousArticle != null

                // Show loading or summary just below TopBar

                if (readerState.articleId != null) {
                    // Content
                    AnimatedContent(
                        targetState = readerState,
                        transitionSpec = {
                            val direction =
                                when {
                                    initialState.nextArticle?.articleId == targetState.articleId -> UPWARD
                                    initialState.previousArticle?.articleId == targetState.articleId -> DOWNWARD
                                    initialState.articleId == targetState.articleId -> {
                                        when (targetState.content) {
                                            is ReaderState.Description -> DOWNWARD
                                            else -> UPWARD
                                        }
                                    }
                                    else -> UPWARD
                                }
                            val exit = 100
                            val enter = exit * 2
                            (slideInVertically(
                                initialOffsetY = { (it * 0.2f * direction).toInt() },
                                animationSpec = spring(
                                    dampingRatio = .9f,
                                    stiffness = Spring.StiffnessLow,
                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                ),
                            ) + fadeIn(
                                tween(
                                    delayMillis = exit,
                                    durationMillis = enter,
                                    easing = LinearOutSlowInEasing,
                                )
                            )) togetherWith
                                    (slideOutVertically(
                                        targetOffsetY = { (it * -0.2f * direction).toInt() },
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessLow,
                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                        ),
                                    ) + fadeOut(
                                        tween(durationMillis = exit, easing = FastOutLinearInEasing)
                                    ))
                        },
                        label = "",
                    ) {
                        // 2a) Full-screen loader overlay:
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

// 2b) Summary dialog when ready:
                        summary?.let { textContent ->
                            BasicAlertDialog(onDismissRequest = { summary = null }) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    tonalElevation = 6.dp
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = 360.dp)        // cap width on phones
                                            .padding(20.dp)
                                    ) {
                                        Text("Summary", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.height(12.dp))

                                        // Scroll area that does NOT change height while scrolling
                                        val listState = rememberLazyListState()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 360.dp)   // fixed max height for the scroll region
                                        ) {
                                            LazyColumn(
                                                state = listState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(end = 4.dp)
                                            ) {
                                                item {
                                                    Text(
                                                        text = textContent,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(16.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(onClick = { summary = null }) {
                                                Text("OK")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        remember { it }
                            .run {
                                val state =
                                    rememberPullToLoadState(
                                        key = content,
                                        onLoadNext = if (isNextArticleAvailable) {
                                            {
                                                val (id, index) = readerState.nextArticle // Removed !!
                                                onLoadArticle(id, index)
                                            }
                                        } else null,
                                        onLoadPrevious = if (isPreviousArticleAvailable) {
                                            {
                                                val (id, index) = readerState.previousArticle // Removed !!
                                                onLoadArticle(id, index)
                                            }
                                        } else null,
                                    )

                                val listState =
                                    rememberSaveable(
                                        inputs = arrayOf(content),
                                        saver = LazyListState.Saver,
                                    ) {
                                        LazyListState()
                                    }

                                val scrollState = rememberScrollState()
                                val scope = rememberCoroutineScope()

                                LaunchedEffect(bringToTop) {
                                    if (bringToTop) {
                                        scope.launch {
                                            if (scrollState.value != 0) {
                                                scrollState.animateScrollTo(0)
                                            } else if (listState.firstVisibleItemIndex != 0) {
                                                listState.animateScrollToItem(0)
                                            }
                                        }.invokeOnCompletion { bringToTop = false }
                                    }
                                }

                                showTopDivider =
                                    snapshotFlow {
                                        scrollState.value >= 120 ||
                                                listState.firstVisibleItemIndex != 0
                                    }.collectAsStateValue(initial = false)

                                CompositionLocalProvider(
                                    LocalTextStyle provides LocalTextStyle.current.run {
                                        merge(
                                            lineHeight = if (lineHeight.isSpecified)
                                                (lineHeight.value * LocalReadingTextLineHeight.current).sp
                                            else TextUnit.Unspecified
                                        )
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Content(
                                            modifier = Modifier.pullToLoad(
                                                state = state,
                                                onScroll = { f ->
                                                    if (abs(f) > 2f)
                                                        isReaderScrollingDown = f < 0f
                                                },
                                                enabled = isPullToSwitchArticleEnabled,
                                            ),
                                            contentPadding = paddings,
                                            content = content.text ?: "",
                                            feedName = feedName,
                                            title = title.toString(),
                                            author = author,
                                            link = link,
                                            publishedDate = publishedDate,
                                            isLoading = content is ReaderState.Loading,
                                            scrollState = scrollState,
                                            listState = listState,
                                            onImageClick = { imgUrl, altText ->
                                                currentImageData = ImageData(imgUrl, altText)
                                                showFullScreenImageViewer = true
                                            },
                                        )
                                        PullToLoadIndicator(
                                            state = state,
                                            canLoadPrevious = isPreviousArticleAvailable,
                                            canLoadNext = isNextArticleAvailable,
                                        )
                                    }
                                }
                            }
                    }
                }
                // Bottom Bar
                if (readerState.articleId != null) {
                    BottomBar(
                        isShow = isShowToolBar,
                        isUnread = readingUiState.isUnread,
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = isNextArticleAvailable,
                        isFullContent =
                        readerState.content is ReaderState.FullContent ||
                                readerState.content is ReaderState.Error,
                        isBoldCharacters = boldCharacters.value,
                        onUnread = { viewModel.updateReadStatus(it) },
                        onStarred = { viewModel.updateStarredStatus(it) },
                        onNextArticle = {
                            readerState.nextArticle?.let {
                                val (id, index) = it
                                onLoadArticle(id, index)
                            }
                        },
                        onFullContent = {
                            if (it) viewModel.renderFullContent()
                            else viewModel.renderDescriptionContent()
                        },
                        onBoldCharacters = { (!boldCharacters).put(context, coroutineScope) },
                        onReadAloud = {
                            viewModel.textToSpeechManager.readHtml(
                                readerState.content.text ?: return@BottomBar // Removed unnecessary safe call
                            )
                        },
                        ttsButton = {
                            TtsButton(
                                onClick = {
                                    when (it) {
                                        TextToSpeechManager.State.Error -> {
                                            context.showToast("TextToSpeech initialization failed")
                                        }
                                        TextToSpeechManager.State.Idle -> {
                                            viewModel.textToSpeechManager.readHtml(
                                                readerState.content.text ?: "" // Removed unnecessary safe call
                                            )
                                        }
                                        is TextToSpeechManager.State.Reading -> {
                                            viewModel.textToSpeechManager.stop()
                                        }
                                        TextToSpeechManager.State.Preparing -> {
                                            /* no-op */
                                        }
                                    }
                                },
                                state = viewModel.textToSpeechManager.stateFlow.collectAsStateValue(),
                            )
                        },
                    )
                }
            }
        } // This closing brace for Scaffold's content lambda was missing its parenthesis
    ) // This closing parenthesis for Scaffold was missing

    if (showFullScreenImageViewer) {
        ReaderImageViewer(
            imageData = currentImageData,
            onDownloadImage = {
                viewModel.downloadImage(
                    it,
                    onSuccess = { context.showToast(context.getString(R.string.image_saved)) },
                    onFailure = { th -> throw th },
                )
            },
            onDismissRequest = { showFullScreenImageViewer = false },
        )
    }
}
