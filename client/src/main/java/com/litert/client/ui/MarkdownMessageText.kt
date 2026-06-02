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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.litert.client.ui.theme.AccentGreen

/**
 * 纯 Compose 原生轻量级 Markdown 渲染器（零第三方依赖）
 *
 * 支持：标题(#)、加粗(**)、斜体(*)、行内代码(`)、代码块(```)、无序列表(- / *)、表格(|)
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
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // 1. 表格检测与聚合渲染
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    tableLines.add(lines[i])
                    i++
                }
                RenderTable(tableLines)
                continue
            }

            // 2. 代码块开关检测
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    // 结束代码块：渲染积累的代码行
                    CodeBlockText(codeBlockLines.joinToString("\n"))
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                i++
                continue
            }

            // 3. 普通行：按 Markdown 语法解析
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
            i++
        }

        // 如果文本在流式传输中截断（代码块未闭合），仍然渲染已有内容
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            CodeBlockText(codeBlockLines.joinToString("\n"))
        }
    }
}

/**
 * 结构化表格原生 Compose 渲染组件（专为手机窄屏进行了卡片化及列对齐优化）
 */
@Composable
fun RenderTable(tableLines: List<String>) {
    // 解析表格的所有单元格，剥离首尾的 pipe 并进行 trim 过滤
    val rows = tableLines.map { line ->
        line.split("|")
            .map { it.trim() }
            .filterIndexed { index, _ -> index > 0 && index < line.split("|").size - 1 }
    }.filter { row ->
        // 过滤掉表格中的 :--- 或 --- 分隔行
        row.none { it.contains("---") }
    }

    if (rows.isEmpty()) return

    val headers = rows.firstOrNull() ?: return
    val dataRows = rows.drop(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
    ) {
        // 1. 特别优化：如果是标准的 3 列电话表格 (使用单位 | 长号/号码 | 短号)
        if (headers.size == 3 && (headers[1].contains("号") || headers[2].contains("号"))) {
            dataRows.forEachIndexed { index, row ->
                if (row.size >= 3) {
                    val unit = row[0]
                    val tel = row[1]
                    val shortTel = row[2]

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = unit,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = tel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentGreen
                            )
                            if (shortTel.isNotEmpty()) {
                                Text(
                                    text = "短号: $shortTel",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    if (index < dataRows.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        )
                    }
                }
            }
        } else {
            // 2. 通用自适应表格排版：在窄屏上按列权重均匀网格对齐
            rows.forEachIndexed { rowIndex, row ->
                val isHeader = rowIndex == 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isHeader) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            fontSize = if (isHeader) 13.sp else 14.sp,
                            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                            color = if (isHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        )
                    }
                }

                if (rowIndex < rows.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    )
                }
            }
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
