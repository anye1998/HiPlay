package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shilapi.xcertplay.hicar.HiCarWiredSettings
import com.shilapi.xcertplay.host.R

/**
 * The action the cable receiver launches the activity with.
 *
 * Its only job is to tell the activity that it was woken by a cable rather than by the driver, which
 * decides two things: the notification goes away, and the receiver starts with the wireless half off.
 */
internal const val ACTION_CABLE = "com.anye1998.hiplay.action.HICAR_CABLE"

/**
 * The app's only entry point that the driver does not press.
 *
 * A cable going into the head unit is the one moment a receiver is unambiguously wanted: the phone is
 * already in the car, and the driver has just said "use this". The receiver is declared in the
 * manifest with a device filter for Huawei's vendor id, so the system cold-starts this process for
 * that broadcast alone.
 *
 * It does not do the work itself. Everything the session needs — permissions, the Bluetooth round,
 * the USB permission dialog — has to happen with a window on screen, so this only gets the activity
 * up and lets it start the receiver. On Android 10 a broadcast receiver may not bring an activity to
 * the front; the start can be refused silently, which is why a full-screen notification is posted
 * first and cancelled by the activity once it is actually visible.
 */
class HiCarUsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (!HiCarWiredSettings.enabled(context)) {
            appendLog("cable: attached, but the wired path is switched off in settings")
            return
        }
        if (!HiCarWiredSettings.autoStart(context)) {
            appendLog("cable: attached; starting from the cable is switched off")
            return
        }
        appendLog("cable: attached; asking the receiver to come up")
        // Posted before the launch attempt: if the system refuses the launch there is nothing to
        // catch, and a notification the driver can tap is the only remaining way in.
        HiCarUsbAttachNotifier.post(context)
        val launch = Intent(context, HiCarHostActivity::class.java)
            .setAction(ACTION_CABLE)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        val refused = runCatching { context.startActivity(launch) }.exceptionOrNull()
        if (refused != null) {
            appendLog("cable: the system refused to open the screen (${refused.javaClass.simpleName}); the notification stays")
        }
    }

    /**
     * Logcat only: there is no window yet, so the screen's own log list does not exist.
     *
     * The activity writes the same tag once it is up, so `adb logcat -s HiCar` still shows a
     * continuous story of the plug-in.
     */
    private fun appendLog(line: String) {
        android.util.Log.i("HiCar", line)
    }
}

/**
 * The fallback that always works: a notification with a full-screen intent.
 *
 * The activity cancels it in `onCreate` when it was opened by the cable, so a launch that succeeded
 * leaves nothing behind; one that the system refused leaves something the driver can tap.
 */
internal object HiCarUsbAttachNotifier {
    private const val CHANNEL = "hicar-cable"
    private const val ID = 0x4843

    fun post(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("数据线连接")
                    .setDescription("插入数据线时启动 HiCar 接收端")
                    .build(),
            )
        }
        val open = Intent(context, HiCarHostActivity::class.java)
            .setAction(ACTION_CABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, ID, open, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_hicar)
            .setContentTitle("数据线已插入")
            .setContentText("点按用数据线连接手机")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}
