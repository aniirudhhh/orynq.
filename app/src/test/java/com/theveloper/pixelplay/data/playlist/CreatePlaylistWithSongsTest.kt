package com.theveloper.pixelplay.data.playlist

import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.youtube.OnlineTrack
import com.theveloper.pixelplay.data.youtube.YouTubeRepository
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CreatePlaylistWithSongsTest {
    private val playlists = mockk<PlaylistPreferencesRepository>()
    private val youtube = mockk<YouTubeRepository>()
    private val create = CreatePlaylistWithSongs(playlists,youtube)
    private val online = OnlineTrack("aaaaaaaaaaa","Song","Artist",null).asSong()
    @Test fun createsPlaylistWithSelectedOnlineSongAndRetainsMetadataFirst() = runTest {
        val result = Playlist("new","Mix",listOf(online.id))
        every { youtube.retainPlaylistSong(online) } just Runs
        coEvery { playlists.createPlaylist(name="Mix",songIds=listOf(online.id)) } returns result
        assertEquals(result,create("  Mix  ",listOf(online)))
        coVerifyOrder { youtube.retainPlaylistSong(online); playlists.createPlaylist(name="Mix",songIds=listOf(online.id)) }
    }
    @Test fun createsMixedPlaylistInSelectedOrderWithoutDuplicates() = runTest {
        val local = online.copy(id="12",contentUriString="content://media/12")
        val ids = listOf(local.id,online.id)
        every { youtube.retainPlaylistSong(online) } just Runs
        coEvery { playlists.createPlaylist(name="Mix",songIds=ids) } returns Playlist("new","Mix",ids)
        assertEquals(ids,create("Mix",listOf(local,online,local)).songIds)
        verify(exactly=1) { youtube.retainPlaylistSong(online) }
        verify(exactly=0) { youtube.retainPlaylistSong(local) }
    }
    @Test fun rejectsBlankNameEmptySelectionAndMalformedOnlineIdentity() = runTest {
        for ((name,songs) in listOf(" " to listOf(online), "Mix" to emptyList(), "Mix" to listOf(online.copy(id="youtube:invalid")))) {
            try { create(name,songs); fail("Expected invalid selection") } catch (_:IllegalArgumentException) {}
        }
        verify(exactly=0) { youtube.retainPlaylistSong(any()) }
        coVerify(exactly=0) { playlists.createPlaylist(name=any(),songIds=any()) }
    }
    @Test fun metadataFailureDoesNotCreateAnUnplayablePlaylist() = runTest {
        every { youtube.retainPlaylistSong(online) } throws IllegalStateException("storage unavailable")
        try { create("Mix",listOf(online)); fail("Expected storage failure") } catch (_:IllegalStateException) {}
        coVerify(exactly=0) { playlists.createPlaylist(name=any(),songIds=any()) }
    }
    @Test fun playlistWriteFailureIsPropagatedInsteadOfReportingSuccess() = runTest {
        every { youtube.retainPlaylistSong(online) } just Runs
        coEvery { playlists.createPlaylist(name="Mix",songIds=listOf(online.id)) } throws IllegalStateException("database unavailable")
        try { create("Mix",listOf(online)); fail("Expected write failure") } catch (_:IllegalStateException) {}
    }
    @Test fun cancellationRemainsCancellation() = runTest {
        every { youtube.retainPlaylistSong(online) } just Runs
        coEvery { playlists.createPlaylist(name="Mix",songIds=listOf(online.id)) } throws CancellationException()
        try { create("Mix",listOf(online)); fail("Expected cancellation") } catch (_:CancellationException) {}
    }
}
