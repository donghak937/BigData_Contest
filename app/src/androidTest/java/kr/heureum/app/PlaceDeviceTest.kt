package kr.heureum.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.heureum.app.core.*
import kr.heureum.app.data.Preferences
import kr.heureum.app.ui.CampusMapView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceDeviceTest {
    @Test fun zonesPersistUpdateDeleteAndKeepOldCampus(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=Preferences(context,"place-test")
        try{
            prefs.clear();val campus=Campus("학교",37.55,126.95,600f);prefs.saveCampus(campus)
            val dorm=PlaceZone("1","기숙사","dorm",37.55,126.95,60f)
            prefs.saveZone(dorm);prefs.saveZone(dorm.copy(radiusM=80f))
            val reloaded=Preferences(context,"place-test")
            assertEquals(campus,reloaded.campus());assertEquals(listOf(dorm.copy(radiusM=80f)),reloaded.zones())
            assertFalse(reloaded.placeSettingsJson(false).getJSONObject("campus").has("latitude"))
            assertFalse(reloaded.placeSettingsJson(false).getJSONArray("zones").getJSONObject(0).has("latitude"))
            assertEquals(37.55,reloaded.placeSettingsJson(true).getJSONArray("zones").getJSONObject(0).getDouble("latitude"),.000001)
            reloaded.deleteZone("1");assertTrue(reloaded.zones().isEmpty());assertEquals(campus,reloaded.campus())
        }finally{prefs.clear()}
    }
    @Test fun mapRendersMovesAndZoomsWithoutNetwork(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync{
            val map=CampusMapView(instrumentation.targetContext,37.55,126.95,false)
            try{
                map.layout(0,0,600,800);map.campus=Campus("학교",37.55,126.95,400f)
                map.zones=listOf(PlaceZone("1","기숙사","dorm",37.55,126.95,60f))
                var lat=0.0;map.onCenterChanged={a,_->lat=a}
                map.centerOn(37.553,126.95);map.changeZoom(1)
                assertEquals(37.553,lat,.000001);assertEquals(17,map.zoom)
                val image=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
                map.draw(Canvas(image));assertTrue(image.getPixel(300,400)!=0);image.recycle()
            }finally{map.close()}
        }
    }
}
