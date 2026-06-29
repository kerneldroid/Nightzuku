package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String MANAGER_APPLICATION_ID = "kerneldroid.nightzuku";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
    public static final int BINDER_TRANSACTION_setNightDogEnabled = 10002;
    public static final int BINDER_TRANSACTION_getNightDogEnabled = 10003;

    public static final String TERMUX_PACKAGE_NAME = "com.termux";
    public static final String TAPI_META_DATA = "kerneldroid.nightzuku.TAPI_SUPPORT";
    public static final String TAPI_CONTENT_AUTHORITY = "kerneldroid.nightzuku.tapi";
}
