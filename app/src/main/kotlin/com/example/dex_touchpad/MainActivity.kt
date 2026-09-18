package com.example.dex_touchpad

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.dex_touchpad.BuildConfig
import com.example.dex_touchpad.IMouseControl
import com.example.dex_touchpad.databinding.ActivityMainBinding
import com.example.dex_touchpad.services.BinderContainer
import com.example.dex_touchpad.services.ShizukuUserService
import com.example.dex_touchpad.services.TouchpadService
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

private const val TAG = "MainActivity"
private const val SHIZUKU_REQUEST_CODE = 100
private const val PREFS_NAME = "dex_touchpad_prefs"
private const val PREF_SENSITIVITY = "sensitivity"
private const val DEFAULT_SENSITIVITY = 1.5f
private const val ACTION_SEND_BINDER = "intent.dextouchpad.sendBinder"
private const val ACTION_SERVICE_EXIT = "intent.dextouchpad.exit"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var touchpadService: TouchpadService? = null
    private var mouseControl: IMouseControl? = null
    private var isUserServiceBound = false
    private var isBroadcastRegistered = false

    // Shizuku UserService — runs as shell UID, starts the native process
    private val userServiceArgs = UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)
    ).daemon(false).processNameSuffix("user_service").debuggable(false).version(1)

    // Connection for the Shizuku user service (just used to start the native process)
    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku UserService connected — native process should be starting")
            updateStatus("Starting native service...")
            // The native process will send us a binder via broadcast once ready
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku UserService disconnected")
            isUserServiceBound = false
        }
    }

    // Receives the IMouseControl binder from the native privileged process
    private val binderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SEND_BINDER -> {
                    Log.d(TAG, "Received binder from native service")
                    // Try BinderContainer wrapper first, then fall back to raw IBinder extra
                    val rawBinder: IBinder? = runCatching {
                        @Suppress("DEPRECATION")
                        val container = intent.getParcelableExtra<BinderContainer>("binder")
                        container?.binder
                    }.getOrNull() ?: intent.extras?.getBinder("binder")

                    if (rawBinder != null) {
                        mouseControl = IMouseControl.Stub.asInterface(rawBinder)
                        touchpadService?.setMouseControl(mouseControl)
                        binding.touchpadView.mouseControlService = mouseControl
                        updateStatus("Connected — touchpad active")
                    } else {
                        Log.e(TAG, "Received null binder")
                        updateStatus("Error: received null binder")
                    }
                }
                ACTION_SERVICE_EXIT -> {
                    Log.d(TAG, "Native service exited")
                    mouseControl = null
                    touchpadService?.setMouseControl(null)
                    binding.touchpadView.mouseControlService = null
                    updateStatus("Service stopped")
                }
            }
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
        registerBroadcastReceiver()
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
        if (isBroadcastRegistered) {
            unregisterReceiver(binderReceiver)
            isBroadcastRegistered = false
        }
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

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SEND_BINDER)
            addAction(ACTION_SERVICE_EXIT)
        }
        // The native helper runs under Shizuku's shell UID, so this receiver must
        // accept broadcasts from outside the app process.
        ContextCompat.registerReceiver(
            this,
            binderReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        isBroadcastRegistered = true
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
        mouseControl = null
        binding.touchpadView.mouseControlService = null
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

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }
}
