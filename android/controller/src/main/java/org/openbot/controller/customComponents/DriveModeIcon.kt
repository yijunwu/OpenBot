/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: 2020-12-27, 10:57 p.m.
 */

package org.openbot.controller.customComponents

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import org.openbot.controller.DriveMode
import org.openbot.controller.R
import org.openbot.controller.utils.LocalEventBus

class DriveModeIcon @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : Button(context, attrs, defStyleAttr) {

    var driveMode: DriveMode? = null

    init {
        setOnTouchListener(OnTouchListener("{command: DRIVE_MODE}"))
        subscribe("DRIVE_MODE", ::onDataReceived)
    }

    private fun onDataReceived(data: String) {
        Log.i("DriveMode", "Got DRIVE_MODE status: ${data}...")
        when (DriveMode.valueOf(data)) {
            DriveMode.GAME -> gameMode()
            DriveMode.DUAL -> dualMode()
            DriveMode.JOYSTICK -> joystickMode()
        }
    }

    private fun gameMode() {
        driveMode = DriveMode.GAME
        setCompoundDrawablesWithIntrinsicBounds( R.drawable.ic_game, 0, 0, 0)
        val event: LocalEventBus.ProgressEvents = LocalEventBus.ProgressEvents.GameDriveMode
        LocalEventBus.onNext(event)
    }

    private fun dualMode() {
        driveMode = DriveMode.DUAL
        setCompoundDrawablesWithIntrinsicBounds( R.drawable.ic_dual, 0, 0, 0)
        val event: LocalEventBus.ProgressEvents = LocalEventBus.ProgressEvents.DualDriveMode
        LocalEventBus.onNext(event)
    }

    private fun joystickMode() {
        driveMode = DriveMode.JOYSTICK
        setCompoundDrawablesWithIntrinsicBounds( R.drawable.ic_joystick, 0, 0, 0)
        val event: LocalEventBus.ProgressEvents = LocalEventBus.ProgressEvents.JoystickDriveMode
        LocalEventBus.onNext(event)
    }

}
