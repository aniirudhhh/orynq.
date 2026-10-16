package com.theveloper.pixelplay.data.youtube

import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AtomicListeningSyncStoreTest {
    @Test fun queueAndConsentRoundTripWithoutPersistingCredentialsOrTrackingUrls() = runTest {
        val dir = Files.createTempDirectory("orynq-sync").toFile()
        val store = AtomicListeningSyncStore(dir)
        val ticket = ListeningSyncTicket("account", "revision", "abcdefghijk")
        val record = ListeningSyncRecord("account", true, "revision", listOf(PendingListen(ticket, 40_000, 123)))
        store.write(record)
        assertEquals(record, store.read())
        val raw = File(dir, "state.json").readText()
        assertFalse(raw.contains("https://"))
        assertFalse(raw.contains("cookie", ignoreCase = true))
        File(dir, "state.tmp").writeText("interrupted replacement")
        assertEquals(record, store.read())
    }
    @Test fun corruptStateCannotEnableReporting() = runTest {
        val dir = Files.createTempDirectory("orynq-sync-corrupt").toFile()
        File(dir, "state.json").writeText("{broken")
        assertNull(AtomicListeningSyncStore(dir).read())
    }
}
