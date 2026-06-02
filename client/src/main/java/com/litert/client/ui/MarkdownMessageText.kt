package com.litert.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun MarkdownMessageText(
    content: String,
    modifier: Modifier = Modifier
) {
    MarkdownText(
        markdown = content,
        modifier = modifier,
        style = TextStyle(
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp
        )
    )
}
