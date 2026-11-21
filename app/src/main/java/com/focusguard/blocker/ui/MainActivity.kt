package com.focusguard.blocker.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.blocker.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FocusGuardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusGuardScreen() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getInstance(context).blockDao() }
    val rules by dao.observeAllRules().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            loadInstalledApps(context)
        }
        isLoadingApps = false
    }

    val ruleMap = remember(rules) { rules.associateBy { it.target.packageName } }

    val filteredApps = remember(installedApps, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("FocusGuard Blocker") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) { Text("1. Usage", maxLines = 1) }

                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) { Text("2. Access", maxLines = 1) }

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) { Text("3. Overlay", maxLines = 1) }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Search installed apps...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                }
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (isLoadingApps) "Loading apps..." else "Apps (${filteredApps.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            if (isLoadingApps) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val existing = ruleMap[app.packageName]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedApp = app }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = "${app.name} icon",
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )

                                Spacer(Modifier.width(12.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = app.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (existing != null) {
                                        val limitText = existing.target.dailyLimitMinutes?.let { "${it}m/day" }
                                            ?: "No daily limit"
                                        val schedText = if (existing.schedules.isNotEmpty()) {
                                            " • ${existing.schedules.size} schedule(s)"
                                        } else ""
                                        Text(
                                            text = "Active: $limitText$schedText",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                if (existing != null) {
                                    TextButton(onClick = {
                                        scope.launch { dao.deleteTarget(app.packageName) }
                                    }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedApp?.let { app ->
        RuleConfigDialog(
            app = app,
            existingRule = ruleMap[app.packageName],
            onDismiss = { selectedApp = null },
            onSave = { dailyMins, daysMask, startMin, endMin, enableSchedule ->
                scope.launch {
                    dao.upsertTarget(
                        BlockedTarget(
                            packageName = app.packageName,
                            appName = app.name,
                            isEnabled = true,
                            dailyLimitMinutes = dailyMins
                        )
                    )
                    dao.clearSchedulesForPackage(app.packageName)
                    if (enableSchedule && daysMask > 0) {
                        dao.insertSchedule(
                            BlockSchedule(
                                targetPackage = app.packageName,
                                daysOfWeekMask = daysMask,
                                startMinuteOfDay = startMin,
                                endMinuteOfDay = endMin
                            )
                        )
                    }
                    selectedApp = null
                }
            }
        )
    }
}

@Composable
fun RuleConfigDialog(
    app: InstalledApp,
    existingRule: TargetWithSchedules?,
    onDismiss: () -> Unit,
    onSave: (dailyMins: Int?, daysMask: Int, startMin: Int, endMin: Int, enableSchedule: Boolean) -> Unit
) {
    val existingSchedule = existingRule?.schedules?.firstOrNull()

    var enableDailyLimit by remember {
        mutableStateOf(existingRule?.target?.dailyLimitMinutes != null || existingRule == null)
    }
    var dailyMinutesText by remember {
        mutableStateOf((existingRule?.target?.dailyLimitMinutes ?: 30).toString())
    }
    var enableSchedule by remember {
        mutableStateOf(existingSchedule != null || existingRule == null)
    }
    var selectedDays by remember {
        mutableStateOf(
            if (existingSchedule != null) {
                listOf(1, 2, 4, 8, 16, 32, 64).filter { (existingSchedule.daysOfWeekMask and it) != 0 }.toSet()
            } else {
                setOf(1, 2, 4, 8, 16)
            }
        )
    }
    var startHour by remember {
        mutableStateOf(String.format("%02d", (existingSchedule?.startMinuteOfDay ?: 540) / 60))
    }
    var startMinute by remember {
        mutableStateOf(String.format("%02d", (existingSchedule?.startMinuteOfDay ?: 540) % 60))
    }
    var endHour by remember {
        mutableStateOf(String.format("%02d", (existingSchedule?.endMinuteOfDay ?: 1020) / 60))
    }
    var endMinute by remember {
        mutableStateOf(String.format("%02d", (existingSchedule?.endMinuteOfDay ?: 1020) % 60))
    }

    val dayOptions = listOf(
        "M" to 1, "T" to 2, "W" to 4, "T" to 8, "F" to 16, "S" to 32, "S" to 64
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableDailyLimit, onCheckedChange = { enableDailyLimit = it })
                    Text("Daily Time Limit (Minutes)")
                }
                if (enableDailyLimit) {
                    OutlinedTextField(
                        value = dailyMinutesText,
                        onValueChange = { dailyMinutesText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Minutes per day") },
                        singleLine = true
                    )
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableSchedule, onCheckedChange = { enableSchedule = it })
                    Text("Day & Time Window Block")
                }
                if (enableSchedule) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        dayOptions.forEach { (label, bit) ->
                            val isSelected = bit in selectedDays
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedDays = if (isSelected) selectedDays - bit else selectedDays + bit
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = "$startHour:$startMinute",
                            onValueChange = {
                                val parts = it.split(":")
                                if (parts.size == 2) {
                                    startHour = parts[0].take(2)
                                    startMinute = parts[1].take(2)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Start (HH:MM)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = "$endHour:$endMinute",
                            onValueChange = {
                                val parts = it.split(":")
                                if (parts.size == 2) {
                                    endHour = parts[0].take(2)
                                    endMinute = parts[1].take(2)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("End (HH:MM)") },
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mins = if (enableDailyLimit) dailyMinutesText.toIntOrNull() else null
                val mask = selectedDays.sum()
                val sMin = (startHour.toIntOrNull() ?: 9).coerceIn(0, 23) * 60 +
                    (startMinute.toIntOrNull() ?: 0).coerceIn(0, 59)
                val eMin = (endHour.toIntOrNull() ?: 17).coerceIn(0, 23) * 60 +
                    (endMinute.toIntOrNull() ?: 0).coerceIn(0, 59)
                onSave(mins, mask, sMin, eMin, enableSchedule)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        .mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null

            val appName = info.loadLabel(pm).toString()
            val drawable: Drawable = info.loadIcon(pm)
            val bitmap = drawable.toBitmap(width = 96, height = 96).asImageBitmap()

            InstalledApp(
                name = appName,
                packageName = pkg,
                icon = bitmap
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.name.lowercase() }
}
