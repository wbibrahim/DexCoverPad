package com.example.dex_touchpad

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.dex_touchpad.BuildConfig
import com.example.dex_touchpad.IMouseControl
import com.example.dex_touchpad.databinding.ActivityMainBinding
import com.example.dex_touchpad.services.ShizukuUserService
import com.example.dex_touchpad.services.TouchpadService
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

private const val TAG = "MainActivity"
private const val SHIZUKU_REQUEST_CODE = 100
private const val PREFS_NAME = "dex_touchpad_prefs"
private const val PREF_SENSITIVITY = "sensitivity"
private const val DEFAULT_SENSITIVITY = 1.5f

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var touchpadService: TouchpadService? = null
    private var mouseControl: IMouseControl? = null
    private var isUserServiceBound = false

    // Shizuku UserService runs as shell UID and owns the virtual mouse.
    private val userServiceArgs = UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)
    ).daemon(false)
        .tag("mouse")
        .processNameSuffix("user_service")
        .debuggable(false)
        .version(2)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku UserService connected")
            val control = IMouseControl.Stub.asInterface(service)
            try {
                if (control?.isReady == true) {
                    mouseControl = control
                    touchpadService?.setMouseControl(control)
                    binding.touchpadView.mouseControlService = control
                    updateStatus("Connected — touchpad active")
                } else {
                    val reason = control?.error ?: "virtual mouse did not start"
                    clearMouseControl()
                    updateStatus("Could not start touchpad: $reason")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not initialize mouse service", e)
                clearMouseControl()
                updateStatus("Could not start touchpad: ${e.message ?: "service error"}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku UserService disconnected")
            isUserServiceBound = false
            clearMouseControl()
            updateStatus("Touchpad service disconnected — tap Reconnect")
        }
    }

    private val touchpadServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TouchpadService.LocalBinder
            touchpadService = binder.getService()
            touchpadService?.setMouseControl(mouseControl)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            touchpadService = null
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        checkShizukuAndConnect()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        updateStatus("Shizuku disconnected — restart Shizuku")
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    updateStatus("Shizuku permission denied — grant it in the Shizuku app")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupSensitivityControl()
        setupButtons()
        registerShizukuListeners()

        startForegroundService(Intent(this, TouchpadService::class.java))
        bindService(
            Intent(this, TouchpadService::class.java),
            touchpadServiceConnection,
            Context.BIND_AUTO_CREATE
        )

        updateStatus("Waiting for Shizuku...")
    }

    override fun onResume() {
        super.onResume()
        if (Shizuku.pingBinder()) {
            checkShizukuAndConnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterShizukuListeners()
        if (isUserServiceBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding user service", e)
            }
            isUserServiceBound = false
        }
        try {
            unbindService(touchpadServiceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding touchpad service", e)
        }
    }

    private fun registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun unregisterShizukuListeners() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun checkShizukuAndConnect() {
        if (!Shizuku.pingBinder()) {
            updateStatus("Shizuku not running — start Shizuku first")
            return
        }
        if (Shizuku.isPreV11()) {
            updateStatus("Shizuku too old — update it")
            return
        }
        when {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                bindUserService()
            }
            Shizuku.shouldShowRequestPermissionRationale() -> {
                updateStatus("Open Shizuku app and grant permission to Dex Touchpad")
            }
            else -> {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        }
    }

    private fun bindUserService() {
        if (isUserServiceBound) return
        try {
            updateStatus("Connecting via Shizuku...")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            isUserServiceBound = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind user service", e)
            updateStatus("Failed to connect: ${e.message}")
        }
    }

    private fun setupSensitivityControl() {
        val savedSensitivity = prefs.getFloat(PREF_SENSITIVITY, DEFAULT_SENSITIVITY)
        val seekbarProgress = ((savedSensitivity - 0.1f) / 4.9f * 100).toInt()
        binding.sensitivitySeekbar.progress = seekbarProgress
        binding.touchpadView.setSensitivity(savedSensitivity)

        binding.sensitivitySeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = 0.1f + (progress / 100f) * 4.9f
                binding.touchpadView.setSensitivity(sensitivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        binding.btnLeftClick.setOnClickListener {
            try {
                mouseControl?.sendClick(272)
            } catch (e: Exception) {
                Log.w(TAG, "Left click failed", e)
            }
        }
        binding.btnRightClick.setOnClickListener {
            try {
                mouseControl?.sendClick(273)
            } catch (e: Exception) {
                Log.w(TAG, "Right click failed", e)
            }
        }
        binding.btnReconnect.setOnClickListener {
            reconnect()
        }
    }

    private fun reconnect() {
        clearMouseControl()
        if (isUserServiceBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding", e)
            }
            isUserServiceBound = false
        }
        updateStatus("Reconnecting...")
        checkShizukuAndConnect()
    }

    private fun clearMouseControl() {
        mouseControl = null
        touchpadService?.setMouseControl(null)
        binding.touchpadView.mouseControlService = null
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }
}
