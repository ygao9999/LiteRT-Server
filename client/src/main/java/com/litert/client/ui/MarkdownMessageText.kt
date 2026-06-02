package com.litert.client.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MarkdownMessageText(
    content: String,
    modifier: Modifier = Modifier
) {
    Text(text = content, modifier = modifier)
}
