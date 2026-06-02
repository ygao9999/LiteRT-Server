package com.litert.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip

/**
 * 纯 Compose 原生轻量级 Markdown 渲染器（零第三方依赖）
 *
 * 支持：标题(#)、加粗(**)、斜体(*)、行内代码(`)、代码块(```)、无序列表(- / *)
 */
@Composable
fun MarkdownMessageText(
    content: String,
    modifier: Modifier = Modifier
) {
    val lines = content.lines()
    var inCodeBlock = false
    val codeBlockLines = mutableListOf<String>()

    Column(modifier = modifier) {
        for (line in lines) {
            // 代码块开关检测
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    // 结束代码块：渲染积累的代码行
                    CodeBlockText(codeBlockLines.joinToString("\n"))
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                continue
            }

            // 普通行：按 Markdown 语法解析
            when {
                line.startsWith("### ") -> {
                    Text(
                        text = parseInlineMarkdown(line.removePrefix("### ")),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                line.startsWith("## ") -> {
                    Text(
                        text = parseInlineMarkdown(line.removePrefix("## ")),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                line.startsWith("# ") -> {
                    Text(
                        text = parseInlineMarkdown(line.removePrefix("# ")),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = line.length - line.trimStart().length
                    val bulletContent = line.trimStart().removePrefix("- ").removePrefix("* ")
                    Text(
                        text = buildAnnotatedString {
                            append("  ".repeat(indent / 2))
                            append("• ")
                            append(parseInlineMarkdown(bulletContent))
                        },
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                line.isBlank() -> {
                    // 空行保留为间距
                }
                else -> {
                    Text(
                        text = parseInlineMarkdown(line),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        // 如果文本在流式传输中截断（代码块未闭合），仍然渲染已有内容
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            CodeBlockText(codeBlockLines.joinToString("\n"))
        }
    }
}

/**
 * 代码块渲染组件（深色背景 + 等宽字体）
 */
@Composable
private fun CodeBlockText(code: String) {
    Text(
        text = code,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFFE0E0E0),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .padding(12.dp)
    )
}

/**
 * 解析行内 Markdown 格式：**加粗**、*斜体*、`行内代码`
 */
private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // 行内代码 `code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x20808080),
                            fontSize = 14.sp
                        )) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // 加粗 **text**
                i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // 斜体 *text*
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
