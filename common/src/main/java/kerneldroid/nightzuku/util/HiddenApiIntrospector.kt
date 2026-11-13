package kerneldroid.nightzuku.util

import android.os.Build
import android.os.IBinder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime reflection inspector for hidden Android framework Binder interfaces.
 *
 * When a future Android release (e.g., Android 16/17 QPR4+) or OEM ROM modifies the parameter
 * shape, argument order, deviceId type, or return type of a hidden system service method
 * (`IPackageManager`, `IPermissionManager`, `IUserManager`, `IActivityManager`, `IContentProvider`),
 * this utility first lifts ART hidden-API restrictions (`HiddenApiBypass` + `VMRuntime`) and then
 * introspects the live `Stub$Proxy` and interface descriptors (including blacklisted methods via
 * `HiddenApiBypass.getDeclaredMethods`) to dump the exact method signatures present on the device.
 */
object HiddenApiIntrospector {

    private val hiddenApiExempted = AtomicBoolean(false)

    private val PRIMITIVE_OR_COMMON = setOf(
        "void", "boolean", "byte", "short", "char", "int", "long", "float", "double",
        "java.lang.String", "java.lang.Object", "android.os.IBinder", "android.os.Bundle",
        "android.content.pm.PackageInfo", "android.content.pm.ApplicationInfo",
        "android.content.pm.UserInfo"
    )

    /**
     * Lifts ART hidden API restrictions inside both the Manager process and `shizuku_server`
     * (`app_process`). Safe to invoke multiple times and in unit-test environments.
     */
    @JvmStatic
    fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (hiddenApiExempted.get()) return

        var exempted = false
        // 1. Try LSPosed HiddenApiBypass (present in base.apk classpath for both manager and server)
        try {
            val bypassClass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
            val setExemptions = bypassClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            val ok = setExemptions.invoke(null, arrayOf("")) as? Boolean ?: false
            if (ok) {
                exempted = true
            }
        } catch (_: Throwable) {
        }

