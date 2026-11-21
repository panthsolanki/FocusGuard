#!/usr/bin/env bash
set -e

# REPO_URL="${1:-https://github.com/YOUR_USERNAME/FocusGuard.git}"
# PROJECT_DIR="FocusGuard"

# mkdir -p "$PROJECT_DIR"
# cd "$PROJECT_DIR"

# Create directory structure
mkdir -p .github/workflows \
         gradle/wrapper \
         app/src/main/res/xml \
         app/src/main/res/values \
         app/src/main/java/com/focusguard/blocker/data \
         app/src/main/java/com/focusguard/blocker/engine \
         app/src/main/java/com/focusguard/blocker/service \
         app/src/main/java/com/focusguard/blocker/ui

# 1. .gitignore
cat << 'EOF' > .gitignore
*.iml
.gradle
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
*.apk
*.aab
EOF

# 2. settings.gradle.kts
cat << 'EOF' > settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FocusGuard"
include(":app")
EOF

# 3. Root build.gradle.kts
cat << 'EOF' > build.gradle.kts
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
EOF

# 4. gradle.properties
cat << 'EOF' > gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
EOF

# 5. app/build.gradle.kts
cat << 'EOF' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.focusguard.blocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.focusguard.blocker"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
EOF

# 6. AndroidManifest.xml
cat << 'EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="false"
        android:label="FocusGuard"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.BlockOverlayActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleInstance"
            android:noHistory="true"
            android:taskAffinity="" />

        <service
            android:name=".service.AppBlockerAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
EOF

# 7. Resources
cat << 'EOF' > app/src/main/res/xml/accessibility_service_config.xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="false"
    android:description="@string/accessibility_service_desc"
    android:notificationTimeout="50" />
EOF

cat << 'EOF' > app/src/main/res/values/strings.xml
<resources>
    <string name="app_name">FocusGuard</string>
    <string name="accessibility_service_desc">Detects when restricted apps are opened to enforce daily limits and schedules.</string>
</resources>
EOF

# 8. Data Layer
cat << 'EOF' > app/src/main/java/com/focusguard/blocker/data/BlockEntities.kt
package com.focusguard.blocker.data

import androidx.room.*

@Entity(tableName = "blocked_targets")
data class BlockedTarget(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true,
    val dailyLimitMinutes: Int? = null
)

@Entity(
    tableName = "block_schedules",
    foreignKeys = [ForeignKey(
        entity = BlockedTarget::class,
        parentColumns = ["packageName"],
        childColumns = ["targetPackage"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("targetPackage")]
)
data class BlockSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetPackage: String,
    val daysOfWeekMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int
)

data class TargetWithSchedules(
    @Embedded val target: BlockedTarget,
    @Relation(parentColumn = "packageName", entityColumn = "targetPackage")
    val schedules: List<BlockSchedule>
)
EOF

cat << 'EOF' > app/src/main/java/com/focusguard/blocker/data/BlockDao.kt
package com.focusguard.blocker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockDao {
    @Transaction
    @Query("SELECT * FROM blocked_targets")
    fun observeAllRules(): Flow<List<TargetWithSchedules>>

    @Transaction
    @Query("SELECT * FROM blocked_targets WHERE packageName = :pkg AND isEnabled = 1")
    suspend fun getActiveRuleForPackage(pkg: String): TargetWithSchedules?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTarget(target: BlockedTarget)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: BlockSchedule)

    @Query("DELETE FROM block_schedules WHERE targetPackage = :pkg")
    suspend fun clearSchedulesForPackage(pkg: String)

    @Query("DELETE FROM blocked_targets WHERE packageName = :pkg")
    suspend fun deleteTarget(pkg: String)
}
EOF

cat << 'EOF' > app/src/main/java/com/focusguard/blocker/data/AppDatabase.kt
package com.focusguard.blocker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BlockedTarget::class, BlockSchedule::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockDao(): BlockDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focus_guard.db"
                ).build().also { INSTANCE = it }
            }
    }
}
EOF

# 9. Blocking Engine
cat << 'EOF' > app/src/main/java/com/focusguard/blocker/engine/UsageCalculator.kt
package com.focusguard.blocker.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object UsageCalculator {
    fun getTodayUsageMillis(context: Context, targetPackage: String): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val events = usm.queryEvents(startOfDay, now)
        val event = UsageEvents.Event()
        var totalTime = 0L
        var lastResumeTime: Long? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != targetPackage) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (lastResumeTime == null) lastResumeTime = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    lastResumeTime?.let { start ->
                        totalTime += (event.timeStamp - start).coerceAtLeast(0L)
                        lastResumeTime = null
                    }
                }
            }
        }
        lastResumeTime?.let { start ->
            totalTime += (now - start).coerceAtLeast(0L)
        }
        return totalTime
    }
}
EOF

cat << 'EOF' > app/src/main/java/com/focusguard/blocker/engine/RuleEvaluator.kt
package com.focusguard.blocker.engine

import android.content.Context
import com.focusguard.blocker.data.BlockSchedule
import com.focusguard.blocker.data.TargetWithSchedules
import java.util.Calendar

