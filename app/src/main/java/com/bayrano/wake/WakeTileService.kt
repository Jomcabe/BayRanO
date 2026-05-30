package com.bayrano.wake

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bayrano.app.BayRanOApp

/**
 * Quick Settings tile that fires the assistant on tap — a wake path that works
 * regardless of audio focus or which gesture mode is selected (unlike the
 * AVRCP-based glasses gesture in [WakeService]).
 */
class WakeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Ask BayRanO"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        (applicationContext as BayRanOApp).assistantEngine.onWake()
    }
}
