package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test

class MapProjectionTest {
    @Test fun roundTripCoordinatesAtMultipleZoomLevels(){
        for(z in listOf(3,12,16,19))for(p in listOf(37.55 to 126.95,0.0 to 0.0,-33.86 to 151.21)){
            val world=MapProjection.point(p.first,p.second,z);val actual=MapProjection.coordinates(world.first,world.second,z)
            assertEquals(p.first,actual.first,.000001);assertEquals(p.second,actual.second,.000001)
        }
    }
    @Test fun panningWrapsAtDateline(){
        val size=MapProjection.worldSize(16)
        assertEquals(0.0,MapProjection.coordinates(size*1.5,size/2,16).second,.000001)
    }
    @Test fun zoomingDoublesPixelRadiusOfSamePhysicalArea(){
        assertEquals(MapProjection.metersPerPixel(37.55,16)/2,MapProjection.metersPerPixel(37.55,17),.000001)
    }
    @Test fun polesStayFinite(){
        val p=MapProjection.point(90.0,0.0,16)
        assertTrue(p.first.isFinite());assertTrue(p.second.isFinite())
        assertEquals(MapProjection.MAX_LAT,MapProjection.coordinates(p.first,p.second,16).first,.000001)
    }
}
