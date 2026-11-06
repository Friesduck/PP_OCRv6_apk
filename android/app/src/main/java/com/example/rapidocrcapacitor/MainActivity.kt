package com.example.rapidocrcapacitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    private var galleryPermissionRequestedThisLaunch = false

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (!hasFullGalleryAccess()) {
            val message = if (hasPartialGalleryAccess()) {
                "当前只允许访问部分照片，请在系统设置中改为允许所有照片"
            } else {
                "需要允许访问所有照片，才能打开完整图库"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(OcrPlugin::class.java)
        super.onCreate(savedInstanceState)

        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, 0)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onPostResume() {
        super.onPostResume()
        requestGalleryPermissionIfNeeded()
    }

    private fun requestGalleryPermissionIfNeeded() {
        if (galleryPermissionRequestedThisLaunch || hasFullGalleryAccess()) return
        galleryPermissionRequestedThisLaunch = true
        galleryPermissionLauncher.launch(requestedGalleryPermissions())
    }

    private fun requestedGalleryPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else {
            arrayOf(requiredGalleryPermission())
        }

    private fun requiredGalleryPermission(): String = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun hasFullGalleryAccess(): Boolean = ContextCompat.checkSelfPermission(
        this,
        requiredGalleryPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasPartialGalleryAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
}
