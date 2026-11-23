package io.github.milahajek.hayek

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Jazyk aplikace: čeština, nebo angličtina. Výchozí podle telefonu (česky / slovensky → čeština). */
object Lang {
    /** "auto", "cs", "en" */
    var mode by mutableStateOf("auto")
        private set
    var english by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        val m = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        apply(m)
    }

    fun set(ctx: Context, m: String) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", m).apply()
        apply(m)
    }

    private fun apply(m: String) {
        mode = m
        english = when (m) {
            "cs" -> false
            "en" -> true
            else -> Locale.getDefault().language !in setOf("cs", "sk")
        }
    }
}

/** Text v aktuálním jazyce. */
fun L(cs: String, en: String): String = if (Lang.english) en else cs
