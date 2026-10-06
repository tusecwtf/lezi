package com.lezi.babylog.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 11 — export primary/secondary busy presentation is a pure public seam.
 * Expected labels are product Chinese literals from the export surface.
 */
class ExportActionChromeTest {

    @Test
    fun idleKeepsBothLabelsAndEnablesControls() {
        val chrome = exportActionChrome(busyFormat = null)
        assertEquals("导出 TXT 并分享", chrome.txtLabel)
        assertEquals("导出 PDF 并分享", chrome.pdfLabel)
        assertFalse(chrome.txtBusy)
        assertFalse(chrome.pdfBusy)
        assertTrue(chrome.controlsEnabled)
    }

    @Test
    fun pdfBusyOwnsPrimarySpinnerOnly() {
        val chrome = exportActionChrome(busyFormat = ExportFormat.Pdf)
        assertEquals("导出 TXT 并分享", chrome.txtLabel)
        assertEquals("正在生成…", chrome.pdfLabel)
        assertFalse(chrome.txtBusy)
        assertTrue(chrome.pdfBusy)
        assertFalse(chrome.controlsEnabled)
    }

    @Test
    fun txtBusyOwnsSecondarySpinnerOnly() {
        val chrome = exportActionChrome(busyFormat = ExportFormat.Txt)
        assertEquals("正在生成…", chrome.txtLabel)
        assertEquals("导出 PDF 并分享", chrome.pdfLabel)
        assertTrue(chrome.txtBusy)
        assertFalse(chrome.pdfBusy)
        assertFalse(chrome.controlsEnabled)
    }

    @Test
    fun sharePendingLocksControlsWithoutGenerateBusyLabels() {
        val chrome = exportActionChrome(busyFormat = null, sharePending = true)
        assertEquals("导出 TXT 并分享", chrome.txtLabel)
        assertEquals("导出 PDF 并分享", chrome.pdfLabel)
        assertFalse(chrome.txtBusy)
        assertFalse(chrome.pdfBusy)
        assertFalse(chrome.controlsEnabled)
    }
}
