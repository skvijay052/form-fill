package com.skv.formfill

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts pilgrim and contact profiles locally with an Android Keystore AES-GCM key. */
object SecureProfileStore {
    private const val PREFS = "encrypted_profile"
    private const val CIPHER_TEXT = "payload"
    private const val IV = "iv"
    private const val KEY_ALIAS = "formfill_profile_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(context: Context, json: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHER_TEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun decrypt(context: Context): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString(IV, null) ?: return null
        val data = prefs.getString(CIPHER_TEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8))
        } catch (_: Exception) { null }
    }

    fun save(context: Context, profile: PilgrimProfile) {
        encrypt(context, JSONObject(profile.values()).put("_format", 1).toString())
    }

    fun load(context: Context): PilgrimProfile {
        val json = decrypt(context) ?: return PilgrimProfile()
        fun v(k: String) = json.optString(k, "")
        return PilgrimProfile(v("fullName"), v("age"), v("gender"), v("mobile"), v("email"),
            v("dateOfBirth"), v("idType"), v("idNumber"), v("address"), v("city"), v("state"), v("country"), v("postalCode"))
    }

    fun savePilgrims(context: Context, pilgrims: List<PilgrimProfile>, general: PilgrimProfile) {
        val root = JSONObject()
        root.put("_format", 2)
        root.put("general", JSONObject(general.values()))
        root.put("pilgrims", JSONArray().apply {
            pilgrims.forEach { put(JSONObject(it.values())) }
        })
        encrypt(context, root.toString())
    }

    fun loadPilgrims(context: Context): List<PilgrimProfile> {
        val root = decrypt(context) ?: return listOf(PilgrimProfile())
        if (root.optInt("_format", 1) < 2) return listOf(load(context))
        val array = root.optJSONArray("pilgrims") ?: return listOf(PilgrimProfile())
        if (array.length() == 0) return listOf(PilgrimProfile())
        return (0 until array.length()).map { i -> fromJson(array.optJSONObject(i) ?: JSONObject()) }
    }

    fun loadGeneral(context: Context): PilgrimProfile {
        val root = decrypt(context) ?: return PilgrimProfile()
        if (root.optInt("_format", 1) < 2) return load(context)
        return fromJson(root.optJSONObject("general") ?: JSONObject())
    }

    private fun fromJson(json: JSONObject): PilgrimProfile {
        fun v(k: String) = json.optString(k, "")
        return PilgrimProfile(v("fullName"), v("age"), v("gender"), v("mobile"), v("email"),
            v("dateOfBirth"), v("idType"), v("idNumber"), v("address"), v("city"), v("state"), v("country"), v("postalCode"))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
