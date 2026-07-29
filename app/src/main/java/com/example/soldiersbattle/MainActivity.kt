package com.example.soldiersbattle

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BluetoothGameManager.Listener {

    private lateinit var bluetoothManager: BluetoothGameManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var setupScreen: android.view.View
    private lateinit var gameScreen: android.view.View
    private lateinit var statusText: TextView
    private lateinit var pairedDevicesContainer: LinearLayout
    private lateinit var gameView: GameView
    private lateinit var myHpText: TextView
    private lateinit var enemyHpText: TextView
    private lateinit var ammoText: TextView
    private lateinit var connStatusText: TextView
    private lateinit var resultBanner: TextView
    private lateinit var restartBtn: Button

    private var amHost = false

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onPermissionsGranted()
        } else {
            Toast.makeText(this, "لازم تسمح بصلاحيات البلوتوث عشان اللعبة تشتغل", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        bluetoothAdapter = btManager.adapter

        bluetoothManager = BluetoothGameManager(this)

        setupScreen = findViewById(R.id.setupScreen)
        gameScreen = findViewById(R.id.gameScreen)
        statusText = findViewById(R.id.statusText)
        pairedDevicesContainer = findViewById(R.id.pairedDevicesContainer)
        gameView = findViewById(R.id.gameView)
        myHpText = findViewById(R.id.myHpText)
        enemyHpText = findViewById(R.id.enemyHpText)
        ammoText = findViewById(R.id.ammoText)
        connStatusText = findViewById(R.id.connStatusText)
        resultBanner = findViewById(R.id.resultBanner)
        restartBtn = findViewById(R.id.restartBtn)

        findViewById<Button>(R.id.enableBtBtn).setOnClickListener { requestPermissionsThenEnableBt() }
        findViewById<Button>(R.id.refreshPairedBtn).setOnClickListener { refreshPairedDevices() }
        findViewById<Button>(R.id.hostBtn).setOnClickListener { startHosting() }

        findViewById<Button>(R.id.leftBtn).setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> gameView.setMovingLeft(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> gameView.setMovingLeft(false)
            }
            true
        }
        findViewById<Button>(R.id.rightBtn).setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> gameView.setMovingRight(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> gameView.setMovingRight(false)
            }
            true
        }
        findViewById<Button>(R.id.duckBtn).setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> gameView.setDucking(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> gameView.setDucking(false)
            }
            true
        }
        findViewById<Button>(R.id.shootBtn).setOnClickListener { gameView.shoot() }

        restartBtn.setOnClickListener {
            gameView.resetGame()
            bluetoothManager.send("RESTART")
            resultBanner.visibility = android.view.View.GONE
            restartBtn.visibility = android.view.View.GONE
        }

        gameView.sendMessage = { msg -> bluetoothManager.send(msg) }
        gameView.onHudUpdate = { myHp, enemyHp, ammo, maxAmmo ->
            myHpText.text = "أنت: $myHp%"
            enemyHpText.text = "الخصم: $enemyHp%"
            ammoText.text = "🔫 $ammo/$maxAmmo"
        }
        gameView.onGameOver = { iWon ->
            resultBanner.text = if (iWon) "فزت! 🏆" else "خسرت 💀"
            resultBanner.visibility = android.view.View.VISIBLE
            restartBtn.visibility = android.view.View.VISIBLE
        }

        requestPermissionsThenEnableBt()
    }

    private fun requestPermissionsThenEnableBt() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        if (!bluetoothAdapter.isEnabled) {
            statusText.text = "فعّل البلوتوث يدويًا من إعدادات الجهاز ثم اضغط 'تحديث قائمة الأجهزة'"
            Toast.makeText(this, "رجاءً فعّل البلوتوث من الإعدادات", Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "البلوتوث شغّال ✅ — استضف أو اختر جهاز مقترن"
            refreshPairedDevices()
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun refreshPairedDevices() {
        pairedDevicesContainer.removeAllViews()
        if (!bluetoothAdapter.isEnabled || !hasConnectPermission()) return

        val paired: Set<BluetoothDevice> = try {
            bluetoothAdapter.bondedDevices
        } catch (e: SecurityException) {
            emptySet()
        }

        if (paired.isEmpty()) {
            val tv = TextView(this)
            tv.text = "ما فيه أجهزة مقترنة بعد. اقرن الجهازين من إعدادات البلوتوث أولاً."
            tv.setTextColor(android.graphics.Color.WHITE)
            pairedDevicesContainer.addView(tv)
            return
        }

        for (device in paired) {
            val btn = Button(this)
            btn.text = "🔗 اتصل بـ: ${safeName(device)}"
            btn.setOnClickListener { connectToDevice(device) }
            pairedDevicesContainer.addView(btn)
        }
    }

    private fun safeName(device: BluetoothDevice): String {
        return try { device.name ?: device.address } catch (e: SecurityException) { device.address }
    }

    private fun startHosting() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "فعّل البلوتوث أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        amHost = true
        statusText.text = "بانتظار اتصال صديقك... (تأكد إنه يضغط على اسم جهازك من قائمته)"
        bluetoothManager.startServer(bluetoothAdapter)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        amHost = false
        statusText.text = "جاري الاتصال بـ ${safeName(device)}..."
        bluetoothManager.connectToDevice(bluetoothAdapter, device)
    }

    // ---------------- BluetoothGameManager.Listener callbacks ----------------
    override fun onConnected(deviceName: String) {
        runOnUiThread {
            setupScreen.visibility = android.view.View.GONE
            gameScreen.visibility = android.view.View.VISIBLE
            connStatusText.text = "متصل بـ $deviceName ✅"
            gameView.isHost = amHost
            gameView.resetGame()
            gameView.startLoop()
        }
    }

    override fun onMessageReceived(message: String) {
        runOnUiThread { gameView.onNetworkMessage(message) }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            statusText.text = "فشل الاتصال: $reason"
        }
    }

    override fun onConnectionLost() {
        runOnUiThread {
            Toast.makeText(this, "انقطع الاتصال بالجهاز الثاني", Toast.LENGTH_LONG).show()
            gameView.stopLoop()
            gameScreen.visibility = android.view.View.GONE
            setupScreen.visibility = android.view.View.VISIBLE
            statusText.text = "انقطع الاتصال. حاول تتصل من جديد."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView.stopLoop()
        bluetoothManager.stop()
    }
}
