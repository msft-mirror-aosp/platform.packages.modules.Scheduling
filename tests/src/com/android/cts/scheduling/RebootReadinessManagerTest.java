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

package com.android.cts.scheduling;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.provider.DeviceConfig;
import android.scheduling.RebootReadinessManager;
import android.scheduling.RebootReadinessManager.RebootReadinessStatus;
import android.scheduling.RebootReadinessManager.RequestRebootReadinessStatusListener;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test system RebootReadinessManager APIs.
 */
@RunWith(JUnit4.class)
public class RebootReadinessManagerTest {

    private static class RebootCallback implements RequestRebootReadinessStatusListener {
        private final boolean mIsReadyToReboot;
        private final long mEstimatedFinishTime;
        private final String mSubsystemName;

        RebootCallback(boolean isReadyToReboot, long estimatedFinishTime, String subsystemName) {
            mIsReadyToReboot = isReadyToReboot;
            mEstimatedFinishTime = estimatedFinishTime;
            mSubsystemName = subsystemName;
        }

        @Override
        public RebootReadinessStatus onRequestRebootReadinessStatus() {
            return new RebootReadinessStatus(mIsReadyToReboot, mEstimatedFinishTime,
                    mSubsystemName);
        }
    }

    /** Utility to ensure that DeviceConfig property is updated */
    private static class ConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        private CountDownLatch mLatch;
        private String mPropertyName;
        private String mExpectedValue;

        ConfigListener(String propertyName, String expectedValue) {
            mPropertyName = propertyName;
            mLatch = new CountDownLatch(1);
            mExpectedValue = expectedValue;
        }

