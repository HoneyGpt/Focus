package com.example

import android.content.Context
import android.content.SharedPreferences

object QuoteManager {
    private const val PREF_QUOTE = "FocusQuotePrefs"
    private const val KEY_USER_QUOTE = "user_motivational_quote"
    private const val DEFAULT_QUOTE = "Success is the sum of small efforts, repeated day in and day out."

    fun saveQuote(context: Context, newQuote: String) {
        val prefs = context.getSharedPreferences(PREF_QUOTE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_QUOTE, newQuote.trim()).apply()
    }

    fun getSavedQuote(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_QUOTE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_QUOTE, DEFAULT_QUOTE) ?: DEFAULT_QUOTE
    }
}
