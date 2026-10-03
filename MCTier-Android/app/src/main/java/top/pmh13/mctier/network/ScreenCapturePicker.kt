package top.pmh13.mctier.network

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/** Shared system picker: Android 14+ offers an app or the entire display. */
internal fun screenCaptureIntent(context: Context): Intent =
    (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()
