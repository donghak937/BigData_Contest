package kr.heureum.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.heureum.app.core.*
import kr.heureum.app.data.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val now=System.currentTimeMillis()
    private lateinit var store:LocalStore
    @Before fun setup(){store=LocalStore(context,"payment-test.db");store.clearAll()}
    @After fun teardown(){store.clearAll();store.close();context.deleteDatabase("payment-test.db")}
    private fun parsed(kind:String="approval")=ParsedPayment("학생식당","8000",kind)
    @Test fun duplicateSourcesCancellationAndEmaStayIndependent(){
        assertTrue(store.addPayment(now,"test.card",parsed(),"f","k"))
        assertFalse(store.addPayment(now+10_000,"test.toss",parsed(),"f","k2"))
        val id=store.payments(0,now+20_000).single().id
        val s=Segment(now,now+60_000,"식사","medium","결제 근거",null,"학생식당",0,null,false,null,null,null,null,paymentId=id)
        store.saveSegment(s);store.correctSegment(now,"휴식")
        val prompt=store.createPrompt(now,now,"식사","test","uncertainty")
        assertTrue(store.addPayment(now+20_000,"test.card",parsed("cancel"),"f","kc"))
        assertFalse(store.payments(0,now+30_000).first{it.kind=="approval"}.usable)
        val segment=store.segments(now,now+60_000).single()
        assertEquals("활동 미확인",segment.activity);assertEquals("휴식",segment.reportedActivity)
        assertFalse(store.answerPrompt(prompt,"식사"))
    }
    @Test fun amountsBodiesIdentifiersNeverAppearInDefaultOrOptionalExport(){
        store.addPayment(now,"test.card",parsed(),"secret-match-hash","secret-key-hash")
        val default=JSONObject(store.export(false));assertFalse(default.has("payments"));assertFalse(default.getBoolean("paymentsIncluded"))
        val optional=store.paymentExport().toString()
        assertTrue(optional.contains("학생식당"));assertFalse(optional.contains("8000"));assertFalse(optional.contains("secret"));assertFalse(optional.contains("amount"));assertFalse(optional.contains("notificationKey"))
    }
    @Test fun retentionAndPaymentOnlyDeletion(){
        store.addPayment(now-31*86_400_000L,"test.card",parsed(),"old","old")
        store.addPayment(now,"test.card",parsed(),"new","new")
        store.saveCourse(Course(title="통계학",weekday=1,startMinute=600,endMinute=660,validFrom="2026-01-01",validUntil="2026-12-31"))
        store.maintenance(now);assertEquals(1,store.payments(0,now+1).size)
        store.clearPayments();assertTrue(store.payments(0,now+1).isEmpty());assertEquals(1,store.courses().size)
    }
    @Test fun upgradeFromVersionOneKeepsCoursesAndAddsPaymentStorage(){
        val name="payment-migration.db";context.deleteDatabase(name)
        context.openOrCreateDatabase(name,0,null).use{db->
            db.execSQL("CREATE TABLE courses(id INTEGER PRIMARY KEY,title TEXT,weekday INTEGER,startMinute INTEGER,endMinute INTEGER,room TEXT,validFrom TEXT,validUntil TEXT,source TEXT)")
            db.execSQL("INSERT INTO courses VALUES(1,'통계학',1,600,660,'','2026-01-01','2026-12-31','manual')")
            db.execSQL("CREATE TABLE segments(start INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE geo(measuredAt INTEGER PRIMARY KEY,latitude REAL,longitude REAL,accuracyM REAL)")
            db.version=1
        }
        LocalStore(context,name).use{upgraded->
            assertEquals("통계학",upgraded.courses().single().title)
            assertTrue(upgraded.addPayment(now,"test.card",parsed(),"f","k"))
            upgraded.addGeo(GeoSample(37.0,127.0,5f,now,now));assertEquals(now,upgraded.geoSince(0).single().sessionStart)
        };context.deleteDatabase(name)
    }
    @Test fun gatesRejectBeforeReadingNotificationContent(){
        val preferences=Preferences(context);val before=preferences.paymentEnabled
        synchronized(PaymentCollection.lock){preferences.paymentEnabled=false}
        try { assertFalse(PaymentCollection.accept(context,"test.card","key",now){fail("disabled collector read text");"" to ""}) }
        finally{preferences.paymentEnabled=before}
    }
    @Test fun actualNotificationListenerCollectsOnlyWhileRecording(){
        Assume.assumeTrue("Grant notification listener access on the dedicated emulator",PaymentCollection.allowed(context))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs=Preferences(context);val live=LocalStore(context)
        val nm=context.getSystemService(NotificationManager::class.java)
        val channel="synthetic-payment-test"
        nm.createNotificationChannel(NotificationChannel(channel,"Synthetic payment test",NotificationManager.IMPORTANCE_LOW))
        try {
            synchronized(PaymentCollection.lock){prefs.paymentEnabled=true;prefs.paymentSources=setOf(context.packageName);prefs.paymentSince=System.currentTimeMillis();live.clearPayments()}
            instrumentation.runOnMainSync{context.startForegroundService(Intent(context,TrackingService::class.java))}
            val deadline=System.currentTimeMillis()+5000
            while(!TrackingService.isRunning && System.currentTimeMillis()<deadline)Thread.sleep(50)
            assertTrue(TrackingService.isRunning)
            fun post(id:Int,merchant:String){nm.notify(id,Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("카드 승인").setContentText("가맹점: $merchant\n8,000원").build())}
            android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(context,PaymentNotificationService::class.java))
            val binding=System.currentTimeMillis()+40_000
            while(!PaymentNotificationService.connected && System.currentTimeMillis()<binding)Thread.sleep(50)
            assertTrue("Listener must connect before a new notification is posted",PaymentNotificationService.connected)
            post(801,"학생식당")
            val collected=System.currentTimeMillis()+5000
            while(live.payments(0,System.currentTimeMillis()+1).isEmpty() && System.currentTimeMillis()<collected)Thread.sleep(50)
            assertEquals("학생식당",live.payments(0,System.currentTimeMillis()+1).single().merchant)
            post(801,"학생식당");Thread.sleep(300);assertEquals(1,live.payments(0,System.currentTimeMillis()+1).size)
            synchronized(PaymentCollection.lock){prefs.paymentSources=emptySet()};post(802,"GS25 테스트점");Thread.sleep(300)
            assertEquals(1,live.payments(0,System.currentTimeMillis()+1).size)
            synchronized(PaymentCollection.lock){prefs.paymentSources=setOf(context.packageName);prefs.paymentEnabled=false};post(803,"CU 테스트점");Thread.sleep(300)
            assertEquals(1,live.payments(0,System.currentTimeMillis()+1).size)
            synchronized(PaymentCollection.lock){prefs.paymentEnabled=true;prefs.tracking=false};post(804,"이마트24 테스트점");Thread.sleep(300)
            assertEquals(1,live.payments(0,System.currentTimeMillis()+1).size)
        } finally {
            synchronized(PaymentCollection.lock){prefs.paymentEnabled=false;prefs.paymentSources=emptySet();prefs.tracking=false;live.clearPayments()}
            context.stopService(Intent(context,TrackingService::class.java));(801..804).forEach{nm.cancel(it)};nm.deleteNotificationChannel(channel);live.close()
            instrumentation.runOnMainSync{activity.finish()}
        }
    }
}
