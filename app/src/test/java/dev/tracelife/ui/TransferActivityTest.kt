package dev.tracelife.ui

import android.app.Activity
import android.content.Intent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.transfer.CompletedExports
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferActivityTest {
    private lateinit var controller: ActivityController<TransferActivity>
    private lateinit var app: TraceLifeApplication
    private val activity get() = controller.get()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {
            app.repository.createRecord(RecordDraft("合成导出资料：先做小练习再查资料。", Occurrence(LocalDate.now())), "synthetic-export-record")
        }.get(20, TimeUnit.SECONDS)
        controller = Robolectric.buildActivity(TransferActivity::class.java).setup()
        drain()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `reading export generates private complete zip before launching system save and survives cancelled picker`() {
        click("navReading")
        click("exportReading")
        val launched = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.intent.action)
        assertEquals("application/zip", launched.intent.type)
        val name = requireNotNull(launched.intent.getStringExtra(Intent.EXTRA_TITLE))
        val file = requireNotNull(CompletedExports(app).get(name))
        assertTrue(file.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath))
        ZipFile(file).use { archive -> assertNotNull(archive.getEntry("manifest.json")) }
        activity.onActivityResult(launched.requestCode, Activity.RESULT_CANCELED, null)
        drain()
        assertTrue(file.isFile)
        assertTrue(texts().any { it.contains("等待另存") })
        click("saveReading")
        assertEquals(name, shadowOf(activity).nextStartedActivityForResult.intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun `backup is encrypted and picker recreation keeps file without regenerating or saving the password`() {
        click("navBackup")
        field("备份口令").setText("synthetic-export-password")
        click("createBackup")
        val launched = shadowOf(activity).nextStartedActivityForResult
        val name = requireNotNull(launched.intent.getStringExtra(Intent.EXTRA_TITLE))
        val file = requireNotNull(CompletedExports(app).get(name))
        assertEquals("application/octet-stream", launched.intent.type)
        val password = "synthetic-export-password".toCharArray()
        val preview = app.io.submit<dev.tracelife.transfer.RestorePreview> { app.dataTransfer.previewBackup(file, password) }.get(20, TimeUnit.SECONDS)
        password.fill('\u0000')
        assertEquals(1, preview.records)
        controller.recreate()
        drain()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals("", field("备份口令").text.toString())
        assertEquals(listOf(name), CompletedExports(app).list().map { it.name })
        activity.onActivityResult(launched.requestCode, Activity.RESULT_CANCELED, null)
        click("saveBackup")
        assertEquals(name, shadowOf(activity).nextStartedActivityForResult.intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun `completed files remain available from entry screen after a fresh activity`() {
        click("navReading")
        click("exportReading")
        val launched = shadowOf(activity).nextStartedActivityForResult
        val name = requireNotNull(launched.intent.getStringExtra(Intent.EXTRA_TITLE))
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(TransferActivity::class.java).setup()
        drain()
        click("saveExport:$name")
        assertEquals(name, shadowOf(activity).nextStartedActivityForResult.intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(1, CompletedExports(app).list().size)
        assertNull(CompletedExports(app).get("../$name"))
    }

    @Test fun `picker result after recreation delivers the same complete snapshot and shows verified success`() {
        click("navReading")
        click("exportReading")
        val launched = shadowOf(activity).nextStartedActivityForResult
        val name = requireNotNull(launched.intent.getStringExtra(Intent.EXTRA_TITLE))
        val original = requireNotNull(CompletedExports(app).get(name)).readBytes()
        val destination = File(app.cacheDir, "synthetic-system-document.zip")
        val provider = SyntheticDocumentProvider(destination).apply {
            attachInfo(app, ProviderInfo().apply { authority = "synthetic-export-provider"; exported = false })
        }
        ShadowContentResolver.registerProviderInternal("synthetic-export-provider", provider)
        controller.recreate()
        drain()
        activity.onActivityResult(launched.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://synthetic-export-provider/created-document")))
        drain()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("File was not delivered; dialog: ${dialog?.findViewById<TextView>(android.R.id.message)?.text}", destination.exists())
        assertArrayEquals(original, destination.readBytes())
        assertTrue(texts().contains("文档已另存"))
        assertEquals(1, CompletedExports(app).list().size)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts() = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun field(label: String): EditText = descendants(activity.window.decorView).filterIsInstance<TextView>()
        .filter { it !is EditText && it.text.toString() == label }.mapNotNull { caption ->
            val parent = caption.parent as? ViewGroup ?: return@mapNotNull null
            parent.getChildAt(parent.indexOfChild(caption) + 1) as? EditText
        }.single()
    private fun click(tag: String) {
        assertTrue(requireNotNull(activity.window.decorView.findViewWithTag<View>(tag)) { "Missing $tag" }.performClick())
        drain()
    }
    private fun drain() {
        repeat(4) { app.io.submit {}.get(30, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }

    private class SyntheticDocumentProvider(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        override fun getType(uri: Uri) = "application/zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