        public void awaitPropertyChange(int timeout, TimeUnit unit) throws InterruptedException {
            Log.i(TAG, "Waiting for property " + mPropertyName);
            if (!mLatch.await(timeout, unit)) {
                fail("Timed out waiting for properties to get updated");
            }
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            Log.d(TAG, "Properties changed: " + properties.getKeyset());
            if (mLatch != null && properties.getKeyset().contains(mPropertyName)) {
                mLatch.countDown();
            }
            if (!Objects.equals(properties.getString(mPropertyName, null), mExpectedValue)) {
                fail("Property was not set to the expected value: " + mPropertyName + " != "
                        + mExpectedValue);
            }
        }
    }

    private static final String TAG = "RebootReadinessManagerTest";
    private static final String TEST_CALLBACK_PREFIX = "TESTCOMPONENT";

    private static final RequestRebootReadinessStatusListener BLOCKING_CALLBACK =
            new RebootCallback(false, 0, TEST_CALLBACK_PREFIX + ": blocking component");
    private static final RequestRebootReadinessStatusListener READY_CALLBACK = new RebootCallback(
            true, 0, TEST_CALLBACK_PREFIX + ": non-blocking component");

    private static final String PROPERTY_ACTIVE_POLLING_INTERVAL_MS = "active_polling_interval_ms";
    private static final String PROPERTY_DISABLE_INTERACTIVITY_CHECK =
            "disable_interactivity_check";
    private static final String PROPERTY_INTERACTIVITY_THRESHOLD_MS = "interactivity_threshold_ms";
    private static final String PROPERTY_DISABLE_APP_ACTIVITY_CHECK = "disable_app_activity_check";
    private static final String PROPERTY_DISABLE_SUBSYSTEMS_CHECK = "disable_subsystems_check";
    private static final int POLLING_INTERVAL_MS_VALUE = 500;

    RebootReadinessManager mRebootReadinessManager =
            (RebootReadinessManager) InstrumentationRegistry.getContext().getSystemService(
                    Context.REBOOT_READINESS_SERVICE);

    private static final HandlerThread sThread = new HandlerThread("RebootReadinessManagerTest");
    private static HandlerExecutor sHandlerExecutor;
    private static Handler sHandler;

    @BeforeClass
    public static void setupClass() throws Exception {
        sThread.start();
        sHandlerExecutor = new HandlerExecutor(sThread.getThreadHandler());
        sHandler = new Handler(sThread.getLooper());
        adoptShellPermissions();
        setPropertyAndWait(PROPERTY_DISABLE_INTERACTIVITY_CHECK, "true");
        setPropertyAndWait(PROPERTY_DISABLE_APP_ACTIVITY_CHECK, "true");
        setPropertyAndWait(PROPERTY_DISABLE_SUBSYSTEMS_CHECK, "true");
        setPropertyAndWait(PROPERTY_ACTIVE_POLLING_INTERVAL_MS,
                Integer.toString(POLLING_INTERVAL_MS_VALUE));

    }

    @After
    public void tearDown() {
        mRebootReadinessManager.removeRequestRebootReadinessStatusListener(READY_CALLBACK);
        mRebootReadinessManager.removeRequestRebootReadinessStatusListener(BLOCKING_CALLBACK);
        mRebootReadinessManager.cancelPendingReboot();
    }

    @AfterClass
    public static void teardownClass() {
        sThread.quitSafely();
        dropShellPermissions();
    }

    @Test
    public void testRegisterAndUnregisterCallback() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.cancelPendingReboot();

        mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                sHandlerExecutor, BLOCKING_CALLBACK);
        assertThat(isReadyToReboot()).isFalse();
        mRebootReadinessManager.removeRequestRebootReadinessStatusListener(BLOCKING_CALLBACK);
        mRebootReadinessManager.cancelPendingReboot();
        assertThat(isReadyToReboot()).isTrue();
    }

    @Test
    public void testCallbackReadyToReboot() throws Exception {
        mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                sHandlerExecutor, READY_CALLBACK);
        CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean extra = intent.getBooleanExtra(
                        RebootReadinessManager.EXTRA_IS_READY_TO_REBOOT, false);
                assertThat(extra).isEqualTo(true);
                latch.countDown();
            }
        };
        InstrumentationRegistry.getContext().registerReceiver(receiver,
                new IntentFilter(RebootReadinessManager.ACTION_REBOOT_READY));
        mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                sHandlerExecutor, READY_CALLBACK);
        assertThat(isReadyToReboot()).isTrue();
        InstrumentationRegistry.getContext().unregisterReceiver(receiver);
    }

    @Test
    public void testCallbackNotReadyToReboot() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                sHandlerExecutor, READY_CALLBACK);
        mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                sHandlerExecutor, BLOCKING_CALLBACK);
        mRebootReadinessManager.cancelPendingReboot();
        assertThat(isReadyToReboot()).isFalse();
    }

    @Test
    public void testRebootPermissionCheck() {
        dropShellPermissions();
        try {
            mRebootReadinessManager.markRebootPending();
            fail("Expected to throw SecurityException");
        } catch (SecurityException expected) {
        } finally {
            adoptShellPermissions();
        }
    }

    @Test
    public void testSignalRebootReadinessPermissionCheck() {
        dropShellPermissions();
        try {
            mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                    sHandlerExecutor, READY_CALLBACK);
            fail("Expected to throw SecurityException");
        } catch (SecurityException expected) {
        } finally {
            adoptShellPermissions();
        }
    }


    @Test
    public void testCancelPendingReboot() throws Exception {
        mRebootReadinessManager.addRequestRebootReadinessStatusListener(
                sHandlerExecutor, BLOCKING_CALLBACK);
        mRebootReadinessManager.markRebootPending();
        mRebootReadinessManager.cancelPendingReboot();
        CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                fail("Reboot readiness checks should be cancelled so no broadcast should be sent.");
            }
        };
        InstrumentationRegistry.getContext().registerReceiver(receiver,
                new IntentFilter(RebootReadinessManager.ACTION_REBOOT_READY));
        mRebootReadinessManager.removeRequestRebootReadinessStatusListener(BLOCKING_CALLBACK);

        // Ensure that no broadcast is received when reboot readiness checks are canceled.
        latch.await(10, TimeUnit.SECONDS);
        assertThat(latch.getCount()).isEqualTo(1);
        InstrumentationRegistry.getContext().unregisterReceiver(receiver);
    }

    @Test
    public void testCancelPendingRebootWhenNotRegistered() {
        // Ensure that the process does not crash or throw an exception
        mRebootReadinessManager.cancelPendingReboot();
    }

    @Test
    public void testDisableInteractivityCheck() throws Exception {
        setPropertyAndWait(PROPERTY_DISABLE_INTERACTIVITY_CHECK, "false");

        assertThat(isReadyToReboot()).isFalse();

        setPropertyAndWait(PROPERTY_DISABLE_INTERACTIVITY_CHECK, "true");

        assertThat(isReadyToReboot()).isTrue();
    }

    @Test
    public void testRebootReadinessStatus() {
        RebootReadinessStatus status = new RebootReadinessStatus(false, 1000, "test");
        assertThat(status.isReadyToReboot()).isFalse();
        assertThat(status.getEstimatedFinishTime()).isEqualTo(1000);
        assertThat(status.getLogSubsystemName()).isEqualTo("test");
    }

    @Test
    public void testRebootReadinessStatusWithEmptyNameThrowsException() {
        try {
            RebootReadinessStatus status = new RebootReadinessStatus(false, 1000, "");
            fail("Expected to throw exception when an empty name is supplied.");
        } catch (IllegalArgumentException expected) {
        }
    }

    private boolean isReadyToReboot() throws Exception {
        mRebootReadinessManager.markRebootPending();
        waitForPolling();
        return mRebootReadinessManager.isReadyToReboot();
    }

    private static void adoptShellPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.REBOOT, Manifest.permission.WRITE_DEVICE_CONFIG,
                Manifest.permission.SIGNAL_REBOOT_READINESS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    private static void dropShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private static void setPropertyAndWait(String property, String value)
            throws InterruptedException {
        // Since the OnPropertiesChangedListener only detects a change in property, first check if
        // property is already the desired value.
        if (DeviceConfig.getString(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                property,  /* defaultValue= */ "").equals(value)) {
            return;
        }

        ConfigListener configListener = new ConfigListener(property, value);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                sHandlerExecutor, configListener);
        try {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS, property, value,
                    false);
            configListener.awaitPropertyChange(10, TimeUnit.SECONDS);
        } finally {
            DeviceConfig.removeOnPropertiesChangedListener(configListener);
        }
    }

    private static void waitForPolling() throws InterruptedException {
        // TODO(b/333555726): Attempt to fully synchronize execution of polling and
        //  latch::countDown by running them both on the same thread.
        // Currently, we synchronize latch:countdown with RebootReadinessStatusListeners.
        CountDownLatch latch = new CountDownLatch(1);
        // wait 500 ms longer than polling interval.
        sHandler.postDelayed(latch::countDown, POLLING_INTERVAL_MS_VALUE + 500);

        if (!latch.await(POLLING_INTERVAL_MS_VALUE + 2000, TimeUnit.MILLISECONDS)) {
            fail("Timed out waiting for main executor to finish");
        }
    }
}
