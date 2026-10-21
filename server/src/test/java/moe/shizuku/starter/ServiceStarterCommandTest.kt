package moe.shizuku.starter

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceStarterCommandTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun captureArgv(
        className: String,
        processSuffix: String,
        signers: Set<String> = setOf("aa11", "bb22"),
        debug: Boolean = false
    ): List<String> {
        val argvFile = File(tempFolder.root, "captured_argv.bin")
        val fakeAppProcess = File(tempFolder.root, "fake_app_process").apply {
            writeText("#!/bin/sh\nprintf '%s\\0' \"\$@\" > '${argvFile.absolutePath}'\n")
            setExecutable(true)
        }

        val cmd = ServiceStarter.commandForUserService(
            appProcess = fakeAppProcess.absolutePath,
            managerApkPath = "/data/app/kerneldroid.nightzuku/base.apk",
            token = "tok-123",
            packageName = "com.example.client",
            classname = className,
            processNameSuffix = processSuffix,
            callingUid = 10234,
            debug = debug,
            expectedSigners = signers
        )

        val proc = ProcessBuilder("/bin/sh", "-c", "$cmd wait")
            .directory(tempFolder.root)
            .start()
        proc.waitFor(10, TimeUnit.SECONDS)
        return argvFile.readText().split('\u0000').dropLast(1)
    }

    @Test
    fun `commandForUserService passes expected arguments and signer digests`() {
        val argv = captureArgv("com.example.client.MyUserService", "daemon")

        assertEquals(
            listOf(
                "/system/bin",
                "--nice-name=com.example.client:daemon",
                "moe.shizuku.starter.ServiceStarter",
                "--token=tok-123",
                "--package=com.example.client",
                "--class=com.example.client.MyUserService",
                "--uid=10234",
                "--signers=aa11,bb22"
            ),
            argv
        )
    }

    @Test
    fun `commandForUserService escapes hostile shell metacharacters in className and suffix`() {
        val marker = File(tempFolder.root, "injected_marker")
        val maliciousClass = "Svc'; touch '${marker.absolutePath}'; '"
        val maliciousSuffix = "proc \$(touch ${marker.absolutePath}) `id`"

        val argv = captureArgv(maliciousClass, maliciousSuffix, debug = true)

        assertFalse("Shell injection must never execute", marker.exists())
        assertEquals("--class=$maliciousClass", argv.single { it.startsWith("--class=") })
        assertEquals(
            "--nice-name=com.example.client:$maliciousSuffix",
            argv.single { it.startsWith("--nice-name=") }
        )
        assertEquals(
            "--debug-name=com.example.client:$maliciousSuffix",
            argv.single { it.startsWith("--debug-name=") }
        )
    }
}
