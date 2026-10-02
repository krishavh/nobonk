package ai.genwhy.nobonk.testing

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Debug-only ordinary editor used to verify an overlay never covers or steals the keyboard. */
class TypingHarnessActivity : Activity() {
    lateinit var editor: EditText
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor = EditText(this).apply { hint = "Type while NoBonk runs" }
        setContentView(editor)
    }
    fun showKeyboard() {
        editor.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }
    fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
    }
}
