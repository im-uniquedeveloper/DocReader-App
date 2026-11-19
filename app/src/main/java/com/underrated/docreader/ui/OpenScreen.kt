package com.underrated.docreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.underrated.docreader.R
import com.underrated.docreader.pdf.PdfEngine
import java.text.DateFormat
import java.util.Date

@Composable
private fun DocumentMark(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(painterResource(R.drawable.ic_pdf), contentDescription = null, modifier = modifier, tint = tint)
}

private fun formatColor(format: String) = when (format) { "PDF" -> Color(0xFFC43D43); "DOCX" -> Color(0xFF2463B5); "XLSX" -> Color(0xFF218052); else -> Color(0xFF52647A) }

@Composable
private fun FormatBadge(format: String) {
    val color = formatColor(format)
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.10f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DocumentMark(Modifier.size(18.dp), color)
            Text(format, style = MaterialTheme.typography.labelMedium, color = if (LocalDocReaderDark.current) MaterialTheme.colorScheme.onSurface else color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenScreen(lastError: String?, engine: PdfEngine, onEngineChange: (PdfEngine) -> Unit,
    onPickDocument: () -> Unit, recent: List<RecentDocument> = emptyList(),
    onOpenRecent: (RecentDocument) -> Unit = {}) {
    var settings by remember { mutableStateOf(false) }
    val dark = LocalDocReaderDark.current
    val homeBackground = MaterialTheme.colorScheme.background
    val cardBackground = if (dark) MaterialTheme.colorScheme.surfaceContainer else Color.White
    val homeText = if (dark) MaterialTheme.colorScheme.onSurface else Color(0xFF18283E)
    val visibleRecent = recent.sortedByDescending { it.opened }.take(4)
    Scaffold(containerColor = homeBackground, contentColor = homeText, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = homeBackground), title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF174A91)) {
                DocumentMark(Modifier.padding(10.dp).size(24.dp), Color.White)
            }
            Column { Text("DocReader", style = MaterialTheme.typography.titleLarge)
                Text("PDF • Word • Excel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }, actions = { IconButton(onClick = { settings = true }) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
        } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp)) {
                item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        DocumentMark(Modifier.padding(20.dp).size(48.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text("All your documents, one place", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text("Open and read PDF, Word, and Excel files easily.", style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onPickDocument, modifier = Modifier.widthIn(min = 224.dp, max = 320.dp).heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF174A91), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                        Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Document", style = MaterialTheme.typography.labelLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("PDF", "DOCX", "XLSX").forEach { FormatBadge(it) } }
                } }
                lastError?.let { message -> item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                } } }
                item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                item { Text("Recent Documents", style = MaterialTheme.typography.titleMedium) }
                if (recent.isEmpty()) item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DocumentMark(Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("No recent documents yet", style = MaterialTheme.typography.titleSmall)
                            Text("Open a file to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(visibleRecent, key = { it.uri }) { document ->
                    val format = document.name.substringAfterLast('.', "DOC").uppercase()
                    Card(onClick = { onOpenRecent(document) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBackground, contentColor = homeText)) {
                        ListItem(headlineContent = { Text(document.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = { Text(format + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(document.opened)), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { DocumentMark(Modifier.size(24.dp), formatColor(format)) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = homeText, supportingColor = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF566579)))
                    }
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") },
        text = { Column { Text("Appearance", style = MaterialTheme.typography.titleSmall)
            val mode = LocalAppearance.current
            val update = LocalSetAppearance.current
            listOf("Light", "Dark", "System Default").forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = mode == choice, onClick = { update(choice) }, role = androidx.compose.ui.semantics.Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == choice, onClick = null)
                    Text(choice, Modifier.padding(start = 12.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
}
