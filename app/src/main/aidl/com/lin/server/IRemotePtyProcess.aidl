package com.lin.server;

interface IRemotePtyProcess {
    ParcelFileDescriptor getPtyFd();
    void resize(long size);
    int waitFor();
    void destroy();
}
