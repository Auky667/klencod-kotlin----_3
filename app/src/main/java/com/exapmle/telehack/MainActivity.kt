package com.example.telegrammedia

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    // منفّذ منفصل لرفع الملفات حتى لا تتجمّد واجهة التطبيق أثناء الإرسال
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var tokenInput: EditText
    private lateinit var chatIdInput: EditText
    private lateinit var statusText: TextView
    private lateinit var chooseButton: Button
    private lateinit var sendButton: Button

    // لا يحتوي هذا المتغير إلا على الملفات التي اختارها المستخدم بنفسه
    private var selectedFiles: List<Uri> = emptyList()

    // أداة اختيار ملفات أندرويد الرسمية، ولا تمنح التطبيق صلاحية فحص بقية الهاتف
    private val filePicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            selectedFiles = uris
            statusText.text = if (uris.isEmpty()) {
                "لم يتم اختيار أي ملفات."
            } else {
                "تم اختيار ${uris.size} ملف. اضغط إرسال عندما تكون مستعدًا."
            }
            sendButton.isEnabled = uris.isNotEmpty()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إنشاء الواجهة برمجيًا لتبقى الأمثلة في ملف Kotlin واحد
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }

        val scroll = ScrollView(this)
        scroll.addView(page)

        page.addView(TextView(this).apply {
            text = "إرسال ملفات مختارة إلى تيليجرام"
            textSize = 22f
        })

        page.addView(TextView(this).apply {
            text = "أدخل بيانات البوت وChat ID. لا تشارك Token مع أي شخص."
            textSize = 14f
        })

        tokenInput = EditText(this).apply {
            hint = "Bot Token"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        page.addView(tokenInput, matchWidth())

        chatIdInput = EditText(this).apply {
            hint = "Chat ID"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        page.addView(chatIdInput, matchWidth())

        chooseButton = Button(this).apply {
            text = "اختيار الصور والفيديوهات"
            setOnClickListener {
                // يفتح منتقي الملفات؛ لن يتم إرسال شيء بمجرد الاختيار
                filePicker.launch(arrayOf("image/*", "video/*"))
            }
        }
        page.addView(chooseButton, matchWidth())

        sendButton = Button(this).apply {
            text = "إرسال الملفات المختارة"
            isEnabled = false
            setOnClickListener { sendSelectedFiles() }
        }
        page.addView(sendButton, matchWidth())

        statusText = TextView(this).apply {
            text = "لم يتم اختيار ملفات بعد."
            textSize = 16f
        }
        page.addView(statusText, matchWidth())

        setContentView(scroll)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 18
        }

    private fun sendSelectedFiles() {
        // التحقق من البيانات قبل بدء الإرسال
        val token = tokenInput.text.toString().trim()
        val chatId = chatIdInput.text.toString().trim()

        if (token.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "أدخل Bot Token وChat ID أولًا", Toast.LENGTH_LONG).show()
            return
        }

        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "اختر ملفًا واحدًا على الأقل", Toast.LENGTH_LONG).show()
            return
        }

        // نأخذ نسخة من القائمة حتى لا تتغير أثناء عملية الإرسال
        val filesToSend = selectedFiles.toList()
        sendButton.isEnabled = false
        chooseButton.isEnabled = false
        statusText.text = "جارٍ تجهيز الإرسال..."

        executor.execute {
            var successCount = 0
            var failureCount = 0

            filesToSend.forEachIndexed { index, uri ->
                runOnUiThread {
                    statusText.text = "جارٍ إرسال الملف ${index + 1} من ${filesToSend.size}..."
                }

                try {
                    uploadOneFile(token, chatId, uri)
                    successCount++
                    runOnUiThread {
                        statusText.text = "تم إرسال ${successCount} ملف بنجاح."
                    }
                } catch (error: Exception) {
                    failureCount++
                    // نعرض اسم الملف وسبب الفشل للمستخدم
                    val name = getFileName(uri)
                    runOnUiThread {
                        statusText.text =
                            "فشل إرسال: $name\nالسبب: ${error.message ?: "خطأ غير معروف"}"
                    }
                }
            }

            runOnUiThread {
                statusText.text =
                    "انتهى الإرسال.\nنجح: $successCount\nفشل: $failureCount"
                sendButton.isEnabled = selectedFiles.isNotEmpty()
                chooseButton.isEnabled = true
            }
        }
    }

    // إرسال ملف واحد إلى Telegram Bot API باستخدام multipart/form-data
    @Throws(Exception::class)
    private fun uploadOneFile(token: String, chatId: String, uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(uri)
        val boundary = "----AndroidBoundary${UUID.randomUUID()}"

        // نختار طريقة تيليجرام المناسبة حسب نوع الملف
        val method = when {
            mimeType.startsWith("image/") -> "sendPhoto"
            mimeType.startsWith("video/") -> "sendVideo"
            else -> "sendDocument"
        }

        val fieldName = when (method) {
            "sendPhoto" -> "photo"
            "sendVideo" -> "video"
            else -> "document"
        }

        val connection = (URL("https://api.telegram.org/bot$token/$method")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
        }

        try {
            DataOutputStream(connection.outputStream).use { output ->
                // إضافة Chat ID الذي سيستقبل الملف
                writeTextPart(output, boundary, "chat_id", chatId)

                // بداية جزء الملف
                output.writeBytes("--$boundary\r\n")
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"$fieldName\"; " +
                            "filename=\"${safeFileName(fileName)}\"\r\n"
                )
                output.writeBytes("Content-Type: $mimeType\r\n\r\n")

                // قراءة الملف المختار ونسخه إلى اتصال تيليجرام
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                } ?: throw IOException("تعذّر فتح الملف المختار")

                output.writeBytes("\r\n--$boundary--\r\n")
                output.flush()
            }

            // قراءة رد تيليجرام لمعرفة هل تم الإرسال فعلًا
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = responseStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode !in 200..299 || !response.contains("\"ok\":true")) {
                throw IOException("Telegram API ($responseCode): $response")
            }
        } finally {
            connection.disconnect()
        }
    }

    // إضافة حقل نصي داخل طلب multipart
    private fun writeTextPart(
        output: DataOutputStream,
        boundary: String,
        fieldName: String,
        value: String
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"$fieldName\"\r\n\r\n"
        )
        output.write(value.toByteArray(Charsets.UTF_8))
        output.writeBytes("\r\n")
    }

    // الحصول على اسم الملف من مزوّد الملفات في أندرويد
    private fun getFileName(uri: Uri): String {
        var name = "selected_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }

    // إزالة علامات الاقتباس والأسطر الجديدة من اسم الملف لتفادي كسر ترويسة الطلب
    private fun safeFileName(name: String): String =
        name.replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")

    override fun onDestroy() {
        // إيقاف المنفّذ عند إغلاق الشاشة
        executor.shutdown()
        super.onDestroy()
    }
}