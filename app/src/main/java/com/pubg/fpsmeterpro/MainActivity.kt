package com.pubg.fpsmeterpro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 410
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(26, 34, 26, 24)
            setBackgroundColor(0xFF0B1118.toInt())
        }
        val title = TextView(this).apply {
            text = "PUBG FPS Meter Pro"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "عداد أداء عائم بدون روت"
            textSize = 16f
            setTextColor(0xFF9FB2C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        val explanation = TextView(this).apply {
            text = "يعرض التطبيق 3 قراءات منفصلة:\\n\\n• معدل التقاط الشاشة: عدد الصور التي وصلتنا من Android كل ثانية.\\n• مؤشر تغيّر الصورة: مدى تغيّر محتوى الشاشة، وليس FPS اللعبة المؤكد.\\n• معدل تحديث الشاشة: مثل 60 أو 90Hz.\\n\\nلا يوجد روت ولا اتصال بالإنترنت. سيطلب Android إذن تسجيل الشاشة عند كل جلسة."
            textSize = 15f
            setTextColor(0xFFE1E8EF.toInt())
            setLineSpacing(5f, 1f)
        }
        val start = Button(this).apply {
            text = "السماح وتشغيل العداد العائم"
            setOnClickListener { requestStart() }
        }
        val stop = Button(this).apply {
            text = "إيقاف القياس"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MeterService::class.java))
                Toast.makeText(this@MainActivity, "تم إرسال أمر الإيقاف", Toast.LENGTH_SHORT).show()
            }
        }
        val note = TextView(this).apply {
            text = "مهم: لا يستطيع تطبيق عادي قراءة عداد FPS الداخلي للعبة PUBG مباشرةً على كل أجهزة Android. لذلك لن نعرض التقدير على أنه رقم مضمون."
            textSize = 13f
            setTextColor(0xFFFFC66D.toInt())
            setPadding(0, 18, 0, 0)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        root.addView(explanation, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(start, LinearLayout.LayoutParams(-1, -2))
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))
        root.addView(note, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun requestStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "فعّل السماح بالظهور فوق التطبيقات ثم ارجع واضغط تشغيل", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Android callback retained for compatibility with KlencodIDE")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, MeterService::class.java).apply {
                    action = MeterService.ACTION_START
                    putExtra(MeterService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(MeterService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
                else startService(service)
            } else {
                Toast.makeText(this, "لم يتم منح إذن تسجيل الشاشة", Toast.LENGTH_SHORT).show()
            }
        }
    }
}