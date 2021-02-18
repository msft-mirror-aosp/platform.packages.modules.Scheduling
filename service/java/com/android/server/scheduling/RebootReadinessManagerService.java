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
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.scheduling.IRebootReadinessCallback;
import android.scheduling.IRebootReadinessManager;
import android.scheduling.RebootReadinessManager;
import android.util.Log;

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

    @GuardedBy("mLock")
    private boolean mReadyToReboot = false;

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
    public void markRebootPending() {
        mContext.enforceCallingPermission(Manifest.permission.REBOOT,
                "Caller does not have REBOOT permission.");
        mHandler.post(this::pollRebootReadinessState);
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
        boolean readyToReboot = getRebootReadiness();
        synchronized (mLock) {
            mReadyToReboot = readyToReboot;
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
}
