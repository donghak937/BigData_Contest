package kr.heureum.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.heureum.app.core.*
import kr.heureum.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutomaticPlacesDeviceTest {
    @Test fun jsonReadsClosedBuildingsWithoutInventingCodes(){
        val data=JSONObject("""{"elements":[{"type":"way","id":1,"tags":{"name":"생활관","building":"dormitory"},"geometry":[{"lat":37,"lon":127},{"lat":37,"lon":127.001},{"lat":37.001,"lon":127.001},{"lat":37,"lon":127}]},{"type":"node","id":2,"lat":37,"lon":127,"tags":{"name":"학술관","amenity":"library"}}]}""")
        val places=OsmPlaces.parse(data)
        assertEquals(2,places.size);assertEquals("dorm",places[0].kind);assertEquals(1,places[0].outlines.size)
        assertEquals("label",places[1].relation(GeoSample(37.0,127.0,10f,0)))
        assertFalse(places[0].matchesRoom("NTH 311"))
    }
    @Test fun incompleteRelationsCannotCreateFootprints(){
        val data=JSONObject("""{"elements":[{"type":"relation","id":3,"tags":{"name":"학교","amenity":"university"},"center":{"lat":37,"lon":127},"members":[{"type":"way","role":"outer","geometry":[{"lat":37,"lon":127},{"lat":37.001,"lon":127.001}]}]}]}""")
        assertTrue(OsmPlaces.parse(data).single().outlines.isEmpty())
    }
    @Test fun defaultIsOfflineAndAutomaticCacheCanBeCleared(){
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=Preferences(c,"automatic-test")
        try{prefs.clear();assertFalse(prefs.automaticPlaces);prefs.automaticPlaces=true;prefs.placeConsentSeen=true
            assertTrue(Preferences(c,"automatic-test").automaticPlaces);assertTrue(Preferences(c,"automatic-test").placeConsentSeen)
        }finally{prefs.clear()}
    }
    @Test fun publicCampusDataContainsDormsAndNamedBuildings(){
        val path=InstrumentationRegistry.getArguments().getString("publicPlacesFile")
        assumeTrue("Optional public map snapshot regression",path!=null)
        val places=OsmPlaces.parse(JSONObject(File(path!!).readText().removePrefix("\uFEFF")))
        assertEquals(9,places.count{it.kind=="dorm" && it.outlines.isNotEmpty()})
        val newton=places.single{it.name=="뉴턴홀"};assertTrue(newton.outlines.isNotEmpty())
        assertEquals("classroom",newton.kind)
        val dorm=places.single{it.name=="비전관"}
        assertEquals("inside",dorm.relation(GeoSample(dorm.center.latitude,dorm.center.longitude,1f,System.currentTimeMillis())))
    }
}
