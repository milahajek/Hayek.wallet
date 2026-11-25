package io.github.milahajek.hayek

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncognitoTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun editor(root: View): View? {
        if (root.onCheckIsTextEditor()) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) editor(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun editorInfoFor(screen: Screen): EditorInfo {
        rule.runOnUiThread { ViewModelProvider(rule.activity)[WalletViewModel::class.java].screen = screen }
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction())[0].performClick()
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnUiThread { editor(rule.activity.window.decorView)!!.onCreateInputConnection(info) }
        return info
    }

    private fun incognito(i: EditorInfo) = i.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    @Test fun restoreIsIncognito() {
        val i = editorInfoFor(Screen.Restore)
        assertTrue("chybí NO_PERSONALIZED_LEARNING", incognito(i))
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, i.inputType and InputType.TYPE_MASK_VARIATION)
        assertTrue(i.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        println("RESTORE imeOptions=0x" + i.imeOptions.toString(16) + " inputType=0x" + i.inputType.toString(16))
    }

    @Test fun verifyIsIncognito() {
        assertTrue(incognito(editorInfoFor(Screen.Verify("abandon ".repeat(23) + "art"))))
    }

    @Test fun watchIsIncognito() {
        assertTrue(incognito(editorInfoFor(Screen.WatchImport)))
    }

    @Test fun serverFieldIsNormal() {
        val i = editorInfoFor(Screen.Settings)
        assertTrue("pole serveru nemá být anonymní", !incognito(i))
        println("SETTINGS imeOptions=0x" + i.imeOptions.toString(16))
    }
}
