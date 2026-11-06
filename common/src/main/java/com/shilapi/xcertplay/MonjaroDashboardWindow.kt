package com.shilapi.xcertplay

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/** A display as [MonjaroClusterDisplay.choose] sees it; the addresses are null where the platform hides them. */
internal data class DisplayDescription(
    val displayId: Int,
    val name: String,
    val uniqueId: String?,
    val physicalId: Long?,
    val width: Int,
    val height: Int,
) {
    override fun toString(): String =
        "$displayId:$name ${width}x$height uniqueId=${uniqueId ?: "?"} physicalId=${physicalId ?: "?"}"
}

/** The display chosen for the custom dashboard, or null and why not. */
internal data class ClusterDisplayChoice(val displayId: Int?, val reason: String)

/**
 * Finds the Android display that firmware in engineering mode shows on the cluster: physical display 130, which
 * is 1920x720 like the centre and passenger displays, so only its address tells it apart.
 */
internal object MonjaroClusterDisplay {
    const val PHYSICAL_ID = 130L
    const val WIDTH = 1920
    const val HEIGHT = 720

    fun choose(displays: List<DisplayDescription>): ClusterDisplayChoice {
        if (displays.all { it.uniqueId == null && it.physicalId == null }) {
            return ClusterDisplayChoice(null, "no display address readable")
        }
        displays.firstOrNull { uniqueIdPhysical(it) != null && it.physicalId != null && uniqueIdPhysical(it) != it.physicalId }
            ?.let { return ClusterDisplayChoice(null, "display ${it.displayId} has two addresses") }
        val matches = displays.filter { uniqueIdPhysical(it) == PHYSICAL_ID || it.physicalId == PHYSICAL_ID }
        val match = when (matches.size) {
            0 -> return ClusterDisplayChoice(null, "no display at local:$PHYSICAL_ID")
            1 -> matches.single()
            else -> return ClusterDisplayChoice(null, "${matches.size} displays at local:$PHYSICAL_ID")
        }
        if (match.width != WIDTH || match.height != HEIGHT) {
            return ClusterDisplayChoice(null, "local:$PHYSICAL_ID is ${match.width}x${match.height}, not ${WIDTH}x$HEIGHT")
        }
        return ClusterDisplayChoice(match.displayId, "display ${match.displayId} at local:$PHYSICAL_ID")
    }

    private fun uniqueIdPhysical(display: DisplayDescription): Long? =
        display.uniqueId?.takeIf { it.startsWith("local:") }?.removePrefix("local:")?.toLongOrNull()

    /** `Display.getUniqueId()` and `Display.getAddress()` are hidden APIs. */
    fun describe(display: Display): DisplayDescription {
        val size = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
        val uniqueId = runCatching { Display::class.java.getMethod("getUniqueId").invoke(display) as? String }.getOrNull()
        val physicalId = runCatching {
            val address = Display::class.java.getMethod("getAddress").invoke(display) ?: return@runCatching null
            address.javaClass.getMethod("getPhysicalDisplayId").invoke(address) as? Long
        }.getOrNull()
        return DisplayDescription(display.displayId, display.name.orEmpty(), uniqueId, physicalId, size.x, size.y)
    }
}

/**
 * The custom dashboard as an opaque overlay on the cluster display, neither focusable nor touchable. An
 * application overlay outlives DiPlay's activities, as the CarPlay session does.
 */
internal class MonjaroDashboardWindow(
    private val context: Context,
    private val content: () -> MonjaroDashboardContent,
    private val log: (String) -> Unit,
) : DashboardWindow {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var root: View? = null
    private var windows: WindowManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var opListener: AppOpsManager.OnOpChangedListener? = null
    private var reported: String? = null

    override fun available(): Boolean {
        val permitted = Settings.canDrawOverlays(context)
        val all = displays?.displays.orEmpty().map(MonjaroClusterDisplay::describe)
        val choice = MonjaroClusterDisplay.choose(all)
        val report = "custom dashboard overlay=${if (permitted) "allowed" else "missing"} ${choice.reason}; " +
            "displays=${all.joinToString()}"
        if (report != reported) {
            reported = report
            log(report)
        }
        return permitted && choice.displayId != null
    }

    override fun startWatching(changed: () -> Unit) {
        stopWatching()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = changed()
            override fun onDisplayRemoved(displayId: Int) = changed()
            override fun onDisplayChanged(displayId: Int) = changed()
        }
        displays?.registerDisplayListener(listener, main)
        displayListener = listener
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return
        val op = AppOpsManager.OnOpChangedListener { _, packageName ->
            if (packageName == context.packageName) main.post(changed)
        }
        ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, op)
        opListener = op
    }

    override fun stopWatching() {
        displayListener?.let { displays?.unregisterDisplayListener(it) }
        displayListener = null
        opListener?.let { context.getSystemService(AppOpsManager::class.java)?.stopWatchingMode(it) }
        opListener = null
    }

    override fun show(firstFrame: () -> Unit): Boolean {
        hide()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("custom dashboard needs Android 11 window contexts")
            return false
        }
        val displayId = MonjaroClusterDisplay.choose(displays?.displays.orEmpty().map(MonjaroClusterDisplay::describe))
            .displayId ?: return false
        val display = displays?.getDisplay(displayId) ?: return false
        return add(display, firstFrame)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun add(display: Display, firstFrame: () -> Unit): Boolean {
        val windowContext = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windows = windowContext.getSystemService(WindowManager::class.java) ?: return false
        val frame = FrameLayout(windowContext).apply {
            setBackgroundColor(Color.BLACK)
            addView(content().create(windowContext), FrameLayout.LayoutParams(
                MonjaroClusterDisplay.WIDTH, MonjaroClusterDisplay.HEIGHT, Gravity.TOP or Gravity.START))
        }
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                view.viewTreeObserver.registerFrameCommitCallback { main.post(firstFrame) }
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DiPlay custom dashboard"
        }
        try {
            windows.addView(frame, params)
        } catch (error: RuntimeException) {
            log("custom dashboard window refused on display ${display.displayId} (${error.javaClass.simpleName})")
            return false
        }
        root = frame
        this.windows = windows
        log("custom dashboard window added on display ${display.displayId}")
        return true
    }

    override fun hide() {
        val view = root ?: return
        root = null
        runCatching { windows?.removeViewImmediate(view) }
        windows = null
        log("custom dashboard window removed")
    }
}
