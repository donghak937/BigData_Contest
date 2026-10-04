package kr.heureum.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import kr.heureum.app.core.*
import kr.heureum.app.data.*
import kr.heureum.app.ui.CampusMapView
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity:Activity(){
    private val ink=Color.rgb(34,35,42)
    private val muted=Color.rgb(110,111,123)
    private val violet=Color.rgb(104,87,221)
    private val cream=Color.rgb(247,246,242)
    private val pale=Color.rgb(237,234,253)
    private lateinit var store:LocalStore
    private lateinit var prefs:Preferences
    private lateinit var ocr:TimetableOcr
    private lateinit var root:LinearLayout
    private lateinit var body:LinearLayout
    private var tab=0
    private var registered=false
    private var ocrBusy=false
    private var includeCoordinates=false
    private var displayDate=LocalDate.now(STUDY_ZONE)
    private val handler=Handler(Looper.getMainLooper())
    private val locationListeners=mutableListOf<LocationListener>()
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(tab==0 || tab==2 || tab==3)render()}}

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        store=LocalStore(this);prefs=Preferences(this);ocr=TimetableOcr(this)
        prefs.tracking=TrackingService.isRunning
        TrackingService.ensureChannels(this)
        tab=state?.getInt("tab")?:0
        state?.getString("date")?.let{displayDate=LocalDate.parse(it)}
        render();handleIntent(intent)
    }
    override fun onSaveInstanceState(out:Bundle){out.putInt("tab",tab);out.putString("date",displayDate.toString());super.onSaveInstanceState(out)}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
    private fun handleIntent(i:Intent){
        if(i.action==Intent.ACTION_SEND && i.type?.startsWith("image/")==true){
            @Suppress("DEPRECATION") val uri=i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            uri?.let(::readImage)
        }
        if(i.hasExtra("emaId")){tab=2;render();store.prompts().firstOrNull{it.id==i.getLongExtra("emaId",-1) && it.status=="pending"}?.let(::answerDialog)}
    }
    override fun onResume(){
        super.onResume()
        if(!registered){ContextCompat.registerReceiver(this,receiver,IntentFilter(TrackingService.ACTION_UPDATED),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true}
        store.maintenance(System.currentTimeMillis());render()
    }
    override fun onPause(){if(registered){unregisterReceiver(receiver);registered=false};super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);locationListeners.forEach{runCatching{getSystemService(LocationManager::class.java).removeUpdates(it)}};ocr.close();store.close();super.onDestroy()}

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun bg(color:Int,radius:Int=20)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Int=15,color:Int=ink,bold:Boolean=false)=TextView(this).apply{
        this.text=value;textSize=size.toFloat();setTextColor(color);if(bold)typeface=Typeface.create("sans-serif",Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun gap(parent:LinearLayout,n:Int=12){parent.addView(Space(this),LinearLayout.LayoutParams(1,dp(n)))}
    private fun label(parent:LinearLayout,t:String,s:Int=14,color:Int=muted,bold:Boolean=false){parent.addView(text(t,s,color,bold))}
    private fun button(t:String,primary:Boolean=false,click:()->Unit)=Button(this).apply{
        this.text=t;isAllCaps=false;textSize=14f;setTextColor(if(primary)Color.WHITE else ink);background=bg(if(primary)violet else Color.WHITE,14)
        minHeight=dp(48);minimumHeight=dp(48);setPadding(dp(14),dp(4),dp(14),dp(4));setOnClickListener{click()}
        layoutParams=LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(8)}
    }
    private fun card(parent:LinearLayout=body):LinearLayout=column().apply{
        background=bg(Color.WHITE);setPadding(dp(20),dp(20),dp(20),dp(20))
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})
    }
    private fun title(t:String,sub:String){label(body,t,29,ink,true);gap(body,6);label(body,sub,14);gap(body,22)}
    private fun pill(t:String,color:Int=violet)=text(t,12,color,true).apply{background=bg(if(color==violet)pale else Color.rgb(234,244,238),8);setPadding(dp(10),dp(6),dp(10),dp(6))}
    private fun toast(t:String){Toast.makeText(this,t,Toast.LENGTH_LONG).show()}
    private fun error(t:String){AlertDialog.Builder(this).setTitle("확인해 주세요").setMessage(t).setPositiveButton("확인",null).show()}

    private fun render(){
        if(isFinishing || isDestroyed)return
        root=column().apply{setBackgroundColor(cream)}
        setContentView(root)
        root.setOnApplyWindowInsetsListener{v,insets->
            if(Build.VERSION.SDK_INT>=30){val p=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(0,p.top,0,p.bottom)}
            else {@Suppress("DEPRECATION") v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom)}
            insets
        }
        root.requestApplyInsets()
        val brand=row().apply{setPadding(dp(24),dp(16),dp(24),dp(10))}
        brand.addView(text("흐름",24,violet,true),LinearLayout.LayoutParams(0,-2,1f))
        brand.addView(pill(if(prefs.tracking)"기록 중" else "수집 꺼짐",if(prefs.tracking)Color.rgb(42,120,75) else violet))
        root.addView(brand)
        val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false}
        body=column().apply{setPadding(dp(24),dp(14),dp(24),dp(22))}
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(tab){0->today();1->timetable();2->ema();3->settings()}
        val nav=row().apply{setPadding(dp(12),dp(8),dp(12),dp(8));background=bg(Color.WHITE,0)}
        listOf("오늘","시간표","EMA","설정").forEachIndexed{i,s->
            val item=text(s,14,if(tab==i)violet else muted,tab==i).apply{gravity=Gravity.CENTER;minHeight=dp(52);background=if(tab==i)bg(pale,12)else null;setOnClickListener{tab=i;render()}}
            nav.addView(item,LinearLayout.LayoutParams(0,dp(52),1f))
        }
        root.addView(nav)
    }
    private fun dayRange(date:LocalDate)=date.atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli()
    private fun time(t:Long)=Instant.ofEpochMilli(t).atZone(STUDY_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun confidence(s:String)=when(s){"high"->"신뢰도 높음";"medium"->"신뢰도 보통";else->"확인 필요"}
    private fun appLabel(pkg:String?):String=if(pkg==null)"사용 앱 없음" else runCatching{packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString()}.getOrDefault(pkg)
    private fun today(){
        title("하루를 기록하는 흐름", "적게 입력하고, 필요한 순간만 확인해요.")
        val dateRow=row()
        dateRow.addView(button("‹"){displayDate=displayDate.minusDays(1);render()},LinearLayout.LayoutParams(dp(48),dp(48)))
        dateRow.addView(text(displayDate.format(DateTimeFormatter.ofPattern("M월 d일 E요일",java.util.Locale.KOREAN)),16,ink,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        dateRow.addView(button("›"){if(displayDate<LocalDate.now(STUDY_ZONE)){displayDate=displayDate.plusDays(1);render()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        body.addView(dateRow);gap(body,14)
        val range=dayRange(displayDate);val segments=store.segments(range.first,range.second)
        val current=segments.firstOrNull()
        val summary=card()
        summary.background=bg(pale)
        label(summary,if(displayDate==LocalDate.now(STUDY_ZONE))"최근 활동" else "마지막 기록",12,violet,true);gap(summary,10)
        label(summary,current?.reportedActivity?:current?.activity?:"아직 기록이 없어요",27,ink,true);gap(summary,6)
        label(summary,current?.let{"${it.place} · ${confidence(it.confidence)}"}?:"시간표를 연결하고 기록을 켜면 주변 장소를 자동으로 찾아요.",14)
        if(current!=null){gap(summary,12);label(summary,current.reason,13);gap(summary,8);label(summary,"관측 ${time(current.observedFrom)}–${time(current.end)} · ${if(current.verification=="estimated")"자동 추정" else "사용자 확인"}",12,violet)}
        if(!prefs.tracking)summary.addView(button("기록 시작하기",true){startTracking()})
        val stats=row()
        fun stat(value:String,name:String){val v=column();label(v,value,23,ink,true);label(v,name,12);stats.addView(v,LinearLayout.LayoutParams(0,-2,1f))}
        stat("${segments.size}","기록 구간")
        stat("${segments.count{it.verification!="estimated"}}","확인한 구간")
        stat("${segments.sumOf{it.screenMs}/60_000}분","수집 중 화면 사용")
        body.addView(stats);gap(body,24)
        label(body,"활동 타임라인",18,ink,true);gap(body,12)
        if(segments.isEmpty()){
            val empty=card();label(empty,"하루를 일일이 입력하지 않아도 돼요.",16,ink,true);gap(empty,8)
            label(empty,"1. 에타 시간표 이미지를 가져와요.\n2. 기록을 켜고 자동 장소 연결을 허용해요.\n3. 주변 건물과 시간표를 비교해 5분 구간으로 정리해요.",14)
            empty.addView(button("시간표 연결하기"){tab=1;render()})
        }
        segments.take(80).forEach{s->
            val c=card();val r=row();r.addView(text("${time(s.observedFrom)} — ${time(s.end)}",13,muted),LinearLayout.LayoutParams(0,-2,1f));r.addView(pill(if(s.verification=="estimated")"자동 추정" else "사용자 확인"));c.addView(r)
            gap(c,12);label(c,s.reportedActivity?:s.activity,19,ink,true);label(c,listOfNotNull(s.course,s.place).joinToString(" · "),13);gap(c,8)
            label(c,if(s.usageAvailable)"화면 ${s.screenMs/60_000}분 · ${appLabel(s.topPackage)}" else "앱 사용정보 권한이 없어 화면 사용은 미확인이에요.",12)
            gap(c,8);label(c,s.reason,12)
            if(s.reportedActivity!=null)label(c,"자동 추정: ${s.activity}",12,violet)
            c.addView(button("활동 수정"){activityPicker("이 구간의 활동",s.reportedActivity?:s.activity){answer->store.correctSegment(s.start,answer);render()}})
        }
        if(segments.size>80)label(body,"최신 80개 구간을 표시해요. 전체 기록은 설정에서 내보낼 수 있어요.",12)
    }

    private fun timetable(){
        title("시간표를 한 번만", "예정된 수업에 실제 위치를 더해요.")
        val intro=card();label(intro,"에타 시간표 가져오기",19,ink,true);gap(intro,8)
        label(intro,"에타 시간표 이미지를 저장하거나 화면을 캡처한 뒤 가져오세요. 읽어온 과목과 시간을 확인하면 준비가 끝나요.",14)
        intro.addView(button(if(ocrBusy)"이미지를 읽고 있어요…" else "시간표 이미지 가져오기",true){if(!ocrBusy)startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),IMAGE_REQUEST)})
        intro.addView(button("기기 캘린더에서 가져오기"){openCalendars()})
        val semester=card();label(semester,"학기 기간",16,ink,true);label(semester,"${prefs.semesterFrom} → ${prefs.semesterUntil}",14);semester.addView(button("학기 기간 변경"){semesterDialog()})
        label(body,"등록된 수업",18,ink,true);gap(body,12)
        val courses=store.courses()
        if(courses.isEmpty()){val c=card();label(c,"아직 연결된 시간표가 없어요.",15);label(c,"인식이 어려운 과목은 직접 추가할 수도 있어요.",13)}
        val grouped=courses.groupBy{Triple(it.title,it.weekday,it.startMinute)}
        grouped.values.forEach{rows->val course=rows.first();val c=card();label(c,course.title,18,ink,true);gap(c,6)
            label(c,"${dayName(course.weekday)} ${minuteText(course.startMinute)}–${minuteText(course.endMinute)}${if(course.room.isNotBlank())" · ${course.room}" else ""}",14)
            label(c,if(rows.size>1)"캘린더에서 가져온 일정 ${rows.size}회" else "${course.validFrom} ~ ${course.validUntil}",12)
            if(!course.source.startsWith("calendar:"))c.addView(button("수업 수정"){courseDialog(course)})
            c.addView(button("삭제"){AlertDialog.Builder(this).setTitle("이 수업을 삭제할까요?").setMessage("${course.title} · 연결된 ${rows.size}개 일정을 삭제해요.").setNegativeButton("취소",null).setPositiveButton("삭제"){_,_->rows.forEach{store.deleteCourse(it.id)};render()}.show()})
        }
        body.addView(button("수업 직접 추가"){courseDialog(null)})
    }
    private fun dayName(n:Int)=listOf("월요일","화요일","수요일","목요일","금요일","토요일","일요일")[n-1]
    private fun field(parent:LinearLayout,label:String,value:String="",hint:String=""):EditText{
        gap(parent,10);label(parent,label,12)
        return EditText(this).apply{textSize=16f;setText(value);this.hint=hint;setSingleLine();setTextColor(ink);parent.addView(this,LinearLayout.LayoutParams(-1,dp(52)))}
    }
    private fun dialogContent():LinearLayout=column().apply{setPadding(dp(24),dp(6),dp(24),dp(16))}
    private fun scrolled(v:View)=ScrollView(this).apply{addView(v)}
    private fun semesterDialog(){
        val form=dialogContent();val from=field(form,"시작 날짜",prefs.semesterFrom,"YYYY-MM-DD");val until=field(form,"종료 날짜",prefs.semesterUntil,"YYYY-MM-DD")
        label(form,"새로 가져오거나 추가하는 시간표에 적용해요.",12)
        val dialog=AlertDialog.Builder(this).setTitle("학기 기간").setView(form).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val a=runCatching{LocalDate.parse(from.text.toString())}.getOrNull();val b=runCatching{LocalDate.parse(until.text.toString())}.getOrNull()
            if(a==null || b==null || a>b || b.toEpochDay()-a.toEpochDay()>365){toast("날짜 형식과 기간을 확인해 주세요. 최대 1년까지 가능해요.");return@setOnClickListener}
            prefs.semesterFrom=a.toString();prefs.semesterUntil=b.toString();dialog.dismiss();render()
        }};dialog.show()
    }
    private fun courseDialog(course:Course?){
        val f=dialogContent();val name=field(f,"과목",course?.title?:"");val day=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,(1..7).map(::dayName));setSelection((course?.weekday?:1)-1)}
        gap(f,12);label(f,"요일",12);f.addView(day)
        val start=field(f,"시작",minuteText(course?.startMinute?:540),"09:00");val end=field(f,"종료",minuteText(course?.endMinute?:600),"10:00");val room=field(f,"강의 장소",course?.room?:"")
        val a=field(f,"학기 시작",course?.validFrom?:prefs.semesterFrom);val b=field(f,"학기 종료",course?.validUntil?:prefs.semesterUntil)
        val dialog=AlertDialog.Builder(this).setTitle(if(course==null)"수업 추가" else "수업 수정").setView(scrolled(f)).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val row=Course(course?.id?:0,name.text.toString().trim(),day.selectedItemPosition+1,parseMinute(start.text.toString())?:-1,parseMinute(end.text.toString())?:-1,room.text.toString(),a.text.toString(),b.text.toString(),course?.source?:"manual")
            val e=row.validate();if(e!=null){toast(e);return@setOnClickListener};store.saveCourse(row);dialog.dismiss();render()
        }};dialog.show()
    }

    private fun readImage(uri:Uri){
        if(ocrBusy)return
        ocrBusy=true;tab=1;render();toast("이미지에서 요일·시간·수업을 읽고 있어요.")
        ocr.read(uri){result->if(isDestroyed)return@read;ocrBusy=false;render();result.fold(onSuccess={if(it.drafts.isEmpty())calibrate(it)else previewImport(it,it.drafts)},onFailure={error("이미지를 읽지 못했어요. ${it.message?:"다른 이미지로 다시 시도해 주세요."}")})}
    }
    private fun calibrate(result:OcrResult){
        val f=dialogContent();label(f,"요일이나 수업 시간이 틀렸다면 표 위치를 조정해 다시 읽을 수 있어요. 좌표는 이미지의 왼쪽 위부터의 비율(%)예요.",13)
        val image=ImageView(this).apply{setImageBitmap(result.bitmap);adjustViewBounds=true;contentDescription="가져온 시간표 원본"};f.addView(image,LinearLayout.LayoutParams(-1,dp(200)))
        val previous=result.axis
        val firstDay=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,(1..7).map{ "첫 요일: ${dayName(it)}" });setSelection((previous?.dayCenters?.first()?.first?:1)-1)};f.addView(firstDay)
        fun percent(value:Float,dimension:Int)="%.1f".format(java.util.Locale.US,(value/dimension*100).coerceIn(0f,100f))
        val spacing=previous?.dayCenters?.zipWithNext()?.map{(a,b)->(b.second-a.second)/(b.first-a.first)}?.average()?.toFloat()
        val hours=if(previous==null)9 else minOf(8,24-previous.hour,((result.bitmap.height-previous.hourY)/previous.pixelsPerHour).toInt()).coerceAtLeast(1)
        val days=field(f,"보이는 요일 수",previous?.dayCenters?.size?.toString()?:"5")
        val left=field(f,"첫 수업 칸 왼쪽 (%)",if(previous!=null && spacing!=null)percent(previous.dayCenters.first().second-spacing/2,result.bitmap.width)else "12")
        val right=field(f,"마지막 수업 칸 오른쪽 (%)",if(previous!=null && spacing!=null)percent(previous.dayCenters.last().second+spacing/2,result.bitmap.width)else "99")
        val top=field(f,"첫 시각의 가로선 높이 (%)",previous?.let{percent(it.hourY,result.bitmap.height)}?:"15")
        val bottom=field(f,"마지막 시각의 가로선 높이 (%)",previous?.let{percent(it.hourY+hours*it.pixelsPerHour,result.bitmap.height)}?:"95")
        val first=field(f,"첫 가로선 시각",minuteText((previous?.hour?:9)*60));val last=field(f,"마지막 가로선 시각",minuteText(((previous?.hour?:9)+hours)*60))
        val d=AlertDialog.Builder(this).setTitle("시간축 확인").setView(scrolled(f)).setNegativeButton("취소",null).setPositiveButton("다시 읽기",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val n=days.text.toString().toIntOrNull();val l=left.text.toString().toFloatOrNull();val r=right.text.toString().toFloatOrNull();val t=top.text.toString().toFloatOrNull();val b=bottom.text.toString().toFloatOrNull();val a=parseMinute(first.text.toString());val z=parseMinute(last.text.toString())
            val startDay=firstDay.selectedItemPosition+1
            if(n==null || n !in 2..7 || startDay+n-1>7 || l==null || r==null || t==null || b==null || l !in 0f..100f || r !in 0f..100f || t !in 0f..100f || b !in 0f..100f || l>=r || t>=b || a==null || z==null || a>=z || a%60!=0){toast("요일·좌표·시각을 확인해 주세요. 첫 시각은 정각으로 입력해 주세요.");return@setOnClickListener}
            val w=result.bitmap.width;val h=result.bitmap.height
            val axis=GridAxis((0 until n).map{(startDay+it) to (l+(r-l)*(it+.5f)/n)*w/100},t*h/100,a/60,(b-t)*h/100/((z-a)/60f))
            ocrBusy=true;d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            ocr.readGrid(result,axis){outcome->
                ocrBusy=false
                if(isDestroyed || !d.isShowing)return@readGrid
                d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                outcome.fold(onSuccess={read->
                    if(read.drafts.isEmpty())toast("수업 칸을 찾지 못했어요. 표 전체와 색상 수업 칸이 보이는 이미지로 시도해 주세요.")
                    else{d.dismiss();previewImport(read,read.drafts)}
                },onFailure={error("수업 칸을 읽지 못했어요. ${it.message?:"다시 시도해 주세요."}")})
            }
        }};d.show()
    }
    private data class DraftFields(val selected:CheckBox,val title:EditText,val day:Spinner,val start:EditText,val end:EditText,val room:EditText)
    private fun previewImport(result:OcrResult,drafts:List<ImportDraft>){
        val f=dialogContent();label(f,"인식 결과를 원본과 비교해 주세요. 선택한 수업으로 이전 이미지 시간표를 교체해요. 캘린더·직접 추가한 수업은 유지돼요.",13)
        val image=ImageView(this).apply{setImageBitmap(result.bitmap);adjustViewBounds=true;contentDescription="시간표 원본"};f.addView(image,LinearLayout.LayoutParams(-1,dp(220)))
        val adjust=button("시간축 다시 지정",false){};f.addView(adjust)
        val fields=drafts.map{draft->
            val c=card(f);val selected=CheckBox(this).apply{text="이 수업 가져오기";isChecked=true};c.addView(selected)
            if(draft.needsReview)label(c,"글씨 인식 결과가 달라요. 과목명과 장소를 원본에서 확인해 주세요.",13,violet)
            val name=field(c,"과목",draft.title)
            val day=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,(1..7).map(::dayName));setSelection(draft.weekday-1)};c.addView(day)
            DraftFields(selected,name,day,field(c,"시작",minuteText(draft.startMinute)),field(c,"종료",minuteText(draft.endMinute)),field(c,"장소",draft.room))
        }
        val d=AlertDialog.Builder(this).setTitle("${drafts.size}개 수업을 읽었어요").setView(scrolled(f)).setNegativeButton("취소",null).setPositiveButton("확인하고 가져오기",null).create()
        adjust.setOnClickListener{d.dismiss();calibrate(result)}
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val rows=fields.filter{it.selected.isChecked}.map{Course(title=it.title.text.toString().trim(),weekday=it.day.selectedItemPosition+1,startMinute=parseMinute(it.start.text.toString())?:-1,endMinute=parseMinute(it.end.text.toString())?:-1,room=it.room.text.toString(),validFrom=prefs.semesterFrom,validUntil=prefs.semesterUntil,source="everytime_image")}
            if(rows.isEmpty()){toast("가져올 수업을 선택해 주세요.");return@setOnClickListener}
            val e=rows.mapNotNull{it.validate()}.firstOrNull();if(e!=null){toast(e);return@setOnClickListener}
            store.replaceCourses("everytime_image",rows);d.dismiss();render();toast("시간표를 저장했어요.")
        }};d.show()
    }
    private fun openCalendars(){
        if(checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR),CALENDAR_PERMISSION);return}
        val calendars=runCatching{CalendarImporter.calendars(this)}.getOrElse{error("캘린더를 읽지 못했어요.");return}
        if(calendars.isEmpty()){error("기기에 연결된 캘린더가 없어요. 에타에서 외부 캘린더로 내보낼 수 있다면 먼저 연결해 주세요.");return}
        AlertDialog.Builder(this).setTitle("수업이 있는 캘린더 선택").setItems(calendars.map{it.name}.toTypedArray()){_,index->
            val choice=calendars[index];val from=LocalDate.parse(prefs.semesterFrom).atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli();val until=LocalDate.parse(prefs.semesterUntil).plusDays(1).atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli()
            val rows=runCatching{CalendarImporter.courses(this,choice.id,from,until)}.getOrElse{error("일정을 읽지 못했어요. 학기 기간과 캘린더 권한을 확인해 주세요.");return@setItems}
            if(rows.isEmpty()){error("학기 기간 안에 가져올 일정이 없어요. 종일·자정을 넘는 일정은 제외돼요.");return@setItems}
            val names=rows.map{"${it.validFrom} ${minuteText(it.startMinute)} ${it.title}"}.toTypedArray();val checks=BooleanArray(rows.size){true}
            AlertDialog.Builder(this).setTitle("수업 일정만 선택해 주세요").setMultiChoiceItems(names,checks){_,i,v->checks[i]=v}.setNegativeButton("취소",null).setPositiveButton("가져오기"){_,_->
                val selected=rows.filterIndexed{i,_->checks[i]};if(selected.isEmpty()){toast("일정을 선택하지 않았어요.");return@setPositiveButton}
                store.replaceCourses("calendar:${choice.id}",selected);render();toast("${selected.size}개 일정을 가져왔어요. 변경 시 다시 가져와 주세요.")
            }.show()
        }.show()
    }

    private fun ema(){
        title("필요한 순간만 확인", "확실하지 않은 기록을 짧게 보완해요.")
        store.maintenance(System.currentTimeMillis())
        val prompts=store.prompts();val today=LocalDate.now(STUDY_ZONE)
        val todays=prompts.filter{Instant.ofEpochMilli(it.createdAt).atZone(STUDY_ZONE).toLocalDate()==today}
        val policy=card();label(policy,"오늘 ${todays.size} / ${prefs.maxPrompts}회",23,ink,true);gap(policy,8)
        label(policy,"질문 사이 ${prefs.gapMinutes}분 · 21:00–09:00 알림 쉬기\n응답은 선택이에요. 미응답 기록은 자동 확인되지 않아요.",13)
        val pending=prompts.filter{it.status=="pending"}
        if(pending.isEmpty()){val c=card();label(c,"지금은 답할 질문이 없어요.",17,ink,true);gap(c,8);label(c,"기록을 켜면 애매한 구간에만 질문이 생겨요.",14)}
        pending.forEach{p->val c=card();c.background=bg(pale);label(c,"${time(p.segmentStart)}의 활동",12,violet,true);gap(c,8)
            label(c,if(p.suggested=="활동 미확인")"이때 어떤 활동을 했나요?" else "이때 ${p.suggested} 중이었나요?",21,ink,true);gap(c,10);label(c,p.reason,13)
            if(p.suggested!="활동 미확인")c.addView(button("맞아요",true){respond(p,p.suggested)})
            c.addView(button("활동 선택하기",p.suggested=="활동 미확인"){answerDialog(p)})
            c.addView(button("건너뛰기"){store.answerPrompt(p.id,null,true);cancelPrompt(p);render()})
        }
        label(body,"최근 질문",18,ink,true);gap(body,12)
        prompts.filter{it.status!="pending"}.take(12).forEach{p->val c=card();label(c,"${time(p.segmentStart)} · ${if(p.kind=="validation")"검증 질문" else "불확실한 구간"}",14,ink,true)
            label(c,when(p.status){"answered"->"응답 완료";"skipped"->"건너뜀";else->"응답 없이 만료됨"},13)}
    }
    private fun answerDialog(p:Prompt){activityPicker("${time(p.segmentStart)}의 실제 활동",p.suggested){respond(p,it)}}
    private fun activityPicker(title:String,suggested:String,done:(String)->Unit){
        val options=listOf("수업","공부","업무","식사","이동","휴식","기타")
        AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()){_,i->
            if(options[i]=="기타"){
                val f=dialogContent();val value=field(f,"실제 활동","","짧게 입력해 주세요")
                val d=AlertDialog.Builder(this).setTitle("다른 활동").setView(f).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
                d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val v=value.text.toString().trim();if(v.isBlank()){toast("활동을 입력해 주세요.")}else{d.dismiss();done(v)}}};d.show()
            }else done(options[i])
        }.setNegativeButton("취소",null).show()
    }
    private fun cancelPrompt(p:Prompt){getSystemService(NotificationManager::class.java).cancel(1000+p.id.toInt())}
    private fun respond(p:Prompt,answer:String){store.maintenance(System.currentTimeMillis());if(!store.answerPrompt(p.id,answer))toast("이미 처리되었거나 만료된 질문이에요.");cancelPrompt(p);render()}

    private fun settings(){
        title("내 기록, 내 설정", "데이터는 이 기기에 보관해요.")
        val tracking=card();label(tracking,"활동 기록",18,ink,true);gap(tracking,6)
        label(tracking,"위치와 앱 사용 기록을 수집해요. 수집 중에는 상태 알림이 표시돼요.",13)
        tracking.addView(button(if(prefs.tracking)"수집 중지" else "수집 시작",!prefs.tracking){if(prefs.tracking){stopTracking();render()}else startTracking()})
        val permissions=card();label(permissions,"데이터 연결",18,ink,true);gap(permissions,8)
        label(permissions,"위치: ${if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)"허용" else "미허용"}\n앱 사용정보: ${if(UsageCollector.allowed(this))"연결됨" else "미연결"}\n알림: ${if(getSystemService(NotificationManager::class.java).areNotificationsEnabled())"켜짐" else "꺼짐 · 앱 안에서 EMA 확인 가능"}",14)
        permissions.addView(button("위치 권한 설정"){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION_PERMISSION)})
        permissions.addView(button("앱 사용정보 연결"){runCatching{startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:$packageName")))}.onFailure{startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}})
        permissions.addView(button("알림 설정"){if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),NOTIFICATION_PERMISSION)else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))})
        val campus=card();label(campus,"자동 장소 인식",18,ink,true);gap(campus,6)
        label(campus,AutomaticPlaces.status(this),14)
        label(campus,"학교·기숙사·건물 이름과 경계를 지도에서 가져와요. 지도 정보가 빠졌거나 GPS가 모호하면 확인 필요로 남겨요.",13)
        campus.addView(button(if(prefs.automaticPlaces)"주변 장소 지도 보기" else "자동 장소 연결",true){if(prefs.automaticPlaces)automaticMapDialog()else placeConnection{render()}})
        if(prefs.automaticPlaces)campus.addView(button("자동 장소 연결 끄기"){prefs.automaticPlaces=false;AutomaticPlaces.clear(this);render()})
        campus.addView(button("고급 설정 · 장소 직접 보정 (선택)"){manualPlacesDialog()})
        val ema=card();label(ema,"EMA 질문 빈도",18,ink,true);label(ema,"하루 최대 ${prefs.maxPrompts}회 · 최소 ${prefs.gapMinutes}분 간격",14)
        ema.addView(button("질문 빈도 변경"){emaSettings()})
        val validation=CheckBox(this).apply{text="연구용 검증 질문";isChecked=prefs.validation;setOnCheckedChangeListener{_,v->prefs.validation=v}}
        ema.addView(validation);label(ema,"켜면 신뢰도가 높은 일부 구간도 확인해요. 하루 최대 1회이며 전체 질문 상한 안에 포함돼요. 별도 정확도 평가에는 대표 표본이 필요해요.",12)
        val data=card();label(data,"데이터 보관",18,ink,true);gap(data,8)
        label(data,"위치·활동·EMA는 30일 뒤 자동 삭제해요. 시간표와 장소는 직접 삭제할 때까지 유지돼요. 활동 기록은 서버로 전송하지 않아요. 자동 장소 연결 시 약 4km 범위와 IP가 지도 제공자에 전달되고, 건물 정보는 기기에 7일간 저장해요. 지도 화면에서도 표시 지역의 배경을 인터넷으로 불러와요.",13)
        data.addView(button("연구 기록 내보내기"){exportDialog()})
        data.addView(button("모든 데이터 삭제"){AlertDialog.Builder(this).setTitle("모든 데이터를 삭제할까요?").setMessage("수집을 중지하고 시간표, 장소, 위치, 활동, EMA, 설정, 지도 캐시를 삭제해요. 내보낸 파일은 따로 관리해 주세요.").setNegativeButton("취소",null).setPositiveButton("삭제"){_,_->stopTracking();store.clearAll();prefs.clear();AutomaticPlaces.clear(this);java.io.File(filesDir,"map-tiles").deleteRecursively();getSystemService(NotificationManager::class.java).cancelAll();render()}.show()})
    }
    private fun emaSettings(){
        val f=dialogContent();val cap=field(f,"하루 최대 질문 수 (0~6)",prefs.maxPrompts.toString());val gap=field(f,"최소 간격 (30~240분)",prefs.gapMinutes.toString())
        val d=AlertDialog.Builder(this).setTitle("질문 빈도").setView(f).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val a=cap.text.toString().toIntOrNull();val b=gap.text.toString().toIntOrNull();if(a==null || b==null || a !in 0..6 || b !in 30..240){toast("범위를 확인해 주세요.");return@setOnClickListener};prefs.maxPrompts=a;prefs.gapMinutes=b;d.dismiss();render()}};d.show()
    }
    private fun placeConnection(done:()->Unit){
        AlertDialog.Builder(this).setTitle("주변 장소를 자동으로 찾을까요?")
            .setMessage("학교·기숙사·건물 정보를 지도에서 가져와 이름을 입력하지 않아도 돼요. 약 4km 범위와 IP가 OpenStreetMap의 Overpass 제공자에게 전달돼요. 정확한 GPS 기록, 시간표, 앱 사용, EMA는 보내지 않아요. 지도 데이터가 없는 건물은 미확인으로 남겨요.")
            .setNegativeButton("지금은 건너뛰기"){_,_->prefs.placeConsentSeen=true;prefs.automaticPlaces=false;done()}
            .setPositiveButton("자동 연결"){_,_->prefs.placeConsentSeen=true;prefs.automaticPlaces=true;done()}.show()
    }
    private fun automaticMapDialog(){
        val f=dialogContent();val items=AutomaticPlaces.places(this)
        val fix=store.geoSince(System.currentTimeMillis()-600_000).lastOrNull{it.accuracyM<=100}
        val center=fix?.let{MapPoint(it.latitude,it.longitude)}?:items.firstOrNull()?.center?:MapPoint(37.5665,126.978)
        val map=CampusMapView(this,center.latitude,center.longitude).apply{showSelection=false;mapPlaces=items;currentFix=fix}
        val status=text(AutomaticPlaces.status(this),14);f.addView(status)
        label(f,"주황: 기숙사 · 초록: 건물 · 보라: 학교. 지도상의 이름과 경계예요. GPS 위치는 오차가 반영된 파란 원으로 표시해요.",12)
        f.addView(map,LinearLayout.LayoutParams(-1,dp(300)))
        val controls=row();controls.addView(button("−"){map.changeZoom(-1)},LinearLayout.LayoutParams(0,dp(48),1f));controls.addView(button("＋"){map.changeZoom(1)},LinearLayout.LayoutParams(0,dp(48),1f));f.addView(controls)
        f.addView(button("현재 위치에서 자동 찾기",true){currentLocation { location ->
            if(map.isAttachedToWindow){val sample=GeoSample(location.latitude,location.longitude,location.accuracy,location.time);map.currentFix=sample;map.centerOn(sample.latitude,sample.longitude);AutomaticPlaces.refresh(this,sample)}
        }})
        label(f,"© OpenStreetMap contributors · 공개 지도에 없는 건물은 구분이 어려워요. 시간표 약어와 지도 이름이 연결되지 않으면 확인 필요로 남겨요.",12)
        val d=AlertDialog.Builder(this).setTitle("주변 장소 지도").setView(scrolled(f)).setPositiveButton("닫기",null).create()
        val update=object:Runnable{override fun run(){if(!d.isShowing)return;status.text=AutomaticPlaces.status(this@MainActivity);map.mapPlaces=AutomaticPlaces.places(this@MainActivity);handler.postDelayed(this,1500)}}
        d.setOnDismissListener{handler.removeCallbacks(update);map.close()};d.show();handler.post(update)
    }
    private fun manualPlacesDialog(){
        val f=dialogContent();label(f,"자동 장소 인식만으로 사용할 수 있어요. 이 설정은 지도 오류를 직접 보정하고 싶을 때만 사용해요.",13)
        f.addView(button("학교 범위 보정"){placeDialog("campus")});f.addView(button("기숙사 보정"){placeDialog("dorm")});f.addView(button("수업 건물 보정"){placeDialog("classroom")})
        prefs.zones().forEach { z ->
            f.addView(button("${z.name} 수정"){placeDialog(z.kind,z)})
            f.addView(button("${z.name} 보정 삭제"){AlertDialog.Builder(this).setTitle("${z.name} 보정을 삭제할까요?").setNegativeButton("취소",null).setPositiveButton("삭제"){_,_->prefs.deleteZone(z.id);toast("보정을 삭제했어요.")}.show()})
        }
        AlertDialog.Builder(this).setTitle("직접 보정 · 선택 사항").setView(scrolled(f)).setPositiveButton("닫기",null).show()
    }
    private fun placeDialog(kind:String,zone:PlaceZone?=null){
        val school=prefs.campus();val isCampus=kind=="campus"
        val seed=store.geoSince(System.currentTimeMillis()-120_000).lastOrNull { it.accuracyM<=100 }
        val initialLat=zone?.latitude?:school?.latitude?:seed?.latitude?:37.5665
        val initialLon=zone?.longitude?:school?.longitude?:seed?.longitude?:126.9780
        val initialRadius=zone?.radiusM?:if(isCampus)school?.radiusM?:400f else 60f
        val f=dialogContent()
        label(f,"지도를 끌어 십자 표시를 건물 중심에 맞추고 반경을 정해요. 보라: 학교 · 주황: 기숙사 · 초록: 수업 건물",13)
        val map=CampusMapView(this,initialLat,initialLon).apply{
            radiusM=initialRadius;campus=if(isCampus)null else school;zones=prefs.zones();selectedId=zone?.id
            selectionColor=if(isCampus)violet else if(kind=="dorm")Color.rgb(209,122,50)else Color.rgb(40,135,107)
        }
        if(zone==null && school==null && seed==null)map.changeZoom(-4)
        f.addView(map,LinearLayout.LayoutParams(-1,dp(260)))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        controls.addView(button("−"){map.changeZoom(-1)},LinearLayout.LayoutParams(0,dp(48),1f))
        controls.addView(button("＋"){map.changeZoom(1)},LinearLayout.LayoutParams(0,dp(48),1f));f.addView(controls)
        f.addView(button("현재 위치로 이동"){currentLocation{fix->if(map.isAttachedToWindow){map.centerOn(fix.latitude,fix.longitude);map.changeZoom(16-map.zoom)}}})
        val name=field(f,if(isCampus)"학교 이름" else "장소 이름",zone?.name?:if(isCampus)school?.name?:"학교" else if(kind=="dorm")"기숙사" else "수업 건물")
        val lat=field(f,"중심 위도",initialLat.toString());val lon=field(f,"중심 경도",initialLon.toString())
        map.onCenterChanged={a,b->lat.setText("%.6f".format(java.util.Locale.US,a));lon.setText("%.6f".format(java.util.Locale.US,b))}
        f.addView(button("입력한 좌표로 지도 이동"){
            val a=lat.text.toString().toDoubleOrNull();val b=lon.text.toString().toDoubleOrNull()
            if(a==null || b==null || !a.isFinite() || !b.isFinite() || a !in -85.0..85.0 || b !in -180.0..180.0)toast("좌표를 확인해 주세요.")else map.centerOn(a,b)
        })
        val radius=field(f,if(isCampus)"반경 (50~3000m)" else "반경 (15~1000m)",initialRadius.toInt().toString())
        radius.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){s?.toString()?.toFloatOrNull()?.takeIf{it.isFinite() && it in 15f..3000f}?.let{map.radiusM=it}}
            override fun afterTextChanged(s:Editable?)=Unit
        })
        val keys=if(kind=="classroom")field(f,"시간표 장소 코드 (쉼표로 구분)",zone?.roomKeys?.joinToString(", ")?:"","예: NTH, 뉴턴홀")else null
        if(keys!=null){
            label(f,"이 코드가 포함된 수업 장소만 이 건물과 연결해요. 예: NTH → NTH 311, NTH213. 학교 전체 반경을 수업 건물로 지정하지 마세요.",12)
            val rooms=store.courses().map{it.room}.filter{it.isNotBlank()}.distinct()
            if(rooms.isNotEmpty())label(f,"시간표 장소: ${rooms.joinToString(", ")}",12)
        }
        label(f,"GPS 오차가 건물보다 크거나 기숙사와 강의동 범위가 겹치면 확인이 필요해요. 지도 배경 요청으로 OSM에 표시 지역과 IP가 전달돼요. 활동·EMA 기록은 보내지 않아요.",12)
        val d=AlertDialog.Builder(this).setTitle(if(isCampus)"학교 범위" else if(kind=="dorm")"기숙사 범위" else "수업 건물 범위").setView(scrolled(f)).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        d.setOnDismissListener{map.close()}
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val a=lat.text.toString().toDoubleOrNull();val b=lon.text.toString().toDoubleOrNull();val r=radius.text.toString().toFloatOrNull()
            if(a==null || b==null || r==null || !a.isFinite() || !b.isFinite() || !r.isFinite() || a !in -85.0..85.0 || b !in -180.0..180.0 || r !in (if(isCampus)50f..3000f else 15f..1000f) || name.text.isBlank()){toast("이름·좌표·반경을 확인해 주세요.");return@setOnClickListener}
            val roomKeys=keys?.text?.toString()?.split(',','，')?.map{it.trim()}?.filter{it.isNotBlank()}?:emptyList()
            if(kind=="classroom" && roomKeys.isEmpty()){toast("시간표와 연결할 장소 코드를 입력해 주세요.");return@setOnClickListener}
            if(isCampus)prefs.saveCampus(Campus(name.text.toString().trim(),a,b,r))
            else prefs.saveZone(PlaceZone(zone?.id?:java.util.UUID.randomUUID().toString(),name.text.toString().trim(),kind,a,b,r,roomKeys))
            d.dismiss();render()
        }};d.show()
    }
    private fun currentLocation(done:(Location)->Unit){
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION_PERMISSION);toast("권한 허용 후 다시 눌러 주세요.");return}
        val lm=getSystemService(LocationManager::class.java)
        val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{runCatching{lm.isProviderEnabled(it)}.getOrDefault(false)}
        if(providers.isEmpty()){toast("기기 위치 서비스를 켜 주세요.");return}
        var received=false
        val listener=object:LocationListener{
            override fun onLocationChanged(location:Location){
                if(received || !location.hasAccuracy() || location.accuracy>100 || System.currentTimeMillis()-location.time !in 0..120_000)return
                received=true;lm.removeUpdates(this);locationListeners.remove(this);done(location)
            }
            override fun onProviderEnabled(provider:String)=Unit
            override fun onProviderDisabled(provider:String)=Unit
            @Deprecated("Legacy location callback")override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?)=Unit
        }
        locationListeners.add(listener)
        try{providers.forEach{lm.requestLocationUpdates(it,1000,0f,listener,Looper.getMainLooper());lm.getLastKnownLocation(it)?.let(listener::onLocationChanged)}}catch(_:SecurityException){toast("위치 권한을 확인해 주세요.")}
        toast("위치를 확인 중이에요. 실내에서는 시간이 걸릴 수 있어요.")
        handler.postDelayed({runCatching{lm.removeUpdates(listener)};locationListeners.remove(listener);if(!received)toast("정확한 위치를 얻지 못했어요. 실외에서 다시 시도해 주세요.")},30_000)
    }
    private fun startTracking(){
        if(!prefs.placeConsentSeen){placeConnection{startTracking()};return}
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),START_PERMISSION);return}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),START_NOTIFICATION);return}
        launchTracking()
    }
    private fun launchTracking(){
        try{startForegroundService(Intent(this,TrackingService::class.java));prefs.tracking=true;render();toast("활동 기록을 시작했어요.")}
        catch(e:RuntimeException){prefs.tracking=false;error("수집을 시작하지 못했어요. 위치 권한과 기기 설정을 확인해 주세요.")}
    }
    private fun stopTracking(){prefs.tracking=false;stopService(Intent(this,TrackingService::class.java))}
    private fun exportDialog(){
        val f=dialogContent();label(f,"파일에는 시간표, 추정 활동, 장소 설정, 앱 식별자, EMA 응답이 들어가요. 좌표는 선택한 경우에만 포함돼요.",13)
        val coords=CheckBox(this).apply{text="원본 GPS 좌표 포함";isChecked=false};f.addView(coords)
        AlertDialog.Builder(this).setTitle("연구 기록 내보내기").setView(f).setNegativeButton("취소",null).setPositiveButton("파일 저장"){_,_->includeCoordinates=coords.isChecked;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"heureum-${LocalDate.now(STUDY_ZONE)}.json"),EXPORT_REQUEST)}.show()
    }
    @Deprecated("Activity result bridge")override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        when(requestCode){IMAGE_REQUEST->readImage(uri);EXPORT_REQUEST->{store.maintenance(System.currentTimeMillis());runCatching{
            val json=JSONObject(store.export(includeCoordinates)).put("schemaVersion",2).put("currentPlaceSettings",prefs.placeSettingsJson(includeCoordinates))
            contentResolver.openOutputStream(uri)?.use{it.write(json.toString(2).toByteArray(Charsets.UTF_8))}?:error("파일을 열 수 없어요.")
        }.onSuccess{toast("기록을 저장했어요.")}.onFailure{error("기록을 저장하지 못했어요.")}}}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        when(requestCode){START_PERMISSION->{if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)startTracking()else toast("위치 권한이 있어야 기록을 시작할 수 있어요.")};START_NOTIFICATION->launchTracking();CALENDAR_PERMISSION->{if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)openCalendars()else toast("캘린더 권한을 허용하지 않았어요.")}}
        render()
    }
    companion object{private const val IMAGE_REQUEST=10;private const val EXPORT_REQUEST=11;private const val LOCATION_PERMISSION=20;private const val START_PERMISSION=21;private const val START_NOTIFICATION=22;private const val CALENDAR_PERMISSION=23;private const val NOTIFICATION_PERMISSION=24}
}
