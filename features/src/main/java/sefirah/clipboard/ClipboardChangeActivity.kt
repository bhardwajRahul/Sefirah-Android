/*
 * Acknowledgment:
 * Portions of this code are adapted from XClipper by Kaustubh Patange.
 * Licensed under the Apache License 2.0.
 */

package sefirah.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.ClipboardInfo
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class ClipboardChangeActivity : FragmentActivity() {
    @Inject lateinit var networkManager: NetworkManager

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return

        lifecycleScope.launch {
            /** Delay gives [ClipboardManager] time to capture clipboard text. */
            delay(500.milliseconds)
            if (!hasWindowFocus()) return@launch

            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val data = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            if (!data.isNullOrEmpty()) {
                networkManager.sendClipboardMessage(ClipboardInfo("text/plain", data))
            }
            finish()
        }
    }

    companion object {
        fun launch(context: Context) = with(context) {
            val intent = Intent(this, ClipboardChangeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}
