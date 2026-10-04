package kr.heureum.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.heureum.app.core.*

data class OcrResult(val bitmap: Bitmap,val boxes: List<TextBox>,val axis: GridAxis?,val drafts: List<ImportDraft>,val wordBoxes:List<TextBox> = emptyList())

class TimetableOcr(private val context:Context) {
    private val recognizer=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    fun read(uri:Uri,done:(Result<OcrResult>)->Unit){
        try{
            val opt=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opt)}
            require(opt.outWidth>0 && opt.outHeight>0){"이미지를 읽을 수 없어요."}
            opt.inSampleSize=1
            while(maxOf(opt.outWidth,opt.outHeight)/opt.inSampleSize>2400)opt.inSampleSize*=2
            opt.inJustDecodeBounds=false
            val bitmap=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opt)}?:error("이미지를 읽을 수 없어요.")
            recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener{result->
                val lines=result.textBlocks.flatMap{it.lines}
                val lineBoxes=lines.mapNotNull{line->line.boundingBox?.let{b->TextBox(line.text,b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())}}
                // A header row can be one OCR line. Keep its individual axis elements too.
                val axisElements=lines.filter{it.elements.size>1}.flatMap{it.elements}.filter{
                    it.text.trim().removeSuffix("요일") in listOf("월","화","수","목","금","토","일") || Regex("^(\\d{1,2})(?::00|시)?$").matches(it.text.trim())
                }.mapNotNull{element->element.boundingBox?.let{b->TextBox(element.text,b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())}}
                val boxes=(lineBoxes+axisElements).distinct()
                val words=lines.flatMap{it.elements}.mapNotNull{element->element.boundingBox?.let{b->
                    TextBox(element.text.trim().trimStart('|','｜'),b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())
                }}
                val axis=GridParser.detectAxis(boxes)?.let{ColorBlockDetector.alignAxis(bitmap.width,bitmap.height,it,bitmap::getPixel)}
                val raw=OcrResult(bitmap,boxes,axis,emptyList(),words)
                if(axis==null)done(Result.success(raw))else readGrid(raw,axis,done)
            }.addOnFailureListener{done(Result.failure(it))}
        }catch(e:Exception){done(Result.failure(e))}
    }
    /** Read one colored cell at a time; neither weekday nor duration comes from OCR text. */
    fun readGrid(result:OcrResult,axis:GridAxis,done:(Result<OcrResult>)->Unit){
        val bitmap=result.bitmap
        val cells=ColorBlockDetector.detect(bitmap.width,bitmap.height,axis,bitmap::getPixel)
        val boxes=mutableListOf<TextBox>()
        fun next(index:Int){
            if(index==cells.size){
                done(Result.success(result.copy(boxes=boxes,axis=axis,drafts=parse(bitmap,boxes,axis))))
                return
            }
            val cell=cells[index]
            val left=(cell.left+4).coerceAtMost(cell.right-1);val top=cell.top
            val width=(cell.right-left-4).coerceAtLeast(1);val height=cell.bottom-top
            val pixels=IntArray(width*height)
            bitmap.getPixels(pixels,0,width,left,top,width,height)
            // Only retain words contained in this cell; whole-grid OCR lines can cross weekdays.
            val contextWords=result.wordBoxes.filter{it.left>=cell.left-1 && it.right<=cell.right+1 && it.y>=cell.top && it.y<cell.bottom}
            val variants=mutableListOf(contextWords)
            fun readVariant(mode:Int){
                val prepared=pixels.copyOf()
                val brightness=(Color.red(cell.color)+Color.green(cell.color)+Color.blue(cell.color))/3f
                if(mode!=0)for(i in prepared.indices){
                    val c=pixels[i]
                    if(mode==1)prepared[i]=if(ColorBlockDetector.isText(c,cell.color))Color.BLACK else Color.WHITE
                    else{
                        val value=(Color.red(c)+Color.green(c)+Color.blue(c))/3f
                        val strength=if(value>=brightness)(value-brightness)/(255-brightness).coerceAtLeast(1f) else (brightness-value)/brightness.coerceAtLeast(1f)
                        val gray=(255*(1-strength.coerceIn(0f,1f))).toInt()
                        prepared[i]=Color.rgb(gray,gray,gray)
                    }
                }
                val cleaned=Bitmap.createBitmap(prepared,width,height,Bitmap.Config.ARGB_8888)
                val factor=if(width<350)3 else 1
                val padding=12
                val input=Bitmap.createBitmap(width*factor+padding*2,height*factor+padding*2,Bitmap.Config.ARGB_8888)
                Canvas(input).apply{
                    drawColor(Color.WHITE)
                    drawBitmap(cleaned,null,android.graphics.Rect(padding,padding,padding+width*factor,padding+height*factor),Paint(Paint.FILTER_BITMAP_FLAG))
                }
                cleaned.recycle()
                recognizer.process(InputImage.fromBitmap(input,0)).addOnSuccessListener{read->
                    val lines=read.textBlocks.flatMap{it.lines}.mapNotNull{line->line.boundingBox?.let{b->
                        val text=line.text.trim().trimStart('|','｜')
                        if(text.isBlank())null else TextBox(text,left+(b.left-padding)/factor.toFloat(),top+(b.top-padding)/factor.toFloat(),left+(b.right-padding)/factor.toFloat(),top+(b.bottom-padding)/factor.toFloat())
                    }}
                    input.recycle();variants+=lines
                    if(mode<2)readVariant(mode+1)else{boxes+=OcrConsensus.merge(variants);next(index+1)}
                }.addOnFailureListener{input.recycle();done(Result.failure(it))}
            }
            readVariant(0)
        }
        try{next(0)}catch(e:Exception){done(Result.failure(e))}
    }
    fun parse(bitmap:Bitmap,boxes:List<TextBox>,axis:GridAxis):List<ImportDraft>{
        val cells=ColorBlockDetector.detect(bitmap.width,bitmap.height,axis,bitmap::getPixel)
        return GridParser.drafts(boxes,axis){x,y->
            cells.firstOrNull{x>=it.left && x<it.right && y>=it.top && y<it.bottom}?.let{it.top.toFloat() to it.bottom.toFloat()}
        }
    }
    fun close(){recognizer.close()}
}
