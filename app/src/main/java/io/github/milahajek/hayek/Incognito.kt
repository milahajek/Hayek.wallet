package io.github.milahajek.hayek

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Upraví požadavek na klávesnici tak, aby se nic neučila, nic nenavrhovala a nic neukládala.
 * - IME_FLAG_NO_PERSONALIZED_LEARNING = oficiální „anonymní režim“ klávesnice v Androidu
 * - VISIBLE_PASSWORD + NO_SUGGESTIONS = klávesnice text bere jako heslo (neučí se ho), ale zůstává vidět
 */
fun applyIncognito(info: EditorInfo) {
    info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    info.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
}

/** Obal pro textová pole, kam se píší obnovovací slova nebo klíče. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IncognitoKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modified = PlatformTextInputMethodRequest { outAttrs ->
                request.createInputConnection(outAttrs).also { applyIncognito(outAttrs) }
            }
            nextHandler.startInputMethod(modified)
        },
        content = content,
    )
}
