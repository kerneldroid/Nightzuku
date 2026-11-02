package rikka.shizuku.server

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import moe.shizuku.server.IShizukuApplication

class ShizukuClientManager(
    configManager: ShizukuConfigManager
) : ClientManager<ShizukuConfigManager>(configManager) {

    private val attachedRecords = ArrayList<ClientRecord>()
    private val legacyBinders = ConcurrentHashMap<Pair<Int, Int>, IShizukuApplication>()
    private val legacyDeaths = ConcurrentHashMap<Pair<Int, Int>, IBinder.DeathRecipient>()
    private val oneTimeGrantedUids = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    var onClientDiedCallback: ((uid: Int, pid: Int, packageName: String?) -> Unit)? = null

    @Synchronized
    fun getActiveClients(): List<ClientRecord> {
        attachedRecords.removeIf { record -> findClient(record.uid, record.pid) !== record }
        return ArrayList(attachedRecords)
    }

    fun registerLegacyApplication(uid: Int, pid: Int, application: IShizukuApplication) {
        val key = uid to pid
        val oldApp = legacyBinders.remove(key)
        val oldDeath = legacyDeaths.remove(key)
        if (oldApp != null && oldDeath != null) {
            try {
                oldApp.asBinder().unlinkToDeath(oldDeath, 0)
            } catch (_: Throwable) {
            }
        }

        val death = IBinder.DeathRecipient {
            if (legacyBinders.remove(key, application)) {
                legacyDeaths.remove(key)
            }
        }
        legacyBinders[key] = application
        legacyDeaths[key] = death
        try {
            application.asBinder().linkToDeath(death, 0)
        } catch (_: Throwable) {
            legacyBinders.remove(key, application)
            legacyDeaths.remove(key, death)
        }
    }

    fun getLegacyApplication(uid: Int, pid: Int): IShizukuApplication? {
        return legacyBinders[uid to pid]
    }

    fun markOneTimeGrant(uid: Int) {
        oneTimeGrantedUids.add(uid)
    }

    fun clearOneTimeGrant(uid: Int): Boolean {
        return oneTimeGrantedUids.remove(uid)
    }

    fun isOneTimeGranted(uid: Int): Boolean {
        return oneTimeGrantedUids.contains(uid)
    }

    override fun onClientRemoved(clientRecord: ClientRecord) {
        notifyProcessDied(clientRecord.uid, clientRecord.pid)
    }

    fun notifyProcessDied(uid: Int, pid: Int) {
        val key = uid to pid
        val oldApp = legacyBinders.remove(key)
        val oldDeath = legacyDeaths.remove(key)
        if (oldApp != null && oldDeath != null) {
            try {
                oldApp.asBinder().unlinkToDeath(oldDeath, 0)
            } catch (_: Throwable) {
            }
        }
        var removedRecord: ClientRecord? = null
        synchronized(this) {
            val iterator = attachedRecords.iterator()
            while (iterator.hasNext()) {
                val record = iterator.next()
                if (record.uid == uid && record.pid == pid) {
                    removedRecord = record
                    iterator.remove()
                    break
                }
            }
        }
        val pkg = removedRecord?.packageName
        onClientDiedCallback?.invoke(uid, pid, pkg)
    }

    @Synchronized
    override fun addClient(
        uid: Int,
        pid: Int,
        client: IShizukuApplication?,
        packageName: String?,
        apiVersion: Int
    ): ClientRecord? {
        val record = super.addClient(uid, pid, client, packageName, apiVersion) ?: return null
        attachedRecords.removeIf { old ->
            (old.uid == uid && old.pid == pid) || findClient(old.uid, old.pid) !== old
        }
        attachedRecords.add(record)

        if (configManager.isAccessPaused()) {
            record.allowed = false
        } else if (isOneTimeGranted(uid)) {
            record.allowed = true
            configManager.recordConnection(uid)
        } else if (record.allowed && packageName != null) {
            if (!configManager.verifyClientIdentity(uid, packageName)) {
                record.allowed = false
            } else {
                configManager.recordConnection(uid)
            }
        }

        return record
    }
}
