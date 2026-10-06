package kr.heureum.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.net.Uri
import android.os.*
import kr.heureum.app.core.*
import kr.heureum.app.data.*
import java.time.Instant

class TrackingService:Service(),LocationListener {
    private lateinit var store:LocalStore
    private lateinit var prefs:Preferences
    private lateinit var locations:LocationManager
    private val handler=Handler(Looper.getMainLooper())
    private var running=false
    private var beganAt=0L
    private val tick=object:Runnable { override fun run(){if(!running)return;runCatching{capture()}.onFailure{prefs.tracking=false;stopSelf()};handler.postDelayed(this,60_000)} }
    override fun onCreate(){super.onCreate();store=LocalStore(this);prefs=Preferences(this);locations=getSystemService(LocationManager::class.java);ensureChannels(this)}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP"){prefs.tracking=false;stopSelf();return START_NOT_STICKY}
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){prefs.tracking=false;stopSelf();return START_NOT_STICKY}
        val n=Notification.Builder(this,TRACKING_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("흐름 · 기록 중")
            .setContentText("위치와 앱 사용으로 활동을 추정해요. 언제든 중지할 수 있어요.").setOngoing(true)
            .setContentIntent(openApp(this)).addAction(Notification.Action.Builder(null,"수집 중지",PendingIntent.getService(this,1,Intent(this,TrackingService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()).build()
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)else startForeground(1,n)
            if(!running){
                running=true;isRunning=true;beganAt=System.currentTimeMillis();prefs.trackingSince=beganAt;prefs.tracking=true
                for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
                    if(!locations.isProviderEnabled(provider))continue
                    try { locations.requestLocationUpdates(provider,120_000,0f,this,Looper.getMainLooper());locations.getLastKnownLocation(provider)?.let(::onLocationChanged) }catch(_:SecurityException){}catch(_:IllegalArgumentException){}
                }
                handler.post(tick)
            }
        }catch(_:SecurityException){prefs.tracking=false;stopSelf()}
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location:Location) {
        if(!running || !prefs.tracking)return
        if(location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy>=0 && System.currentTimeMillis()-location.time in 0..600_000)
            store.addGeo(GeoSample(location.latitude,location.longitude,location.accuracy,location.time,beganAt))
    }
    private fun capture() {
        if(!prefs.tracking){stopSelf();return}
        val now=System.currentTimeMillis();store.maintenance(now)
        val start=now/BUCKET_MS*BUCKET_MS
        val samples=store.geoSince(now-600_000).filter{it.measuredAt<=now}
        samples.maxByOrNull{it.measuredAt}?.let { AutomaticPlaces.refresh(this,it) }
        val mapPlaces=AutomaticPlaces.places(this)
        val usage=UsageCollector.read(this,maxOf(start,beganAt),now)
        val device=DeviceContext(usage.screenMs,now-maxOf(start,beganAt),AppCategory.of(usage.topPackage,androidCategory(usage.topPackage)),usage.available)
        val base=InferenceEngine.predict(now,store.courses(),prefs.campus(),samples,prefs.zones(),mapPlaces,device)
        val p=if(prefs.paymentEnabled && PaymentCollection.allowed(this))PaymentInference.prediction(base,now,store.payments(maxOf(prefs.trackingSince,now-30*60_000),now+1),store.geoSince(prefs.trackingSince.coerceAtLeast(now-3*3_600_000)),mapPlaces) else base
        val fix=samples.maxByOrNull{it.measuredAt}
        store.saveSegment(Segment(start,now,p.activity,p.confidence,p.reason,p.course,p.place,usage.screenMs,usage.topPackage,usage.available,fix?.latitude,fix?.longitude,fix?.accuracyM,fix?.measuredAt,observedFrom=maxOf(start,beganAt),paymentId=p.paymentId))
        sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
        // Wait for a meaningful observed interval; never backfill unobserved time as fact.
        if(now-maxOf(start,beganAt)<180_000)return
        val prompts=store.prompts()
        if(prompts.any{it.segmentStart==start})return
        val today=Instant.ofEpochMilli(now).atZone(STUDY_ZONE).toLocalDate()
        val todays=prompts.filter{Instant.ofEpochMilli(it.createdAt).atZone(STUDY_ZONE).toLocalDate()==today}
        val policy=EmaPolicy(prefs.maxPrompts,prefs.gapMinutes)
        if(!policy.canAsk(now,todays.size,prompts.maxOfOrNull{it.createdAt},prompts.any{it.status=="pending"}))return
        val validation=prefs.validation && !p.needsEma && p.confidence=="high" && todays.none{it.kind=="validation"} &&
            ((start/BUCKET_MS+today.toEpochDay())%6==0L)
        if(!p.needsEma && !validation)return
        val id=store.createPrompt(start,now,p.activity,p.reason,if(validation)"validation" else "uncertainty")
        if(id>0){notifyPrompt(this,store.prompts().first{it.id==id});sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))}
    }
    /** ApplicationInfo.category of a launcher app (visible via the manifest's launcher <queries>), or -1. */
    private fun androidCategory(pkg:String?):Int=if(pkg==null)-1 else runCatching{packageManager.getApplicationInfo(pkg,0).category}.getOrDefault(-1)
    override fun onProviderEnabled(provider:String)=Unit
    override fun onProviderDisabled(provider:String)=Unit
    @Deprecated("Legacy location callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?)=Unit
    override fun onDestroy(){running=false;isRunning=false;prefs.tracking=false;handler.removeCallbacksAndMessages(null);runCatching{locations.removeUpdates(this)};store.close();super.onDestroy()}
    companion object {
        var isRunning=false
            private set
        const val ACTION_UPDATED="kr.heureum.app.UPDATED"
        const val TRACKING_CHANNEL="tracking"
        const val EMA_CHANNEL="ema"
        fun ensureChannels(c:Context){c.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(TRACKING_CHANNEL,"활동 수집 상태",NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(EMA_CHANNEL,"짧은 활동 확인",NotificationManager.IMPORTANCE_DEFAULT)
        ))}
        fun openApp(c:Context)=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notifyPrompt(c:Context,p:Prompt){
            if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
            val open=PendingIntent.getActivity(c,p.id.toInt(),Intent(c,MainActivity::class.java).putExtra("emaId",p.id).setData(Uri.parse("heureum://ema/${p.id}")),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n=Notification.Builder(c,EMA_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(if(p.suggested=="활동 미확인")"방금 어떤 활동을 했나요?" else "방금 ${p.suggested} 중이었나요?")
                .setContentText("한 번의 선택으로 기록을 확인해 주세요.").setContentIntent(open).setAutoCancel(true)
                .setDeleteIntent(action(c,p,"skip"))
            if(p.suggested!="활동 미확인")n.addAction(Notification.Action.Builder(null,"맞아요",action(c,p,"confirm")).build())
            n.addAction(Notification.Action.Builder(null,"건너뛰기",action(c,p,"skip")).build())
            c.getSystemService(NotificationManager::class.java).notify(1000+p.id.toInt(),n.build())
        }
        private fun action(c:Context,p:Prompt,a:String)=PendingIntent.getBroadcast(c,p.id.toInt(),Intent(c,EmaActionReceiver::class.java).setAction(a).setData(Uri.parse("heureum://ema/${p.id}/$a")).putExtra("id",p.id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

class EmaActionReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val store=LocalStore(context);store.maintenance(System.currentTimeMillis())
        val p=store.prompts().firstOrNull{it.id==intent.getLongExtra("id",-1)}
        if(p!=null){
            store.answerPrompt(p.id,if(intent.action=="confirm")p.suggested else null,intent.action!="confirm")
            context.getSystemService(NotificationManager::class.java).cancel(1000+p.id.toInt())
            context.sendBroadcast(Intent(TrackingService.ACTION_UPDATED).setPackage(context.packageName))
        }
        store.close()
    }
}
