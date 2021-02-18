package android.scheduling;

import android.os.RemoteCallback;

/**
 * Callback interface for system subcomponents to declare whether they are performing
 * reboot-blocking work.
 *
 * {@hide}
 */
oneway interface IRebootReadinessCallback {
  void onRebootPending(in RemoteCallback reply);
}