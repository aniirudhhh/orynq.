package com.theveloper.pixelplay.data.youtube

import org.junit.Assert.*
import org.junit.Test

class OnlineSongActionsTest {
    private fun song(id: Char) = OnlineTrack(id.toString().repeat(11), "Song $id", "Artist", null).asSong()
    @Test fun sharesPublicMusicLinkAndRejectsInvalidOrLocalIds() {
        val song = song('a')
        assertEquals("https://music.youtube.com/watch?v=aaaaaaaaaaa", musicShareUrl(song))
        assertNull(musicShareUrl(song.copy(id = "123")))
        assertNull(musicShareUrl(song.copy(id = "youtube:invalid?cookie=private")))
    }
    @Test fun mixedPlaylistPreservesOrderAndDuplicateSelections() {
        val local = song('a').copy(id = "123", contentUriString = "content://media/123")
        val online = OnlineTrack("bbbbbbbbbbb", "Online song", "Artist", null)
        assertEquals(listOf(online.asSong(),local,online.asSong()),resolveOnlinePlaylistSongs(
            listOf("youtube:bbbbbbbbbbb","123","missing","youtube:bbbbbbbbbbb"),listOf(local),listOf(online)))
    }
    @Test fun playlistTracksKeepStablePlaybackUriAndMetadataAfterReload() {
        val tracks = listOf(OnlineTrack("aaaaaaaaaaa","Saved online song","Artist","https://example.com/art",123_000))
        val stored = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(OnlineTrack.serializer()),tracks)
        val restored = kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(OnlineTrack.serializer()),stored)
        val song = resolveOnlinePlaylistSongs(listOf("youtube:aaaaaaaaaaa"),emptyList(),restored).single()
        assertEquals("youtube://aaaaaaaaaaa",song.contentUriString)
        assertEquals("Saved online song",song.title)
        assertEquals(123_000L,song.duration)
    }
    @Test fun playNextInsertsAfterCurrentAndQueueAppendRetainsOriginalOrder() {
        val a=song('a');val b=song('b');val c=song('c');val d=song('d')
        assertEquals(listOf(a,b,d,c),queueWithAddedSong(listOf(a,b,c),d,b.id,true))
        assertEquals(listOf(a,b,c,d),queueWithAddedSong(listOf(a,b,c),d,b.id,false))
    }
    @Test fun emptyQueueMissingCurrentAndRepeatedSongsRemainValid() {
        val a=song('a');val b=song('b')
        assertEquals(listOf(a),queueWithAddedSong(emptyList(),a,null,true))
        assertEquals(listOf(a,b),queueWithAddedSong(listOf(a),b,"missing",true))
        assertEquals(listOf(a,a),queueWithAddedSong(listOf(a),a,a.id,true))
    }
}
