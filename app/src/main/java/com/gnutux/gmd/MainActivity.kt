package com.gnutux.gmd

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gnutux.gmd.ui.GmdApp as GmdUi
import com.gnutux.gmd.ui.GmdTheme
import com.gnutux.gmd.ui.GmdViewModel

class MainActivity : ComponentActivity() {

    private var sharedUrl by mutableStateOf<String?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = extractUrl(intent)

        // بلا هذا الإذن لا يظهر إشعار التقدُّم على أندرويد 13 فما فوق، والتنزيل
        // يجري أعمى. يُطلب مرّةً ولا يُلحّ.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            GmdTheme {
                val vm: GmdViewModel = viewModel()
                GmdUi(vm = vm, incomingUrl = sharedUrl, onUrlConsumed = { sharedUrl = null })
            }
        }
    }

    /** التطبيق singleTask، فمشاركة رابطٍ ثانٍ تصل هنا لا إلى onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrl(intent)?.let { sharedUrl = it }
    }

    /**
     * ورقة المشاركة ترسل نصّاً حرّاً غالباً — عنوان المقطع ثمّ رابطه مثلاً — فنلتقط
     * أوّل رابطٍ فيه بدل رفض ما ليس رابطاً خالصاً.
     */
    private fun extractUrl(intent: Intent?): String? {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return URL_RE.find(raw)?.value ?: raw.trim().takeIf { it.startsWith("http") }
    }

    private companion object {
        val URL_RE = Regex("""https?://\S+""")
    }
}
