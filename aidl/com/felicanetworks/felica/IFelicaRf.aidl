package com.felicanetworks.felica;

import android.os.Bundle;
import android.os.IBinder;

interface IFelicaRf {
    Bundle open(String packageName, IBinder callback);
    int close(String packageName, int handle, IBinder callback);
    Bundle transceive(String packageName, int handle, in byte[] commandData, int timeoutMs);
    int cancel(String packageName, int handle);
    int connect(String packageName, int mode, int options);
    int disconnect(String packageName, int handle);
}
