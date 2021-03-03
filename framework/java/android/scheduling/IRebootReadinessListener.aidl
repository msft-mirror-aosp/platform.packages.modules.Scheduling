package android.scheduling;

import android.os.RemoteCallback;

/**
 * Listener interface for system subcomponents to declare whether they are performing
 * reboot-blocking work.
 *
 * {@hide}
 */
oneway interface IRebootReadinessListener {
  void onRebootPending(in RemoteCallback reply);
}