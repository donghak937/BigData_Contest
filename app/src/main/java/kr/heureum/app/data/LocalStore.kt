package kr.heureum.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.heureum.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

class LocalStore(context: Context, databaseName: String = "heureum.db") : SQLiteOpenHelper(context.applicationContext ?: context, databaseName, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.rawQuery("PRAGMA secure_delete=ON",null).use { it.moveToFirst() } }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE courses(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,weekday INTEGER NOT NULL,startMinute INTEGER NOT NULL,endMinute INTEGER NOT NULL,room TEXT NOT NULL,validFrom TEXT NOT NULL,validUntil TEXT NOT NULL,source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE geo(measuredAt INTEGER PRIMARY KEY,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracyM REAL NOT NULL)")
        db.execSQL("CREATE TABLE segments(start INTEGER PRIMARY KEY,end INTEGER NOT NULL,observedFrom INTEGER NOT NULL,activity TEXT NOT NULL,confidence TEXT NOT NULL,reason TEXT NOT NULL,course TEXT,place TEXT NOT NULL,screenMs INTEGER NOT NULL,topPackage TEXT,usageAvailable INTEGER NOT NULL,latitude REAL,longitude REAL,accuracyM REAL,locationTime INTEGER,reportedActivity TEXT,reportedAt INTEGER,verification TEXT NOT NULL DEFAULT 'estimated')")
        db.execSQL("CREATE TABLE prompts(id INTEGER PRIMARY KEY AUTOINCREMENT,segmentStart INTEGER NOT NULL UNIQUE,createdAt INTEGER NOT NULL,suggested TEXT NOT NULL,reason TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',answeredAt INTEGER,response TEXT)")
        db.execSQL("CREATE INDEX prompts_time ON prompts(createdAt)")
        createPayments(db)
    }
    private fun createPayments(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE segments ADD COLUMN paymentId INTEGER")
        db.execSQL("ALTER TABLE geo ADD COLUMN sessionStart INTEGER")
        db.execSQL("CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT,observedAt INTEGER NOT NULL,source TEXT NOT NULL,merchant TEXT NOT NULL,category TEXT NOT NULL,kind TEXT NOT NULL,usable INTEGER NOT NULL,fingerprint TEXT NOT NULL,notificationKey TEXT NOT NULL)")
        db.execSQL("CREATE INDEX payments_time ON payments(observedAt)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<2)createPayments(db) }

    @Synchronized fun addPayment(at:Long,source:String,p:ParsedPayment,fingerprint:String,key:String):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            // Same approval from two selected apps, or notification updates, count once.
            val exists=db.rawQuery("SELECT id FROM payments WHERE kind=? AND ((fingerprint=? AND ABS(observedAt-?)<=90000) OR (notificationKey=? AND fingerprint=? AND ABS(observedAt-?)<=300000)) LIMIT 1",arrayOf(p.kind,fingerprint,at.toString(),key,fingerprint,at.toString())).use{it.moveToFirst()}
            if(exists){db.setTransactionSuccessful();return false}
            val cancellation=db.rawQuery("SELECT id FROM payments WHERE fingerprint=? AND kind='cancel' AND ABS(observedAt-?)<=86400000 LIMIT 1",arrayOf(fingerprint,at.toString())).use{it.moveToFirst()}
            db.insertOrThrow("payments",null,ContentValues().apply{
                put("observedAt",at);put("source",source);put("merchant",p.merchant);put("category",PaymentParser.category(p.merchant));put("kind",p.kind)
                put("usable",if(p.kind=="approval" && !cancellation)1 else 0);put("fingerprint",fingerprint);put("notificationKey",key)
            })
            if(p.kind=="cancel"){
                db.update("payments",ContentValues().apply{put("usable",0)},"fingerprint=? AND kind='approval' AND ABS(observedAt-?)<=86400000",arrayOf(fingerprint,at.toString()))
                db.execSQL("UPDATE segments SET activity='활동 미확인',confidence='low',reason='결제 근거와 일치할 수 있는 취소 알림이 있어 식사 추정을 철회했어요.' WHERE paymentId IN (SELECT id FROM payments WHERE usable=0)")
                db.execSQL("UPDATE prompts SET status='expired',reason='결제 취소로 추정이 철회됐어요.' WHERE status='pending' AND segmentStart IN (SELECT start FROM segments WHERE paymentId IN (SELECT id FROM payments WHERE usable=0))")
            }
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    @Synchronized fun payments(from:Long,until:Long):List<PaymentEvent> = readableDatabase.rawQuery("SELECT * FROM payments WHERE observedAt>=? AND observedAt<? ORDER BY observedAt DESC",arrayOf(from.toString(),until.toString())).use{c->
        buildList{while(c.moveToNext())add(PaymentEvent(c.l("id"),c.l("observedAt"),c.s("source"),c.s("merchant"),c.s("category"),c.s("kind"),c.i("usable")==1))}
    }
    @Synchronized fun clearPayments(){writableDatabase.delete("payments",null,null)}
    @Synchronized fun paymentExport(places:List<MapPlace> = emptyList()):JSONArray {
        val rows=JSONArray();val samples=geoSince(0)
        payments(0,System.currentTimeMillis()+1).forEach{e->
            val o=PaymentInference.observation(e,samples,places)
            rows.put(JSONObject().put("id",e.id).put("observedAt",e.observedAt).put("timeMeaning","notification posted time; not verified transaction time")
                .put("source",e.source).put("merchant",e.merchant).put("category",e.category).put("kind",e.kind).put("usable",e.usable)
                .put("activityCandidate",o.activity).put("reason",o.reason).put("place",o.place?:JSONObject.NULL)
                .put("observedFrom",o.from?:JSONObject.NULL).put("observedUntil",o.until?:JSONObject.NULL).put("observedMinutes",o.observedMinutes?:JSONObject.NULL)
                .put("placeInterpretation","computed from current map cache; observed span, not actual eating duration"))
        };return rows
    }

    @Synchronized fun courses(): List<Course> = readableDatabase.rawQuery("SELECT * FROM courses ORDER BY weekday,startMinute", null).use { c ->
        buildList { while (c.moveToNext()) add(Course(c.l("id"),c.s("title"),c.i("weekday"),c.i("startMinute"),c.i("endMinute"),c.s("room"),c.s("validFrom"),c.s("validUntil"),c.s("source"))) }
    }
    @Synchronized fun saveCourse(course: Course) {
        require(course.validate() == null) { course.validate()!! }
        val v = ContentValues().apply {
            put("title",course.title); put("weekday",course.weekday); put("startMinute",course.startMinute); put("endMinute",course.endMinute)
            put("room",course.room); put("validFrom",course.validFrom); put("validUntil",course.validUntil); put("source",course.source)
        }
        if (course.id == 0L) writableDatabase.insertOrThrow("courses", null, v) else writableDatabase.update("courses",v,"id=?", arrayOf(course.id.toString()))
    }
    @Synchronized fun replaceCourses(source: String, rows: List<Course>) {
        require(rows.all { it.validate() == null && it.source == source })
        val db = writableDatabase; db.beginTransaction()
        try { db.delete("courses", "source=?", arrayOf(source)); rows.forEach(::saveCourse); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    @Synchronized fun deleteCourse(id: Long) { writableDatabase.delete("courses", "id=?", arrayOf(id.toString())) }
    @Synchronized fun addGeo(fix: GeoSample) {
        val v = ContentValues().apply { put("measuredAt",fix.measuredAt); put("latitude",fix.latitude); put("longitude",fix.longitude); put("accuracyM",fix.accuracyM);put("sessionStart",fix.sessionStart) }
        writableDatabase.insertWithOnConflict("geo",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun geoSince(since: Long): List<GeoSample> = readableDatabase.rawQuery("SELECT * FROM geo WHERE measuredAt>=? ORDER BY measuredAt", arrayOf(since.toString())).use { c ->
        buildList { while (c.moveToNext()) add(GeoSample(c.d("latitude"),c.d("longitude"),c.f("accuracyM"),c.l("measuredAt"),c.nl("sessionStart"))) }
    }
    @Synchronized fun saveSegment(s: Segment) {
        // Update measurements without destroying independent EMA responses.
        val v = ContentValues().apply {
            put("start",s.start); put("end",s.end); put("observedFrom",s.observedFrom); put("activity",s.activity); put("confidence",s.confidence); put("reason",s.reason)
            put("course",s.course); put("place",s.place); put("screenMs",s.screenMs); put("topPackage",s.topPackage); put("usageAvailable",if(s.usageAvailable)1 else 0)
            put("latitude",s.latitude); put("longitude",s.longitude); put("accuracyM",s.accuracyM); put("locationTime",s.locationTime)
            put("paymentId",s.paymentId)
        }
        if (writableDatabase.update("segments",v,"start=?", arrayOf(s.start.toString())) == 0) writableDatabase.insertOrThrow("segments",null,v)
    }
    @Synchronized fun segments(from: Long, until: Long): List<Segment> = readableDatabase.rawQuery("SELECT * FROM segments WHERE start>=? AND start<? ORDER BY start DESC",arrayOf(from.toString(),until.toString())).use { c ->
        buildList { while(c.moveToNext()) add(Segment(c.l("start"),c.l("end"),c.s("activity"),c.s("confidence"),c.s("reason"),c.ns("course"),c.s("place"),c.l("screenMs"),c.ns("topPackage"),c.i("usageAvailable")==1,c.nd("latitude"),c.nd("longitude"),c.nf("accuracyM"),c.nl("locationTime"),c.ns("reportedActivity"),c.s("verification"),c.l("observedFrom"),c.nl("paymentId"))) }
    }
    @Synchronized fun correctSegment(start: Long, activity: String) {
        writableDatabase.update("segments",ContentValues().apply { put("reportedActivity",activity); put("reportedAt",System.currentTimeMillis()); put("verification","corrected") },"start=?",arrayOf(start.toString()))
        writableDatabase.update("prompts",ContentValues().apply { put("status","answered"); put("answeredAt",System.currentTimeMillis()); put("response",activity) },"segmentStart=? AND status='pending'",arrayOf(start.toString()))
    }
    @Synchronized fun prompts(): List<Prompt> = readableDatabase.rawQuery("SELECT * FROM prompts ORDER BY createdAt DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(Prompt(c.l("id"),c.l("segmentStart"),c.l("createdAt"),c.s("suggested"),c.s("reason"),c.s("kind"),c.s("status"))) }
    }
    @Synchronized fun createPrompt(start: Long, now: Long, suggested: String, reason: String, kind: String): Long {
        return writableDatabase.insertWithOnConflict("prompts",null,ContentValues().apply {
            put("segmentStart",start); put("createdAt",now); put("suggested",suggested); put("reason",reason); put("kind",kind); put("status","pending")
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun answerPrompt(id: Long, response: String?, skipped: Boolean = false): Boolean {
        val p = prompts().firstOrNull { it.id == id && it.status == "pending" } ?: return false
        val db = writableDatabase; db.beginTransaction()
        try {
            db.update("prompts",ContentValues().apply { put("status",if(skipped)"skipped" else "answered");put("answeredAt",System.currentTimeMillis());put("response",response) },"id=?",arrayOf(id.toString()))
            if (!skipped && !response.isNullOrBlank()) db.update("segments",ContentValues().apply {
                put("reportedActivity",response);put("reportedAt",System.currentTimeMillis());put("verification",if(response==p.suggested)"confirmed" else "corrected")
            },"start=?",arrayOf(p.segmentStart.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }
    @Synchronized fun maintenance(now: Long) {
        writableDatabase.execSQL("UPDATE prompts SET status='expired' WHERE status='pending' AND createdAt<?",arrayOf(now-2*3_600_000L))
        val cutoff = now - 30 * 86_400_000L
        for (table in listOf("segments","geo","prompts","payments")) {
            val col = when(table) { "segments" -> "start"; "geo" -> "measuredAt"; "payments" -> "observedAt"; else -> "createdAt" }
            writableDatabase.delete(table,"$col<?",arrayOf(cutoff.toString()))
        }
    }
    @Synchronized fun clearAll() {
        val db=writableDatabase;db.beginTransaction()
        try { listOf("courses","geo","segments","prompts","payments").forEach { db.delete(it,null,null) };db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    @Synchronized fun export(includeCoordinates: Boolean): String {
        val root=JSONObject().put("schemaVersion",3).put("paymentsIncluded",false).put("exportedAt",Instant.now().toString()).put("studyTimezone",STUDY_ZONE.id)
            .put("retentionDays",30).put("coordinatesIncluded",includeCoordinates)
        for(table in listOf("courses","segments","prompts") + if(includeCoordinates)listOf("geo") else emptyList()) {
            val rows=JSONArray()
            readableDatabase.rawQuery("SELECT * FROM $table",null).use { c -> while(c.moveToNext()) {
                val obj=JSONObject()
                for(i in 0 until c.columnCount) {
                    val name=c.getColumnName(i)
                    if(!includeCoordinates && name in listOf("latitude","longitude","accuracyM","locationTime"))continue
                    obj.put(name,when(c.getType(i)) { Cursor.FIELD_TYPE_NULL -> JSONObject.NULL;Cursor.FIELD_TYPE_INTEGER -> c.getLong(i);Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i);else -> c.getString(i) })
                }
                rows.put(obj)
            } }
            root.put(table,rows)
        }
        return root.toString(2)
    }
    private fun Cursor.s(n:String)=getString(getColumnIndexOrThrow(n))
    private fun Cursor.i(n:String)=getInt(getColumnIndexOrThrow(n))
    private fun Cursor.l(n:String)=getLong(getColumnIndexOrThrow(n))
    private fun Cursor.d(n:String)=getDouble(getColumnIndexOrThrow(n))
    private fun Cursor.f(n:String)=getFloat(getColumnIndexOrThrow(n))
    private fun Cursor.ns(n:String):String?=if(isNull(getColumnIndexOrThrow(n)))null else s(n)
    private fun Cursor.nd(n:String):Double?=if(isNull(getColumnIndexOrThrow(n)))null else d(n)
    private fun Cursor.nf(n:String):Float?=if(isNull(getColumnIndexOrThrow(n)))null else f(n)
    private fun Cursor.nl(n:String):Long?=if(isNull(getColumnIndexOrThrow(n)))null else l(n)
}

class Preferences(context: Context, name: String = "settings") {
    private val p=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    var tracking: Boolean get()=p.getBoolean("tracking",false);set(v){p.edit().putBoolean("tracking",v).apply()}
    var trackingSince: Long get()=p.getLong("trackingSince",Long.MAX_VALUE);set(v){p.edit().putLong("trackingSince",v).apply()}
    var paymentEnabled: Boolean get()=p.getBoolean("paymentEnabled",false);set(v){p.edit().putBoolean("paymentEnabled",v).apply()}
    var paymentSince: Long get()=p.getLong("paymentSince",Long.MAX_VALUE);set(v){p.edit().putLong("paymentSince",v).apply()}
    var paymentSources: Set<String> get()=p.getStringSet("paymentSources",emptySet())!!.toSet();set(v){p.edit().putStringSet("paymentSources",v.toSet()).apply()}
    var paymentSalt: String get()=p.getString("paymentSalt",null)?:java.util.UUID.randomUUID().toString().also{p.edit().putString("paymentSalt",it).apply()};set(v){p.edit().putString("paymentSalt",v).apply()}
    var maxPrompts: Int get()=p.getInt("maxPrompts",3);set(v){p.edit().putInt("maxPrompts",v.coerceIn(0,6)).apply()}
    var gapMinutes: Int get()=p.getInt("gapMinutes",90);set(v){p.edit().putInt("gapMinutes",v.coerceIn(30,240)).apply()}
    var validation: Boolean get()=p.getBoolean("validation",false);set(v){p.edit().putBoolean("validation",v).apply()}
    var automaticPlaces: Boolean get()=p.getBoolean("automaticPlaces",false);set(v){p.edit().putBoolean("automaticPlaces",v).apply()}
    var placeConsentSeen: Boolean get()=p.getBoolean("placeConsentSeen",false);set(v){p.edit().putBoolean("placeConsentSeen",v).apply()}
    var lastPlaceLookup: Long get()=p.getLong("lastPlaceLookup",0);set(v){p.edit().putLong("lastPlaceLookup",v).apply()}
    var lastKakaoLookup: Long get()=p.getLong("lastKakaoLookup",0);set(v){p.edit().putLong("lastKakaoLookup",v).apply()}
    /** Participant's own building fixes, keyed by map place id. Kept on device only. */
    fun placeCorrections():Map<String,PlaceCorrection> = runCatching {
        val rows=JSONArray(p.getString("placeCorrections","[]"))
        (0 until rows.length()).map { i -> val r=rows.getJSONObject(i);PlaceCorrection(r.getString("id"),r.optString("name").takeIf{it.isNotBlank()},r.optString("kind").takeIf{it.isNotBlank()}) }.associateBy { it.placeId }
    }.getOrDefault(emptyMap())
    fun savePlaceCorrection(c:PlaceCorrection){writePlaceCorrections(placeCorrections()+(c.placeId to c))}
    fun deletePlaceCorrection(id:String){writePlaceCorrections(placeCorrections()-id)}
    private fun writePlaceCorrections(all:Map<String,PlaceCorrection>){p.edit().putString("placeCorrections",JSONArray().apply{all.values.forEach{c->put(JSONObject().put("id",c.placeId).put("name",c.name?:"").put("kind",c.kind?:""))}}.toString()).apply()}
    var placeLookupStatus: String get()=p.getString("placeLookupStatus","위치가 잡히면 주변 장소를 자동으로 찾아요.")!!;set(v){p.edit().putString("placeLookupStatus",v).apply()}
    var semesterFrom: String get()=p.getString("semesterFrom",LocalDate.now(STUDY_ZONE).toString())!!;set(v){p.edit().putString("semesterFrom",v).apply()}
    var semesterUntil: String get()=p.getString("semesterUntil",LocalDate.now(STUDY_ZONE).plusMonths(4).toString())!!;set(v){p.edit().putString("semesterUntil",v).apply()}
    fun campus():Campus?=if(!p.contains("lat"))null else Campus(p.getString("campusName","학교")!!,p.getString("lat","0")!!.toDouble(),p.getString("lon","0")!!.toDouble(),p.getFloat("radius",400f))
    fun saveCampus(c:Campus){p.edit().putString("campusName",c.name).putString("lat",c.latitude.toString()).putString("lon",c.longitude.toString()).putFloat("radius",c.radiusM).apply()}
    fun zones():List<PlaceZone> = runCatching {
        val rows=JSONArray(p.getString("zones","[]"))
        (0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i);val keys=row.optJSONArray("roomKeys")?:JSONArray()
            PlaceZone(row.getString("id"),row.getString("name"),row.getString("kind"),row.getDouble("latitude"),row.getDouble("longitude"),row.getDouble("radiusM").toFloat(),(0 until keys.length()).map { keys.getString(it) })
        }.filter { it.valid() }
    }.getOrDefault(emptyList())
    fun saveZone(zone:PlaceZone){require(zone.valid());writeZones(zones().filterNot { it.id==zone.id }+zone)}
    fun deleteZone(id:String){writeZones(zones().filterNot { it.id==id })}
    private fun writeZones(zones:List<PlaceZone>){p.edit().putString("zones",zoneJson(zones,true).toString()).apply()}
    fun placeSettingsJson(includeCoordinates:Boolean):JSONObject {
        val root=JSONObject().put("zones",zoneJson(zones(),includeCoordinates)).put("automaticPlacesEnabled",automaticPlaces).put("automaticPlaceSource","OpenStreetMap via Overpass (+ Kakao Local when configured); local cache up to 7 days")
            .put("placeCorrections",JSONArray().apply{placeCorrections().values.forEach{c->put(JSONObject().put("id",c.placeId).put("name",c.name?:JSONObject.NULL).put("kind",c.kind?:JSONObject.NULL))}})
        campus()?.let { c ->
            val row=JSONObject().put("name",c.name).put("radiusM",c.radiusM.toDouble())
            if(includeCoordinates)row.put("latitude",c.latitude).put("longitude",c.longitude)
            root.put("campus",row)
        }
        return root
    }
    private fun zoneJson(zones:List<PlaceZone>,includeCoordinates:Boolean)=JSONArray().apply{zones.forEach { z ->
        val row=JSONObject().put("id",z.id).put("name",z.name).put("kind",z.kind).put("radiusM",z.radiusM.toDouble()).put("roomKeys",JSONArray(z.roomKeys))
        if(includeCoordinates)row.put("latitude",z.latitude).put("longitude",z.longitude)
        put(row)
    }}
    fun clear(){p.edit().clear().apply()}
}
