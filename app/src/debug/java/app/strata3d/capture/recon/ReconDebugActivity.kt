package app.strata3d.capture.recon

import android.app.Activity
import android.os.Bundle

/**
 * Debug builds only (src/debug): starts or cancels a reconstruction from adb, before the app has its own screens
 * for it. An activity rather than a broadcast, because Android only lets a visible app start a foreground service.
 *
 *   adb shell am start -n app.strata3d.debug/app.strata3d.capture.recon.ReconDebugActivity --es id <captureId>
 *   adb shell am start -n app.strata3d.debug/app.strata3d.capture.recon.ReconDebugActivity --es id <captureId> --ez review true
 *   adb shell am start -n app.strata3d.debug/app.strata3d.capture.recon.ReconDebugActivity --es id <captureId> --ez resume true
 *   adb shell am start -n app.strata3d.debug/app.strata3d.capture.recon.ReconDebugActivity --ez cancel true
 */
class ReconDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("cancel", false)) ReconService.cancel(this)
        else intent.getStringExtra("id")?.let { id ->
            if (intent.getBooleanExtra("resume", false)) ReconService.resume(this, id)
            else ReconService.start(this, id, intent.getBooleanExtra("review", false))
        }
        finish()
    }
}
