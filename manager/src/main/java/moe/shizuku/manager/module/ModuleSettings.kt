package moe.shizuku.manager.module

import androidx.annotation.StringRes
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ModuleSettings {

    private const val KEY_ACCESS_MODE = "adb_modules_access_mode"
    private const val KEY_ALLOW_BACKGROUND_ACTIONS = "adb_modules_allow_background_actions"
    private const val KEY_CUSTOM_ACTION = "adb_modules_custom_action"
    private const val KEY_CUSTOM_SERVICE = "adb_modules_custom_service"
    private const val KEY_CUSTOM_WEB_BRIDGE = "adb_modules_custom_web_bridge"
    private const val KEY_CUSTOM_WEB_NETWORK = "adb_modules_custom_web_network"
    private const val KEY_CUSTOM_WEB_DOWNLOAD = "adb_modules_custom_web_download"
    private const val KEY_RECOMMAND_WEBUI = "adb_modules_recommand_webui"
    private const val KEY_RECOMMAND_ACTION = "adb_modules_recommand_action"
    private const val KEY_AI_MODEL = "adb_modules_ai_model"
    private const val KEY_AI_API_KEY = "adb_modules_ai_api_key"
    private const val KEY_AI_API_KEY_ENCRYPTED = "adb_modules_ai_api_key_encrypted"
    private const val KEY_AI_API_KEY_IV = "adb_modules_ai_api_key_iv"
    private const val KEY_AI_CHECKER_UNLOCKED = "adb_modules_ai_checker_unlocked"

    enum class AccessMode(
        val value: String,
        @param:StringRes val labelRes: Int,
        @param:StringRes val summaryRes: Int
    ) {
        SAFE(
            "safe",
            R.string.modules_access_mode_safe,
            R.string.modules_access_mode_safe_summary
        ),
        CUSTOM(
            "custom",
            R.string.modules_access_mode_custom,
            R.string.modules_access_mode_custom_summary
        ),
        FULL(
            "full",
            R.string.modules_access_mode_full,
            R.string.modules_access_mode_full_summary
        );

        companion object {
            fun fromValue(value: String?): AccessMode {
                return entries.firstOrNull { it.value == value } ?: SAFE
            }
        }
    }

    enum class AiModel(val modelId: String, @param:StringRes val labelRes: Int) {
        GEMINI_31_PRO(
            "gemini-3.1-pro-preview",
            R.string.modules_ai_model_gemini_31_pro
        ),
        GEMINI_3_FLASH(
            "gemini-3-flash-preview",
            R.string.modules_ai_model_gemini_3_flash
        ),
        GEMINI_31_FLASH_LITE(
            "gemini-3.1-flash-lite-preview",
            R.string.modules_ai_model_gemini_31_flash_lite
        );

        companion object {
            fun fromId(modelId: String?): AiModel {
                return entries.firstOrNull { it.modelId == modelId } ?: GEMINI_3_FLASH
            }
        }
    }

    data class CustomPermissions(
        val action: Boolean,
        val service: Boolean,
        val webBridge: Boolean,
        val webNetwork: Boolean,
        val webDownload: Boolean
    )

    fun getAccessMode(): AccessMode {
        return AccessMode.fromValue(
            ShizukuSettings.getPreferences().getString(KEY_ACCESS_MODE, AccessMode.SAFE.value)
        )
    }

    fun setAccessMode(mode: AccessMode) {
        ShizukuSettings.getPreferences().edit().putString(KEY_ACCESS_MODE, mode.value).apply()
    }

    fun allowBackgroundActions(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, false)
    }

    fun setAllowBackgroundActions(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, value).apply()
    }

    fun getCustomPermissions(): CustomPermissions {
        val prefs = ShizukuSettings.getPreferences()
        return CustomPermissions(
            action = prefs.getBoolean(KEY_CUSTOM_ACTION, false),
            service = prefs.getBoolean(KEY_CUSTOM_SERVICE, false),
            webBridge = prefs.getBoolean(KEY_CUSTOM_WEB_BRIDGE, false),
            webNetwork = prefs.getBoolean(KEY_CUSTOM_WEB_NETWORK, false),
            webDownload = prefs.getBoolean(KEY_CUSTOM_WEB_DOWNLOAD, false)
        )
    }

    fun setCustomPermissions(value: CustomPermissions) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_CUSTOM_ACTION, value.action)
            .putBoolean(KEY_CUSTOM_SERVICE, value.service)
            .putBoolean(KEY_CUSTOM_WEB_BRIDGE, value.webBridge)
            .putBoolean(KEY_CUSTOM_WEB_NETWORK, value.webNetwork)
            .putBoolean(KEY_CUSTOM_WEB_DOWNLOAD, value.webDownload)
            .apply()
    }

    fun canRunAction(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().action
        }
    }

    fun canRunService(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().service
        }
    }

    fun canExposeWebBridge(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webBridge
        }
    }

    fun canUseWebNetwork(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> false
            AccessMode.CUSTOM -> getCustomPermissions().webNetwork
        }
    }

    fun canDownloadWebFiles(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webDownload
        }
    }

    fun recommandForWebUi(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_RECOMMAND_WEBUI, true)
    }

    fun setRecommandForWebUi(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_RECOMMAND_WEBUI, value).apply()
    }

    fun recommandForAction(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_RECOMMAND_ACTION, true)
    }

    fun setRecommandForAction(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_RECOMMAND_ACTION, value).apply()
    }

    fun getAiModel(): AiModel {
        return AiModel.fromId(ShizukuSettings.getPreferences().getString(KEY_AI_MODEL, null))
    }

    fun setAiModel(value: AiModel) {
        ShizukuSettings.getPreferences().edit().putString(KEY_AI_MODEL, value.modelId).apply()
    }

    fun getAiApiKey(): String {
        val prefs = ShizukuSettings.getPreferences()
        val encrypted = prefs.getString(KEY_AI_API_KEY_ENCRYPTED, null)
        val iv = prefs.getString(KEY_AI_API_KEY_IV, null)
        if (!encrypted.isNullOrBlank() && !iv.isNullOrBlank()) {
            return decryptApiKey(encrypted, iv).getOrDefault("")
        }

        val legacy = prefs.getString(KEY_AI_API_KEY, "")?.trim().orEmpty()
        if (legacy.isNotBlank()) {
            setAiApiKey(legacy)
            return legacy
        }
        return ""
    }

    fun setAiApiKey(value: String) {
        val clean = value.trim()
        val editor = ShizukuSettings.getPreferences().edit().remove(KEY_AI_API_KEY)
        if (clean.isBlank()) {
            editor
                .remove(KEY_AI_API_KEY_ENCRYPTED)
                .remove(KEY_AI_API_KEY_IV)
                .apply()
            return
        }

        val encrypted = encryptApiKey(clean)
        editor
            .putString(KEY_AI_API_KEY_ENCRYPTED, encrypted.cipherText)
            .putString(KEY_AI_API_KEY_IV, encrypted.iv)
            .apply()
    }

    fun isAiCheckerUnlocked(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_AI_CHECKER_UNLOCKED, false)
    }

    fun setAiCheckerUnlocked(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_AI_CHECKER_UNLOCKED, value).apply()
    }

    private fun encryptApiKey(value: String): EncryptedApiKey {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return EncryptedApiKey(
            cipherText = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    private fun decryptApiKey(cipherText: String, iv: String): Result<String> {
        return runCatching {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(AES_GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(AI_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                AI_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private data class EncryptedApiKey(
        val cipherText: String,
        val iv: String
    )

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AI_KEY_ALIAS = "shizuku_modules_ai_api_key"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_GCM_TAG_BITS = 128
}
