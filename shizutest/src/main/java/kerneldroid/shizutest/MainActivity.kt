package kerneldroid.shizutest

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuSystemProperties
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*












enum class TestStatus {
    IDLE, RUNNING, GOOD, FAILED
}

data class DiagnosticTest(
    val id: String,
    val title: String,
    val description: String,
    val status: TestStatus = TestStatus.IDLE,
    val summary: String = "",
    val details: String = "",
    val executionTimeMs: Long = 0L
)

data class TestResult(
    val status: TestStatus,
    val summary: String,
    val details: String
)


class MainActivity : ComponentActivity() {

    private val binderReceived = mutableStateOf(false)
    private val permissionGranted = mutableStateOf(false)


    private val testsList = mutableStateListOf<DiagnosticTest>()

    private val BINDER_RECEIVED_LISTENER = Shizuku.OnBinderReceivedListener {
        binderReceived.value = true
        permissionGranted.value = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    private val BINDER_DEAD_LISTENER = Shizuku.OnBinderDeadListener {
        binderReceived.value = false
        permissionGranted.value = false
    }

    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        permissionGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER)
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)

        binderReceived.value = Shizuku.pingBinder()
        permissionGranted.value = if (Shizuku.pingBinder()) Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED else false


        if (testsList.isEmpty()) {
            testsList.addAll(listOf(
                DiagnosticTest("ping", "Binder Service Ping", "Verify binder connectivity state"),
                DiagnosticTest("latency", "IPC Transaction Speed", "Measure IPC transaction roundtrip delay"),
                DiagnosticTest("selinux", "SELinux Context Check", "Verify server SELinux confinement domain"),
                DiagnosticTest("shell", "High-Privilege Shell Spawner", "Spawn process and execute 'id' command"),
                DiagnosticTest("package", "Package Manager Query", "Query packages via high-privilege IPC"),
                DiagnosticTest("properties", "System Properties Read", "Access restricted Android system properties")
            ))
        }

        setContent {
            MonetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MinimalistDashboard(
                        binderActive = binderReceived.value,
                        permissionActive = permissionGranted.value,
                        tests = testsList,
                        onRequestPermission = { requestShizukuPermission() },
                        onRunAutoTest = { runAutoTest() },
                        onDownloadLogs = { exportLogsToFile() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER)
        Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER)
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Error: Nightzuku is inactive.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission already granted.", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Exception: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTest(id: String, transform: (DiagnosticTest) -> DiagnosticTest) {
        val index = testsList.indexOfFirst { it.id == id }
        if (index != -1) {
            testsList[index] = transform(testsList[index])
        }
    }

    private fun runAutoTest() {
        lifecycleScope.launch {

            for (i in 0 until testsList.size) {
                testsList[i] = testsList[i].copy(
                    status = TestStatus.IDLE,
                    summary = "",
                    details = "",
                    executionTimeMs = 0L
                )
            }


            runTestItem("ping") {
                val active = Shizuku.pingBinder()
                if (active) {
                    TestResult(
                        status = TestStatus.GOOD,
                        summary = "Active",
                        details = "Shizuku service binder is alive and accessible.\nAPI Version: ${Shizuku.getVersion()}\nServer UID: ${Shizuku.getUid()}"
                    )
                } else {
                    throw IllegalStateException("Binder service is inactive. Make sure the Nightzuku server is running in the background.")
                }
            }


            runTestItem("latency") {
                if (!Shizuku.pingBinder()) throw IllegalStateException("Binder is inactive.")
                val trials = 5
                val times = LongArray(trials)
                for (i in 0 until trials) {
                    val start = System.nanoTime()
                    Shizuku.pingBinder()
                    times[i] = System.nanoTime() - start
                    delay(40)
                }
                val minMs = times.minOrNull()!! / 1_000_000.0
                val maxMs = times.maxOrNull()!! / 1_000_000.0
                val avgMs = times.average() / 1_000_000.0
                TestResult(
                    status = TestStatus.GOOD,
                    summary = String.format(Locale.US, "%.2f ms", avgMs),
                    details = String.format(
                        Locale.US,
                        "Binder latency over %d roundtrips:\n- Min: %.3f ms\n- Max: %.3f ms\n- Avg: %.3f ms",
                        trials, minMs, maxMs, avgMs
                    )
                )
            }


            runTestItem("selinux") {
                if (!Shizuku.pingBinder()) throw IllegalStateException("Binder is inactive.")
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("Shizuku permission not granted.")
                }
                val seContext = Shizuku.getSELinuxContext()
                if (!seContext.isNullOrEmpty()) {
                    TestResult(
                        status = TestStatus.GOOD,
                        summary = seContext.substringAfterLast(":"),
                        details = "SELinux Context retrieved successfully:\n$seContext"
                    )
                } else {
                    throw IllegalStateException("SELinux context returned empty or null.")
                }
            }


            runTestItem("shell") {
                if (!Shizuku.pingBinder()) throw IllegalStateException("Binder is inactive.")
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("Shizuku permission not granted.")
                }
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true
                val process = newProcessMethod.invoke(null, arrayOf("id"), null, null) as Process
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    val summary = if (output.contains("uid=0")) "root" else if (output.contains("uid=2000")) "shell" else "Success"
                    TestResult(
                        status = TestStatus.GOOD,
                        summary = summary,
                        details = "Shell command spawned and executed successfully:\nExit Code: 0\nOutput:\n$output"
                    )
                } else {
                    throw IllegalStateException("Shell process exited with code $exitCode.\nError output:\n$error")
                }
            }


            runTestItem("package") {
                if (!Shizuku.pingBinder()) throw IllegalStateException("Binder is inactive.")
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("Shizuku permission not granted.")
                }
                val count = queryPackagesCount()
                TestResult(
                    status = TestStatus.GOOD,
                    summary = "$count packages",
                    details = "Queried system IPackageManager package list through high-privilege binder wrap transaction:\n- Output: $count active application packages count."
                )
            }


            runTestItem("properties") {
                if (!Shizuku.pingBinder()) throw IllegalStateException("Binder is inactive.")
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("Shizuku permission not granted.")
                }
                val model = ShizukuSystemProperties.get("ro.product.model")
                val fingerprint = ShizukuSystemProperties.get("ro.build.fingerprint")
                val brand = ShizukuSystemProperties.get("ro.product.brand")
                TestResult(
                    status = TestStatus.GOOD,
                    summary = model ?: "Success",
                    details = "Restricted Android System Properties fetched via Shizuku binder successfully:\n- ro.product.brand = $brand\n- ro.product.model = $model\n- ro.build.fingerprint = $fingerprint"
                )
            }


            exportLogsToFile()
        }
    }

    private suspend inline fun runTestItem(id: String, crossinline block: suspend () -> TestResult) {
        updateTest(id) { it.copy(status = TestStatus.RUNNING, details = "Running test execution routine...") }
        delay(400)
        val startTime = System.currentTimeMillis()
        try {
            val result = block()
            val duration = System.currentTimeMillis() - startTime
            updateTest(id) {
                it.copy(
                    status = result.status,
                    summary = result.summary,
                    details = result.details,
                    executionTimeMs = duration
                )
            }
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            updateTest(id) {
                it.copy(
                    status = TestStatus.FAILED,
                    summary = e.message ?: "Failed",
                    details = sw.toString(),
                    executionTimeMs = duration
                )
            }
        }
    }

    private fun queryPackagesCount(): Int {
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
        val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
        val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
        val packageManagerInstance = asInterfaceMethod.invoke(null, binder)

        val methods = packageManagerInstance.javaClass.methods
        val getInstalledPackagesMethod = methods
            .filter { it.name == "getInstalledPackages" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Long::class.javaPrimitiveType }
            .maxByOrNull { it.parameterTypes.size }
            ?: throw IllegalStateException("Method getInstalledPackages not found in PackageManager interface")

        val paramCount = getInstalledPackagesMethod.parameterTypes.size
        val args = Array(paramCount) { 0 }
        val result = getInstalledPackagesMethod.invoke(packageManagerInstance, *args)

        val getListMethod = result.javaClass.getMethod("getList")
        val list = (getListMethod.invoke(result) as? List<Any>) ?: emptyList<Any>()
        return list.size
    }

    private fun exportLogsToFile() {
        try {
            val externalDir = getExternalFilesDir(null)
            val logFile = File(externalDir, "shizutest_diagnostics.log")
            logFile.printWriter().use { out ->
                out.println("==================================================")
                out.println("SHIZUTEST AUTOMATED DIAGNOSTICS REPORT")
                out.println("Date: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date()))
                out.println("OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                out.println("Device Model: ${Build.MODEL}")
                out.println("==================================================")
                testsList.forEach { test ->
                    out.println("Test: ${test.title} (${test.id})")
                    out.println("Status: ${test.status} (${test.summary})")
                    out.println("Time: ${test.executionTimeMs} ms")
                    out.println("Details:\n${test.details}")
                    out.println("--------------------------------------------------")
                }
            }
            Toast.makeText(this, "Logs saved: ${logFile.name}", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Failed to save logs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}





@Composable
fun MinimalistDashboard(
    binderActive: Boolean,
    permissionActive: Boolean,
    tests: List<DiagnosticTest>,
    onRequestPermission: () -> Unit,
    onRunAutoTest: () -> Unit,
    onDownloadLogs: () -> Unit
) {
    val scrollState = rememberScrollState()
    val outerGap = 20.dp
    val plaqueGap = 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = outerGap, vertical = 12.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(plaqueGap)
    ) {
        Spacer(modifier = Modifier.height(12.dp))


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SHIZUTEST",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            IconButton(
                onClick = onDownloadLogs,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Download Logs",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))


        PlaqueContainerMinimal(title = "Device Environment", icon = Icons.Rounded.Devices) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EnvItemMinimal(label = "Android", value = "v${Build.VERSION.RELEASE}")
                EnvItemMinimal(label = "SDK", value = Build.VERSION.SDK_INT.toString())
                EnvItemMinimal(label = "Device", value = Build.MODEL)
            }
        }


        PlaqueContainerMinimal(title = "Nightzuku Service State", icon = Icons.Rounded.SettingsInputAntenna) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Binder Service Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    StatusBadgeMinimal(active = binderActive, activeText = "Active", inactiveText = "Inactive")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Client Permission Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    StatusBadgeMinimal(active = permissionActive, activeText = "Granted", inactiveText = "Denied")
                }

                if (binderActive) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 1.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Manager Version: ${Shizuku.getVersion()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "Privilege UID: ${Shizuku.getUid()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }


        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRunAutoTest() },
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircleFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RUN AUTOMATED DIAGNOSTICS",
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    letterSpacing = 1.sp
                )
            }
        }

        if (!permissionActive) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(imageVector = Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Authorize Application Package", fontWeight = FontWeight.Bold)
            }
        }


        Text(
            text = "DIAGNOSTIC VERIFICATION CHECKS",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )

        tests.forEach { test ->
            TestItemCard(test = test)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun TestItemCard(
    test: DiagnosticTest,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }


    val isAutoExpanded = test.status == TestStatus.RUNNING || test.status == TestStatus.FAILED
    val isVisible = expanded || isAutoExpanded

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = test.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = test.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))


                StatusIndicator(status = test.status, summary = test.summary)
            }

            AnimatedVisibility(visible = isVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 1.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Detailed Transaction Log:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (test.details.isEmpty()) "Pending diagnostics execution..." else test.details,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (test.status == TestStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (test.executionTimeMs > 0L) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Execution time: ${test.executionTimeMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(status: TestStatus, summary: String) {
    val containerColor: Color
    val contentColor: Color
    val text: String

    when (status) {
        TestStatus.IDLE -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            text = "Idle"
        }
        TestStatus.RUNNING -> {
            containerColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            text = "Testing"
        }
        TestStatus.GOOD -> {
            containerColor = Color(0xFF388E3C).copy(alpha = 0.15f)
            contentColor = Color(0xFF81C784)
            text = if (summary.isNotEmpty()) "Good: $summary" else "Good"
        }
        TestStatus.FAILED -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            text = "Failed"
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (status == TestStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = contentColor,
                    strokeWidth = 1.5.dp
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = contentColor
            )
        }
    }
}





@Composable
fun PlaqueContainerMinimal(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.primaryColorEmulated(),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}


@Composable
fun MaterialTheme.primaryColorEmulated(): Color = Color(0xFF82C7A5)

@Composable
fun EnvItemMinimal(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun StatusBadgeMinimal(active: Boolean, activeText: String, inactiveText: String) {
    val containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val textColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (active) activeText else inactiveText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = textColor
        )
    }
}

@Composable
fun MonetTheme(content: @Composable () -> Unit) {

    val monetColorScheme = darkColorScheme(
        primary = Color(0xFF82C7A5),
        onPrimary = Color(0xFF003822),
        primaryContainer = Color(0xFF005234),
        onPrimaryContainer = Color(0xFF9DF4C0),
        secondary = Color(0xFFBCCBB0),
        onSecondary = Color(0xFF273421),
        secondaryContainer = Color(0xFF3D4A36),
        onSecondaryContainer = Color(0xFFD8E7CC),
        tertiary = Color(0xFFE4C39B),
        onTertiary = Color(0xFF422D12),
        background = Color(0xFF191C1A),
        surface = Color(0xFF222522),
        surfaceContainerHighest = Color(0xFF2C302C),
        onBackground = Color(0xFFE1E3DF),
        onSurface = Color(0xFFE1E3DF),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC)
    )

    MaterialTheme(
        colorScheme = monetColorScheme,
        content = content
    )
}