        // 2. Fallback / reinforcement via dalvik.system.VMRuntime (works in privileged app_process or after bypass)
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val runtime = getRuntime.invoke(null)
            if (runtime != null) {
                val setExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    Array<String>::class.java
                )
                setExemptions.invoke(runtime, arrayOf("L"))
                exempted = true
            }
        } catch (_: Throwable) {
        }

        if (exempted) {
            hiddenApiExempted.set(true)
        }
    }

    /**
     * Retrieves all declared and public methods on [clazz], combining standard reflection with
     * `HiddenApiBypass.getDeclaredMethods(clazz)` on API 28+ so blacklisted methods on strict
     * OEM firmwares are never hidden from QPR introspection.
     */
    @JvmStatic
    fun getAllMethodsUnfiltered(clazz: Class<*>): List<Method> {
        exemptHiddenApis()
        val collected = LinkedHashMap<String, Method>()

        // 1. Direct ART ArtMethod introspection via HiddenApiBypass.getDeclaredMethods if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val bypassClass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
                val getDeclaredMethods = bypassClass.getDeclaredMethod("getDeclaredMethods", Class::class.java)
                val rawList = getDeclaredMethods.invoke(null, clazz) as? List<*>
                if (rawList != null) {
                    for (item in rawList) {
                        if (item is Method) {
                            collected[methodKey(item)] = item
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Standard declaredMethods and methods
        runCatching {
            for (m in clazz.declaredMethods) {
                collected.putIfAbsent(methodKey(m), m)
            }
        }
        runCatching {
            for (m in clazz.methods) {
                collected.putIfAbsent(methodKey(m), m)
            }
        }

        return collected.values.toList()
    }

    private fun methodKey(method: Method): String {
        val params = method.parameterTypes.joinToString(",") { it.name }
        return "${method.returnType.name}:${method.name}($params)"
    }

    /**
     * Resolves a system service Binder via `android.os.ServiceManager.getService(serviceName)`
     * and converts it to its typed AIDL `Stub$Proxy` instance if possible.
     */
    @JvmStatic
    fun resolveServiceProxy(serviceName: String, interfaceName: String): Any? {
        exemptHiddenApis()
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, serviceName) as? IBinder ?: return null
            val stubClass = Class.forName("$interfaceName\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Produces a detailed reflection snapshot of all methods matching [methodKeywords] on
     * [interfaceName] and/or [targetProxy]. Also inspects custom container return types
     * (such as `ParceledListSlice` or `PackageInfoList`).
     */
    @JvmStatic
    fun inspectInterfaceMethods(
        serviceName: String,
        interfaceName: String,
        targetProxy: Any? = null,
        methodKeywords: List<String> = emptyList()
    ): String {
        exemptHiddenApis()
        val sb = StringBuilder(512)
        sb.appendLine("Hidden API Reflection Snapshot:")
        sb.appendLine("  Service: $serviceName | Interface: $interfaceName | HiddenApiBypass: ${hiddenApiExempted.get()}")
        sb.appendLine("  SDK: ${Build.VERSION.SDK_INT} (Preview: ${Build.VERSION.PREVIEW_SDK_INT}, Codename: ${Build.VERSION.CODENAME}, Release: ${Build.VERSION.RELEASE})")

        val classesToInspect = LinkedHashSet<Class<*>>()
        try {
            classesToInspect.add(Class.forName(interfaceName))
        } catch (t: Throwable) {
            sb.appendLine("  Interface class lookup failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        val resolvedProxy = targetProxy ?: resolveServiceProxy(serviceName, interfaceName)
        if (resolvedProxy != null) {
            classesToInspect.add(resolvedProxy.javaClass)
            resolvedProxy.javaClass.interfaces.forEach { classesToInspect.add(it) }
        } else {
            sb.appendLine("  Live proxy: unavailable (service '$serviceName' not bound or restricted)")
        }

        val discoveredSignatures = LinkedHashSet<String>()
        val customReturnTypes = LinkedHashSet<Class<*>>()

        for (clazz in classesToInspect) {
            val methods = getAllMethodsUnfiltered(clazz)
            for (method in methods) {
                if (method.declaringClass == Any::class.java) continue
                val matchesKeyword = methodKeywords.isEmpty() || methodKeywords.any { kw ->
                    method.name.contains(kw, ignoreCase = true)
                }
                if (!matchesKeyword) continue

                discoveredSignatures.add(formatMethodSignature(method))
                val retType = method.returnType
                if (!retType.isPrimitive && !retType.isArray && retType.name !in PRIMITIVE_OR_COMMON) {
                    customReturnTypes.add(retType)
                }
            }
        }

        if (discoveredSignatures.isEmpty()) {
            sb.appendLine("  Matched Methods: <none matching ${methodKeywords.joinToString()}>")
        } else {
            sb.appendLine("  Matched Methods (${discoveredSignatures.size}):")
            for (sig in discoveredSignatures.sorted()) {
                sb.appendLine("    - $sig")
            }
        }

        if (customReturnTypes.isNotEmpty()) {
            sb.appendLine("  Return Container Types:")
            for (retClass in customReturnTypes) {
                val accessors = getAllMethodsUnfiltered(retClass)
                    .filter { it.declaringClass != Any::class.java && Modifier.isPublic(it.modifiers) }
                    .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }}):${it.returnType.simpleName}" }
                    .distinct()
                    .sorted()
                sb.appendLine("    - ${retClass.name} -> [${accessors.joinToString(", ")}]")
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Runs a non-destructive compatibility probe against critical Android hidden interfaces
     * (`package`, `permissionmgr`, `user`, `activity`) to detect QPR/OEM signature drift.
     */
    @JvmStatic
    fun runLiveCompatProbe(): String {
        exemptHiddenApis()
        val sb = StringBuilder(768)
        sb.appendLine("[QPR / Hidden API Live Compatibility Probe (HiddenApiBypass=${hiddenApiExempted.get()})]")
        val targets = listOf(
            Triple(
                "package",
                "android.content.pm.IPackageManager",
                listOf("getPackageInfo", "getApplicationInfo", "getInstalledPackages", "checkUidPermission")
            ),
            Triple(
                "permissionmgr",
                "android.permission.IPermissionManager",
                listOf("checkPermission", "checkUidPermission", "grantRuntimePermission", "revokeRuntimePermission")
            ),
            Triple(
                "user",
                "android.os.IUserManager",
                listOf("getUsers", "getUserInfo", "getProfileParent")
            ),
            Triple(
                "activity",
                "android.app.IActivityManager",
                listOf("getCurrentUser", "getContentProviderExternal", "checkPermission", "forceStopPackage")
            )
        )

        for ((serviceName, interfaceName, keywords) in targets) {
            val clazz = runCatching { Class.forName(interfaceName) }.getOrNull()
            if (clazz == null) {
                sb.appendLine("  • $interfaceName ($serviceName): CLASS_NOT_FOUND")
                continue
            }
            val methods = getAllMethodsUnfiltered(clazz)
            val matched = methods.filter { m -> keywords.any { kw -> m.name == kw } }
                .map { formatMethodSignature(it) }
                .distinct()
                .sorted()
            if (matched.isEmpty()) {
                sb.appendLine("  • $interfaceName ($serviceName): NO_EXPECTED_METHODS_FOUND (potential QPR break!)")
            } else {
                sb.appendLine("  • $interfaceName ($serviceName):")
                for (sig in matched) {
                    sb.appendLine("      $sig")
                }
            }
        }
        return sb.toString().trimEnd()
    }

    @JvmStatic
    fun formatMethodSignature(method: Method): String {
        val params = method.parameterTypes.joinToString(", ") { formatTypeName(it) }
        val ret = formatTypeName(method.returnType)
        return "$ret ${method.name}($params)"
    }

    private fun formatTypeName(type: Class<*>): String {
        if (type.isArray) {
            return "${formatTypeName(type.componentType!!)}[]"
        }
        return type.simpleName.ifEmpty { type.name }
    }
}
