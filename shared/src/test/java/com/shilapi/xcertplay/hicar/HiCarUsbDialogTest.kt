package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which window the accessibility service is allowed to click in.
 *
 * A wrong answer here presses a button in someone else's dialog, which is why the rule is narrow and
 * why it is tested rather than eyeballed: both a USB mention and a confirming label have to be in the
 * same window, and the charging notification is excluded by name.
 */
class HiCarUsbDialogTest {

    @Test
    fun `the standard prompt is recognised and the button named`() {
        val texts = listOf("是否允许访问 USB 设备？", "允许", "拒绝")
        assertEquals("允许", HiCarUsbDialog.confirmLabel(texts))
    }

    @Test
    fun `the accessory phrasing and the english dialog both work`() {
        assertEquals("确定", HiCarUsbDialog.confirmLabel(listOf("使用 USB 配件？", "取消", "确定")))
        assertEquals("Allow", HiCarUsbDialog.confirmLabel(listOf("Allow access to USB device?", "Deny", "Allow")))
        assertEquals("OK", HiCarUsbDialog.confirmLabel(listOf("USB accessory", "OK")))
    }

    @Test
    fun `a dialog without any usb mention is left alone`() {
        // A camera or location prompt also has 允许; pressing it would grant something nobody asked for.
        assertNull(HiCarUsbDialog.confirmLabel(listOf("允许访问相机？", "允许", "拒绝")))
        assertNull(HiCarUsbDialog.confirmLabel(listOf("是否允许「HiCar」访问你的通讯录？", "允许")))
    }

    @Test
    fun `a usb mention without a confirming button is not pressed`() {
        assertNull(HiCarUsbDialog.confirmLabel(listOf("USB 调试已连接", "打开设置")))
        assertNull(HiCarUsbDialog.confirmLabel(listOf("USB")))
    }

    @Test
    fun `the charging notification is never mistaken for the prompt`() {
        // It says USB and it has a button; it just is not the one that grants the device.
        assertNull(HiCarUsbDialog.confirmLabel(listOf("正在通过 USB 充电", "允许", "打开")))
        assertNull(HiCarUsbDialog.confirmLabel(listOf("Charging this device via USB", "Allow")))
    }

    @Test
    fun `blank text never matches`() {
        assertNull(HiCarUsbDialog.confirmLabel(listOf("", "   ")))
        assertNull(HiCarUsbDialog.confirmLabel(emptyList()))
        // Padding around a label is trimmed, because the system adds it for accessibility.
        assertEquals("允许", HiCarUsbDialog.confirmLabel(listOf("USB", "  允许  ")))
    }
}
