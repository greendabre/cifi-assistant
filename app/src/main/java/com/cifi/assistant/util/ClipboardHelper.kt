package com.cifi.assistant.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object ClipboardHelper {

    fun copyToClipboard(context: Context, label: String, text: String, showToast: Boolean = true) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        if (showToast) {
            Toast.makeText(context, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
