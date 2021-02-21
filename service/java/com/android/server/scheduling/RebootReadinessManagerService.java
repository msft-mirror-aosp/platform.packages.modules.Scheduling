/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.scheduling;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.scheduling.IRebootReadinessCallback;
import android.scheduling.IRebootReadinessManager;
import android.scheduling.RebootReadinessManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of service that analyzes device state to detect if the device is in a suitable
 * state to reboot.
 *
 * @hide
 */
public class RebootReadinessManagerService extends IRebootReadinessManager.Stub {
    private static final String TAG = "RebootReadinessManager";

    private final RemoteCallbackList<IRebootReadinessCallback> mCallbacks =
            new RemoteCallbackList<IRebootReadinessCallback>();
    private final Handler mHandler;

    private final Object mLock = new Object();

    private final Context mContext;


    // TODO(b/161353402): Make configurable via DeviceConfig
    private static final long POLLING_FREQUENCY_WHILE_IDLE_MS = TimeUnit.SECONDS.toMillis(2);
    private static final long POLLING_FREQUENCY_WHILE_ACTIVE_MS = TimeUnit.SECONDS.toMillis(2);

    @GuardedBy("mLock")
    private boolean mReadyToReboot = false;

    // A mapping of uid to package name for uids which have called markRebootPending. Reboot
    // readiness state changed broadcasts will only be sent to the values in this map.
    @GuardedBy("mLock")
    private final SparseArray<ArraySet<String>> mCallingUidToPackageMap = new SparseArray<>();

    // When true, reboot readiness checks should not be performed.
    @GuardedBy("mLock")
    private boolean mCanceled = false;

    @VisibleForTesting
    RebootReadinessManagerService(Context context) {
        mHandler = new Handler(Looper.getMainLooper());
        mContext = context;
    }

