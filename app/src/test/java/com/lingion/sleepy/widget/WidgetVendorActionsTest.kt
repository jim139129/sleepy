package com.lingion.sleepy.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetVendorActionsTest {
    @Test
    fun `Xiaomi refresh action is the public AppVault broadcast`() {
        assertEquals(
            "miui.appwidget.action.APPWIDGET_UPDATE",
            WidgetVendorActions.XIAOMI_UPDATE_ACTION
        )
    }
}
