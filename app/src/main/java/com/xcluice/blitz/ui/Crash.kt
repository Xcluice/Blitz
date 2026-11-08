package com.xcluice.blitz.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CrashScreen(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Blitz crashed last time", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Text(
            text = "Send this report so it can be fixed.",
            color = Pal.Ink2,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        Glass(Modifier.weight(1f).fillMaxWidth(), radius = 22.dp) {
            SelectionContainer {
                Text(
                    text = report,
                    color = Pal.Ink,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            GhostButton("Copy", Modifier.weight(1f), onClick = onCopy)
            Spacer(Modifier.padding(5.dp))
            PrimaryButton("Dismiss", Modifier.weight(1f), onClick = onDismiss)
        }
    }
}
