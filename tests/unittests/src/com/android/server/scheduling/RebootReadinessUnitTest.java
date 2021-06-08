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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyLong;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.TetheringManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallback;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.scheduling.IRequestRebootReadinessStatusListener;
import android.scheduling.RebootReadinessManager;

import androidx.test.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * Tests for {@link com.android.server.scheduling.RebootReadinessManagerService}.
 */
public class RebootReadinessUnitTest {

    private Context mMockContext;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ActivityManager mActivityManager;

    private AlarmManager mAlarmManager;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PowerManager mPowerManager;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TetheringManager mTetheringManager;

    @Captor
    private ArgumentCaptor<Intent> mIntentArgumentCaptor;

    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverArgumentCaptor;

    @Captor
    private ArgumentCaptor<IntentFilter> mIntentFilters;

    @Captor
    private ArgumentCaptor<Integer> mIntCaptor;


    private MockitoSession mSession;

    private RebootReadinessManagerService mService;
    private RebootReadinessLogger mLogger;

    private static final String TEST_PACKAGE = "test.package";
    private static final String TEST_PACKAGE2 = "test.package2";

    private static final String PROPERTY_ACTIVE_POLLING_INTERVAL_MS = "active_polling_interval_ms";
    private static final String PROPERTY_DISABLE_INTERACTIVITY_CHECK =
            "disable_interactivity_check";
    private static final String PROPERTY_INTERACTIVITY_THRESHOLD_MS = "interactivity_threshold_ms";
    private static final String PROPERTY_DISABLE_APP_ACTIVITY_CHECK = "disable_app_activity_check";
    private static final String PROPERTY_DISABLE_SUBSYSTEMS_CHECK = "disable_subsystems_check";
    private static final String PROPERTY_ALARM_CLOCK_THRESHOLD_MS = "alarm_clock_threshold_ms";
    private static final String PROPERTY_LOGGING_BLOCKING_ENTITY_THRESHOLD_MS =
            "logging_blocking_entity_threshold_ms";

    private static final String COMPONENT_NAME = "component";

    // Small delay to allow DeviceConfig updates to propagate.
    private static final int DEVICE_CONFIG_DELAY = 1000;

    // Small delay to allow reboot readiness state to change.
    private static final int STATE_CHANGE_DELAY = 5000;

