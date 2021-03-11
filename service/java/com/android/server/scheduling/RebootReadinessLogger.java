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
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

/**
 * Class for handling the storage of logging information related to reboot readiness.
 */
final class RebootReadinessLogger {
    private static final String TAG = "RebootReadinessLogger";

    private static final String MODULE_NAME = "com.android.scheduling";

    private static final String REBOOT_STATS_FILE = "reboot-readiness/reboot-stats.xml";

    private static final Object sLock = new Object();

    private RebootReadinessLogger() {
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
    static void writeAfterRebootReadyBroadcast(@CurrentTimeMillisLong long startTime,
            @CurrentTimeMillisLong long readyTime, int timesBlockedByInteractivity,
            int timesBlockedBySubsystems, int timesBlockedByAppActivity) {
        synchronized (sLock) {
            File deDir = ApexEnvironment.getApexEnvironment(
                    MODULE_NAME).getDeviceProtectedDataDir();
            AtomicFile rebootStatsFile = new AtomicFile(new File(deDir, REBOOT_STATS_FILE));

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
}
