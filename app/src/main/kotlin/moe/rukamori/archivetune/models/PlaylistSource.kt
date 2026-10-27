/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.models

import moe.rukamori.archivetune.db.entities.PlaylistEntity

/** Where a local playlist comes from, as shown by the badge in the library. */
enum class PlaylistSource {
    PERSONAL,
    YOUTUBE,
    SPOTIFY,
}

fun PlaylistEntity.playlistSource(): PlaylistSource =
    when {
        browseId != null -> PlaylistSource.YOUTUBE
        spotifyId != null -> PlaylistSource.SPOTIFY
        else -> PlaylistSource.PERSONAL
    }
