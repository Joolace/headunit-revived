package com.andrerinas.headunitrevived.aap

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionKeyPolicyTest {

    @Test
    fun `unmapped back key keeps system back behavior`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                keyMappings = emptyMap(),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `mapped physical back key routes to projection`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                keyMappings = mapOf(KeyEvent.KEYCODE_BACK to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `physical back can be mapped to another Android Auto action`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                keyMappings = mapOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `non-back keys do not use the back override`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                keyMappings = mapOf(KeyEvent.KEYCODE_BACK to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER
            )
        )
    }
}
