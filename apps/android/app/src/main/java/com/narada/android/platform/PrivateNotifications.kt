package com.narada.android.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.narada.android.MainActivity
import com.narada.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivateNotifications @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notification_channel_description)
        channel.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    fun runtimePermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun canPost(): Boolean = runtimePermissionGranted() && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun postGeneric(): Boolean {
        // Keep the permission check next to notify; the worker NEVER launches a prompt.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!canPost()) return false
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_LOCAL_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_generic))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setLocalOnly(true)
            .setAutoCancel(true).setContentIntent(pending).build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true // Accepted by Android; not a delivery/display receipt.
    }
    /** No sender, text, identifiers, ciphertext, reply action or private PendingIntent extras. */
    fun postChat(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!canPost()) return false
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_PRIVATE_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.app_name))
            .setContentText("Private message activity. Open NARADA to view.")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setLocalOnly(true).setAutoCancel(true).setContentIntent(pending).build()
        NotificationManagerCompat.from(context).notify(CHAT_NOTIFICATION_ID, notification)
        return true // OS submission, never a display/read receipt.
    }
    companion object { const val CHAT_NOTIFICATION_ID = 1002; const val CHANNEL = "narada_local_activity_v1"; const val NOTIFICATION_ID = 1001 }
}
