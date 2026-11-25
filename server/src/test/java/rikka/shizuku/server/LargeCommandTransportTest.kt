package rikka.shizuku.server

import java.io.File
import moe.shizuku.starter.ArgvPayloadCodec
import moe.shizuku.starter.ArgvTrampoline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LargeCommandTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `small commands bypass transport with zero I_O`() {
        val cmd = arrayOf(
            "/system/bin/app_process",
            "-Djava.class.path=/data/app/client.apk",
            "/system/bin",
            "--nice-name=test",
            "com.example.Tool",
            "--flag=short"
        )
        val prepared = LargeCommandTransport.prepare(
            cmd = cmd,
            env = null,
            managerApkPath = "/data/app/kerneldroid.nightzuku/base.apk",
            spoolDirOverride = tempFolder.root
        )
        assertArrayEquals(cmd, prepared.cmd)
        assertNull(prepared.cleanupFile)
    }

    @Test
    fun `oversized app_process command is rewritten to ArgvTrampoline and preserves full payload`() {
        val largePayload = "A".repeat(150 * 1024) // > 128 KiB Linux MAX_ARG_STRLEN
        val cmd = arrayOf(
            "/system/bin/app_process64",
            "-Djava.class.path=/data/app/client/base.apk",
            "-Xcompiler-option",
            "--debuggable",
            "/system/bin",
            "--nice-name=client:worker",
            "com.example.ClientMain",
            "--payload=$largePayload",
            "--tail=ok"
        )

        val managerApk = "/data/app/kerneldroid.nightzuku/base.apk"
        val prepared = LargeCommandTransport.prepare(
            cmd = cmd,
            env = null,
            managerApkPath = managerApk,
            spoolDirOverride = tempFolder.root
        )

        assertNotNull(prepared.cleanupFile)
        assertTrue(prepared.cleanupFile!!.exists())
        assertTrue(
            "Rewritten argv must stay tiny (< 1 KiB)",
            LargeCommandTransport.totalArgvBytes(prepared.cmd) < 1024
        )
        assertTrue(
            prepared.cmd.contains("-Djava.class.path=/data/app/client/base.apk:$managerApk")
        )
        assertTrue(prepared.cmd.contains("--nice-name=client:worker"))
        assertTrue(prepared.cmd.contains(ArgvTrampoline::class.java.name))

        val argFileOpt = prepared.cmd.single { it.startsWith(ArgvTrampoline.ARG_FILE_PREFIX) }
        val payloadPath = argFileOpt.removePrefix(ArgvTrampoline.ARG_FILE_PREFIX)

        val decoded = ArgvPayloadCodec.readAndDelete(payloadPath)
        assertEquals("com.example.ClientMain", decoded.targetClass)
        assertArrayEquals(arrayOf("--payload=$largePayload", "--tail=ok"), decoded.args)
        assertFalse("Payload file must be unlinked immediately on read", File(payloadPath).exists())
    }

    @Test
    fun `oversized sh -c script is materialized to temp script file`() {
        val hugeScript = "echo " + "x".repeat(140 * 1024)
        val cmd = arrayOf("/system/bin/sh", "-c", hugeScript)

        val prepared = LargeCommandTransport.prepare(
            cmd = cmd,
            env = null,
            managerApkPath = "/data/app/kerneldroid.nightzuku/base.apk",
            spoolDirOverride = tempFolder.root
        )

        assertNotNull(prepared.cleanupFile)
        val scriptPath = prepared.cleanupFile!!.absolutePath
        assertEquals("/system/bin/sh", prepared.cmd[0])
        assertEquals(scriptPath, prepared.cmd[1])
        val escapedPath = scriptPath.replace("'", "'\\''")
        val expectedHeader = "rm -f -- '$escapedPath' >/dev/null 2>&1 || true\n"
        val content = prepared.cleanupFile!!.readText()
        assertTrue("Script must start with self-unlink header", content.startsWith(expectedHeader))
        assertEquals(expectedHeader + hugeScript, content)

        prepared.cleanup()
        assertFalse(prepared.cleanupFile!!.exists())
    }

    @Test
    fun `oversized sh -c with positional args preserves arg0 and positional parameters`() {
        val hugeScript = "echo \"\$0:\$1:\$2\" #" + "y".repeat(140 * 1024)
        val cmd = arrayOf("/system/bin/sh", "-c", hugeScript, "my-script", "first-arg", "second-arg")

        val prepared = LargeCommandTransport.prepare(
            cmd = cmd,
            env = null,
            managerApkPath = "/data/app/kerneldroid.nightzuku/base.apk",
            spoolDirOverride = tempFolder.root
        )

        assertNotNull(prepared.cleanupFile)
        val scriptPath = prepared.cleanupFile!!.absolutePath
        assertArrayEquals(
            arrayOf(
                "/system/bin/sh",
                "-c",
                "__nz_s=\$1; shift; . \"\$__nz_s\"",
                "my-script",
                scriptPath,
                "first-arg",
                "second-arg"
            ),
            prepared.cmd
        )
        val escapedPath = scriptPath.replace("'", "'\\''")
        val expectedHeader = "unset __nz_s; rm -f -- '$escapedPath' >/dev/null 2>&1 || true\n"
        assertEquals(expectedHeader + hugeScript, prepared.cleanupFile!!.readText())

        prepared.cleanup()
        assertFalse(prepared.cleanupFile!!.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload exceeding MAX_EXTENDED_CMD_BYTES is rejected`() {
        val tooBig = "Z".repeat(LargeCommandTransport.MAX_EXTENDED_CMD_BYTES + 1024)
        LargeCommandTransport.prepare(
            cmd = arrayOf("/system/bin/app_process", "/system/bin", "com.example.Main", tooBig),
            env = null,
            managerApkPath = "/data/app/kerneldroid.nightzuku/base.apk",
            spoolDirOverride = tempFolder.root
        )
    }
}
