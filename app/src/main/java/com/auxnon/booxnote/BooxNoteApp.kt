package com.auxnon.booxnote

import android.app.Application
import android.os.Build
import com.onyx.android.sdk.rx.RxManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BooxNoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { RxManager.Builder.initAppContext(this) }
        // The pencil engine reaches ResManager for its brush-mask resources, and it holds the
        // context in a lateinit - so without this it throws UninitializedPropertyAccessException on
        // the first stroke. NeoPencilPen's caller catches that and falls back, which is why the
        // pencil rendered with no texture even once the base.lite classes were present.
        runCatching { com.onyx.android.sdk.base.utils.ResManager.init(this) }
        // What the firmware says each brush's tilt should be. Onyx's own demo builds its charcoal
        // TiltConfig from exactly this call, so these are the authoritative per-brush values rather
        // than anything we could infer - logged once at startup so they are visible without having
        // to route a stroke through the native path to trigger the lookup.
        runCatching {
            val device = com.onyx.android.sdk.device.Device.currentDevice()
            HardwarePenStyle.entries.forEach { style ->
                val p = runCatching { device.getStrokeParameters(style.hardwareStrokeStyle) }.getOrNull()
                android.util.Log.i(
                    "PenParams",
                    "${style.label} (style=${style.hardwareStrokeStyle}): ${p?.joinToString() ?: "none"}"
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }
}
