package moe.shizuku.manager.module.catalog

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TokenStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val token = "ghp_" + "a".repeat(36)

    @Before
    fun setUp() {
        // Robolectric does not ship an AndroidKeyStore provider.
        val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TokenStore.keyProviderOverride = { testKey }
        TokenStore.clearToken(context)
    }

    @After
    fun tearDown() {
        TokenStore.keyProviderOverride = null
        TokenStore.clearToken(context)
    }

    @Test
    fun `token round trips through encrypted storage`() {
        assertTrue(TokenStore.setToken(context, token))
        assertEquals(token, TokenStore.getToken(context))

        val stored = prefsFile().readText()
        assertFalse(stored.contains(token))
        assertTrue(stored.contains("github_pat_encrypted"))
    }

    @Test
    fun `legacy plaintext token is migrated and removed`() {
        prefs().edit().putString("github_pat", token).commit()

        assertEquals(token, TokenStore.getToken(context))

        val stored = prefsFile().readText()
        assertFalse(stored.contains(token))
        assertTrue(stored.contains("github_pat_encrypted"))
    }

    @Test
    fun `startup migration encrypts a legacy token`() {
        prefs().edit().putString("github_pat", token).commit()

        TokenStore.migrateLegacyToken(context)

        val stored = prefsFile().readText()
        assertFalse(stored.contains(token))
        assertTrue(stored.contains("github_pat_encrypted"))
        assertEquals(token, TokenStore.getToken(context))
    }

    @Test
    fun `encryption failure never falls back to plaintext`() {
        TokenStore.keyProviderOverride = { throw KeyStoreException("keystore unavailable") }

        assertFalse(TokenStore.setToken(context, token))
        assertNull(prefs().getString("github_pat", null))
        assertNull(prefs().getString("github_pat_encrypted", null))
    }

    @Test
    fun `clear removes both encrypted and legacy entries`() {
        prefs().edit().putString("github_pat", token).commit()
        assertTrue(TokenStore.setToken(context, token))

        TokenStore.clearToken(context)

        assertNull(TokenStore.getToken(context))
        assertNull(prefs().getString("github_pat", null))
        assertNull(prefs().getString("github_pat_encrypted", null))
    }

    private fun prefs() = context.getSharedPreferences("catalog_token_prefs", Context.MODE_PRIVATE)

    private fun prefsFile() = File(context.dataDir, "shared_prefs/catalog_token_prefs.xml")
}
