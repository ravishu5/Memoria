package dev.miniscreenpipe

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A separate test APK's screen, never shipped in the product. */
class CaptureFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 120, 40, 40)
            addView(TextView(this@CaptureFixtureActivity).apply { text = "Kotlin memory capture verification"; textSize = 26f })
            if(intent.getBooleanExtra("sensitive",false))addView(TextView(this@CaptureFixtureActivity).apply {
                text="Contact person@example.com or +91 98765 43210";textSize=20f
            })
            intent.getStringExtra("url")?.let { url -> addView(TextView(this@CaptureFixtureActivity).apply {
                id=dev.miniscreenpipe.test.R.id.url_bar;text=url;textSize=20f
            }) }
            if (intent.getBooleanExtra("password", false) || intent.getBooleanExtra("hiddenPassword",false)) addView(EditText(this@CaptureFixtureActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("test-password-never-record")
                if(intent.getBooleanExtra("hiddenPassword",false))visibility=android.view.View.INVISIBLE
            })
        })
    }
}