sealed class BlockVerdict {
    object Allowed : BlockVerdict()
    data class Blocked(val appName: String, val reason: String) : BlockVerdict()
}

object RuleEvaluator {
    fun evaluate(context: Context, rule: TargetWithSchedules): BlockVerdict {
        val target = rule.target
        if (!target.isEnabled) return BlockVerdict.Allowed

        val now = Calendar.getInstance()
        for (schedule in rule.schedules) {
            if (isScheduleActiveNow(now, schedule)) {
                val endStr = formatMinutes(schedule.endMinuteOfDay)
                return BlockVerdict.Blocked(
                    appName = target.appName,
                    reason = "Scheduled block active until $endStr"
                )
            }
        }

        val limitMins = target.dailyLimitMinutes
        if (limitMins != null && limitMins > 0) {
            val usedMillis = UsageCalculator.getTodayUsageMillis(context, target.packageName)
            val limitMillis = limitMins * 60_000L
            if (usedMillis >= limitMillis) {
                return BlockVerdict.Blocked(
                    appName = target.appName,
                    reason = "Daily time limit of ${limitMins}m reached"
                )
            }
        }

        return BlockVerdict.Allowed
    }

    private fun isScheduleActiveNow(cal: Calendar, schedule: BlockSchedule): Boolean {
        val currentMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayBit = calendarDayToBit(cal.get(Calendar.DAY_OF_WEEK))
        val start = schedule.startMinuteOfDay
        val end = schedule.endMinuteOfDay

        return if (start <= end) {
            (schedule.daysOfWeekMask and todayBit) != 0 && currentMinute in start until end
        } else {
            val yesterdayCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayBit = calendarDayToBit(yesterdayCal.get(Calendar.DAY_OF_WEEK))

            val activeLateTonight = (schedule.daysOfWeekMask and todayBit) != 0 && currentMinute >= start
            val activeEarlyMorning = (schedule.daysOfWeekMask and yesterdayBit) != 0 && currentMinute < end
            activeLateTonight || activeEarlyMorning
        }
    }

    private fun calendarDayToBit(calDay: Int): Int = when (calDay) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 4
        Calendar.THURSDAY -> 8
        Calendar.FRIDAY -> 16
        Calendar.SATURDAY -> 32
        Calendar.SUNDAY -> 64
        else -> 0
    }

    private fun formatMinutes(totalMins: Int): String =
        String.format("%02d:%02d", totalMins / 60, totalMins % 60)
}
EOF

# 10. Service & UI
cat << 'EOF' > app/src/main/java/com/focusguard/blocker/service/AppBlockerAccessibilityService.kt
package com.focusguard.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.focusguard.blocker.data.AppDatabase
import com.focusguard.blocker.engine.BlockVerdict
import com.focusguard.blocker.engine.RuleEvaluator
import com.focusguard.blocker.ui.BlockOverlayActivity
import kotlinx.coroutines.*

class AppBlockerAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentForegroundPkg: String? = null
    private var monitorJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        monitorJob = serviceScope.launch {
            while (isActive) {
                currentForegroundPkg?.let { checkAndEnforce(it) }
                delay(3_000L)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg == "com.android.systemui") return
            currentForegroundPkg = pkg
            serviceScope.launch { checkAndEnforce(pkg) }
        }
    }

    private suspend fun checkAndEnforce(pkg: String) {
        val dao = AppDatabase.getInstance(applicationContext).blockDao()
        val rule = dao.getActiveRuleForPackage(pkg) ?: return
        val verdict = RuleEvaluator.evaluate(applicationContext, rule)

        if (verdict is BlockVerdict.Blocked) {
            val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("APP_NAME", verdict.appName)
                putExtra("REASON", verdict.reason)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
EOF

cat << 'EOF' > app/src/main/java/com/focusguard/blocker/ui/BlockOverlayActivity.kt
package com.focusguard.blocker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class BlockOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appName = intent.getStringExtra("APP_NAME") ?: "This App"
        val reason = intent.getStringExtra("REASON") ?: "Restricted by FocusGuard"

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goHome() }
        })

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("App Blocked", color = Color(0xFFFF5252), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(appName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(reason, color = Color.LightGray, fontSize = 16.sp)
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { goHome() }) {
                        Text("Return to Home Screen")
                    }
                }
            }
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}
EOF

cat << 'EOF' > app/src/main/java/com/focusguard/blocker/ui/MainActivity.kt
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
EOF

# 11. GitHub Actions CI Workflow to Automatically Build the APK on Push
cat << 'EOF' > .github/workflows/build-apk.yml
name: Build FocusGuard APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Build Debug APK
        run: gradle assembleDebug

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: FocusGuard-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
EOF

# 12. Initialize Git & Push to GitHub
git init -b main
git add .
git commit -m "Initial commit: FocusGuard Android App Blocker with CI APK builder"
git remote add origin "$REPO_URL"
git push -u origin main

echo "✅ All files pushed to $REPO_URL!"
echo "➡️ Go to the 'Actions' tab in your GitHub repository to download FocusGuard-debug-apk."
