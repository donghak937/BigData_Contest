package kr.heureum.app.data

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import kr.heureum.app.core.*
import java.time.Instant

object UsageCollector {
    fun allowed(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.packageName) == AppOpsManager.MODE_ALLOWED
    fun read(context: Context, from: Long, until: Long): UsageSlice {
        if(!allowed(context))return UsageSlice(0,null,false)
        return try {
            val events=context.getSystemService(UsageStatsManager::class.java).queryEvents(from-86_400_000L,until)
                ?: return UsageSlice(0,null,false)
            var interactive=false;var foreground:String?=null;var last=from
            var screen=0L;val apps=mutableMapOf<String,Long>();val e=UsageEvents.Event()
            while(events.hasNextEvent()) {
                events.getNextEvent(e)
                val at=e.timeStamp.coerceIn(from,until)
                val elapsed=(at-last).coerceAtLeast(0)
                if(interactive)screen+=elapsed
                if(interactive && foreground!=null && foreground!=context.packageName)apps[foreground!!]=(apps[foreground!!]?:0)+elapsed
                if(e.timeStamp>=from)last=at
                when(e.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> interactive=true
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> { interactive=false;foreground=null }
                    UsageEvents.Event.ACTIVITY_RESUMED -> { foreground=e.packageName;interactive=true }
                    UsageEvents.Event.ACTIVITY_PAUSED -> if(foreground==e.packageName)foreground=null
                }
            }
            val tail=(until-last).coerceAtLeast(0)
            if(interactive)screen+=tail
            if(interactive && foreground!=null && foreground!=context.packageName)apps[foreground!!]=(apps[foreground!!]?:0)+tail
            UsageSlice(screen.coerceIn(0,(until-from).coerceAtLeast(0)),apps.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,true)
        } catch(_:SecurityException){UsageSlice(0,null,false)}
    }
}

data class CalendarChoice(val id: Long,val name: String)
object CalendarImporter {
    fun calendars(context:Context):List<CalendarChoice> {
        if(context.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return emptyList()
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),"${CalendarContract.Calendars.VISIBLE}=1",null,null)?.use { c ->
            buildList{while(c.moveToNext())add(CalendarChoice(c.getLong(0),c.getString(1)?:"캘린더"))}
        }?:emptyList()
    }
    fun courses(context:Context,calendarId:Long,from:Long,until:Long):List<Course> {
        require(until>from && until-from<=366*86_400_000L)
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().apply { android.content.ContentUris.appendId(this,from);android.content.ContentUris.appendId(this,until) }.build()
        return context.contentResolver.query(uri,arrayOf(CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END,CalendarContract.Instances.EVENT_LOCATION,CalendarContract.Instances.ALL_DAY),"${CalendarContract.Instances.CALENDAR_ID}=?",arrayOf(calendarId.toString()),"${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while(c.moveToNext()) {
                    if(c.getInt(4)==1)continue
                    val start=Instant.ofEpochMilli(c.getLong(1)).atZone(STUDY_ZONE);val end=Instant.ofEpochMilli(c.getLong(2)).atZone(STUDY_ZONE)
                    if(start.toLocalDate()!=end.toLocalDate())continue
                    val row=Course(title=c.getString(0)?:"수업",weekday=start.dayOfWeek.value,startMinute=start.hour*60+start.minute,endMinute=end.hour*60+end.minute,room=c.getString(3)?:"",validFrom=start.toLocalDate().toString(),validUntil=start.toLocalDate().toString(),source="calendar:$calendarId")
                    if(row.validate()==null)add(row)
                }
            }
        }?:emptyList()
    }
}
