package com.forgemanager.app.shizuku;

import android.os.ParcelFileDescriptor;

interface IPrivilegedFileService {
    String[] list(String path, boolean showHidden) = 1;
    String stat(String path) = 2;
    ParcelFileDescriptor openRead(String path) = 3;
    ParcelFileDescriptor openWrite(String path, boolean truncate) = 4;
    boolean create(String parent, String name) = 5;
    boolean mkdir(String parent, String name) = 6;
    boolean rename(String source, String target) = 7;
    boolean deleteRecursively(String path) = 8;
    int serviceUid() = 9;
    void destroy() = 16777114;
}
