package moe.shizuku.manager.module.catalog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the GitHub token encrypted with a key that never leaves the Android Keystore.
 *
 * Older builds stored the PAT as plain text in SharedPreferences. [getToken] transparently
 * migrates such a value: it is encrypted, written under [KEY_GITHUB_PAT_ENCRYPTED], and the
 * plain-text entry is removed in the same commit.
 */
object TokenStore {
    private const val TAG = "TokenStore"
    private const val PREFS_NAME = "catalog_token_prefs"

    /** Legacy plain-text entry, kept only for migration. */
    private const val KEY_GITHUB_PAT = "github_pat"
    private const val KEY_GITHUB_PAT_ENCRYPTED = "github_pat_encrypted"

    private const val KEY_ALIAS = "nightzuku.catalog.github_pat"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val SEPARATOR = ":"

    private val lock = Any()

    /**
     * Test seam: Robolectric has no AndroidKeyStore provider, so unit tests can inject
     * a plain JVM AES key. Production always leaves this null.
     */
    @Volatile
    internal var keyProviderOverride: (() -> SecretKey)? = null

    private fun getPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Migrates a legacy plain-text token during application startup, before any screen
     * reads it. Safe to call repeatedly; only the first call with a legacy value writes.
     */
    fun migrateLegacyToken(context: Context) {
        synchronized(lock) {
            val prefs = getPrefs(context)
            if (!prefs.contains(KEY_GITHUB_PAT)) {
                return
            }
            val legacy = prefs.getString(KEY_GITHUB_PAT, null)
            if (!legacy.isNullOrBlank()) {
                setToken(context, legacy)
            } else {
                prefs.edit().remove(KEY_GITHUB_PAT).commit()
            }
        }
    }

    fun getToken(context: Context): String? = synchronized(lock) {
        val prefs = getPrefs(context)

        prefs.getString(KEY_GITHUB_PAT_ENCRYPTED, null)?.let { stored ->
            val decrypted = decrypt(stored)
            if (decrypted != null) {
                return decrypted
            }
            // The key was invalidated or the payload is corrupt; drop it instead of
            // repeatedly failing, then fall through to the legacy migration path.
            prefs.edit().remove(KEY_GITHUB_PAT_ENCRYPTED).commit()
        }

        val legacy = prefs.getString(KEY_GITHUB_PAT, null)
        if (!legacy.isNullOrBlank()) {
            setToken(context, legacy)
            return legacy
        }
        return null
    }

    /**
     * Encrypts and persists [token]. The plain-text legacy entry is removed even when
     * encryption is unavailable, so a token is never written to disk unencrypted.
     */
    fun setToken(context: Context, token: String): Boolean = synchronized(lock) {
        val trimmed = token.trim()
        val editor = getPrefs(context).edit()
            .remove(KEY_GITHUB_PAT)
            .remove(KEY_GITHUB_PAT_ENCRYPTED)

        if (trimmed.isEmpty()) {
            editor.commit()
            return true
        }

        val encrypted = encrypt(trimmed)
        if (encrypted == null) {
            editor.commit()
            Log.e(TAG, "Could not encrypt the GitHub token; it was not persisted")
            return false
        }
        return editor.putString(KEY_GITHUB_PAT_ENCRYPTED, encrypted).commit()
    }

    fun clearToken(context: Context) {
        synchronized(lock) {
            getPrefs(context).edit()
                .remove(KEY_GITHUB_PAT)
                .remove(KEY_GITHUB_PAT_ENCRYPTED)
                .commit()
        }
    }

    fun isValidTokenFormat(token: String): Boolean {
        val trimmed = token.trim()
        return trimmed.length >= 30 &&
                (trimmed.startsWith("ghp_") || trimmed.startsWith("github_pat_"))
    }

    private fun encrypt(value: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(iv, Base64.NO_WRAP) +
                SEPARATOR +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }.getOrElse {
            Log.e(TAG, "Unable to encrypt the GitHub token", it)
            null
        }
    }

    private fun decrypt(payload: String): String? {
        return runCatching {
            val parts = payload.split(SEPARATOR, limit = 2)
            require(parts.size == 2) { "Malformed encrypted token" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "Stored GitHub token could not be decrypted", it)
            deleteKey()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        keyProviderOverride?.let { return it() }

        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
            return it
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }
}
