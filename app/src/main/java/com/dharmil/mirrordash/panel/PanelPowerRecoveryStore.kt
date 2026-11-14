package com.dharmil.mirrordash.panel

import android.content.Context

/** Stores only whether a previous panel-off may need a manual or Shizuku restore. */
object PanelPowerRecoveryStore {
    private const val PREFERENCES_NAME = "panel_power_recovery"
    private const val KEY_RESTORE_PENDING = "restore_pending"

    fun isRestorePending(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_PENDING, false)

    fun setRestorePending(context: Context, pending: Boolean): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTORE_PENDING, pending)
            .commit()
}
