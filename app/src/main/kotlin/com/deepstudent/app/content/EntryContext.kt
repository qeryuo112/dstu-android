package com.deepstudent.app.content

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

internal tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.activityOrNull()
    else -> null
}

internal fun logOpenFailure(owner: String, failure: Throwable) {
    Log.e("DstuS115Entry", "stage=open-failed owner=$owner type=${failure.javaClass.simpleName}")
}

@Composable
internal fun EntryUnavailable(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label) }
}
