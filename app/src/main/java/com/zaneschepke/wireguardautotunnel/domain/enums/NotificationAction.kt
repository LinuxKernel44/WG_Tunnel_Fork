package com.zaneschepke.wireguardautotunnel.domain.enums

import android.content.Context
import com.zaneschepke.wireguardautotunnel.R

enum class NotificationAction {
    TUNNEL_OFF,
    AUTO_TUNNEL_OFF,
    STOP_ALL,
    REACTIVATE,
    PAUSE,
    RESUME,
    PAUSE_ADD_5_MIN,
    PAUSE_ADD_15_MIN,
    PAUSE_INDEFINITE;

    fun title(context: Context): String {
        return when (this) {
            TUNNEL_OFF -> context.getString(R.string.stop)
            AUTO_TUNNEL_OFF -> context.getString(R.string.stop)
            STOP_ALL -> context.getString(R.string.stop_all)
            REACTIVATE -> context.getString(R.string.reactivate)
            PAUSE -> context.getString(R.string.pause)
            RESUME -> context.getString(R.string.resume)
            PAUSE_ADD_5_MIN -> context.getString(R.string.pause_add_5_min)
            PAUSE_ADD_15_MIN -> context.getString(R.string.pause_add_15_min)
            PAUSE_INDEFINITE -> context.getString(R.string.pause_indefinite_short)
        }
    }
}
