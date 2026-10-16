package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.size.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.youtube.*
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.youtube.OnlineCollectionState

@Composable
internal fun HomeFeedShelfView(shelf: HomeFeedShelf, player: PlayerViewModel, onOpen: (HomeFeedItem) -> Unit,
    collectionQueue: Boolean = false, onScrolling: ((String, Boolean) -> Unit)? = null,
    onSeen: ((String) -> Unit)? = null, exposureEnabled: Boolean = false, viewport: Rect = Rect.Zero,
    exposureVersion: Long = 0, onSongMenu: (com.theveloper.pixelplay.data.model.Song, () -> Unit) -> Unit) {
    val songs = remember(shelf) { shelf.items.mapNotNull { it.track?.asSong() } }
    val rowState = rememberLazyListState()
    val scrollingCallback by rememberUpdatedState(onScrolling)
    LaunchedEffect(rowState, shelf.id) {
        snapshotFlow { rowState.isScrollInProgress }.distinctUntilChanged().collect { scrollingCallback?.invoke(shelf.id, it) }
    }
    DisposableEffect(shelf.id) { onDispose { scrollingCallback?.invoke(shelf.id, false) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (songs.isNotEmpty()) TextButton(onClick = { player.showAndPlaySong(songs.first(), songs, shelf.title) }) {
                Text(stringResource(R.string.home_feed_play_all))
            }
        }
        when (shelf.layout) {
            HomeShelfLayout.ROWS -> BoxWithConstraints(Modifier.fillMaxWidth()) {
              val pageWidth = maxWidth
              LazyRow(state = rowState, modifier = Modifier.fillMaxWidth(),
                  flingBehavior = rememberSnapFlingBehavior(rowState)) {
                items(shelf.items.chunked(4)) { group ->
                    Column(Modifier.width(pageWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { item ->
                            val song = item.track?.asSong()
                            if (song != null) QuickPickExposure(item.track!!.videoId, exposureEnabled, viewport, exposureVersion, onSeen) { modifier ->
                                HomeCatalogSong(song, player, songs,
                                    onPlay = if (collectionQueue) ({ player.showAndPlaySong(song, songs, shelf.title) }) else null,
                                    modifier = modifier, onSongMenu = onSongMenu)
                            }
                            else HomeCollectionRow(item, onOpen)
                        }
                    }
                }
              }
            }
            HomeShelfLayout.GRID -> LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shelf.items.chunked(3)) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { item -> HomeFeedCard(item, 112, onClick = {
                            item.track?.let { if (collectionQueue) player.showAndPlaySong(it.asSong(), songs, shelf.title)
                                else player.playOnlineRadio(it.asSong()) } ?: onOpen(item)
                        }) }
                    }
                }
            }
            HomeShelfLayout.CARDS -> LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(shelf.items, key = { it.key }) { item -> HomeFeedCard(item, 156, onClick = {
                    item.track?.let { if (collectionQueue) player.showAndPlaySong(it.asSong(), songs, shelf.title)
                        else player.playOnlineRadio(it.asSong()) } ?: onOpen(item)
                }) }
            }
        }
    }
}

@Composable
private fun QuickPickExposure(videoId: String, enabled: Boolean, viewport: Rect, version: Long, onSeen: ((String) -> Unit)?,
    content: @Composable (Modifier) -> Unit) {
    var bounds by remember(videoId) { mutableStateOf(Rect.Zero) }
    var total by remember(videoId) { mutableStateOf(0L) }
    val clipped = bounds.intersect(viewport)
    val halfVisible = total > 0 && clipped.width.coerceAtLeast(0f) * clipped.height.coerceAtLeast(0f) >= total * .5f
    val callback by rememberUpdatedState(onSeen)
    LaunchedEffect(videoId, enabled, halfVisible, version) {
        if (enabled && halfVisible) { delay(1_000); callback?.invoke(videoId) }
    }
    content(if (onSeen == null) Modifier else Modifier.onGloballyPositioned { coordinates ->
        bounds = coordinates.boundsInWindow()
        total = coordinates.size.width.toLong() * coordinates.size.height
    })
}

@Composable
private fun HomeFeedCard(item: HomeFeedItem, width: Int, onClick: () -> Unit) {
    Column(Modifier.width(width.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
        .padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmartImage(item.artwork?.let(::highResolutionYouTubeArtwork), item.title,
            Modifier.size(width.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            targetSize = Size(600, 600), contentScale = ContentScale.Crop)
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun HomeCollectionRow(item: HomeFeedItem, onOpen: (HomeFeedItem) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(16.dp))
        .clickable { onOpen(item) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmartImage(item.artwork?.let(::highResolutionYouTubeArtwork), item.title,
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), targetSize = Size(240, 240), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCollectionSheet(state: OnlineCollectionState, player: PlayerViewModel,
    onDismiss: () -> Unit, onOpen: (HomeFeedItem) -> Unit, onLoadMore: () -> Unit,
    onSongMenu: (com.theveloper.pixelplay.data.model.Song, () -> Unit) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f), contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.item.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.home_feed_close)) }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
            if (state.error != null) item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(state.error)
                    TextButton(onClick = { if (state.feed.continuation != null) onLoadMore() else onOpen(state.item) }) {
                        Text(stringResource(R.string.home_feed_retry)) }
                }
            }
            if (!state.loading && state.error == null && state.feed.shelves.isEmpty() && state.feed.tracks.isEmpty())
                item { Text(stringResource(R.string.home_feed_empty_collection), Modifier.padding(horizontal = 20.dp)) }
            if (state.feed.shelves.isNotEmpty()) {
                items(state.feed.shelves, key = { it.id }) { HomeFeedShelfView(it, player, onOpen, collectionQueue = true, onSongMenu = onSongMenu) }
            } else {
                val songs = state.feed.tracks.map { it.asSong() }
                if (songs.isNotEmpty()) item {
                    FilledTonalButton(onClick = { player.showAndPlaySong(songs.first(), songs, state.item.title) },
                        modifier = Modifier.padding(horizontal = 20.dp)) { Text(stringResource(R.string.home_feed_play_all)) }
                }
                items(songs, key = { it.id }) { song ->
                    HomeCatalogSong(song, player, songs, onPlay = { player.showAndPlaySong(song, songs, state.item.title) }, onSongMenu = onSongMenu)
                }
            }
            if (state.feed.continuation != null) item {
                TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(if (state.loadingMore) R.string.home_feed_loading_more else R.string.home_feed_load_more))
                }
            }
        }
    }
}
