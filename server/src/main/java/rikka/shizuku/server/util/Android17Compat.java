package rikka.shizuku.server.util;

import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;

public class Android17Compat {

    private static final String TAG = "ShizukuAndroid17Compat";
    private static final int DEVICE_ID_DEFAULT = 0; // Context.DEVICE_ID_DEFAULT

    @SuppressWarnings("unchecked")
    public static List<PackageInfo> getInstalledPackages(long flags, int userId) {
        try {
            return PackageManagerApis.getInstalledPackagesNoThrow(flags, userId);
        } catch (NoSuchMethodError e) {
            // Android 17 (API 37) compatibility fallback
            try {
                IBinder binder = ServiceManager.getService("package");
                Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
                Object pm = stubClass.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);

                for (Method method : pm.getClass().getMethods()) {
                    if ("getInstalledPackages".equals(method.getName())) {
                        Class<?>[] paramTypes = method.getParameterTypes();
                        Object[] args = new Object[paramTypes.length];
                        for (int i = 0; i < paramTypes.length; i++) {
                            if (paramTypes[i] == long.class) {
                                args[i] = flags;
                            } else if (paramTypes[i] == int.class) {
                                args[i] = userId;
                            } else {
                                args[i] = null;
                            }
                        }
                        
                        Object result = method.invoke(pm, args);
                        if (result != null) {
                            Method getListMethod = result.getClass().getMethod("getList");
                            return (List<PackageInfo>) getListMethod.invoke(result);
                        }
                    }
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for getInstalledPackages failed", ex);
            }
            return new ArrayList<>();
        }
    }

    public static void grantRuntimePermission(String packageName, String permissionName, int userId) throws android.os.RemoteException {
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId);
        } catch (NoSuchMethodError e) {
            try {
                IBinder binder = ServiceManager.getService("permissionmgr");
                Class<?> stubClass = Class.forName("android.permission.IPermissionManager$Stub");
                Object pm = stubClass.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);

                for (Method method : pm.getClass().getMethods()) {
                    if ("grantRuntimePermission".equals(method.getName())) {
                        Class<?>[] paramTypes = method.getParameterTypes();
                        // Search for the method signature matching: String, String, int (deviceId), int (userId)
                        if (paramTypes.length >= 3 && paramTypes[0] == String.class && paramTypes[1] == String.class) {
                            Object[] args = new Object[paramTypes.length];
                            args[0] = packageName;
                            args[1] = permissionName;
                            
                            // For API 37+, it has deviceId and userId
                            if (paramTypes.length == 4 && paramTypes[2] == int.class && paramTypes[3] == int.class) {
                                args[2] = DEVICE_ID_DEFAULT; // deviceId
                                args[3] = userId; // userId
                            } else {
                                // Just fill ints with userId as best effort fallback
                                for (int i = 2; i < paramTypes.length; i++) {
                                    if (paramTypes[i] == int.class) {
                                        args[i] = userId;
                                    }
                                }
                            }
                            method.invoke(pm, args);
                            return;
                        }
                    }
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for grantRuntimePermission failed", ex);
            }
        }
    }

    public static void revokeRuntimePermission(String packageName, String permissionName, int userId) throws android.os.RemoteException {
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId);
        } catch (NoSuchMethodError e) {
            try {
                IBinder binder = ServiceManager.getService("permissionmgr");
                Class<?> stubClass = Class.forName("android.permission.IPermissionManager$Stub");
                Object pm = stubClass.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);

                for (Method method : pm.getClass().getMethods()) {
                    if ("revokeRuntimePermission".equals(method.getName())) {
                        Class<?>[] paramTypes = method.getParameterTypes();
                        if (paramTypes.length >= 3 && paramTypes[0] == String.class && paramTypes[1] == String.class) {
                            Object[] args = new Object[paramTypes.length];
                            args[0] = packageName;
                            args[1] = permissionName;
                            
                            if (paramTypes.length == 5 && paramTypes[2] == int.class && paramTypes[3] == int.class && paramTypes[4] == String.class) {
                                // revokeRuntimePermission(String, String, int deviceId, int userId, String reason)
                                args[2] = DEVICE_ID_DEFAULT;
                                args[3] = userId;
                                args[4] = "shizuku";
                            } else if (paramTypes.length == 4 && paramTypes[2] == int.class && paramTypes[3] == int.class) {
                                args[2] = DEVICE_ID_DEFAULT;
                                args[3] = userId;
                            } else {
                                for (int i = 2; i < paramTypes.length; i++) {
                                    if (paramTypes[i] == int.class) {
                                        args[i] = userId;
                                    }
                                }
                            }
                            method.invoke(pm, args);
                            return;
                        }
                    }
                }
            } catch (Throwable ex) {
                Log.e(TAG, "Android 17 fallback for revokeRuntimePermission failed", ex);
            }
        }
    }
}
