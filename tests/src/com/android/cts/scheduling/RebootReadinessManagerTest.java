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
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.provider.DeviceConfig;
import android.scheduling.RebootReadinessManager;
import android.scheduling.RebootReadinessManager.RebootReadinessListener;
import android.scheduling.RebootReadinessManager.RebootReadinessStatus;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test system RebootReadinessManager APIs.
 */
@RunWith(JUnit4.class)
public class RebootReadinessManagerTest {

    private static class RebootCallback implements RebootReadinessListener {
        private final boolean mIsReadyToReboot;
        private final long mEstimatedFinishTime;
        private final String mSubsystemName;

        RebootCallback(boolean isReadyToReboot, long estimatedFinishTime, String subsystemName) {
            mIsReadyToReboot = isReadyToReboot;
            mEstimatedFinishTime = estimatedFinishTime;
            mSubsystemName = subsystemName;
        }

        @Override
        public RebootReadinessStatus onRebootPending() {
            return new RebootReadinessStatus(mIsReadyToReboot, mEstimatedFinishTime,
                    mSubsystemName);
        }
    }

    private static final RebootReadinessListener BLOCKING_CALLBACK = new RebootCallback(
            false, 0, "blocking component");
    private static final RebootReadinessListener READY_CALLBACK = new RebootCallback(
            true, 0, "non-blocking component");

    private static final String PROPERTY_IDLE_POLLING_INTERVAL_MS = "idle_polling_interval_ms";
    private static final String PROPERTY_ACTIVE_POLLING_INTERVAL_MS = "active_polling_interval_ms";
    private static final String PROPERTY_DISABLE_INTERACTIVITY_CHECK =
            "disable_interactivity_check";
    private static final String PROPERTY_INTERACTIVITY_THRESHOLD_MS = "interactivity_threshold_ms";
    private static final String PROPERTY_DISABLE_APP_ACTIVITY_CHECK = "disable_app_activity_check";

    RebootReadinessManager mRebootReadinessManager =
            (RebootReadinessManager) InstrumentationRegistry.getContext().getSystemService(
                    Context.REBOOT_READINESS_SERVICE);

    private static final HandlerThread sThread = new HandlerThread("RebootReadinessManagerTest");
    private static HandlerExecutor sHandlerExecutor;

    @BeforeClass
    public static void setupClass() {
        sThread.start();
        sHandlerExecutor = new HandlerExecutor(sThread.getThreadHandler());
    }

    @Before
    public void setup() {
        adoptShellPermissions();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_ACTIVE_POLLING_INTERVAL_MS, "1000", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_IDLE_POLLING_INTERVAL_MS, "1000", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "true", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_APP_ACTIVITY_CHECK, "true", false);
    }

    @After
    public void tearDown() {
        mRebootReadinessManager.removeRebootReadinessListener(READY_CALLBACK);
        mRebootReadinessManager.removeRebootReadinessListener(BLOCKING_CALLBACK);
        mRebootReadinessManager.cancelPendingReboot(InstrumentationRegistry.getContext());
        dropShellPermissions();
    }

    @AfterClass
    public static void teardownClass() {
        sThread.quitSafely();
    }

    @Test
    public void testRegisterAndUnregisterCallback() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, BLOCKING_CALLBACK);
        // Sleep for the time period between reboot readiness checks
        Thread.sleep(2000);
        assertThat(isReadyToReboot()).isFalse();
        mRebootReadinessManager.removeRebootReadinessListener(BLOCKING_CALLBACK);
        Thread.sleep(2000);
        assertThat(isReadyToReboot()).isTrue();
    }

    @Test
    public void testCallbackReadyToReboot() throws Exception {
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, READY_CALLBACK);
        CountDownLatch latch = new CountDownLatch(2);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            boolean mExpectedExtra = true;
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean extra = intent.getBooleanExtra(Intent.EXTRA_IS_READY_TO_REBOOT, false);
                assertThat(extra).isEqualTo(mExpectedExtra);
                mExpectedExtra = !mExpectedExtra;
                latch.countDown();
            }
        };
        InstrumentationRegistry.getContext().registerReceiver(receiver,
                new IntentFilter(Intent.ACTION_REBOOT_READY));
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, READY_CALLBACK);
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, BLOCKING_CALLBACK);
        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        InstrumentationRegistry.getContext().unregisterReceiver(receiver);
    }

    @Test
    public void testCallbackNotReadyToReboot() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, READY_CALLBACK);
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, BLOCKING_CALLBACK);
        // Sleep for the time period between reboot readiness checks
        Thread.sleep(2000);
        assertThat(isReadyToReboot()).isFalse();
    }

    @Test
    public void testRebootPermissionCheck() {
        dropShellPermissions();
        try {
            mRebootReadinessManager.markRebootPending(InstrumentationRegistry.getContext());
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
            mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, READY_CALLBACK);
            fail("Expected to throw SecurityException");
        } catch (SecurityException expected) {
        } finally {
            adoptShellPermissions();
        }
    }


    @Test
    public void testCancelPendingReboot() throws Exception {
        mRebootReadinessManager.addRebootReadinessListener(sHandlerExecutor, BLOCKING_CALLBACK);
        mRebootReadinessManager.markRebootPending(InstrumentationRegistry.getContext());
        mRebootReadinessManager.cancelPendingReboot(InstrumentationRegistry.getContext());
        CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                fail("Reboot readiness checks should be cancelled so no broadcast should be sent.");
            }
        };
        InstrumentationRegistry.getContext().registerReceiver(receiver,
                new IntentFilter(Intent.ACTION_REBOOT_READY));
        mRebootReadinessManager.removeRebootReadinessListener(BLOCKING_CALLBACK);

        // Ensure that no broadcast is received when reboot readiness checks are canceled.
        latch.await(10, TimeUnit.SECONDS);
        assertThat(latch.getCount()).isEqualTo(1);
        InstrumentationRegistry.getContext().unregisterReceiver(receiver);
    }

    @Test
    public void testCancelPendingRebootWhenNotRegistered() {
        // Ensure that the process does not crash or throw an exception
        mRebootReadinessManager.cancelPendingReboot(InstrumentationRegistry.getContext());
    }

    @Test
    public void testDisableInteractivityCheck() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_INTERACTIVITY_THRESHOLD_MS, Long.toString(Long.MAX_VALUE), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "false", false);
        // Allow a small amount of time for DeviceConfig changes to be noted.
        Thread.sleep(1000);
        assertThat(isReadyToReboot()).isFalse();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "true", false);
        // Allow a small amount of time for DeviceConfig changes to be noted.
        Thread.sleep(1000);
        assertThat(isReadyToReboot()).isTrue();
    }

    private boolean isReadyToReboot() throws Exception {
        mRebootReadinessManager.markRebootPending(InstrumentationRegistry.getContext());
        // Add a small timeout to allow the reboot readiness state to be noted.
        // TODO(b/161353402): Negate the need for this timeout.
        Thread.sleep(1000);
        return mRebootReadinessManager.isReadyToReboot();
    }

    private void adoptShellPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.REBOOT, Manifest.permission.WRITE_DEVICE_CONFIG,
                Manifest.permission.SIGNAL_REBOOT_READINESS);
    }

    private void dropShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }
}
