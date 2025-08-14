package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import me.ash.reader.domain.model.general.Filter


@Suppress("FunctionName")
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.ArticleList(
    pagingItems: LazyPagingItems<ArticleFlowItem>,
    diffMap: Map<String, Diff>,
    isShowFeedIcon: Boolean,
    isShowStickyHeader: Boolean,
    articleListTonalElevation: Int,
    isSwipeEnabled: () -> Boolean = { false },
    isMenuEnabled: Boolean = true,
    onClick: (ArticleWithFeed, Int) -> Unit = { _, _ -> },
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    showArticle: (ArticleWithFeed) -> Boolean = { true },
    hideDateRows: Boolean = false,
) {
    // https://issuetracker.google.com/issues/193785330
    // FIXME: Using sticky header with paging-compose need to iterate through the entire list
    //  to figure out where to add sticky headers, which significantly impacts the performance
    if (!isShowStickyHeader) {
        // Build a filtered snapshot so filtered‑out rows do not leave empty slots
        val snapshot = pagingItems.itemSnapshotList.items
        val display = snapshot.filter { item ->
            when (item) {
                is ArticleFlowItem.Article -> showArticle(item.articleWithFeed)
                is ArticleFlowItem.Date -> !hideDateRows && item.showSpacer
                else -> false
            }
        }

        itemsIndexed(
            items = display,
            key = { idx, it ->
                when (it) {
                    is ArticleFlowItem.Article -> it.articleWithFeed.article.id
                    is ArticleFlowItem.Date -> "date-${it.date}-$idx"
                    else -> "placeholder-$idx"
                }
            },
            contentType = { _, it -> contentType(it) },
        ) { index, item ->
            when (item) {
                is ArticleFlowItem.Article -> {
                    val awf = item.articleWithFeed
                    val article = awf.article
                    SwipeableArticleItem(
                        articleWithFeed = awf,
                        isUnread = diffMap[article.id]?.isUnread ?: article.isUnread,
                        articleListTonalElevation = articleListTonalElevation,
                        onClick = { onClick(it, index) },
                        isSwipeEnabled = isSwipeEnabled,
                        isMenuEnabled = isMenuEnabled,
                        onToggleStarred = onToggleStarred,
                        onToggleRead = onToggleRead,
                        onMarkAboveAsRead = if (index == 0) null else onMarkAboveAsRead,
                        onMarkBelowAsRead = if (index == display.lastIndex) null else onMarkBelowAsRead,
                        onShare = onShare,
                    )
                }
                is ArticleFlowItem.Date -> {
                    // In non-sticky mode we only add spacing when not hiding date rows
                    if (!hideDateRows && item.showSpacer) {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
                else -> Unit
            }
        }
    } else {
        // Sticky-header mode: iterate and only emit items/headers that pass the filter
        for (index in 0 until pagingItems.itemCount) {
            when (val item = pagingItems.peek(index)) {
                is ArticleFlowItem.Article -> {
                    val awf = item.articleWithFeed
                    if (showArticle(awf)) {
                        item(key = key(item), contentType = contentType(item)) {
                            val article = awf.article
                            SwipeableArticleItem(
                                articleWithFeed = awf,
                                isUnread = diffMap[article.id]?.isUnread ?: article.isUnread,
                                articleListTonalElevation = articleListTonalElevation,
                                onClick = { onClick(it, index) },
                                isSwipeEnabled = isSwipeEnabled,
                                isMenuEnabled = isMenuEnabled,
                                onToggleStarred = onToggleStarred,
                                onToggleRead = onToggleRead,
                                onMarkAboveAsRead = if (index == 1) null else onMarkAboveAsRead, // index 0 is a Date
                                onMarkBelowAsRead = if (index == pagingItems.itemCount - 1) null else onMarkBelowAsRead,
                                onShare = onShare,
                            )
                        }
                    }
                }

                is ArticleFlowItem.Date -> {
                    if (!hideDateRows) {
                        if (item.showSpacer) {
                            item { Spacer(modifier = Modifier.height(32.dp)) }
                        }
                        stickyHeader(key = "date-${item.date}-$index", contentType = contentType(item)) {
                            StickyHeader(item.date, isShowFeedIcon, articleListTonalElevation)
                        }
                    }
                }

                else -> { /* no-op */ }
            }
        }
    }
}

private fun key(item: ArticleFlowItem): Any =
    when (item) {
        is ArticleFlowItem.Article -> item.articleWithFeed.article.id
        is ArticleFlowItem.Date -> "date-${item.date}"
        else -> "placeholder"
    }

private fun contentType(item: ArticleFlowItem): String =
    when (item) {
        is ArticleFlowItem.Article -> "article"
        is ArticleFlowItem.Date -> "date"
        else -> "other"
    }