    @Before
    public void setup() {
        mSession =
                ExtendedMockito.mockitoSession().initMocks(
                        this)
                        .strictness(Strictness.LENIENT)
                        .spyStatic(SchedulingStatsLog.class)
                        .startMocking();
        mMockContext = spy(InstrumentationRegistry.getContext());
        mAlarmManager = spy(mMockContext.getSystemService(AlarmManager.class));
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), anyString());
        doNothing().when(mMockContext).sendBroadcastAsUser(any(Intent.class), any(UserHandle.class),
                anyString());
        doReturn(PackageManager.PERMISSION_GRANTED).when(mMockContext)
                .checkCallingOrSelfPermission(anyString());

        doReturn(mActivityManager).when(mMockContext).getSystemService(eq(ActivityManager.class));
        doReturn(mAlarmManager).when(mMockContext).getSystemService(eq(AlarmManager.class));
        doCallRealMethod().when(mAlarmManager).setExact(anyInt(), anyLong(), anyString(), any(
                AlarmManager.OnAlarmListener.class), any(Handler.class));
        doReturn(mPowerManager).when(mMockContext).getSystemService(eq(PowerManager.class));
        when(mPowerManager.isInteractive()).thenReturn(true);
        doReturn(mTetheringManager).when(mMockContext).getSystemService(eq(TetheringManager.class));
        when(mAlarmManager.getNextAlarmClock()).thenReturn(null);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.WRITE_DEVICE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.SCHEDULE_EXACT_ALARM,
                        Manifest.permission.ACCESS_NETWORK_STATE);
        initializeDeviceConfig();
        File testDir = new File(mMockContext.getFilesDir(), "reboot-readiness");
        mLogger = spy(new RebootReadinessLogger(testDir));
        mService = new RebootReadinessManagerService(mMockContext, mLogger);
    }

    private void initializeDeviceConfig()  {
        // Checks may only be scheduled every 5000ms due to AlarmManager scheduling restrictions.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_ACTIVE_POLLING_INTERVAL_MS, "5000", false);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "true", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_APP_ACTIVITY_CHECK, "true", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_SUBSYSTEMS_CHECK, "true", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_ALARM_CLOCK_THRESHOLD_MS, "500000", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_INTERACTIVITY_THRESHOLD_MS, "1000", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_LOGGING_BLOCKING_ENTITY_THRESHOLD_MS, "1000", false);
    }

    @After
    public void teardown() {
        mService.cancelPendingReboot(TEST_PACKAGE);
        mService.cancelPendingReboot(TEST_PACKAGE2);
        mSession.finishMocking();
    }

    /**
     * Test that calling packages are tracked correctly when reboot readiness checks are started
     * or stopped.
     */
    @Test
    public void testCallingPackages() {
        mService.markRebootPending(TEST_PACKAGE);
        assertThat(mService.getCallingPackages().valueAt(0)).contains(TEST_PACKAGE);
        mService.cancelPendingReboot(TEST_PACKAGE);
        assertThat(mService.getCallingPackages().size()).isEqualTo(0);
    }

    /**
     * Test that a registered listener is called as part of the reboot readiness checks.
     */
    @Test
    public void testRegisteredListenerIsCalled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        mService.addRequestRebootReadinessStatusListener(getStatusListener(latch));
        mService.markRebootPending(TEST_PACKAGE);
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    /** Test that the correct intents are sent when the reboot readiness state changes. */
    @Test
    public void testCorrectIntentsSent() throws Exception {
        mService.markRebootPending(TEST_PACKAGE);
        Thread.sleep(STATE_CHANGE_DELAY);
        assertThat(mService.isReadyToReboot()).isTrue();

        // Make device interactive, and therefore not ready to reboot.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "false", false);
        Thread.sleep(DEVICE_CONFIG_DELAY);
        setScreenState(true);
        Thread.sleep(STATE_CHANGE_DELAY);
        assertThat(mService.isReadyToReboot()).isFalse();

        // 2 broadcasts should have been sent: one when the device became ready to reboot, and
        // another when it became no longer ready to reboot.
        verify(mMockContext, times(2)).sendBroadcastAsUser(mIntentArgumentCaptor.capture(),
                any(UserHandle.class), anyString());
        List<Intent> intents = mIntentArgumentCaptor.getAllValues();

        Intent readyIntent = intents.get(0);
        Intent notReadyIntent = intents.get(1);
        boolean sentExtra;

        assertThat(readyIntent.getAction()).isEqualTo(RebootReadinessManager.ACTION_REBOOT_READY);
        assertThat(readyIntent.getPackage()).isEqualTo(TEST_PACKAGE);
        sentExtra = readyIntent.getBooleanExtra(
                RebootReadinessManager.EXTRA_IS_READY_TO_REBOOT, false);
        assertThat(sentExtra).isTrue();

        assertThat(notReadyIntent.getAction()).isEqualTo(
                RebootReadinessManager.ACTION_REBOOT_READY);
        assertThat(notReadyIntent.getPackage()).isEqualTo(TEST_PACKAGE);
        sentExtra = notReadyIntent.getBooleanExtra(
                RebootReadinessManager.EXTRA_IS_READY_TO_REBOOT, false);
        assertThat(sentExtra).isFalse();
    }

    /** Test that a second client will receive an immediate broadcast when they register. */
    @Test
    public void testSecondClientReceivesBroadcastImmediately() throws Exception {
        mService.markRebootPending(TEST_PACKAGE);
        Thread.sleep(STATE_CHANGE_DELAY);
        assertThat(mService.isReadyToReboot()).isTrue();

        mService.markRebootPending(TEST_PACKAGE2);
        verify(mMockContext, times(2)).sendBroadcastAsUser(mIntentArgumentCaptor.capture(),
                any(UserHandle.class), anyString());
        List<Intent> intents = mIntentArgumentCaptor.getAllValues();

        Intent secondIntent = intents.get(1);

        assertThat(secondIntent.getPackage()).isEqualTo(TEST_PACKAGE2);
        assertThat(secondIntent.getAction()).isEqualTo(RebootReadinessManager.ACTION_REBOOT_READY);
        boolean sentExtra = secondIntent.getBooleanExtra(
                RebootReadinessManager.EXTRA_IS_READY_TO_REBOOT, false);
        assertThat(sentExtra).isTrue();
    }

    @Test
    public void testWritingAfterRebootReadyBroadcast() throws Exception {
        mService.markRebootPending(TEST_PACKAGE);
        Thread.sleep(STATE_CHANGE_DELAY);
        assertThat(mService.isReadyToReboot()).isTrue();
        verify(mLogger, times(1)).writeAfterRebootReadyBroadcast(
                anyLong(), anyLong(), eq(0), eq(0), eq(0));

        // Make device interactive and allow the device to become not reboot-ready for a while. Then
        // check that the fact that interactivity has blocked the reboot is noted.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "false", false);
        Thread.sleep(DEVICE_CONFIG_DELAY);
        setScreenState(true);
        Thread.sleep(STATE_CHANGE_DELAY);

        setScreenState(false);
        Thread.sleep(STATE_CHANGE_DELAY);
        verify(mLogger, times(2)).writeAfterRebootReadyBroadcast(
                anyLong(), anyLong(), mIntCaptor.capture(), eq(0), eq(0));
        assertThat(mIntCaptor.getAllValues().get(1)).isAtLeast(1);


    }

    /**
     * Test that the device interactivity checks return the correct result based on the screen
     * state.
     */
    @Test
    public void testInteractivityChecks() throws Exception {
        assertThat(mService.checkDeviceInteractivity()).isFalse();

        setScreenState(false);
        // Screen needs to be off for 1000ms to be ready to reboot
        Thread.sleep(1000);
        assertThat(mService.checkDeviceInteractivity()).isTrue();

        setScreenState(true);
        assertThat(mService.checkDeviceInteractivity()).isFalse();
    }

    /** Test that foreground services block the reboot. */
    @Test
    public void testAppActivityChecks() {
        List<ActivityManager.RunningServiceInfo> runningServicesList = new ArrayList<>();
        when(mActivityManager.getRunningServices(anyInt())).thenReturn(runningServicesList);
        assertThat(mService.checkBackgroundAppActivity()).isTrue();

        // Ensure that non-foreground services do not block the reboot
        ActivityManager.RunningServiceInfo nonForeground = new ActivityManager.RunningServiceInfo();
        nonForeground.foreground = false;
        runningServicesList.add(nonForeground);
        assertThat(mService.checkBackgroundAppActivity()).isTrue();

        // Ensure that foreground services do block the reboot
        ActivityManager.RunningServiceInfo foreground = new ActivityManager.RunningServiceInfo();
        foreground.foreground = true;
        runningServicesList.add(foreground);
        assertThat(mService.checkBackgroundAppActivity()).isFalse();
    }

    /**
     * Test that pending alarm clocks block the reboot if they are within a given threshold from
     * the current time.
     */
    @Test
    public void testAlarmClockBlocksReboot() throws Exception {
        long threshold = TimeUnit.MINUTES.toMillis(15);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_SUBSYSTEMS_CHECK, "false", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_ALARM_CLOCK_THRESHOLD_MS, Long.toString(threshold), false);
        Thread.sleep(DEVICE_CONFIG_DELAY);
        assertThat(mService.checkSystemComponentsState()).isTrue();

        // Alarm is within the threshold, therefore the reboot should be blocked.
        AlarmManager.AlarmClockInfo alarm = new AlarmManager.AlarmClockInfo(
                System.currentTimeMillis() + threshold / 2, null);
        when(mAlarmManager.getNextAlarmClock()).thenReturn(alarm);
        assertThat(mService.checkSystemComponentsState()).isFalse();

        // Alarm is outside the threshold, therefore the reboot should not be blocked.
        alarm = new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + threshold * 2, null);
        when(mAlarmManager.getNextAlarmClock()).thenReturn(alarm);
        assertThat(mService.checkSystemComponentsState()).isTrue();

    }

    /**
     * Test that metrics are logged for long-blocking subsystems.
     */
    @Test
    public void testLongSubsystemBlockingReported() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_SUBSYSTEMS_CHECK, "false", false);
        Thread.sleep(DEVICE_CONFIG_DELAY);
        mService.addRequestRebootReadinessStatusListener(getStatusListener(latch));
        mService.markRebootPending(TEST_PACKAGE);

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        verify(() -> SchedulingStatsLog.write(eq(SchedulingStatsLog.LONG_REBOOT_BLOCKING_REPORTED),
                eq(SchedulingStatsLog
                        .LONG_REBOOT_BLOCKING_REPORTED__REBOOT_BLOCK_REASON__SYSTEM_COMPONENT),
                eq(COMPONENT_NAME), anyInt()));
    }

    /**
     * Test that metrics are logged for long-blocking apps.
     */
    @Test
    public void testLongAppBlockingReported() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_APP_ACTIVITY_CHECK, "false", false);
        Thread.sleep(DEVICE_CONFIG_DELAY);
        List<ActivityManager.RunningServiceInfo> runningServicesList = new ArrayList<>();
        ActivityManager.RunningServiceInfo service = new ActivityManager.RunningServiceInfo();
        service.foreground = true;
        service.uid = 1000;
        runningServicesList.add(service);
        when(mActivityManager.getRunningServices(anyInt())).thenReturn(runningServicesList);
        mService.markRebootPending(TEST_PACKAGE);

        // Allow the app to block the reboot for a small amount of time.
        Thread.sleep(6000);
        verify(() -> SchedulingStatsLog.write(eq(SchedulingStatsLog.LONG_REBOOT_BLOCKING_REPORTED),
                eq(SchedulingStatsLog.LONG_REBOOT_BLOCKING_REPORTED__REBOOT_BLOCK_REASON__APP_UID),
                anyString(), eq(1000)), atLeastOnce());
    }

    /**
     * Test that logging information is correctly written and read before and after the reboot.
     */
    @Test
    public void testReadingAndWritingUnattendedReboot() throws Exception {
        mLogger.writeAfterRebootReadyBroadcast(1000, 2000, 0, 0, 0);
        mLogger.readMetricsPostReboot();
        mLogger.writePostRebootMetrics();
        verify(() -> SchedulingStatsLog.write(eq(SchedulingStatsLog.UNATTENDED_REBOOT_OCCURRED),
                eq(1000L), anyLong(), eq(0), eq(0), eq(0)));
    }

    /**
     * Test that no metrics are logged if the device became not ready to reboot before rebooting.
     */
    @Test
    public void testMetricsClearedWhenNotReadyToReboot() throws Exception {
        mService.markRebootPending(TEST_PACKAGE);
        Thread.sleep(STATE_CHANGE_DELAY);
        assertThat(mService.isReadyToReboot()).isTrue();

        // Make device interactive and allow the device to become not reboot-ready for a while.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_REBOOT_READINESS,
                PROPERTY_DISABLE_INTERACTIVITY_CHECK, "false", false);
        Thread.sleep(DEVICE_CONFIG_DELAY);
        setScreenState(true);
        Thread.sleep(STATE_CHANGE_DELAY);
        assertThat(mService.isReadyToReboot()).isFalse();

        // The device has become not ready to reboot, therefore no metrics should be logged.
        mLogger.readMetricsPostReboot();
        mLogger.writePostRebootMetrics();
        verify(() -> SchedulingStatsLog.write(eq(SchedulingStatsLog.UNATTENDED_REBOOT_OCCURRED),
                anyLong(), anyLong(), anyInt(), anyInt(), anyInt()), never());
    }

    /**
     * Sets the device interactivity on or off, and delays to allow the change to be processed.
     */
    private void setScreenState(boolean screenOn) throws Exception {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        verify(mMockContext, atLeastOnce()).registerReceiver(
                mBroadcastReceiverArgumentCaptor.capture(), mIntentFilters.capture());

        // Get the broadcast receiver that receives interactivity broadcasts.
        int index = -1;
        List<IntentFilter> capturedFilters = mIntentFilters.getAllValues();
        for (int i = 0; i < capturedFilters.size(); i++) {
            if (capturedFilters.get(i).hasAction(Intent.ACTION_SCREEN_ON)) {
                index = i;
            }
        }
        assertThat(index).isNotEqualTo(-1);
        BroadcastReceiver receiver = mBroadcastReceiverArgumentCaptor.getAllValues().get(index);

        // Send updated interactivity state to receiver.
        if (screenOn) {
            receiver.onReceive(mMockContext, new Intent(Intent.ACTION_SCREEN_ON));
        } else {
            receiver.onReceive(mMockContext, new Intent(Intent.ACTION_SCREEN_OFF));
        }

        // ALlow the state change to be processed.
        Thread.sleep(1000);
    }

    /**
     * Returns a status listener which will decrement a passed CountdownLatch when it is called.
     */
    private IRequestRebootReadinessStatusListener getStatusListener(CountDownLatch latch) {
        return new IRequestRebootReadinessStatusListener() {
            @Override
            public void onRequestRebootReadinessStatus(RemoteCallback remoteCallback) {
                latch.countDown();
                Bundle bundle = new Bundle();
                bundle.putBoolean(RebootReadinessManager.IS_REBOOT_READY_KEY, false);
                bundle.putLong(RebootReadinessManager.ESTIMATED_FINISH_TIME_KEY, 1000L);
                bundle.putString(RebootReadinessManager.SUBSYSTEM_NAME_KEY, COMPONENT_NAME);
                remoteCallback.sendResult(bundle);
            }

            @Override
            public IBinder asBinder() {
                return new Binder();
            }
        };
    }
}
