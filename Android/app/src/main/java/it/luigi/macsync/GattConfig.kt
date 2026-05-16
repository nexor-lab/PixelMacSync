package it.luigi.macsync

import java.util.UUID

object GattConfig {
    // Service UUIDs
    val SYSTEM_SERVICE_UUID: UUID = UUID.fromString("b4250001-1000-4000-8000-00805f9b34fb")
    val NOTIF_SERVICE_UUID: UUID = UUID.fromString("b4250002-1000-4000-8000-00805f9b34fb")

    // Characteristic UUIDs - System
    val PROTOCOL_VERSION_CHAR: UUID = UUID.fromString("b4250010-1000-4000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("b4250011-1000-4000-8000-00805f9b34fb")
    val BATTERY_DETAIL_CHAR: UUID = UUID.fromString("b4250015-1000-4000-8000-00805f9b34fb")
    val NETWORK_STATE_CHAR: UUID = UUID.fromString("b4250013-1000-4000-8000-00805f9b34fb")
    val AUDIO_PROFILE_CHAR: UUID = UUID.fromString("b4250016-1000-4000-8000-00805f9b34fb")
    val DND_MODE_CHAR: UUID = UUID.fromString("b4250017-1000-4000-8000-00805f9b34fb")
    val HOTSPOT_TOGGLE_CHAR: UUID = UUID.fromString("b4250014-1000-4000-8000-00805f9b34fb")

    // Characteristic UUIDs - Notif
    val ACTIVE_NOTIF_CHAR: UUID = UUID.fromString("b4250021-1000-4000-8000-00805f9b34fb")

    const val DELIMITER = "\u001F"
}