package com.kingzcheung.xime.password

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kingzcheung.xime.MainActivity

class BitwardenProviderSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("open_fragment", "passwords")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