    /**
     * Lifecycle class for RebootReadinessManagerService.
     */
    public static class Lifecycle extends SystemService {

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            RebootReadinessManagerService rebootReadinessManagerService =
                    new RebootReadinessManagerService(getContext());
            publishBinderService(Context.REBOOT_READINESS_SERVICE, rebootReadinessManagerService);
        }
    }

    @Override
    public void markRebootPending(String callingPackage) {
        mContext.enforceCallingPermission(Manifest.permission.REBOOT,
                "Caller does not have REBOOT permission.");
        synchronized (mLock) {
            // If there are existing clients waiting for a broadcast, reboot readiness checks
            // are already ongoing.
            if (mCallingUidToPackageMap.size() == 0) {
                mCanceled = false;
                mHandler.removeCallbacksAndMessages(null);
                mHandler.post(this::pollRebootReadinessState);
            }
            ArraySet<String> packagesForUid =
                    mCallingUidToPackageMap.get(Binder.getCallingUid(), new ArraySet<>());
            packagesForUid.add(callingPackage);
            mCallingUidToPackageMap.put(Binder.getCallingUid(), packagesForUid);
        }
    }

    @Override
    public void cancelPendingReboot(String callingPackage) {
        mContext.enforceCallingPermission(Manifest.permission.REBOOT,
                "Caller does not have REBOOT permission");
        final int callingUid = Binder.getCallingUid();
        synchronized (mLock) {
            ArraySet<String> packagesForUid =
                    mCallingUidToPackageMap.get(callingUid, new ArraySet<>());
            if (packagesForUid.contains(callingPackage)) {
                packagesForUid.remove(callingPackage);
                if (packagesForUid.size() == 0) {
                    // No remaining clients exist for this calling uid
                    mCallingUidToPackageMap.remove(callingUid);
                }

                // Only cancel readiness checks if there are no more uids with packages
                // waiting for broadcasts
                if (mCallingUidToPackageMap.size() == 0) {
                    mHandler.removeCallbacksAndMessages(null);
                    mCanceled = true;
                    mReadyToReboot = false;
                }
            } else {
                Log.w(TAG, "Package " + callingPackage + " tried to cancel reboot readiness"
                        + " checks but was not a client of this service.");
            }
        }
    }

    @Override
    public boolean isReadyToReboot() {
        mContext.enforceCallingPermission(Manifest.permission.REBOOT,
                "Caller does not have REBOOT permission.");
        synchronized (mLock) {
            return mReadyToReboot;
        }
    }

    @Override
    public void registerRebootReadinessCallback(IRebootReadinessCallback callback) {
        mContext.enforceCallingPermission(Manifest.permission.SIGNAL_REBOOT_READINESS,
                "Caller does not have SIGNAL_REBOOT_READINESS permission.");
        mCallbacks.register(callback);
        try {
            callback.asBinder().linkToDeath(() -> unregisterRebootReadinessCallback(callback), 0);
        } catch (RemoteException e) {
            unregisterRebootReadinessCallback(callback);
        }
    }

    @Override
    public void unregisterRebootReadinessCallback(IRebootReadinessCallback callback) {
        mContext.enforceCallingPermission(Manifest.permission.SIGNAL_REBOOT_READINESS,
                "Caller does not have SIGNAL_REBOOT_READINESS permission.");
        mCallbacks.unregister(callback);
    }

    private void pollRebootReadinessState() {
        synchronized (mLock) {
            final boolean previousRebootReadiness = mReadyToReboot;
            final boolean currentRebootReadiness = getRebootReadiness();
            if (previousRebootReadiness != currentRebootReadiness) {
                noteRebootReadinessStateChanged(currentRebootReadiness);
            }
            // While ready to reboot, it is assumed that a reboot is imminent. It may be useful to
            // poll the device more frequency in this state, in case the device suddenly becomes
            // active and the caller needs to be notified.
            long nextCheckMillis = currentRebootReadiness ? POLLING_FREQUENCY_WHILE_IDLE_MS
                    : POLLING_FREQUENCY_WHILE_ACTIVE_MS;
            if (!mCanceled) {
                mHandler.postDelayed(this::pollRebootReadinessState, nextCheckMillis);
            }
        }
    }

    private boolean getRebootReadiness() {
        return checkSystemComponentsState();
    }

    private boolean checkSystemComponentsState() {
        final List<IRebootReadinessCallback> blockingCallbacks = new ArrayList<>();
        int i = mCallbacks.beginBroadcast();
        CountDownLatch latch = new CountDownLatch(i);
        while (i > 0) {
            i--;
            final IRebootReadinessCallback callback = mCallbacks.getBroadcastItem(i);
            try {
                RemoteCallback remoteCallback = new RemoteCallback(
                        result -> {
                            boolean isReadyToReboot = result.getBoolean(
                                    RebootReadinessManager.IS_REBOOT_READY_KEY);
                            if (!isReadyToReboot) {
                                blockingCallbacks.add(callback);
                            }
                            latch.countDown();
                        }
                );
                callback.onRebootPending(remoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not resolve state of RebootReadinessCallback: " + e);
                return false;
            }
        }
        try {
            latch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException ignore) {
        }
        mCallbacks.finishBroadcast();
        return blockingCallbacks.size() == 0;
    }

    private void noteRebootReadinessStateChanged(boolean isReadyToReboot) {
        synchronized (mLock) {
            mReadyToReboot = isReadyToReboot;
            Intent intent = new Intent(Intent.ACTION_REBOOT_READY);
            intent.putExtra(Intent.EXTRA_IS_READY_TO_REBOOT, isReadyToReboot);

            // Send state change broadcast to any packages which have a pending update
            for (int i = 0; i < mCallingUidToPackageMap.size(); i++) {
                ArraySet<String> packageNames = mCallingUidToPackageMap.valueAt(i);
                for (int j = 0; j < packageNames.size(); j++) {
                    intent.setPackage(packageNames.valueAt(j));
                    mContext.sendBroadcast(intent, Manifest.permission.REBOOT);
                }
            }
        }
    }
}
