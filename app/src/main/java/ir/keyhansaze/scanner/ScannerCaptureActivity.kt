package ir.keyhansaze.scanner

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import com.journeyapps.barcodescanner.CaptureActivity

class ScannerCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        val toggle = Button(this).apply {
            text = "↻ افقی"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD176B63.toInt())
            contentDescription = "تغییر جهت دوربین به افقی یا عمودی"
            setOnClickListener {
                val landscape = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                text = if (landscape) "↻ قائم" else "↻ افقی"
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (16 * resources.displayMetrics.density).toInt()
            marginEnd = (12 * resources.displayMetrics.density).toInt()
        }
        content.addView(toggle, params)
        toggle.bringToFront()
    }
}
