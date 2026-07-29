package com.example.soldiersbattle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * Renders the battlefield and runs the local physics/game loop.
 * isHost == true  -> this device is the LEFT (blue) soldier, bullets fire rightward.
 * isHost == false -> this device is the RIGHT (red) soldier, bullets fire leftward.
 *
 * Networking is decoupled: call GameView.onNetworkMessage() when a message arrives,
 * and set `sendMessage` to a lambda that hands a string off to BluetoothGameManager.
 */
class GameView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var isHost: Boolean = true
    var sendMessage: ((String) -> Unit)? = null
    var onHudUpdate: ((myHp: Int, enemyHp: Int, ammo: Int, maxAmmo: Int) -> Unit)? = null
    var onGameOver: ((iWon: Boolean) -> Unit)? = null

    companion object {
        const val SOLDIER_W = 90f
        const val SOLDIER_H = 160f
        const val MAX_AMMO = 6
        const val RELOAD_MS = 1400L
        const val POS_SYNC_MS = 100L
        const val BULLET_SPEED = 22f
        const val BULLET_HIT_RANGE = 70f
    }

    // ---- local player state ----
    private var myX = 0f
    private var myHp = 100
    private var myAmmo = MAX_AMMO
    private var reloading = false
    private var ducking = false
    private var movingLeft = false
    private var movingRight = false

    // ---- remote player state (received over Bluetooth) ----
    private var enemyX = 0f
    private var enemyHp = 100
    private var enemyDucking = false

    private var gameOver = false

    data class Bullet(var x: Float, val dir: Int, val local: Boolean)
    private val bullets = mutableListOf<Bullet>()

    private val handler = Handler(Looper.getMainLooper())
    private var lastPosSyncTime = 0L
    private var running = false

    // ---- paint objects ----
    private val groundPaint = Paint().apply { color = Color.parseColor("#3A5228") }
    private val skyPaint = Paint().apply { color = Color.parseColor("#5E7A98") }
    private val myPaint = Paint().apply { color = Color.parseColor("#3BA3FF") }
    private val enemyPaint = Paint().apply { color = Color.parseColor("#FF4B4B") }
    private val bulletPaint = Paint().apply { color = Color.parseColor("#FFE93B") }
    private val hitFlashPaint = Paint().apply { color = Color.WHITE }
    private var myHitFlashTicks = 0
    private var enemyHitFlashTicks = 0

    fun resetGame() {
        myHp = 100
        enemyHp = 100
        myAmmo = MAX_AMMO
        reloading = false
        ducking = false
        enemyDucking = false
        gameOver = false
        bullets.clear()
        myX = if (isHost) width * 0.15f else width * 0.75f
        enemyX = if (isHost) width * 0.75f else width * 0.15f
        pushHud()
    }

    fun startLoop() {
        if (running) return
        running = true
        handler.post(loopRunnable)
    }

    fun stopLoop() {
        running = false
        handler.removeCallbacks(loopRunnable)
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (running) {
                tick()
                invalidate()
                handler.postDelayed(this, 16)
            }
        }
    }

    // ---------------- input from buttons ----------------
    fun setMovingLeft(v: Boolean) { movingLeft = v }
    fun setMovingRight(v: Boolean) { movingRight = v }
    fun setDucking(v: Boolean) { ducking = v }

    fun shoot() {
        if (gameOver || reloading || myAmmo <= 0) return
        myAmmo--
        val dir = if (isHost) 1 else -1
        val startX = myX + if (isHost) SOLDIER_W else 0f
        bullets.add(Bullet(startX, dir, true))
        sendMessage?.invoke("SHOOT:$startX:$dir")
        pushHud()
        if (myAmmo == 0) {
            reloading = true
            handler.postDelayed({
                myAmmo = MAX_AMMO
                reloading = false
                pushHud()
            }, RELOAD_MS)
        }
    }

    // ---------------- network message handling ----------------
    fun onNetworkMessage(msg: String) {
        val parts = msg.split(":")
        when (parts[0]) {
            "POS" -> {
                enemyX = parts[1].toFloatOrNull() ?: enemyX
                enemyDucking = parts.getOrNull(2) == "1"
            }
            "SHOOT" -> {
                val x = parts[1].toFloatOrNull() ?: return
                val dir = parts[2].toIntOrNull() ?: return
                bullets.add(Bullet(x, dir, false))
            }
            "HIT" -> {
                val dmg = parts[1].toIntOrNull() ?: 0
                if (!ducking) {
                    myHp = (myHp - dmg).coerceIn(0, 100)
                    myHitFlashTicks = 6
                    pushHud()
                    sendMessage?.invoke("HP:$myHp")
                    if (myHp <= 0 && !gameOver) {
                        gameOver = true
                        sendMessage?.invoke("DEAD")
                        onGameOver?.invoke(false)
                    }
                } else {
                    sendMessage?.invoke("DODGED")
                }
            }
            "HP" -> {
                enemyHp = parts[1].toIntOrNull() ?: enemyHp
                pushHud()
            }
            "DEAD" -> {
                if (!gameOver) {
                    gameOver = true
                    onGameOver?.invoke(true)
                }
            }
            "RESTART" -> {
                resetGame()
            }
        }
    }

    // ---------------- physics tick ----------------
    private fun tick() {
        if (gameOver) return

        val speed = 9f
        if (movingLeft) myX -= speed
        if (movingRight) myX += speed
        val maxX = (width - SOLDIER_W).coerceAtLeast(0f)
        myX = myX.coerceIn(0f, maxX)

        val now = System.currentTimeMillis()
        if (now - lastPosSyncTime > POS_SYNC_MS) {
            lastPosSyncTime = now
            sendMessage?.invoke("POS:$myX:${if (ducking) 1 else 0}")
        }

        val iter = bullets.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.x += b.dir * BULLET_SPEED

            if (b.local) {
                // Only the shooter evaluates whether their own bullet lands a hit.
                val targetX = enemyX
                val reached = if (b.dir > 0) b.x >= targetX else b.x <= targetX + SOLDIER_W
                if (reached) {
                    if (!enemyDucking) {
                        val dmg = 8 + Random.nextInt(8)
                        sendMessage?.invoke("HIT:$dmg")
                        enemyHitFlashTicks = 6
                    }
                    iter.remove()
                    continue
                }
            }

            if (b.x < -40f || b.x > width + 40f) {
                iter.remove()
            }
        }

        if (myHitFlashTicks > 0) myHitFlashTicks--
        if (enemyHitFlashTicks > 0) enemyHitFlashTicks--
    }

    private fun pushHud() {
        onHudUpdate?.invoke(myHp, enemyHp, myAmmo, MAX_AMMO)
    }

    // ---------------- drawing ----------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val groundH = h * 0.18f

        canvas.drawRect(0f, 0f, w, h - groundH, skyPaint)
        canvas.drawRect(0f, h - groundH, w, h, groundPaint)

        val soldierBottom = h - groundH

        drawSoldier(canvas, myX, soldierBottom, myPaint, ducking, myHitFlashTicks > 0)
        drawSoldier(canvas, enemyX, soldierBottom, enemyPaint, enemyDucking, enemyHitFlashTicks > 0)

        for (b in bullets) {
            canvas.drawRect(b.x, soldierBottom - SOLDIER_H * 0.55f, b.x + 16f, soldierBottom - SOLDIER_H * 0.55f + 6f, bulletPaint)
        }
    }

    private fun drawSoldier(canvas: Canvas, x: Float, bottom: Float, paint: Paint, isDucking: Boolean, isHit: Boolean) {
        val heightScale = if (isDucking) 0.6f else 1f
        val top = bottom - SOLDIER_H * heightScale
        val rect = RectF(x, top, x + SOLDIER_W, bottom)
        canvas.drawRect(rect, if (isHit) hitFlashPaint else paint)
        // simple head circle
        canvas.drawCircle(x + SOLDIER_W / 2f, top - 20f, 20f, if (isHit) hitFlashPaint else paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (myX == 0f && enemyX == 0f) {
            resetGame()
        }
    }
}
