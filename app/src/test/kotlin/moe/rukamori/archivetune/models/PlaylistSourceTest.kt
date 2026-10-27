package moe.rukamori.archivetune.models

import moe.rukamori.archivetune.db.entities.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSourceTest {
    @Test
    fun playlistLinkedToSpotifyIsSpotify() {
        assertEquals(PlaylistSource.SPOTIFY, PlaylistEntity(name = "p", spotifyId = "sp").playlistSource())
    }

    @Test
    fun playlistWithBrowseIdIsYouTubeEvenWhenEditable() {
        assertEquals(
            PlaylistSource.YOUTUBE,
            PlaylistEntity(name = "p", browseId = "VL1", isEditable = true).playlistSource(),
        )
    }

    @Test
    fun readOnlyLocalPlaylistIsNotMistakenForYouTube() {
        assertEquals(PlaylistSource.PERSONAL, PlaylistEntity(name = "p", isEditable = false).playlistSource())
    }

    @Test
    fun playlistWithoutRemoteLinkIsPersonal() {
        assertEquals(PlaylistSource.PERSONAL, PlaylistEntity(name = "p").playlistSource())
    }
}
