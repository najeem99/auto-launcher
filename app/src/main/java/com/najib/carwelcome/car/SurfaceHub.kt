package com.najib.carwelcome.car

import android.graphics.Rect
import android.os.Parcel
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.najib.carwelcome.CrashLog

/**
 * Android Auto gives the app one drawing surface. The hub keeps it and hands it to
 * whichever layer is on screen (the dashboard or the YouTube player), so only one
 * of them ever draws on it at a time.
 *
 * A surface accepts one producer at a time, and the dashboard's canvas stays attached
 * to the Surface object it drew on. So each layer gets its own handle to the same
 * surface, and that handle is released when the layer gives the screen up. Releasing
 * it detaches the old producer, so the next layer can attach cleanly.
 */
object SurfaceHub : SurfaceCallback {
    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var stable: Rect? = null
    private var active: SurfaceCallback? = null
    private var lent: SurfaceContainer? = null

    /** New car session: forget the old surface. */
    fun reset() {
        lent?.surface?.release()
        lent = null
        container = null
        visible = null
        stable = null
        active = null
    }

    /** Makes [layer] the one drawing on the car screen. */
    fun show(layer: SurfaceCallback) {
        if (active === layer) return
        takeBack()
        active = layer
        lend()
    }

    /** Takes the surface away from [layer] if it has it. */
    fun release(layer: SurfaceCallback) {
        if (active !== layer) return
        takeBack()
        active = null
    }

    private fun lend() {
        val layer = active ?: return
        val c = container ?: return
        val copy = duplicate(c) ?: return
        lent = copy
        CrashLog.guard("surface available") { layer.onSurfaceAvailable(copy) }
        visible?.let { CrashLog.guard("visible area") { layer.onVisibleAreaChanged(it) } }
        stable?.let { CrashLog.guard("stable area") { layer.onStableAreaChanged(it) } }
    }

    private fun takeBack() {
        val l = lent ?: return
        lent = null
        CrashLog.guard("surface destroyed") { active?.onSurfaceDestroyed(l) }
        runCatching { l.surface?.release() }
    }

    /** A second Surface object for the same car surface. */
    private fun duplicate(c: SurfaceContainer): SurfaceContainer? {
        val src = c.surface ?: return null
        val parcel = Parcel.obtain()
        return try {
            src.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = Surface.CREATOR.createFromParcel(parcel)
            SurfaceContainer(copy, c.width, c.height, c.dpi)
        } catch (e: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    // Calls from Android Auto. An error escaping one of these would end the whole car
    // app, so each is caught and noted in the crash log instead.

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        CrashLog.guard("hub surface available") {
            takeBack()
            container = surfaceContainer
            lend()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        if (lent != null) CrashLog.guard("visible area") { active?.onVisibleAreaChanged(visibleArea) }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        stable = Rect(stableArea)
        if (lent != null) CrashLog.guard("stable area") { active?.onStableAreaChanged(stableArea) }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        CrashLog.guard("hub surface destroyed") { takeBack() }
        container = null
    }

    override fun onClick(x: Float, y: Float) { CrashLog.guard("tap") { active?.onClick(x, y) } }
    override fun onScroll(distanceX: Float, distanceY: Float) { CrashLog.guard("scroll") { active?.onScroll(distanceX, distanceY) } }
    override fun onFling(velocityX: Float, velocityY: Float) { CrashLog.guard("fling") { active?.onFling(velocityX, velocityY) } }
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) { CrashLog.guard("zoom") { active?.onScale(focusX, focusY, scaleFactor) } }
}
