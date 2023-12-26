/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: 2020-12-27, 10:59 p.m.
 */

package org.openbot.controller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.openbot.controller.customComponents.DualDriveSeekBar
import org.openbot.controller.customComponents.VideoViewWebRTC
import org.openbot.controller.databinding.ActivityFullscreenBinding
import org.openbot.controller.utils.LocalEventBus
import org.openbot.controller.utils.Utils
import kotlin.system.exitProcess

class ControllerActivity : /*AppCompat*/
    Activity() { // for some reason AppCompatActivity gives errors in the IDE, but it does compile,
    private val PERMISSION_REQUEST_LOCATION = 101
    private val TAG = "ControllerActivity"
    private lateinit var binding: ActivityFullscreenBinding
    private lateinit var screenSelector: ScreenSelector
    private var driveMode: DriveMode? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissions()

        screenSelector = ScreenSelector(binding)
        ConnectionSelector.init(this)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        createAppEventsSubscription()
        subscribeToStatusInfo()

        binding.leftDriveControl.setDirection(DualDriveSeekBar.LeftOrRight.LEFT)
        binding.rightDriveControl.setDirection(DualDriveSeekBar.LeftOrRight.RIGHT)

        screenSelector.hideControls()

        hideSystemUI()

        BotDataListener.init()

        subscribe("VIDEO_PROTOCOL", ::onDataReceived)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        sendJoystickInput(event)
        return false
    }

    private fun sendJoystickInput(event: MotionEvent) {
        val (left, right) = GameController.processJoystickInput(driveMode ?: return, event, -1)
        DriveCommandReducer.filter(right, left)
    }

    @SuppressLint("CheckResult")
    private fun subscribe(subject: String, onDataReceived: (String) -> Unit) {
        StatusEventBus.addSubject(subject)
        StatusEventBus.subscribe(this.javaClass.simpleName, subject, onNext = {
            onDataReceived(it as String)
        })
    }

    private fun onDataReceived(data: String) {
        // Create the type of video view programmatically based on info from the Bot app.
        when (data) {
            "WEBRTC" -> {
                val view: VideoViewWebRTC = createView(VideoViewWebRTC(this)) as VideoViewWebRTC
                view.init()
            }
            "RTSP" -> {
                Toast.makeText(
                    this,
                    "RTSP not supported by this controller. For video, set your main app to use WebRTC.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createView(view: View): View {
        view.id = R.id.video_view

        val layoutParams = ViewGroup.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        view.layoutParams = layoutParams

        if (binding.video.childCount > 0) {
            binding.video.removeAllViews()
        }
        binding.video.addView(view)
        return view
    }

    private fun setupPermissions() {
        val permission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Permission to get location denied")
            makeRequest()
        }
    }

    private fun makeRequest() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_REQUEST_LOCATION
        )
    }

    @SuppressLint("CheckResult")
    private fun subscribeToStatusInfo() {
        StatusEventBus.addSubject("CONNECTION_ACTIVE")
        StatusEventBus.subscribe(this.javaClass.simpleName, "CONNECTION_ACTIVE", onNext = {
            if (it.toBoolean()) {
                screenSelector.showControls()
            } else {
                screenSelector.hideControls()
                binding.controlModeTiltLayout.stop()
            }
        })
    }

    @SuppressLint("CheckResult")
    private fun createAppEventsSubscription() {
        LocalEventBus.subscriber.start(
            this.javaClass.simpleName,

            {
                Log.i(TAG, "Got $it event")

                when (it) {
                    LocalEventBus.ProgressEvents.ConnectionSuccessful -> {
                        Utils.beep()
                        screenSelector.showControls()
                    }
                    LocalEventBus.ProgressEvents.ConnectionStarted -> {
                    }
                    LocalEventBus.ProgressEvents.ConnectionFailed -> {
                        screenSelector.hideControls()
                    }
                    LocalEventBus.ProgressEvents.StartAdvertising -> {
                        screenSelector.hideControls()
                    }
                    LocalEventBus.ProgressEvents.Disconnected -> {
                        screenSelector.hideControls()
                        binding.controlModeTiltLayout.stop()
                    }
                    LocalEventBus.ProgressEvents.StopAdvertising -> {
                    }
                    LocalEventBus.ProgressEvents.TemporaryConnectionProblem -> {
                        screenSelector.hideControls()
                        ConnectionSelector.getConnection().connect(this)
                    }
                    LocalEventBus.ProgressEvents.PhoneOnTable -> {
                        screenSelector.showControls()
                    }
                    LocalEventBus.ProgressEvents.AdvertisingFailed -> {
                        screenSelector.hideControls()
                    }
                    LocalEventBus.ProgressEvents.GameDriveMode -> {
                        driveMode = DriveMode.GAME
                    }
                    LocalEventBus.ProgressEvents.DualDriveMode -> {
                        driveMode = DriveMode.DUAL
                    }
                    LocalEventBus.ProgressEvents.JoystickDriveMode -> {
                        driveMode = DriveMode.JOYSTICK
                    }
                }
            },
            { throwable ->
                Log.d(
                    "EventsSubscription",
                    "Got error on subscribe: $throwable"
                )
            })
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController.let {
                it?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                window.navigationBarColor = getColor(R.color.colorPrimaryDark)
                it?.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
    }

    @Override
    override fun onPause() {
        super.onPause()
        ConnectionSelector.getConnection().disconnect()
    }

    @Override
    override fun onResume() {
        super.onResume()
        hideSystemUI()

        ConnectionSelector.getConnection().init(this)
        ConnectionSelector.getConnection().connect(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_REQUEST_LOCATION -> {

                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permission has been denied by user")
                    finish()
                    exitProcess(0)
                } else {
                    Log.i(TAG, "Permission has been granted by user")
                }
            }
        }
    }

    data class Control(val left: Float, val right: Float)

    object GameController {
        private fun getCenteredAxis(event: MotionEvent?, axis: Int, historyPos: Int): Float {
            if (event == null || event.device == null) return 0F
            val range = event.device.getMotionRange(axis, event.source)

            // A joystick at rest does not always report an absolute position of
            // (0,0). Use the getFlat() method to determine the range of values
            // bounding the joystick axis center.
            if (range != null) {
                val flat = range.flat
                val value = if (historyPos < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, historyPos)

                // Ignore axis values that are within the 'flat' region of the
                // joystick axis center.
                if (Math.abs(value) > flat) {
                    return value
                }
            }
            return 0F
        }
        
        fun processJoystickInput(driveMode: DriveMode, event: MotionEvent?, historyPos: Int): Control {
            return when (driveMode) {
                DriveMode.DUAL -> {
                    val leftStick: Float = getCenteredAxis(event, MotionEvent.AXIS_Y, historyPos)
                    val rightStick: Float = getCenteredAxis(event, MotionEvent.AXIS_RZ, historyPos)
                    convertDualToControl(leftStick, rightStick)
                }

                DriveMode.GAME -> {
                    var rightTrigger: Float = getCenteredAxis(event, MotionEvent.AXIS_GAS, historyPos)
                    if (rightTrigger == 0f) {
                        rightTrigger = getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER, historyPos)
                    }
                    var leftTrigger: Float = getCenteredAxis(event, MotionEvent.AXIS_BRAKE, historyPos)
                    if (leftTrigger == 0f) {
                        leftTrigger = getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER, historyPos)
                    }

                    // Calculate the steering magnitude by
                    // using the input value from one of these physical controls:
                    // the left control stick, hat axis, or the right control stick.
                    var steeringOffset: Float = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos)
                    if (steeringOffset == 0f) {
                        steeringOffset = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, historyPos)
                    }
                    if (steeringOffset == 0f) {
                        steeringOffset = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos)
                    }
                    convertGameToControl(leftTrigger, rightTrigger, steeringOffset)
                }

                DriveMode.JOYSTICK -> {
                    // Calculate the vertical distance to move by
                    // using the input value from one of these physical controls:
                    // the left control stick, hat switch, or the right control stick.
                    var yAxis: Float = getCenteredAxis(event, MotionEvent.AXIS_Y, historyPos)
                    if (yAxis == 0f) {
                        yAxis = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y, historyPos)
                    }
                    if (yAxis == 0f) {
                        yAxis = getCenteredAxis(event, MotionEvent.AXIS_RZ, historyPos)
                    }

                    // Calculate the horizontal distance to move by
                    // using the input value from one of these physical controls:
                    // the left control stick, hat axis, or the right control stick.
                    var xAxis: Float = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos)
                    if (xAxis == 0f) {
                        xAxis = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, historyPos)
                    }
                    if (xAxis == 0f) {
                        xAxis = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos)
                    }
                    convertJoystickToControl(xAxis, yAxis)
                }
                else -> Control(0F, 0F)
            }
        }

        private fun convertDualToControl(leftStick: Float, rightStick: Float): Control {
            return Control(-leftStick, -rightStick)
        }

        private fun convertGameToControl(leftTrigger: Float, rightTrigger: Float, steeringOffset: Float): Control {
            var left = rightTrigger - leftTrigger
            var right = rightTrigger - leftTrigger
            if (left >= 0) left += steeringOffset else left -= steeringOffset
            if (right >= 0) right -= steeringOffset else right += steeringOffset
            return Control(left, right)
        }

        private fun convertJoystickToControl(xAxis: Float, yAxis: Float): Control {
            var left = -yAxis
            var right = -yAxis
            if (left >= 0) left += xAxis else left -= xAxis
            if (right >= 0) right -= xAxis else right += xAxis
            return Control(left, right)
        }
    }

    companion object
}
