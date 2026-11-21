package com.focusguard.blocker.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focusguard.blocker.data.*
import com.focusguard.blocker.engine.FocusScoreCalculator
import com.focusguard.blocker.engine.FocusScoreSummary
import com.focusguard.blocker.engine.ScheduleHelper
import com.focusguard.blocker.service.AppBlockerAccessibilityService
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val dao = remember { AppDatabase.getInstance(context).blockDao() }
    val rules by dao.observeAllRules().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasUsageAccess by remember { mutableStateOf(checkUsageAccess(context)) }

    var focusSummary by remember {
        mutableStateOf(FocusScoreSummary(score = 100, totalDistractionMinutes = 0L, trackedAppsCount = 0, activeSchedulesCount = 0))
    }

    LaunchedEffect(rules) {
        focusSummary = withContext(Dispatchers.IO) {
            FocusScoreCalculator.calculate(context, rules)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasUsageAccess = checkUsageAccess(context)
                scope.launch {
                    focusSummary = withContext(Dispatchers.IO) {
                        FocusScoreCalculator.calculate(context, rules)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
            // Focus Score Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val scoreColor = when {
                        focusSummary.score >= 80 -> Color(0xFF2E7D32)
                        focusSummary.score >= 50 -> Color(0xFFF57F17)
                        else -> Color(0xFFC62828)
                    }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(color = scoreColor, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${focusSummary.score}%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            "Daily Focus Score",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val statusText = when {
                            focusSummary.trackedAppsCount == 0 -> "Set up daily limits & schedules below"
                            focusSummary.score >= 80 -> "Great focus today! 🎯"
                            focusSummary.score >= 50 -> "Approaching daily limits ⏳"
                            else -> "Focus limit exceeded ⚠️"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${focusSummary.totalDistractionMinutes}m used today • ${focusSummary.activeSchedulesCount} schedule(s) active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Quick Permission Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(if (isAccessibilityEnabled) "1. Access ✓" else "1. Access", maxLines = 1)
                }

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
                ) {
                    Text(if (hasOverlayPermission) "2. Overlay ✓" else "2. Overlay", maxLines = 1)
                }

                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(if (hasUsageAccess) "3. Stats ✓" else "3. Stats", maxLines = 1)
                }
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
                                            ?: "No limit"
                                        val schedText = if (existing.schedules.isNotEmpty()) {
                                            " • " + ScheduleHelper.formatScheduleSummary(existing.schedules.first())
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

    // Default to Weekdays (Mon-Fri: 1+2+4+8+16 = 31)
    var selectedDays by remember {
        mutableStateOf(
            if (existingSchedule != null) {
                listOf(1, 2, 4, 8, 16, 32, 64).filter { (existingSchedule.daysOfWeekMask and it) != 0 }.toSet()
            } else {
                setOf(1, 2, 4, 8, 16)
            }
        )
    }

    // Default to 7:00 PM (1140) to 6:00 PM (1080)
    val initialStart = existingSchedule?.startMinuteOfDay ?: 1140
    val initialEnd = existingSchedule?.endMinuteOfDay ?: 1080

    val (initStartH, initStartM, initStartPm) = remember(existingSchedule) {
        ScheduleHelper.splitMinutesTo12h(initialStart)
    }
    val (initEndH, initEndM, initEndPm) = remember(existingSchedule) {
        ScheduleHelper.splitMinutesTo12h(initialEnd)
    }

    var startHourText by remember { mutableStateOf(initStartH.toString()) }
    var startMinuteText by remember { mutableStateOf(String.format("%02d", initStartM)) }
    var startIsPm by remember { mutableStateOf(initStartPm) }

    var endHourText by remember { mutableStateOf(initEndH.toString()) }
    var endMinuteText by remember { mutableStateOf(String.format("%02d", initEndM)) }
    var endIsPm by remember { mutableStateOf(initEndPm) }

    val dayOptions = listOf(
        "Mon" to 1, "Tue" to 2, "Wed" to 4, "Thu" to 8, "Fri" to 16, "Sat" to 32, "Sun" to 64
    )

    val curStartMin = ScheduleHelper.parseTimeToMinutes(
        startHourText.toIntOrNull() ?: 7,
        startMinuteText.toIntOrNull() ?: 0,
        startIsPm
    )
    val curEndMin = ScheduleHelper.parseTimeToMinutes(
        endHourText.toIntOrNull() ?: 6,
        endMinuteText.toIntOrNull() ?: 0,
        endIsPm
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableDailyLimit, onCheckedChange = { enableDailyLimit = it })
                    Text("Daily Time Limit (Minutes)", fontWeight = FontWeight.SemiBold)
                }
                if (enableDailyLimit) {
                    OutlinedTextField(
                        value = dailyMinutesText,
                        onValueChange = { dailyMinutesText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Minutes per day") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableSchedule, onCheckedChange = { enableSchedule = it })
                    Text("Day & Time Window Block", fontWeight = FontWeight.SemiBold)
                }

                if (enableSchedule) {
                    // Day Presets
                    Text("Day Presets:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = { selectedDays = setOf(1, 2, 4, 8, 16) },
                            label = { Text("Weekdays") }
                        )
                        AssistChip(
                            onClick = { selectedDays = setOf(32, 64) },
                            label = { Text("Weekends") }
                        )
                        AssistChip(
                            onClick = { selectedDays = setOf(1, 2, 4, 8, 16, 32, 64) },
                            label = { Text("Everyday") }
                        )
                    }

                    // Individual Days
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        dayOptions.forEach { (label, bit) ->
                            val isSelected = bit in selectedDays
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedDays = if (isSelected) selectedDays - bit else selectedDays + bit
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Time Presets:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                startHourText = "7"
                                startMinuteText = "00"
                                startIsPm = true
                                endHourText = "6"
                                endMinuteText = "00"
                                endIsPm = true
                            },
                            label = { Text("7 PM - 6 PM") }
                        )
                        AssistChip(
                            onClick = {
                                startHourText = "7"
                                startMinuteText = "00"
                                startIsPm = true
                                endHourText = "6"
                                endMinuteText = "00"
                                endIsPm = false
                            },
                            label = { Text("7 PM - 6 AM") }
                        )
                        AssistChip(
                            onClick = {
                                startHourText = "9"
                                startMinuteText = "00"
                                startIsPm = false
                                endHourText = "5"
                                endMinuteText = "00"
                                endIsPm = true
                            },
                            label = { Text("9 AM - 5 PM") }
                        )
                    }

                    // Start Time Input
                    Text("Start Time", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = startHourText,
                            onValueChange = { input ->
                                val num = input.filter { it.isDigit() }.take(2)
                                startHourText = num
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Hour (1-12)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = startMinuteText,
                            onValueChange = { input ->
                                val num = input.filter { it.isDigit() }.take(2)
                                startMinuteText = num
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Min (00-59)") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = !startIsPm,
                                onClick = { startIsPm = false },
                                label = { Text("AM") }
                            )
                            FilterChip(
                                selected = startIsPm,
                                onClick = { startIsPm = true },
                                label = { Text("PM") }
                            )
                        }
                    }

                    // End Time Input
                    Text("End Time", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = endHourText,
                            onValueChange = { input ->
                                val num = input.filter { it.isDigit() }.take(2)
                                endHourText = num
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Hour (1-12)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = endMinuteText,
                            onValueChange = { input ->
                                val num = input.filter { it.isDigit() }.take(2)
                                endMinuteText = num
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Min (00-59)") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = !endIsPm,
                                onClick = { endIsPm = false },
                                label = { Text("AM") }
                            )
                            FilterChip(
                                selected = endIsPm,
                                onClick = { endIsPm = true },
                                label = { Text("PM") }
                            )
                        }
                    }

                    // Live Schedule Preview Card
                    val dummySchedule = BlockSchedule(
                        targetPackage = app.packageName,
                        daysOfWeekMask = selectedDays.sum(),
                        startMinuteOfDay = curStartMin,
                        endMinuteOfDay = curEndMin
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                ScheduleHelper.formatScheduleSummary(dummySchedule),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (curStartMin > curEndMin) {
                                Text(
                                    "🌙 Overnight window: blocks from evening across into the following day.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mins = if (enableDailyLimit) dailyMinutesText.toIntOrNull() else null
                val mask = selectedDays.sum()
                onSave(mins, mask, curStartMin, curEndMin, enableSchedule)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedServiceName = "${context.packageName}/${AppBlockerAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedServiceName, ignoreCase = true) ||
            (componentName.contains(context.packageName, ignoreCase = true) && componentName.contains("AppBlockerAccessibilityService", ignoreCase = true))) {
            return true
        }
    }
    return false
}

private fun checkUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
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
