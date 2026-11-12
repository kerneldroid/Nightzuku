package rikka.shizuku.server

object ServerConstants {

    const val MANAGER_APP_NOT_FOUND = 50

    const val PERMISSION = "kerneldroid.nightzuku.permission.API"
    const val MANAGER_PERMISSION = "kerneldroid.nightzuku.permission.MANAGER"
    const val LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    const val MANAGER_APPLICATION_ID = "kerneldroid.nightzuku"
    const val REQUEST_PERMISSION_ACTION_SUFFIX = ".intent.action.REQUEST_PERMISSION"
    const val REQUEST_PERMISSION_ACTION = "$MANAGER_APPLICATION_ID$REQUEST_PERMISSION_ACTION_SUFFIX"

    const val BINDER_TRANSACTION_getApplications = 10001
    const val BINDER_TRANSACTION_setNightDogEnabled = 10002
    const val BINDER_TRANSACTION_getNightDogEnabled = 10003
    const val BINDER_TRANSACTION_claimUserServiceLaunch = 10004
    const val BINDER_TRANSACTION_getNightDogStatus = 10005
    const val BINDER_TRANSACTION_setAccessPaused = 10006
    const val BINDER_TRANSACTION_getAccessPaused = 10007
    const val BINDER_TRANSACTION_setOneTimeGrant = 10008
    const val BINDER_TRANSACTION_isOneTimeGrant = 10009
    const val BINDER_TRANSACTION_setAccessTimer = 10010
    const val BINDER_TRANSACTION_getAccessTimerRemaining = 10011
    const val BINDER_TRANSACTION_forceStopApp = 10012
    const val BINDER_TRANSACTION_getAppControlInfo = 10013
    const val BINDER_TRANSACTION_requestBinderForClient = 10014
    const val BINDER_TRANSACTION_getSmartLogs = 10015
    const val BINDER_TRANSACTION_clearSmartLogs = 10016
}
