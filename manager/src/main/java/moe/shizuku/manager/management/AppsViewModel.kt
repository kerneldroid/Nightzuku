package moe.shizuku.manager.management

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.lifecycle.Resource
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ClientRouting

@MainThread
fun ComponentActivity.appsViewModel() = viewModels { AppsViewModel(this) }

/**
 * Everything a row of the app list needs, resolved off the main thread.
 * The UI only renders it; it never talks to Shizuku or PackageManager.
 */
@Immutable
data class AppRow(
    val packageName: String,
    val uid: Int,
    val title: String,
    val icon: ImageBitmap?,
    val requiresRoot: Boolean,
    val granted: Boolean,
    val isNativeWire: Boolean = false,
    val isOneTime: Boolean = false,
    val timerRemainingMs: Long = 0L
) {
    val key: String = keyOf(uid, packageName)

    companion object {
        fun keyOf(uid: Int, packageName: String) = "$uid:$packageName"
    }
}

class AppsViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>()
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    private val _rows = MutableStateFlow<List<AppRow>?>(null)

    /** `null` while loading. Only filled when [load] is called with `buildRows = true`. */
    val rows: StateFlow<List<AppRow>?> = _rows.asStateFlow()

    private val _adbLimited = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when ADB-only Shizuku refused to change a permission. */
    val adbLimited: SharedFlow<Unit> = _adbLimited.asSharedFlow()

    private val loadMutex = Mutex()
    private val mutationMutex = Mutex()

    // Main thread only. A refresh that overlaps a grant/revoke may have read the old
    // server state, so its result is dropped instead of undoing the optimistic update.
    private var inFlight = 0
    private var epoch = 0

    private data class UidStatus(
        val granted: Boolean,
        val isOneTime: Boolean,
        val timerRemainingMs: Long
    )

    private class Loaded(
        val packages: List<PackageInfo>,
        val statusByUid: Map<Int, UidStatus>,
        val rows: List<AppRow>?,
    )

    fun load(onlyCount: Boolean = false, buildRows: Boolean = false) {
        viewModelScope.launch {
            loadMutex.withLock { doLoad(onlyCount, buildRows) }
        }
    }

    private suspend fun doLoad(onlyCount: Boolean, buildRows: Boolean) {
        try {
            val pm = appContext.packageManager
            val loaded = withContext(Dispatchers.IO) {
                val list = AuthorizationManager.getPackages().filter { it.applicationInfo != null }
                val statusByUid = HashMap<Int, UidStatus>()
                for (pi in list) {
                    val uid = pi.applicationInfo!!.uid
                    if (uid !in statusByUid) {
                        val oneTime = AuthorizationManager.isOneTime(uid)
                        val granted = AuthorizationManager.granted(pi.packageName, uid) || oneTime
                        val timerMs = if ( !onlyCount && buildRows ) {
                            AuthorizationManager.getAccessTimerRemaining(uid)
                        } else {
                            0L
                        }
                        statusByUid[uid] = UidStatus(granted, oneTime, timerMs)
                    }
                }
                val rows = if (!onlyCount && buildRows) {
                    list.map {
                        val status = statusByUid.getValue(it.applicationInfo!!.uid)
                        buildRow(pm, it, status)
                    }
                } else {
                    null
                }
                Loaded(list, statusByUid, rows)
            }

            val count = loaded.packages.count { loaded.statusByUid[it.applicationInfo!!.uid]?.granted == true }
            if (!onlyCount) _packages.value = Resource.success(loaded.packages)
            if (loaded.rows != null) _rows.value = loaded.rows
            _grantedCount.value = Resource.success(count)

            if (loaded.rows != null) loadMissingIcons(pm, loaded.packages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _packages.value = Resource.error(e, null)
            _grantedCount.value = Resource.error(e, 0)
        }
    }

    /** Re-reads the permission of every row from the server. Call from the main thread. */
    suspend fun refreshGranted() {
        val snapshot = _rows.value ?: return
        if (inFlight > 0) return
        val startEpoch = epoch

        val fresh = withContext(Dispatchers.IO) {
            snapshot.distinctBy { it.uid }.mapNotNull { row ->
                try {
                    val oneTime = AuthorizationManager.isOneTime(row.uid)
                    val granted = AuthorizationManager.granted(row.packageName, row.uid) || oneTime
                    val timerMs = AuthorizationManager.getAccessTimerRemaining(row.uid)
                    row.uid to UidStatus(granted, oneTime, timerMs)
                } catch (_: Throwable) {
                    null
                }
            }.toMap()
        }

        if (epoch != startEpoch || inFlight > 0) return
        _rows.update { rows ->
            rows?.map { row ->
                val status = fresh[row.uid]
                if (status != null &&
                    (status.granted != row.granted ||
                        status.isOneTime != row.isOneTime ||
                        status.timerRemainingMs != row.timerRemainingMs)
                ) {
                    row.copy(
                        granted = status.granted,
                        isOneTime = status.isOneTime,
                        timerRemainingMs = status.timerRemainingMs
                    )
                } else {
                    row
                }
            }
        }
        publishCount()
    }

    /** Flips the switch immediately; the server call runs in the background and is rolled back on failure. */
    fun toggle(uid: Int, packageName: String) {
        val current = _rows.value?.firstOrNull { it.uid == uid && it.packageName == packageName } ?: return
        val target = !current.granted

        setGrantedLocally(uid, target)
        beginMutation()
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        if (target) {
                            AuthorizationManager.grant(packageName, uid)
                        } else {
                            AuthorizationManager.revoke(packageName, uid)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to ${if (target) "grant" else "revoke"} $packageName", e)
                setGrantedLocally(uid, !target)
                if (e is SecurityException) notifyAdbLimitedIfNeeded()
            } finally {
                endMutation()
            }
        }
    }

    fun setAll(granted: Boolean) {
        val targets = _rows.value?.distinctBy { it.uid } ?: return

        _rows.update { rows ->
            rows?.map { if (it.granted != granted) it.copy(granted = granted, isOneTime = false) else it }
        }
        publishCount()
        beginMutation()
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        for (row in targets) {
                            try {
                                if (granted) {
                                    AuthorizationManager.grant(row.packageName, row.uid)
                                } else {
                                    AuthorizationManager.revoke(row.packageName, row.uid)
                                }
                            } catch (_: SecurityException) {
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to ${if (granted) "grant" else "revoke"} all", e)
            } finally {
                endMutation()
            }
            refreshGranted()
        }
    }

    private fun beginMutation() {
        inFlight++
        epoch++
    }

    private fun endMutation() {
        inFlight--
        epoch++
    }

    private fun setGrantedLocally(uid: Int, granted: Boolean) {
        _rows.update { rows ->
            rows?.map {
                if (it.uid == uid && (it.granted != granted || !granted)) {
                    it.copy(
                        granted = granted,
                        isOneTime = if (!granted) false else it.isOneTime,
                        timerRemainingMs = if (!granted) 0L else it.timerRemainingMs
                    )
                } else {
                    it
                }
            }
        }
        publishCount()
    }

    private fun publishCount() {
        val rows = _rows.value ?: return
        val count = rows.count { it.granted }
        if (_grantedCount.value?.data != count) {
            _grantedCount.value = Resource.success(count)
        }
    }

    private suspend fun notifyAdbLimitedIfNeeded() {
        val serverUid = withContext(Dispatchers.IO) {
            try {
                Shizuku.getUid()
            } catch (_: Throwable) {
                null
            }
        } ?: return
        if (serverUid != 0) _adbLimited.tryEmit(Unit)
    }

    private fun buildRow(pm: PackageManager, pi: PackageInfo, status: UidStatus): AppRow {
        val ai = pi.applicationInfo!!
        val userId = UserHandleCompat.getUserId(ai.uid)
        val label = try {
            ai.loadLabel(pm).toString()
        } catch (_: Throwable) {
            pi.packageName
        }
        val title = if (userId != UserHandleCompat.myUserId()) {
            val userName = try {
                ShizukuSystemApis.getUserInfo(userId).name
            } catch (_: Throwable) {
                null
            }
            if (userName != null) "$label - $userName ($userId)" else "$label ($userId)"
        } else {
            label
        }
        val isNativeWire = ClientRouting.route(pi) == ClientRouting.Wire.NIGHTZUKU
        return AppRow(
            packageName = pi.packageName,
            uid = ai.uid,
            title = title,
            icon = iconCache.get(iconKey(pi)),
            requiresRoot = ai.metaData?.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT") == true,
            granted = status.granted,
            isNativeWire = isNativeWire,
            isOneTime = status.isOneTime,
            timerRemainingMs = status.timerRemainingMs
        )
    }

    private suspend fun loadMissingIcons(pm: PackageManager, packages: List<PackageInfo>) {
        val missing = packages.filter { iconCache.get(iconKey(it)) == null }
        for (chunk in missing.chunked(ICON_CHUNK)) {
            val decoded = coroutineScope {
                chunk.map { pi ->
                    async(Dispatchers.Default) {
                        decodeIcon(pm, pi)?.let { AppRow.keyOf(pi.applicationInfo!!.uid, pi.packageName) to it }
                    }
                }.awaitAll()
            }.filterNotNull().toMap()

            if (decoded.isNotEmpty()) {
                _rows.update { rows ->
                    rows?.map { row -> decoded[row.key]?.let { row.copy(icon = it) } ?: row }
                }
            }
        }
    }

    private fun decodeIcon(pm: PackageManager, pi: PackageInfo): ImageBitmap? {
        return try {
            val bitmap = pi.applicationInfo!!.loadIcon(pm)
                .toBitmap(width = ICON_PX, height = ICON_PX)
                .asImageBitmap()
            iconCache.put(iconKey(pi), bitmap)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun iconKey(pi: PackageInfo): String {
        val ai = pi.applicationInfo!!
        return "${ai.uid}:${pi.packageName}:${ai.sourceDir}"
    }

    private companion object {
        const val TAG = "AppsViewModel"
        const val ICON_PX = 96
        const val ICON_CHUNK = 6

        // Process-wide, so reopening the screen does not decode every icon again.
        val iconCache = LruCache<String, ImageBitmap>(512)
    }
}
