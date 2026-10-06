package kr.heureum.app

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kr.heureum.app.core.PaymentParser
import kr.heureum.app.data.LocalStore
import kr.heureum.app.data.Preferences
import java.security.MessageDigest

/** No network, notification history, SMS, financial login, or raw-text storage. */
class PaymentNotificationService:NotificationListenerService() {
    override fun onListenerConnected(){super.onListenerConnected();connected=true;sendBroadcast(Intent(TrackingService.ACTION_UPDATED).setPackage(packageName))}
    override fun onListenerDisconnected(){connected=false;super.onListenerDisconnected();sendBroadcast(Intent(TrackingService.ACTION_UPDATED).setPackage(packageName))}
    override fun onDestroy(){connected=false;super.onDestroy()}
    companion object { @Volatile var connected=false;private set }
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        // Check gates before reading any notification text.
        PaymentCollection.accept(this,sbn.packageName,sbn.key,sbn.postTime){
            val extras=sbn.notification.extras
            val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body=extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?:extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            title to body
        }
    }
    // Removal/dismissal does not imply a payment cancellation.
}

object PaymentCollection {
    val lock=Any()
    fun allowed(c:Context)=NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    fun accept(c:Context,source:String,key:String,postedAt:Long,content:()->Pair<String,String>):Boolean = synchronized(lock) {
        val prefs=Preferences(c);val now=System.currentTimeMillis()
        if(!prefs.paymentEnabled || !prefs.tracking || !TrackingService.isRunning || !allowed(c) || source !in prefs.paymentSources || postedAt<maxOf(prefs.paymentSince,prefs.trackingSince) || now-postedAt !in 0..120_000)return false
        val (title,body)=content()
        val payment=PaymentParser.parse(title,body)?:return false
        fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest((prefs.paymentSalt+value).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        val fingerprint=hash(payment.merchant.replace(Regex("\\s+"),"").lowercase(java.util.Locale.ROOT)+"|"+payment.amount)
        val store=LocalStore(c)
        try {
            store.maintenance(now)
            val inserted=store.addPayment(postedAt,source,payment,fingerprint,hash(source+key))
            if(inserted && payment.kind=="cancel")store.prompts().filter{it.status=="expired"}.forEach{c.getSystemService(android.app.NotificationManager::class.java).cancel(1000+it.id.toInt())}
            if(inserted)c.sendBroadcast(Intent(TrackingService.ACTION_UPDATED).setPackage(c.packageName))
            inserted
        } finally {store.close()}
    }
}
