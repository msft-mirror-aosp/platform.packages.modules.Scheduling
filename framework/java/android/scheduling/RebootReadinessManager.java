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
package android.scheduling;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;


import java.util.concurrent.Executor;

/**
 * Gathers signals from the device to determine whether it is safe to reboot or not.
 *
 * <p>This service may be used by entities that are applying updates which require the device to be
 * rebooted, to determine when the device is in an unused state and is ready to be rebooted. When
 * an updater has notified this service that there is a pending update that requires a reboot, this
 * service will periodically check several signals which contribute to the reboot readiness
 * decision. When the device has been deemed to be ready to reboot, a broadcast will be sent to
 * the updater to inform it that the device is not being used and may be safely rebooted.
 *
 * <p>Subsystems may register callbacks with this service. These callbacks allow subsystems to
 * inform the reboot readiness decision in the case that they are performing important work
 * that should not be interrupted by a reboot. An example of reboot-blocking work is tethering
 * to another device.
 *
 * TODO(b/161353402): Add link to broadcast once added.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.REBOOT_READINESS_SERVICE)
public final class RebootReadinessManager {
    private static final String TAG = "RebootReadinessManager";

    private final IRebootReadinessManager mService;
    private final ArrayMap<RebootReadinessCallback, RebootReadinessCallbackProxy> mProxyList =
            new ArrayMap<>();

    /**
     * Key used to communicate between {@link RebootReadinessManager} and the system server,
     * indicating the reboot readiness of a component that has registered a
     * {@link RebootReadinessCallback}. The associated value is a boolean.
     *
     * @hide
     */
    public static final String IS_REBOOT_READY_KEY = "IS_REBOOT_READY";

    /**
     * Key used to communicate between {@link RebootReadinessManager} and the system server,
     * indicating the estimated finish time of the reboot-blocking work of a component that has
     * registered a {@link RebootReadinessCallback}. The associated value is a long.
     *
     * @hide
     */
    public static final String ESTIMATED_FINISH_TIME_KEY = "ESTIMATED_FINISH_TIME";

    /**
     * Key used to communicate between {@link RebootReadinessManager} and the system server,
     * indicating the identifier of a component that has registered a
     * {@link RebootReadinessCallback}. The associated value is a String.
     *
     * @hide
     */
    public static final String SUBSYSTEM_NAME_KEY = "SUBSYSTEM_NAME";

    /** {@hide} */
    public RebootReadinessManager(IRebootReadinessManager binder) {
        mService = binder;
    }

    /**
     * An interface implemented by a system component when registering with the
     * {@link RebootReadinessManager}. This callback may be called multiple times when
     * the device's reboot readiness state is being periodically polled.
     */
    public interface RebootReadinessCallback {

        /**
         * Passes a {@link RebootReadinessStatus} to the {@link RebootReadinessManager} to
         * indicate the reboot-readiness of a component.
         *
         * @return a {@link RebootReadinessStatus} indicating the state of the component
         */
        @NonNull RebootReadinessStatus onRebootPending();
    }


    /**
     * A response returned from a {@link RebootReadinessCallback}, indicating if the subsystem
     * is performing work that should block the reboot. If reboot-blocking work is being performed,
     * this response may indicate the estimated completion time of this work, if that value is
     * known.
     */
    public static final class RebootReadinessStatus {
        private final boolean mIsReadyToReboot;
        private final long mEstimatedFinishTime;
        private final String mLogSubsystemName;


        /**
         * Constructs a response which will be returned whenever a {@link RebootReadinessCallback}
         * is polled. The information in this response will be used as a signal to inform the
         * overall reboot readiness signal.
         *
         * If this subsystem is performing important work that should block the reboot, it may
         * be indicated in this response. Additionally, the subsystem may indicate the expected
         * finish time of this reboot-blocking work, if known. The callback will be polled again
         * when the estimated finish time is reached.
         *
         * A non-empty identifier which reflects the name of the entity that registered the
         * {@link RebootReadinessCallback} must be supplied. This identifier will be used for
         * logging purposes.
         *
         * @param isReadyToReboot whether or not this subsystem is ready to reboot.
         * @param estimatedFinishTime the time when this subsystem's reboot blocking work is
         *                            estimated to be finished, if known. This value should be zero
         *                            if the finish time is unknown. This value will be ignored
         *                            if the subsystem is ready to reboot.
         * @param logSubsystemName the name of the subsystem which registered the
         *                         {@link RebootReadinessCallback}.
         */
        public RebootReadinessStatus(boolean isReadyToReboot,
                @CurrentTimeMillisLong long estimatedFinishTime,
                @NonNull String logSubsystemName) {
            mIsReadyToReboot = isReadyToReboot;
            mEstimatedFinishTime = estimatedFinishTime;
            //TODO (b/161353402): Use Preconditions for this check.
            if (TextUtils.isEmpty(logSubsystemName)) {
                throw new IllegalArgumentException("Subsystem name should not be empty.");
            }
            mLogSubsystemName = logSubsystemName;
        }

        /**
         * Returns whether this subsystem is ready to reboot or not.
         *
         * @return {@code true} if this subsystem is ready to reboot, {@code false} otherwise.
         * @hide
         */
        public boolean isReadyToReboot() {
            return mIsReadyToReboot;
        }

        /**
         * Returns the time when the reboot-blocking work is estimated to finish. If this value is
         * greater than 0, the associated {@link RebootReadinessCallback} may not be called again
         * until this time, since this subsystem is assumed to be performing important work
         * until that time. This value is ignored if this subsystem is ready to reboot.
         *
         * @return the time when this subsystem's reboot-blocking work is estimated to finish.
         * @hide
         */
        public @CurrentTimeMillisLong long getEstimatedFinishTime() {
            return mEstimatedFinishTime;
        }

        /**
         * Returns an identifier of the subsystem that registered the callback, which will be used
         * for logging purposes. This identifier should reflect the name of the entity that
         * registered the callback, or the work it is performing. For example, this may be a
         * package name or a service name.
         *
         * @return an identifier of the subsystem that registered the callback.
         * @hide
         */
        public @NonNull String getLogSubsystemName() {
            return mLogSubsystemName;
        }
    }

    private static class RebootReadinessCallbackProxy extends IRebootReadinessCallback.Stub {
        private final RebootReadinessCallback mCallback;
        private final Executor mExecutor;

        RebootReadinessCallbackProxy(RebootReadinessCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onRebootPending(RemoteCallback callback) {
            mExecutor.execute(() -> {
                RebootReadinessStatus response = mCallback.onRebootPending();
                Bundle data = new Bundle();
                data.putBoolean(IS_REBOOT_READY_KEY, response.isReadyToReboot());
                data.putLong(ESTIMATED_FINISH_TIME_KEY, response.getEstimatedFinishTime());
                data.putString(SUBSYSTEM_NAME_KEY, response.getLogSubsystemName());
                callback.sendResult(data);
            });
        }
    }

    /**
     * Notifies the RebootReadinessManager that there is a pending update that requires a reboot to
     * be applied. When the device is in a reboot-ready state, a broadcast will be sent.
     *
     * TODO(b/161353402): Add API to allow caller to cancel reboot readiness checks.
     */
    @RequiresPermission(android.Manifest.permission.REBOOT)
    public void markRebootPending() {
        try {
            mService.markRebootPending();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determines whether the device is ready to be rebooted to apply an update.
     *
     * @return {@code true} if the device is ready to reboot, {@code false} otherwise
     */
    @RequiresPermission(Manifest.permission.REBOOT)
    public boolean isReadyToReboot() {
        try {
            return mService.isReadyToReboot();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link RebootReadinessCallback} with the RebootReadinessManager.
     *
     * @param executor the executor that the callback will be executed on
     * @param callback the callback to be registered
     */
    @RequiresPermission(Manifest.permission.SIGNAL_REBOOT_READINESS)
    public void registerRebootReadinessCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RebootReadinessCallback callback) {
        try {
            RebootReadinessCallbackProxy proxy =
                    new RebootReadinessCallbackProxy(callback, executor);
            mService.registerRebootReadinessCallback(proxy);
            mProxyList.put(callback, proxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link RebootReadinessCallback} with the RebootReadinessManager. The callback
     * will be executed on the main thread.
     *
     * @param callback the callback to be registered
     */
    @RequiresPermission(Manifest.permission.SIGNAL_REBOOT_READINESS)
    public void registerRebootReadinessCallback(@NonNull RebootReadinessCallback callback) {
        Executor executor = new Handler(Looper.getMainLooper())::post;
        registerRebootReadinessCallback(executor, callback);
    }

    /**
     * Unregisters a {@link RebootReadinessCallback} from the RebootReadinessManager.
     *
     * @param callback the callback to unregister
     */
    @RequiresPermission(Manifest.permission.SIGNAL_REBOOT_READINESS)
    public void unregisterRebootReadinessCallback(@NonNull RebootReadinessCallback callback) {
        try {
            RebootReadinessCallbackProxy proxy = mProxyList.get(callback);
            if (proxy != null) {
                mService.unregisterRebootReadinessCallback(proxy);
                mProxyList.remove(callback);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
