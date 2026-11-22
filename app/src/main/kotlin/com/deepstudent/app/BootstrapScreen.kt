package com.deepstudent.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deepstudent.design.DstuBrandMark
import com.deepstudent.design.DstuTheme

@Composable
fun BootstrapScreen() {
    DstuTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DstuBrandMark(contentDescription = null, modifier = Modifier.size(72.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
