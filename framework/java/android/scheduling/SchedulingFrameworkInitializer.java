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

import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * Class holding initialization code for the Device Scheduling module.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class SchedulingFrameworkInitializer {
    private SchedulingFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all Device
     * Scheduling services to {@link Context}, so that {@link Context#getSystemService} can
     * return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     *     {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerStaticService(
                Context.REBOOT_READINESS_SERVICE, RebootReadinessManager.class,
                (service) -> new RebootReadinessManager(
                        IRebootReadinessManager.Stub.asInterface(service)));
    }
}
