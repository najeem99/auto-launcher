package com.najib.carwelcome

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Your HR site username and password, encrypted with a key kept in the phone's keystore. */
object Secrets {
    @Volatile private var sp: SharedPreferences? = null

    private fun store(context: Context): SharedPreferences? = sp ?: synchronized(this) {
        sp ?: runCatching {
            EncryptedSharedPreferences.create(
                "checkin_secrets",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull().also { sp = it }
    }

    fun user(context: Context): String = store(context)?.getString("user", "").orEmpty()
    fun password(context: Context): String = store(context)?.getString("pass", "").orEmpty()

    fun setUser(context: Context, v: String) { store(context)?.edit()?.putString("user", v)?.apply() }
    fun setPassword(context: Context, v: String) { store(context)?.edit()?.putString("pass", v)?.apply() }
}
