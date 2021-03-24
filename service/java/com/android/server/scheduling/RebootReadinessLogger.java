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

import android.annotation.CurrentTimeMillisLong;
import android.content.ApexEnvironment;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;


/**
 * Class for handling the storage of logging information related to reboot readiness.
 */
final class RebootReadinessLogger {
    private static final String TAG = "RebootReadinessLogger";

    private static final String MODULE_NAME = "com.android.scheduling";

    private static final String REBOOT_STATS_FILE = "reboot-readiness/reboot-stats.xml";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private long mStartTime;

    @GuardedBy("mLock")
    private long mReadyTime;

    @GuardedBy("mLock")
    private int mTimesBlockedByInteractivity;

    @GuardedBy("mLock")
    private int mTimesBlockedBySubsystems;

    @GuardedBy("mLock")
    private int mTimesBlockedByAppActivity;

    @GuardedBy("mLock")
    private boolean mNeedsToLogMetrics;

    @GuardedBy("mLock")
    private boolean mShouldDumpMetrics;

    RebootReadinessLogger() {
    }

    /**
     * Persists important information about recent reboot readiness checks. This information is
     * stored when the device first becomes reboot-ready.
     *
     * @param startTime the time the reboot readiness state started to be polled
     * @param readyTime the time the device became reboot-ready
     * @param timesBlockedByInteractivity the number of times the reboot was blocked by device
     *                                    interactivity
     * @param timesBlockedBySubsystems the number of times the reboot was blocked by subsystems
     *                                 that registered callbacks
     * @param timesBlockedByAppActivity the number of times the reboot was blocked by background
     *                                  app activity
     */
    void writeAfterRebootReadyBroadcast(@CurrentTimeMillisLong long startTime,
            @CurrentTimeMillisLong long readyTime, int timesBlockedByInteractivity,
            int timesBlockedBySubsystems, int timesBlockedByAppActivity) {
        synchronized (mLock) {
            File deDir = ApexEnvironment.getApexEnvironment(
                    MODULE_NAME).getDeviceProtectedDataDir();
            AtomicFile rebootStatsFile = new AtomicFile(new File(deDir, REBOOT_STATS_FILE));

            mStartTime = startTime;
            mReadyTime = readyTime;
            mTimesBlockedByInteractivity = timesBlockedByInteractivity;
            mTimesBlockedBySubsystems = timesBlockedBySubsystems;
            mTimesBlockedByAppActivity = timesBlockedByAppActivity;
            mShouldDumpMetrics = true;

            RebootStats rebootStats = new RebootStats();
            rebootStats.setStartTimeMs(startTime);
            rebootStats.setReadyTimeMs(readyTime);
            rebootStats.setTimesBlockedByInteractivity(timesBlockedByInteractivity);
            rebootStats.setTimesBlockedBySubsystems(timesBlockedBySubsystems);
            rebootStats.setTimesBlockedByAppActivity(timesBlockedByAppActivity);
            try (
                FileOutputStream stream = rebootStatsFile.startWrite();
            ) {
                XmlWriter writer = new XmlWriter(new PrintWriter(stream));
                XmlWriter.write(writer, rebootStats);
                writer.close();
                rebootStatsFile.finishWrite(stream);
            } catch (Exception e) {
                Log.e(TAG, "Could not write reboot readiness stats: " + e);
            }
        }
    }

    /**
     * If any metrics were stored before the last reboot, reads them into local variables. These
     * local variables will be logged when the device is first unlocked after reboot.
     */
    void readMetricsPostReboot() {
        synchronized (mLock) {
            AtomicFile rebootStatsFile = getRebootStatsFile();
            if (rebootStatsFile != null) {
                try (FileInputStream stream = rebootStatsFile.openRead()) {
                    RebootStats rebootStats = XmlParser.read(stream);
                    mReadyTime = rebootStats.getReadyTimeMs();
                    mStartTime = rebootStats.getStartTimeMs();
                    mTimesBlockedByInteractivity = rebootStats.getTimesBlockedByInteractivity();
                    mTimesBlockedBySubsystems = rebootStats.getTimesBlockedBySubsystems();
                    mTimesBlockedByAppActivity = rebootStats.getTimesBlockedByAppActivity();
                    mNeedsToLogMetrics = true;
                } catch (Exception e) {
                    Log.e(TAG, "Could not read reboot readiness stats: " + e);
                } finally {
                    rebootStatsFile.delete();
                }
            }
        }
    }

    /**
     * Logs metrics which have been stored across reboots, if any exist. This method will be called
     * after the first time the device is unlocked after reboot.
     */
    void writePostRebootMetrics() {
        synchronized (mLock) {
            if (mNeedsToLogMetrics) {
                mNeedsToLogMetrics = false;
                long timeToUnlockMs = SystemClock.elapsedRealtime();
                long timeToRebootReadyMs = mReadyTime - mStartTime;
                Log.i(TAG, "UnattendedRebootOccurred"
                        + " rebootReadyMs=" + timeToRebootReadyMs
                        + " timeUntilFirstUnlockMs=" + timeToUnlockMs
                        + " blockedByInteractivity=" + mTimesBlockedByInteractivity
                        + " blockedBySubsystems=" + mTimesBlockedBySubsystems
                        + " blockedByAppActivity=" + mTimesBlockedByAppActivity);
                SchedulingStatsLog.write(SchedulingStatsLog.UNATTENDED_REBOOT_OCCURRED,
                        timeToRebootReadyMs,
                        timeToUnlockMs,
                        mTimesBlockedByAppActivity,
                        mTimesBlockedBySubsystems,
                        mTimesBlockedByInteractivity);
                mShouldDumpMetrics = true;
            }
        }
    }

    private static AtomicFile getRebootStatsFile() {
        File deDir = ApexEnvironment.getApexEnvironment(MODULE_NAME).getDeviceProtectedDataDir();
        File file = new File(deDir, REBOOT_STATS_FILE);
        if (file.exists()) {
            return new AtomicFile(new File(deDir, REBOOT_STATS_FILE));
        } else {
            return null;
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            if (mShouldDumpMetrics) {
                pw.println("Previous reboot readiness checks:");
                pw.println("    Start timestamp: " + mStartTime);
                pw.println("    Ready timestamp: " +  mReadyTime);
                pw.println("    Times blocked by interactivity " + mTimesBlockedByInteractivity);
                pw.println("    Times blocked by subsystems " + mTimesBlockedBySubsystems);
                pw.println("    Times blocked by app activity " + mTimesBlockedByAppActivity);
            }
        }
    }
}
