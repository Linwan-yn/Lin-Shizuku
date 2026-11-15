package com.lin.server;

interface ILinApplication {

    oneway void bindApplication(in Bundle data) = 1;

    oneway void dispatchRequestPermissionResult(int requestCode, in Bundle data) = 2;

    oneway void onServiceStarted() = 3;
}
