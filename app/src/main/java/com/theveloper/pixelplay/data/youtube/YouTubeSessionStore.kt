package com.theveloper.pixelplay.data.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal class StoredMusicSession(val cookie: String, val authUser: String, val name: String, val avatar: String?) {
    override fun toString() = "StoredMusicSession(redacted)"
}

/** No plaintext fallback. Both credentials and the key are excluded from Android backups. */
internal class YouTubeSessionStore(context: Context, filename: String = "youtube_music_session") {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))
    private val alias = "orynq.youtube.music.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): StoredMusicSession? = runCatching {
        val bytes = file.readFully()
        require(bytes.size in 29..70_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        Json.decodeFromString<StoredMusicSession>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
    }.getOrElse { file.delete(); null }
    fun write(session: StoredMusicSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(Json.encodeToString(session).encodeToByteArray())
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) }
        catch (failure: Exception) { file.failWrite(out); throw failure }
    }
    fun clear() = file.delete()
}
