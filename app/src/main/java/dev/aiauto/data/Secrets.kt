package dev.aiauto.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.aiauto.core.ChatProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API keys and GitHub token encrypted with a key that never leaves the Android Keystore.
 */
class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    var anthropicKey: String?
        get() = read(KEY_ANTHROPIC)
        set(value) = write(KEY_ANTHROPIC, value)

    var githubToken: String?
        get() = read(KEY_GITHUB)
        set(value) = write(KEY_GITHUB, value)

    /** Optional, for a car chat on an OpenAI model. */
    var openAiKey: String?
        get() = read(KEY_OPENAI)
        set(value) = write(KEY_OPENAI, value)

    /** Optional, for a car chat on a Gemini model. */
    var geminiKey: String?
        get() = read(KEY_GEMINI)
        set(value) = write(KEY_GEMINI, value)

    /** The key the car chat needs for [provider]'s models. */
    fun chatKey(provider: ChatProvider): String? = when (provider) {
        ChatProvider.Anthropic -> anthropicKey
        ChatProvider.OpenAI -> openAiKey
        ChatProvider.Gemini -> geminiKey
    }

    fun setChatKey(provider: ChatProvider, value: String?) {
        when (provider) {
            ChatProvider.Anthropic -> anthropicKey = value
            ChatProvider.OpenAI -> openAiKey = value
            ChatProvider.Gemini -> geminiKey = value
        }
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun write(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val encrypted = runCatching { encrypt(value.trim()) }.getOrElse {
            // The Keystore key can become unusable (for example after a lock screen reset). Start over with a new
            // key; secrets saved with the old key can't be read anymore and need to be entered again.
            resetKey()
            encrypt(value.trim())
        }
        prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun resetKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "aiauto-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_ANTHROPIC = "anthropic"
        const val KEY_GITHUB = "github"
        const val KEY_OPENAI = "openai"
        const val KEY_GEMINI = "gemini"
    }
}
