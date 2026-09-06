//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package org.felica_droid.felica;

import android.app.Application;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.felicanetworks.felica.IFelicaAdapter;
import com.felicanetworks.felica.IFelicaAdapterExtra;
import com.felicanetworks.felica.IFelicaRf;
import com.felicanetworks.felica.IFelicaSe;

public final class FelicaService extends Application {
    private static final String TAG = "FelicaService";

    private static final String FRONTEND_SERVICE = "felica";
    private static final String BACKEND_SERVICE = "org.felica_droid.felica.IFelica/default";

    private static final int ERROR_NONE = 0;
    private static final int ERROR_FAILED = -99;
    private static final int ERROR_INVALID_PARAM = -10;
    private static final int ERROR_NOT_AVAILABLE = -18;

    private static final int STATE_OFF = 1;
    private static final int STATE_ON = 3;

    private static volatile IFelica sBackend;
    private static FelicaAdapterImpl sAdapter;
    private static boolean sRegistrationThreadStarted;
    private static NfceeAccessControl sAccessControl;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "FelicaService started");
        sAccessControl = new NfceeAccessControl(this);
        startRegistrationThread();
    }

    /**
     * The service is registered under app_api_service, so anything on the
     * device can look it up and every entry point has to check for itself.
     * The package name is the caller's own claim, which is why the check
     * covers both halves: that the uid owns the name, and that the signer
     * behind it is one felica_access.xml vouches for.
     */
    private static void enforceAccess(String packageName) {
        int uid = Binder.getCallingUid();
        NfceeAccessControl access = sAccessControl;
        if (access == null || !access.check(uid, packageName)) {
            Log.e(TAG, "refused " + packageName + " (uid " + uid + ")");
            throw new SecurityException(
                    packageName + " (uid " + uid + ") is not in felica_access.xml");
        }
    }

    static synchronized void startRegistrationThread() {
        if (sAdapter != null || sRegistrationThreadStarted) {
            return;
        }
        sRegistrationThreadStarted = true;

        new Thread(() -> {
            Log.i(TAG, "Waiting for felica HAL...");
            IFelica backend = waitForBackend();
            if (backend == null) {
                Log.e(TAG, "NativeFelicaSE not implemented! " + BACKEND_SERVICE
                        + " is not available");
                synchronized (FelicaService.class) {
                    sRegistrationThreadStarted = false;
                }
                return;
            }

            Log.i(TAG, "Connected to felica HAL");
            registerFelicaService();
        }, "FelicaServiceInit").start();
    }

    private static synchronized IFelica waitForBackend() {
        if (sBackend != null) {
            return sBackend;
        }

        try {
            Log.i(TAG, "getService: " + BACKEND_SERVICE);
            IBinder binder = ServiceManager.checkService(BACKEND_SERVICE);
            if (binder == null) {
                binder = ServiceManager.waitForService(BACKEND_SERVICE);
            }
            if (binder == null) {
                return null;
            }

            binder.linkToDeath(() -> {
                Log.w(TAG, "Felica backend died");
                synchronized (FelicaService.class) {
                    sBackend = null;
                }
            }, 0);

            sBackend = IFelica.Stub.asInterface(binder);
            return sBackend;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "failed to get felica backend", e);
            sBackend = null;
            return null;
        }
    }

    private static synchronized IFelica getBackend() {
        if (sBackend != null) {
            return sBackend;
        }

        try {
            IBinder binder = ServiceManager.checkService(BACKEND_SERVICE);
            if (binder == null) {
                return null;
            }

            binder.linkToDeath(() -> {
                Log.w(TAG, "Felica backend died");
                synchronized (FelicaService.class) {
                    sBackend = null;
                }
            }, 0);

            sBackend = IFelica.Stub.asInterface(binder);
            return sBackend;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "failed to reconnect felica backend", e);
            sBackend = null;
            return null;
        }
    }

    private static synchronized void registerFelicaService() {
        if (sAdapter != null) {
            return;
        }

        if (ServiceManager.checkService(FRONTEND_SERVICE) != null) {
            Log.i(TAG, FRONTEND_SERVICE + " already registered");
            return;
        }

        try {
            sAdapter = new FelicaAdapterImpl();
            ServiceManager.addService(FRONTEND_SERVICE, sAdapter);
            Log.i(TAG, "Registered felica service");
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to register " + FRONTEND_SERVICE, e);
            sAdapter = null;
        }
    }

    private static Bundle openError(int error) {
        Bundle b = new Bundle();
        b.putInt("out", -1);
        b.putInt("e", error);
        return b;
    }

    private static Bundle transceiveError(int error) {
        Bundle b = new Bundle();
        b.putByteArray("out", null);
        b.putInt("e", error);
        return b;
    }

    private static int intCall(IntCall call) {
        IFelica backend = getBackend();
        if (backend == null) {
            return ERROR_NOT_AVAILABLE;
        }
        try {
            return call.run(backend);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "backend int call failed", e);
            sBackend = null;
            return ERROR_FAILED;
        }
    }

    private static boolean booleanCall(BooleanCall call, boolean fallback) {
        IFelica backend = getBackend();
        if (backend == null) {
            return fallback;
        }
        try {
            return call.run(backend);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "backend boolean call failed", e);
            sBackend = null;
            return fallback;
        }
    }

    private static Bundle bundleCall(BundleCall call, boolean transceive) {
        IFelica backend = getBackend();
        if (backend == null) {
            return transceive ? transceiveError(ERROR_NOT_AVAILABLE) : openError(ERROR_NOT_AVAILABLE);
        }
        try {
            Bundle result = call.run(backend);
            if (result != null) {
                return result;
            }
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "backend bundle call failed", e);
            sBackend = null;
        }
        return transceive ? transceiveError(ERROR_FAILED) : openError(ERROR_FAILED);
    }

    private static final class FelicaAdapterImpl extends IFelicaAdapter.Stub {
        private final IFelicaSe mSe = new FelicaSeImpl();
        private final IFelicaRf mRf = new FelicaRfImpl();
        private final IFelicaAdapterExtra mExtra = new FelicaAdapterExtraImpl();

        @Override
        public IFelicaSe getFelicaSeInterface(String packageName) {
            enforceAccess(packageName);
            Log.i(TAG, "getFelicaSeInterface: " + packageName);
            return mSe;
        }

        @Override
        public IFelicaRf getFelicaRfInterface(String packageName) {
            enforceAccess(packageName);
            Log.i(TAG, "getFelicaRfInterface: " + packageName);
            return mRf;
        }

        @Override
        public IFelicaAdapterExtra getFelicaAdapterExtraInterface(String packageName) {
            enforceAccess(packageName);
            Log.i(TAG, "getFelicaAdapterExtraInterface: " + packageName);
            return mExtra;
        }
    }

    private static final class FelicaSeImpl extends IFelicaSe.Stub {
        @Override
        public Bundle open(String packageName, IBinder callback) {
            if (callback == null) {
                return openError(ERROR_INVALID_PARAM);
            }
            return bundleCall(backend -> backend.openSe(packageName, callback), false);
        }

        @Override
        public int close(String packageName, int handle, IBinder callback) {
            return intCall(backend -> backend.closeSe(packageName, handle, callback));
        }

        @Override
        public Bundle transceive(String packageName, int handle, byte[] commandApdu, int timeoutMs) {
            if (commandApdu == null || commandApdu.length == 0) {
                return transceiveError(ERROR_INVALID_PARAM);
            }
            return bundleCall(backend -> backend.transceiveSe(packageName, handle, commandApdu, timeoutMs), true);
        }

        @Override
        public int cancel(String packageName, int handle) {
            return intCall(backend -> backend.cancelSe(packageName, handle));
        }

        @Override
        public int connect(String packageName, int mode) {
            return ERROR_NONE;
        }

        @Override
        public int disconnect(String packageName, int handle) {
            return intCall(backend -> backend.disconnectSe(packageName, handle));
        }
    }

    private static final class FelicaRfImpl extends IFelicaRf.Stub {
        @Override
        public Bundle open(String packageName, IBinder callback) {
            if (callback == null) {
                return openError(ERROR_INVALID_PARAM);
            }
            return bundleCall(backend -> backend.openRf(packageName, callback), false);
        }

        @Override
        public int close(String packageName, int handle, IBinder callback) {
            return intCall(backend -> backend.closeRf(packageName, handle, callback));
        }

        @Override
        public Bundle transceive(String packageName, int handle, byte[] commandData, int timeoutMs) {
            if (commandData == null || commandData.length == 0) {
                return transceiveError(ERROR_INVALID_PARAM);
            }
            return bundleCall(backend -> backend.transceiveRf(packageName, handle, commandData, timeoutMs), true);
        }

        @Override
        public int cancel(String packageName, int handle) {
            return intCall(backend -> backend.cancelRf(packageName, handle));
        }

        @Override
        public int connect(String packageName, int mode, int options) {
            return intCall(backend -> backend.connectRf(packageName, mode, options));
        }

        @Override
        public int disconnect(String packageName, int handle) {
            return intCall(backend -> backend.disconnectRf(packageName, handle));
        }
    }

    private static final class FelicaAdapterExtraImpl extends IFelicaAdapterExtra.Stub {
        @Override
        public boolean enable() {
            return booleanCall(IFelica::enable, false);
        }

        @Override
        public boolean disable(boolean persist) {
            return booleanCall(backend -> backend.disable(persist), true);
        }

        @Override
        public int getState() {
            return intCall(IFelica::getState);
        }

        @Override
        public int getRwP2pState() {
            int state = intCall(IFelica::getRwP2pState);
            return state > 0 ? state : STATE_ON;
        }

        @Override
        public boolean setRwP2pMode(boolean isEnable) {
            return booleanCall(backend -> backend.setRwP2pMode(isEnable), false);
        }

        @Override
        public void prepareSwitchedOffState() {
            IFelica backend = getBackend();
            if (backend == null) {
                return;
            }
            try {
                backend.prepareSwitchedOffState();
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "prepareSwitchedOffState failed", e);
                sBackend = null;
            }
        }
    }

    private interface IntCall {
        int run(IFelica backend) throws RemoteException;
    }

    private interface BooleanCall {
        boolean run(IFelica backend) throws RemoteException;
    }

    private interface BundleCall {
        Bundle run(IFelica backend) throws RemoteException;
    }
}
