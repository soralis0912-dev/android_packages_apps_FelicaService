package com.felicanetworks.felica;

import android.os.Bundle;
import android.os.IBinder;

interface IFelicaSe {
    Bundle open(String packageName, IBinder callback);
    int close(String packageName, int handle, IBinder callback);
    Bundle transceive(String packageName, int handle, in byte[] commandApdu, int timeoutMs);
    int cancel(String packageName, int handle);
    int connect(String packageName, int mode);
    int disconnect(String packageName, int handle);
}
