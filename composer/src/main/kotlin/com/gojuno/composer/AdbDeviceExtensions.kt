package com.gojuno.composer

import com.gojuno.commander.android.AdbDevice

val AdbDevice.pathSafeId: String
    get() = this.id.replace(":", "_") // Windows' not fond of colons