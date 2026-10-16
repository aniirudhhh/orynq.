package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import coil.size.Size

@Composable
internal fun HomeFeaturedSong(song: Song, onPlay: () -> Unit) {
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(260.dp)
        .clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onPlay)) {
        SmartImage(song.albumArtUriString, song.title, Modifier.fillMaxSize(), targetSize = Size(1200, 1200), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
        IconButton(onClick = onPlay, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Icon(Icons.Rounded.PlayArrow, "Play ${song.title}", tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = .85f))
        }
    }
}

@Composable
internal fun HomeCatalogSong(song: Song, player: PlayerViewModel, queue: List<Song>, onPlay: (() -> Unit)? = null,
    modifier: Modifier = Modifier, onSongMenu: (Song, () -> Unit) -> Unit) {
    val playback by player.stablePlayerState.collectAsStateWithLifecycle()
    val play = onPlay ?: { if (song.id.startsWith("youtube:")) player.playOnlineRadio(song) else player.showAndPlaySong(song, queue, "YouTube Music") }
    Box(modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem(
            song = song,
            isPlaying = playback.isPlaying,
            isCurrentSong = playback.currentSong?.id == song.id,
            onMoreOptionsClick = { onSongMenu(song, play) },
            onLongPress = { onSongMenu(song, play) },
            onClick = play
        )
    }
}
