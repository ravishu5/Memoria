package dev.miniscreenpipe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Prefs(context: Context) {
    private val ownPackage=context.packageName
    val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        // Rescue selections written by the earlier Search picker under the wrong preference name.
        p.getString("gemini_model",null)?.let { legacy ->
            val edit=p.edit().remove("gemini_model")
            if(legacy in FLASH_LITE_MODELS)edit.putString("model",legacy)
            edit.apply()
        }
    }
    fun saveModel(value:String) { require(value in FLASH_LITE_MODELS) { "Choose a supported Flash-Lite model" };p.edit().putString("model",value).remove("gemini_model").apply() }
    var recording: Boolean
        get() = p.getBoolean("recording", false)
        // This is the user's persistent recording intent. Flush before the process
        // can be killed, including an explicit Pause during shutdown/reconnection.
        set(value) { p.edit().putBoolean("recording", value).commit() }
    val adaptive get() = p.getBoolean("adaptive", true)
    val redactText get() = p.getBoolean("redact_text", false)
    val blockedDomains get() = p.getString("blocked_domains", "")!!
    val grayscale get() = p.getBoolean("grayscale", false)
    val resolution get() = p.getInt("resolution", 720).takeIf { it in setOf(0,480,720,1080) } ?: 720
    val foreground get() = p.getBoolean("foreground", false)
    val appearance get() = p.getString("appearance", "dark")!!
    val privacy get() = p.getBoolean("privacy", false)
    val selectedApps get() = p.getStringSet("selected_apps", emptySet())!!.toSet()
    val onlySelected get() = p.getBoolean("only_selected", false)
    val interval get() = p.getInt("interval", 30)
    val retention get() = p.getInt("retention", 7)
    val exclusions get() = p.getString("exclusions", "bank\npassword\n1password\nauthenticator\nwallet\nbitwarden\nkeepass")!!
    val model get() = p.getString("model", FLASH_LITE_MODELS.first()).takeIf { it in FLASH_LITE_MODELS } ?: FLASH_LITE_MODELS.first()
    fun excluded(pkg: String): Boolean = pkg in setOf(ownPackage, "com.android.systemui", "com.android.settings") ||
        (onlySelected && pkg !in selectedApps) || exclusions.split('\n', ',').any { it.isNotBlank() && pkg.contains(it.trim(), ignoreCase = true) }
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias("mini-gemini")) return store.getKey("mini-gemini", null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mini-gemini", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun saveKey(key: String) {
        if (key.isBlank()) { p.edit().remove("key").remove("iv").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        p.edit().putString("key", Base64.encodeToString(cipher.doFinal(key.trim().toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
    fun key(): String {
        if (!p.contains("key")) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(p.getString("key", ""), Base64.NO_WRAP)), Charsets.UTF_8)
    }
    companion object {
        val FLASH_LITE_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite"
        )
    }
}
