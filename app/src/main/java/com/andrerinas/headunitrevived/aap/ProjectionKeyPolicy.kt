package com.andrerinas.headunitrevived.aap

import android.view.KeyEvent

object ProjectionKeyPolicy {

    fun shouldRouteBackKeyToProjection(keyMappings: Map<Int, Int>, keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK &&
                keyMappings.any { (_, physicalKeyCode) -> physicalKeyCode == KeyEvent.KEYCODE_BACK }
    }
}
