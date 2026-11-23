package io.github.milahajek.hayek

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Obnovovací fráze je zašifrovaná klíčem uloženým v bezpečném čipu telefonu (Android Keystore).
 * Klíč nejde z telefonu vytáhnout a použít ho jde jen do 60 s po ověření otiskem / PINem.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val alias = "seed_key_v1"

    fun hasSeed() = prefs.contains("seed")

    // ---------- Údaje pro práci na pozadí (upozornění na platby) ----------
    // Jen VEŘEJNÝ deskriptor (adresy, ne klíče). Šifrovaný klíčem bez ověření otiskem, aby ho šlo
    // přečíst i bez odemčení – chrání před vynesením souboru, ne před aplikací samotnou.
    private val bgAlias = "bg_key_v1"
    private fun bgKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(bgAlias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(bgAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
    fun saveBg(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, bgKey()) }
        val ct = c.doFinal(value.toByteArray())
        prefs.edit().putString("bg_$name", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }
    fun loadBg(name: String): String? {
        val v = prefs.getString("bg_$name", null) ?: return null
        val (iv, ct) = v.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, bgKey(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct))
    }
    fun removeBg(name: String) = prefs.edit().remove("bg_$name").apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                60, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            // Přidání nového otisku nesmí peněženku zamknout navždy.
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(spec)
        return kg.generateKey()
    }

    /** Musí se volat do 60 s po úspěšném ověření. */
    fun saveSeed(words: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(words.toByteArray())
        prefs.edit()
            .putString("seed", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .commit()
    }

    /** Musí se volat do 60 s po úspěšném ověření. */
    fun loadSeed(): String {
        val ct = Base64.decode(prefs.getString("seed", null), Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct))
    }

    /** Další tajný údaj (např. přístupový klíč k Lightning uzlu), šifrovaný stejným klíčem. Do 60 s po ověření. */
    fun saveExtra(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray())
        prefs.edit()
            .putString("x_$name", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString("x_${name}_iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .commit()
    }

    fun hasExtra(name: String) = prefs.contains("x_$name")

    /** Do 60 s po ověření. */
    fun loadExtra(name: String): String? {
        val ct = prefs.getString("x_$name", null) ?: return null
        val iv = prefs.getString("x_${name}_iv", null) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }

    fun removeExtra(name: String) {
        prefs.edit().remove("x_$name").remove("x_${name}_iv").commit()
    }

    // ---- Hlídač otisků: pozná, že do telefonu někdo přidal nový otisk prstu ----
    private val canaryAlias = "bio_canary_v1"

    /** Vytvoří hlídací klíč, který Android zneplatní, jakmile se změní otisky prstů. */
    fun ensureBioCanary() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(canaryAlias)) return
            val spec = KeyGenParameterSpec.Builder(canaryAlias, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        } catch (_: Exception) {
            // telefon bez otisku – hlídač nejde vytvořit, silné ověření pak stejně používá PIN
        }
    }

    /** true = od posledního ověření se změnily otisky prstů (někdo přidal / odebral otisk). */
    fun fingerprintsChanged(): Boolean = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val k = ks.getKey(canaryAlias, null) as? SecretKey
        if (k == null) false else { Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, k); false }
    } catch (e: KeyPermanentlyInvalidatedException) {
        true
    } catch (e: Exception) {
        false
    }

    fun resetBioCanary() {
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(canaryAlias) } catch (_: Exception) {}
        ensureBioCanary()
    }

    fun wipe() {
        prefs.edit().clear().commit()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply { deleteEntry(alias); deleteEntry(canaryAlias); deleteEntry(bgAlias) }
        } catch (_: Exception) {}
    }
}

/** Je na telefonu nastavený zámek obrazovky / otisk? */
fun canAuthenticate(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/** Má telefon nastavený otisk prstu (nebo jinou silnou biometrii)? */
fun hasStrongBiometric(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * @param strong true = jen otisk prstu (PIN nestačí). Používá se pro odeslání platby, zobrazení
 *  slov a smazání peněženky – PIN může někdo odkoukat, otisk ne. Když telefon otisk nemá, použije se PIN.
 */
fun authenticate(activity: FragmentActivity, title: String, strong: Boolean, onOk: () -> Unit, onFail: (String) -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED)
                    onFail(errString.toString())
            }
        })
    val fingerprintOnly = strong && hasStrongBiometric(activity)
    val b = BiometricPrompt.PromptInfo.Builder().setTitle(title)
    if (fingerprintOnly) {
        b.setSubtitle(L("Potvrďte otiskem prstu", "Confirm with your fingerprint"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(L("Zrušit", "Cancel"))
    } else {
        b.setSubtitle(L("Ověřte se otiskem prstu nebo PINem", "Verify with fingerprint or PIN")).setAllowedAuthenticators(AUTHENTICATORS)
    }
    prompt.authenticate(b.build())
}
