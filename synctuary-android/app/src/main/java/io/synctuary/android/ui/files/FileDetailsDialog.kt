package io.synctuary.android.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.synctuary.android.data.api.dto.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileDetailsDialog(
    entry: FileEntry,
    currentPath: String,
    onDismiss: () -> Unit,
) {
    val fullPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DetailRow("Name", entry.name)
                DetailRow("Type", if (entry.type == "dir") "Folder" else (entry.mime_type ?: "File"))
                if (entry.type != "dir" && entry.size != null) {
                    DetailRow("Size", formatDetailSize(entry.size))
                }
                DetailRow("Path", fullPath)
                DetailRow("Modified", formatDetailDate(entry.modified_at))
                entry.sha256?.let { DetailRow("SHA-256", it.take(16) + "...") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
    }
}

private fun formatDetailSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB (%,d bytes)".format(bytes / 1024.0, bytes)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB (%,d bytes)".format(bytes / (1024.0 * 1024), bytes)
    else -> "%.2f GiB (%,d bytes)".format(bytes / (1024.0 * 1024 * 1024), bytes)
}

private fun formatDetailDate(epochSeconds: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .format(Date(epochSeconds * 1000))
}
