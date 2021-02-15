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
import android.content.Context;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.scheduling.RebootReadinessManager;
import android.scheduling.RebootReadinessManager.RebootReadinessCallback;
import android.scheduling.RebootReadinessManager.RebootReadinessStatus;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test system RebootReadinessManager APIs.
 */
@RunWith(JUnit4.class)
public class RebootReadinessManagerTest {

    private static class RebootCallback implements RebootReadinessCallback {
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

    private static final RebootReadinessCallback BLOCKING_CALLBACK = new RebootCallback(
            false, 0, "blocking component");
    private static final RebootReadinessCallback READY_CALLBACK = new RebootCallback(
            true, 0, "non-blocking component");

    RebootReadinessManager mRebootReadinessManager =
            (RebootReadinessManager) InstrumentationRegistry.getContext().getSystemService(
                    Context.REBOOT_READINESS_SERVICE);


    @Before
    public void setup() {
        adoptShellPermissions();
    }

    @After
    public void tearDown() {
        mRebootReadinessManager.unregisterRebootReadinessCallback(READY_CALLBACK);
        mRebootReadinessManager.unregisterRebootReadinessCallback(BLOCKING_CALLBACK);
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testRegisterAndUnregisterCallback() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.registerRebootReadinessCallback(BLOCKING_CALLBACK);
        assertThat(isReadyToReboot()).isFalse();
        mRebootReadinessManager.unregisterRebootReadinessCallback(BLOCKING_CALLBACK);
        assertThat(isReadyToReboot()).isTrue();
    }

    @Test
    public void testCallbackReadyToReboot() throws Exception {
        mRebootReadinessManager.registerRebootReadinessCallback(READY_CALLBACK);
        assertThat(isReadyToReboot()).isTrue();
    }

    @Test
    public void testCallbackNotReadyToReboot() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        mRebootReadinessManager.registerRebootReadinessCallback(READY_CALLBACK);
        mRebootReadinessManager.registerRebootReadinessCallback(BLOCKING_CALLBACK);
        assertThat(isReadyToReboot()).isFalse();
    }

    @Test
    public void testRegisterCallbackWithExecutor() throws Exception {
        assertThat(isReadyToReboot()).isTrue();
        HandlerThread thread = new HandlerThread("testRegisterCallback");
        thread.start();
        mRebootReadinessManager.registerRebootReadinessCallback(
                new HandlerExecutor(thread.getThreadHandler()), BLOCKING_CALLBACK);
        assertThat(isReadyToReboot()).isFalse();
        mRebootReadinessManager.unregisterRebootReadinessCallback(BLOCKING_CALLBACK);
        assertThat(isReadyToReboot()).isTrue();
    }

    @Test
    public void testRebootPermissionCheck() {
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        try {
            mRebootReadinessManager.markRebootPending();
            fail("Expected to throw SecurityException");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testSignalRebootReadinessPermissionCheck() {
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        try {
            mRebootReadinessManager.registerRebootReadinessCallback(READY_CALLBACK);
            fail("Expected to throw SecurityException");
        } catch (SecurityException expected) {
        }
    }

    private boolean isReadyToReboot() throws Exception {
        mRebootReadinessManager.markRebootPending();
        // Add a small timeout to allow the reboot readiness state to be noted.
        // TODO(b/161353402): Negate the need for this timeout.
        Thread.sleep(1000);
        return mRebootReadinessManager.isReadyToReboot();
    }

    private void adoptShellPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.REBOOT, Manifest.permission.SIGNAL_REBOOT_READINESS);
    }
}
