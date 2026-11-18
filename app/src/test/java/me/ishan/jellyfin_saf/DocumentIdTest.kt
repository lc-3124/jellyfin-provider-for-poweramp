package me.ishan.jellyfin_saf

import org.jellyfin.sdk.model.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdTest {
	private val album = UUID.fromString("11111111-1111-1111-1111-111111111111")
	private val track = UUID.fromString("22222222-2222-2222-2222-222222222222")
	
	@Test
	fun rootsRoundTrip() {
		assertTrue(DocumentId.parse(DocumentId.ROOT_ID) is DocumentId.Type.Root)
		assertTrue(DocumentId.parse(DocumentId.CACHE_ROOT_ID) is DocumentId.Type.CacheRoot)
	}
	
	@Test
	fun onlineTrackRoundTrip() {
		val id = DocumentId.forTrack(album, track, 1234L, 5678L)
		val parsed = DocumentId.parse(id) as DocumentId.Type.Track
		assertEquals(album, parsed.albumId)
		assertEquals(track, parsed.trackId)
		assertEquals(1234L, parsed.sizeBytes)
		assertEquals(5678L, parsed.durationMs)
	}
	
	@Test
	fun cacheAlbumRoundTrip() {
		val id = DocumentId.forCacheAlbum(album)
		val parsed = DocumentId.parse(id) as DocumentId.Type.CacheAlbum
		assertEquals(album, parsed.albumId)
	}
	
	@Test
	fun cacheTrackRoundTrip() {
		val id = DocumentId.forCacheTrack(album, track, 0L, 0L)
		val parsed = DocumentId.parse(id) as DocumentId.Type.CacheTrack
		assertEquals(album, parsed.albumId)
		assertEquals(track, parsed.trackId)
		assertEquals(0L, parsed.sizeBytes)
		assertEquals(0L, parsed.durationMs)
	}
	
	@Test
	fun malformedIdsAreRejected() {
		assertNull(DocumentId.parse("nope"))
		assertNull(DocumentId.parse("trk;only-three;parts"))
		assertNull(DocumentId.parse("ctrk;a;b;c;d"))
	}
	
	@Test
	fun onlineAndCacheTracksAreDistinguishable() {
		val online = DocumentId.forTrack(album, track, 1L, 2L)
		val cache = DocumentId.forCacheTrack(album, track, 1L, 2L)
		assertTrue(DocumentId.parse(online) is DocumentId.Type.Track)
		assertTrue(DocumentId.parse(cache) is DocumentId.Type.CacheTrack)
	}
}
