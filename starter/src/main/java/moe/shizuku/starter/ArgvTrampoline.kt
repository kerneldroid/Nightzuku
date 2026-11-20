package moe.shizuku.starter

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Transparent `app_process` trampoline that hydrates oversized `main(String[] args)` payloads
 * written by `LargeCommandTransport` (up to [ArgvPayloadCodec.MAX_PAYLOAD_BYTES]), immediately
 * unlinks the backing file, and dispatches directly to the target class's `main` method inside
 * the same JVM, UID, SELinux domain, and classpath.
 */
object ArgvTrampoline {

    const val ARG_FILE_PREFIX = "--argv-file="

    @JvmStatic
    fun main(args: Array<String>) {
        val argvFilePath = args.firstOrNull { it.startsWith(ARG_FILE_PREFIX) }
            ?.substring(ARG_FILE_PREFIX.length)
        if (argvFilePath.isNullOrBlank()) {
            System.err.println("ArgvTrampoline: missing $ARG_FILE_PREFIX argument")
            System.exit(2)
            return
        }

        val payload = try {
            ArgvPayloadCodec.readAndDelete(argvFilePath)
        } catch (t: Throwable) {
            System.err.println("ArgvTrampoline: failed to load payload: ${t.message}")
            t.printStackTrace(System.err)
            System.exit(2)
            return
        }

        if (payload.targetClass.isBlank()) {
            System.err.println("ArgvTrampoline: empty target main class")
            System.exit(2)
            return
        }

        try {
            val targetClass = Class.forName(payload.targetClass)
            val mainMethod = targetClass.getMethod("main", Array<String>::class.java)
            if (!Modifier.isStatic(mainMethod.modifiers)) {
                throw NoSuchMethodException("${payload.targetClass}.main(String[]) is not static")
            }
            mainMethod.invoke(null, payload.args as Any)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            cause.printStackTrace(System.err)
            System.exit(1)
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            System.exit(1)
        }
    }
}
