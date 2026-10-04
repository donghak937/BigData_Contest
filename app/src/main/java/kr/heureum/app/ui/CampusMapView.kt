package kr.heureum.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kr.heureum.app.core.*
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import kotlin.math.*

/** Native map. Requests visible tiles only, and never sends survey or sensor records. */
class CampusMapView(context:Context, initialLatitude:Double, initialLongitude:Double, private val networkEnabled:Boolean = true):View(context) {
    var latitude=initialLatitude.coerceIn(-MapProjection.MAX_LAT,MapProjection.MAX_LAT);private set
    var longitude=initialLongitude;private set
    var zoom=16;private set
    var radiusM=80f;set(v){field=v;invalidate()}
    var selectionColor=Color.rgb(104,87,221)
    var campus:Campus?=null
    var zones:List<PlaceZone> = emptyList()
    var selectedId:String?=null
    var showSelection=true
    var mapPlaces:List<MapPlace> = emptyList();set(v){field=v;invalidate()}
    var currentFix:GeoSample?=null;set(v){field=v;invalidate()}
    var onCenterChanged:((Double,Double)->Unit)?=null
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler=Handler(Looper.getMainLooper())
    private val executor=Executors.newFixedThreadPool(2)
    private val bitmaps=object:android.util.LruCache<String,Bitmap>(40){}
    private val pending=mutableSetOf<String>()
    private val failed=mutableMapOf<String,Long>()
    private var closed=false
    @Volatile private var visibleKeys:Set<String> = emptySet()
    private var lastX=0f;private var lastY=0f;private var downX=0f;private var downY=0f
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        private var accumulated=1f
        override fun onScaleBegin(detector:ScaleGestureDetector):Boolean {accumulated=1f;return true}
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            accumulated*=detector.scaleFactor
            if(accumulated>1.4f){changeZoom(1);accumulated=1f}
            if(accumulated<.7f){changeZoom(-1);accumulated=1f}
            return true
        }
    })
    init {contentDescription="학교 지도. 끌어서 중심을 옮기고 확대·축소할 수 있어요.";isFocusable=true}
    fun centerOn(lat:Double,lon:Double){latitude=lat.coerceIn(-MapProjection.MAX_LAT,MapProjection.MAX_LAT);longitude=lon;invalidate();onCenterChanged?.invoke(latitude,longitude)}
    fun changeZoom(delta:Int){zoom=(zoom+delta).coerceIn(3,19);invalidate()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);canvas.drawColor(Color.rgb(240,239,235))
        val center=MapProjection.point(latitude,longitude,zoom)
        val tileSize=256f*density
        val originX=center.first-width/(2*density);val originY=center.second-height/(2*density)
        val minX=floor(originX/256).toInt();val minY=floor(originY/256).toInt()
        val maxX=floor((originX+width/density)/256).toInt();val maxY=floor((originY+height/density)/256).toInt()
        val count=1 shl zoom
        visibleKeys=(minX..maxX).flatMap{x->(minY..maxY).filter{it in 0 until count}.map{y->"$zoom-${((x%count)+count)%count}-$y"}}.toSet()
        for(x in minX..maxX)for(y in minY..maxY){
            if(y !in 0 until count)continue
            val wrapped=((x%count)+count)%count;val key="$zoom-$wrapped-$y"
            val left=((x*256-originX)*density).toFloat();val top=((y*256-originY)*density).toFloat()
            val destination=RectF(left,top,left+tileSize,top+tileSize)
            val tile=bitmaps.get(key)
            if(tile!=null)canvas.drawBitmap(tile,null,destination,paint)
            else {
                paint.style=Paint.Style.STROKE;paint.color=Color.rgb(220,219,214);paint.strokeWidth=density
                canvas.drawRect(destination,paint);paint.style=Paint.Style.FILL
                if(networkEnabled)loadTile(key,zoom,wrapped,y)
            }
        }
        fun drawArea(name:String,lat:Double,lon:Double,radius:Float,color:Int,selected:Boolean=false){
            val p=MapProjection.point(lat,lon,zoom)
            var dx=p.first-center.first
            if(dx>MapProjection.worldSize(zoom)/2)dx-=MapProjection.worldSize(zoom)
            if(dx< -MapProjection.worldSize(zoom)/2)dx+=MapProjection.worldSize(zoom)
            val x=(width/2+dx*density).toFloat();val y=(height/2+(p.second-center.second)*density).toFloat()
            val r=(radius/MapProjection.metersPerPixel(lat,zoom)*density).toFloat()
            paint.style=Paint.Style.FILL;paint.color=color;paint.alpha=35;canvas.drawCircle(x,y,r,paint)
            paint.alpha=255;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;canvas.drawCircle(x,y,r,paint)
            paint.style=Paint.Style.FILL;canvas.drawCircle(x,y,4*density,paint)
            if(!selected){paint.textSize=12*density;paint.typeface=Typeface.DEFAULT_BOLD
                val labelWidth=paint.measureText(name);paint.color=Color.WHITE;paint.alpha=220;canvas.drawRoundRect(x-labelWidth/2-5*density,y+7*density,x+labelWidth/2+5*density,y+25*density,4*density,4*density,paint)
                paint.alpha=255;paint.color=color;canvas.drawText(name,x-labelWidth/2,y+21*density,paint)}
        }
        campus?.let{drawArea(it.name,it.latitude,it.longitude,it.radiusM,Color.rgb(104,87,221))}
        zones.filterNot{it.id==selectedId}.forEach{drawArea(it.name,it.latitude,it.longitude,it.radiusM,if(it.kind=="dorm")Color.rgb(209,122,50)else Color.rgb(40,135,107))}
        val labels=mutableListOf<RectF>()
        mapPlaces.sortedBy { if(it.kind=="dorm")0 else if(it.kind=="classroom")1 else 2 }.forEach { place ->
            val color=if(place.kind=="dorm")Color.rgb(209,122,50)else if(place.kind=="campus")Color.rgb(104,87,221)else Color.rgb(40,135,107)
            val p=MapProjection.point(place.center.latitude,place.center.longitude,zoom)
            val x=(width/2+(p.first-center.first)*density).toFloat();val y=(height/2+(p.second-center.second)*density).toFloat()
            if(x in -width.toFloat()..width*2f && y in -height.toFloat()..height*2f){
                val path=Path().apply{fillType=Path.FillType.EVEN_ODD}
                (place.outlines+place.holes).forEach { ring -> ring.forEachIndexed { i,point ->
                    val q=MapProjection.point(point.latitude,point.longitude,zoom);val a=(width/2+(q.first-center.first)*density).toFloat();val b=(height/2+(q.second-center.second)*density).toFloat()
                    if(i==0)path.moveTo(a,b)else path.lineTo(a,b)
                };path.close() }
                paint.color=color;paint.alpha=30;paint.style=Paint.Style.FILL;canvas.drawPath(path,paint)
                paint.alpha=255;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*density;canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
                if(place.kind!="campus" || zoom<16){
                    paint.textSize=11*density;paint.typeface=Typeface.DEFAULT_BOLD;canvas.drawCircle(x,y,3*density,paint)
                    val w=paint.measureText(place.name);val left=if(x+w+8*density>width)x-w-5*density else x+5*density
                    val box=RectF(left-3*density,y-13*density,left+w+3*density,y+4*density)
                    if(place.name!=MapPlaceTags.label(place.kind) && box.left>=0 && box.right<=width && box.top>=24*density && box.bottom<height-28*density && labels.none { RectF.intersects(it,box) }){
                        canvas.drawText(place.name,left,y,paint);labels+=box
                    }
                }
            }
        }
        currentFix?.let { drawArea("GPS 위치",it.latitude,it.longitude,it.accuracyM,Color.rgb(32,105,219)) }
        if(showSelection){
        drawArea("선택 위치",latitude,longitude,radiusM,selectionColor,true)
        paint.color=selectionColor;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density
        canvas.drawLine(width/2f-12*density,height/2f,width/2f+12*density,height/2f,paint)
        canvas.drawLine(width/2f,height/2f-12*density,width/2f,height/2f+12*density,paint);paint.style=Paint.Style.FILL
        }
        paint.color=Color.WHITE;paint.alpha=235;canvas.drawRect(0f,height-26*density,width.toFloat(),height.toFloat(),paint);paint.alpha=255
        paint.color=Color.DKGRAY;paint.textSize=11*density;paint.typeface=Typeface.DEFAULT
        canvas.drawText("© OpenStreetMap contributors",8*density,height-8*density,paint)
        if(bitmaps.size()==0){paint.textSize=12*density;canvas.drawText(if(!networkEnabled)"지도 테스트" else if(failed.isNotEmpty())"배경 지도 연결 실패 · 저장된 장소를 표시해요" else "지도를 불러오는 중이에요",8*density,20*density,paint)}
    }
    private fun loadTile(key:String,z:Int,x:Int,y:Int) {
        if(closed || key in pending || pending.size>=8 || !isAttachedToWindow || System.currentTimeMillis()-(failed[key]?:0)<60_000)return
        pending+=key
        executor.execute {
            var bitmap:Bitmap?=null
            try {
                if(closed || key !in visibleKeys){handler.post{pending-=key;if(!closed)invalidate()};return@execute}
                val folder=File(context.filesDir,"map-tiles").apply{mkdirs()};val file=File(folder,"$key.png")
                if(file.exists() && System.currentTimeMillis()-file.lastModified()<7*86_400_000L)bitmap=BitmapFactory.decodeFile(file.path)
                if(bitmap==null && !closed){
                    val connection=URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                    try{
                        connection.setRequestProperty("User-Agent","Heureum/0.3 (Android; kr.heureum.app)")
                        connection.connectTimeout=8000;connection.readTimeout=8000
                        if(file.exists())connection.ifModifiedSince=file.lastModified()
                        if(connection.responseCode==HttpURLConnection.HTTP_NOT_MODIFIED){file.setLastModified(System.currentTimeMillis());bitmap=BitmapFactory.decodeFile(file.path)}
                        else if(connection.responseCode==HttpURLConnection.HTTP_OK){
                            val bytes=connection.inputStream.use{it.readBytes()}
                            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                            if(decoded!=null){file.writeBytes(bytes);bitmap=decoded}
                        }
                    }finally{connection.disconnect()}
                }
            }catch(_:Exception){}
            handler.post{
                pending-=key
                if(!closed){val loaded=bitmap;if(loaded!=null)bitmaps.put(key,loaded)else failed[key]=System.currentTimeMillis();invalidate()}
            }
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        parent?.requestDisallowInterceptTouchEvent(true);scaler.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{lastX=event.x;lastY=event.y;downX=event.x;downY=event.y}
            MotionEvent.ACTION_MOVE->{if(!scaler.isInProgress && event.pointerCount==1){
                val center=MapProjection.point(latitude,longitude,zoom)
                val coord=MapProjection.coordinates(center.first-(event.x-lastX)/density,center.second-(event.y-lastY)/density,zoom)
                centerOn(coord.first,coord.second)
            };lastX=event.x;lastY=event.y}
            MotionEvent.ACTION_UP->{parent?.requestDisallowInterceptTouchEvent(false)
                if(abs(event.x-downX)+abs(event.y-downY)<10*density){performClick()
                    if(event.y>height-26*density)context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}
            }
            MotionEvent.ACTION_CANCEL->parent?.requestDisallowInterceptTouchEvent(false)
        };return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    fun close(){closed=true;visibleKeys=emptySet();executor.shutdownNow();handler.removeCallbacksAndMessages(null);bitmaps.evictAll()}
    override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
}
