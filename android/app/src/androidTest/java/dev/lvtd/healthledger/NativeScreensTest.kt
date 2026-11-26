package dev.lvtd.healthledger

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeScreensTest {
    private fun children(v: View): List<View> = buildList {
        add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) addAll(children(v.getChildAt(i)))
    }

    private fun waitFor(s: ActivityScenario<LedgerScreenActivity>, text: String, action: (List<View>)->Unit = {}) {
        repeat(80) {
            var found = false
            s.onActivity { activity ->
                val views=children(activity.window.decorView)
                assertFalse(views.any { it is android.webkit.WebView })
                if (views.filterIsInstance<TextView>().any { it.text.toString()==text }) {
                    found=true
                    action(views)
                }
            }
            if(found) return
            Thread.sleep(100)
        }
        fail("Missing native control: $text")
    }

    @Test fun formsAndCredentialsStayOnSelectedOrigin() {
        val origin="https://journal.example:8443"
        assertEquals("/settings?saved=name",localPath(origin,"/settings","/settings?saved=name"))
        for (other in listOf("//example.org", "http://journal.example:8443", "https://journal.example", "file:///tmp/file")) {
            assertTrue(runCatching { localPath(origin,"/sources",other) }.isFailure)
        }
    }

    @Test fun signInConnectAndDisconnectOnLoopback() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires disposable USB loopback server",args.getString("nativeScreens")=="true")
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val origin="http://127.0.0.1:38689"
        LedgerApp.servers.edit().putString("origin",origin).apply()
        fun screen(path: String,title: String)=ActivityScenario.launch<LedgerScreenActivity>(
            Intent(ctx,LedgerScreenActivity::class.java).putExtra("path",path).putExtra("title",title))
        fun submit(views: List<View>, email:String, password:String, label:String) {
            val edits=views.filterIsInstance<EditText>()
            assertEquals(2,edits.size)
            edits[0].setText(email); edits[1].setText(password)
            views.filterIsInstance<Button>().single { it.text==label }.performClick()
        }
        screen("/sign-in","Sign in").use { activity ->
            waitFor(activity,"Password") { views -> submit(views,"phone-probe@example.test","local-native-fixture","Sign in") }
            for(i in 1..60) { if(LedgerSession.current()!=null) break; Thread.sleep(100) }
            assertNotNull(LedgerSession.current())
        }
        screen("/sources#source-picooc","PICOOC").use { activity ->
            waitFor(activity,"Connect PICOOC") { views -> submit(views,"scale@example.test","synthetic-provider-only","Connect PICOOC") }
            waitFor(activity,"Disconnect PICOOC") { views -> views.filterIsInstance<Button>().single { it.text=="Disconnect PICOOC" }.performClick() }
            activity.onActivity {
                val confirm = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .mapNotNull { window -> window.findViewById<Button>(android.R.id.button1) }.single()
                assertEquals("Continue", confirm.text.toString())
                confirm.performClick()
            }
            waitFor(activity,"Connect PICOOC")
        }
        for ((path,title,expected) in listOf(
            Triple("/dashboard#reminders","Reminders","Save schedule"),
            Triple("/dashboard#medicines","Medicines","Save course"),
            Triple("/dashboard#files","Documents","Upload document"),
            Triple("/settings#profile","Profile","Save changes"),
            Triple("/sources#manual-import","Import history","No recent imports.")
        )) screen(path,title).use { waitFor(it,expected) }
    }
}
