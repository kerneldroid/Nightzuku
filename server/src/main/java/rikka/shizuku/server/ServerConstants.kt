package rikka.shizuku.server

object ServerConstants {

    const val MANAGER_APP_NOT_FOUND = 50

    const val PERMISSION = "kerneldroid.nightzuku.permission.API"
    const val MANAGER_PERMISSION = "kerneldroid.nightzuku.permission.MANAGER"
    const val LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    const val MANAGER_APPLICATION_ID = "kerneldroid.nightzuku"
    const val REQUEST_PERMISSION_ACTION = "$MANAGER_APPLICATION_ID.intent.action.REQUEST_PERMISSION"

    const val BINDER_TRANSACTION_getApplications = 10001
    const val BINDER_TRANSACTION_setNightDogEnabled = 10002
    const val BINDER_TRANSACTION_getNightDogEnabled = 10003
    const val BINDER_TRANSACTION_claimUserServiceLaunch = 10004
    const val BINDER_TRANSACTION_getNightDogStatus = 10005
}
