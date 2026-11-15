package com.lin.server;

import com.lin.server.IRemoteProcess;
import com.lin.server.ILinApplication;
import com.lin.server.IUserServiceCallback;

import com.lin.server.IRemotePtyProcess;

interface ILinService {

    int getVersion() = 2;

    int getUid() = 3;

    String getSELinuxContext() = 8;

    String getVersionName() = 18;

    int getVersionCode() = 19;


    int checkPermission(String permission) = 4;

    boolean shouldShowRequestPermissionRationale() = 16;

    String[] getSupportedPermissions() = 110;

    boolean checkSelfPermission(String permission) = 111;

    void requestPermission(String permission, int requestCode) = 112;


    IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir) = 7;

    IRemotePtyProcess newPtyProcess(in String[] cmd, in String[] env, in String dir) = 500;


    String getSystemProperty(in String name, in String defaultValue) = 9;

    void setSystemProperty(in String name, in String value) = 10;


    void attachApplication(in ILinApplication application, in Bundle args) = 17;



    void exit() = 100;

    oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data) = 104;

    int getFlagForUid(int uid, String permission) = 105;

    void updateFlagForUid(int uid, String permission, int flag) = 106;

    int getAuthorizedCount() = 109;

    void grantRuntimePermission(String packageName, String permissionName, int userId) = 107;

    void revokeRuntimePermission(String packageName, String permissionName, int userId) = 108;


    String startUserService(in Bundle args, in IUserServiceCallback callback) = 200;

    void stopUserService(String token) = 201;

    void attachUserService(in IBinder binder, in Bundle options) = 202;

    int getUserServiceCount() = 203;

    List<String> getLogs() = 300;

    void clearLogs() = 301;

    List<String> getLogsForUid(int uid) = 302;

    boolean isShizukuCompatEnabled() = 400;

    void setShizukuCompatEnabled(boolean enabled) = 401;

    boolean isDaemonEnabled() = 402;

    void setDaemonEnabled(boolean enabled) = 403;

    IBinder getSystemService(String name) = 404;

}
