package com.bayrano.wake

/**
 * Which glasses gesture wakes the assistant.
 *
 * On Ray-Ban Meta these arrive over Bluetooth AVRCP as media transport events:
 *  - [VOLUME_UP]   — swipe forward on the temple == AVRCP "volume up".
 *  - [DOUBLE_TAP]  — double-tap == AVRCP "skip to next track".
 *
 * Both only reach us while BayRanO is the active media session (see WakeService).
 */
enum class WakeTrigger(val label: String) {
    VOLUME_UP("Swipe forward (volume up)"),
    DOUBLE_TAP("Double-tap (skip track)"),
}
