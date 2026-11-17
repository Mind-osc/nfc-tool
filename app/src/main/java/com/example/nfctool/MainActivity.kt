package com.example.nfctool

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfc: NfcAdapter? = null
    private var pendingWrite: NdefMessage? = null

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var typeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContentView(buildUi())

        if (nfc == null) status.text = "❌ Η συσκευή δεν έχει NFC"
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            text = "📖 Λειτουργία ανάγνωσης — πλησίασε ένα tag"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad)
        }
        typeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply { id = 1; text = "Κείμενο"; isChecked = true })
            addView(RadioButton(context).apply { id = 2; text = "URL" })
        }
        input = EditText(this).apply { hint = "Τι να γραφτεί στο tag" }

        val writeBtn = Button(this).apply {
            text = "✍️ Εγγραφή σε tag"
            setOnClickListener { armWrite() }
        }
        val cancelBtn = Button(this).apply {
            text = "Ακύρωση εγγραφής"
            setOnClickListener {
                pendingWrite = null
                status.text = "📖 Λειτουργία ανάγνωσης — πλησίασε ένα tag"
            }
        }
        output = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        listOf(status, typeGroup, input, writeBtn, cancelBtn, output).forEach(root::addView)
        return ScrollView(this).apply { addView(root) }
    }

    private fun armWrite() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast("Γράψε κάτι πρώτα"); return }
        val record = if (typeGroup.checkedRadioButtonId == 2)
            NdefRecord.createUri(if (text.contains("://")) text else "https://$text")
        else
            NdefRecord.createTextRecord("el", text)
        pendingWrite = NdefMessage(arrayOf(record))
        status.text = "✍️ Έτοιμο για εγγραφή — πλησίασε το tag"
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc ?: return
        if (!adapter.isEnabled) {
            status.text = "⚠️ Το NFC είναι κλειστό"
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(this, this, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    // Καλείται σε background thread όταν βρεθεί tag
    override fun onTagDiscovered(tag: Tag) {
        val msg = pendingWrite
        val result = if (msg != null) writeTag(tag, msg) else readTag(tag)
        runOnUiThread {
            output.text = result
            if (msg != null && result.startsWith("✅")) {
                pendingWrite = null
                status.text = "📖 Λειτουργία ανάγνωσης — πλησίασε ένα tag"
            }
        }
    }

    private fun readTag(tag: Tag): String {
        val sb = StringBuilder()
        sb.append("UID: ").append(tag.id.toHex()).append('\n')
        sb.append("Τεχνολογίες: ").append(tag.techList.joinToString { it.substringAfterLast('.') }).append("\n\n")

        val ndef = Ndef.get(tag) ?: return sb.append("Το tag δεν είναι NDEF (ή είναι κενό/μη φορμαρισμένο)").toString()
        try {
            ndef.connect()
            sb.append("Τύπος: ${ndef.type}\n")
            sb.append("Χωρητικότητα: ${ndef.maxSize} bytes\n")
            sb.append("Εγγράψιμο: ${if (ndef.isWritable) "Ναι" else "Όχι"}\n\n")
            val message = ndef.ndefMessage
            if (message == null || message.records.isEmpty()) {
                sb.append("(Κενό tag)")
            } else {
                message.records.forEachIndexed { i, r -> sb.append("Εγγραφή ${i + 1}: ${parseRecord(r)}\n") }
            }
        } catch (e: Exception) {
            sb.append("Σφάλμα ανάγνωσης: ${e.message}")
        } finally {
            runCatching { ndef.close() }
        }
        return sb.toString()
    }

    private fun parseRecord(r: NdefRecord): String {
        r.toUri()?.let { return "🔗 $it" }
        if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT)) {
            val p = r.payload
            val utf16 = (p[0].toInt() and 0x80) != 0
            val langLen = p[0].toInt() and 0x3F
            val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
            return "📝 " + String(p, 1 + langLen, p.size - 1 - langLen, charset)
        }
        if (r.tnf == NdefRecord.TNF_MIME_MEDIA) {
            return "MIME ${String(r.type)}: ${String(r.payload)}"
        }
        return "Άγνωστο (TNF=${r.tnf}) ${r.payload.toHex()}"
    }

    private fun writeTag(tag: Tag, msg: NdefMessage): String {
        val size = msg.toByteArray().size
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> "❌ Το tag είναι κλειδωμένο (read-only)"
                    ndef.maxSize < size -> "❌ Δεν χωράει: $size / ${ndef.maxSize} bytes"
                    else -> { ndef.writeNdefMessage(msg); "✅ Γράφτηκε επιτυχώς ($size bytes)" }
                }
            } catch (e: Exception) {
                "❌ Σφάλμα εγγραφής: ${e.message}"
            } finally {
                runCatching { ndef.close() }
            }
        }
        NdefFormatable.get(tag)?.let { f ->
            return try {
                f.connect(); f.format(msg)
                "✅ Φορμαρίστηκε και γράφτηκε ($size bytes)"
            } catch (e: Exception) {
                "❌ Σφάλμα φορμαρίσματος: ${e.message}"
            } finally {
                runCatching { f.close() }
            }
        }
        return "❌ Το tag δεν υποστηρίζει NDEF"
    }

    private fun ByteArray.toHex() = joinToString(":") { "%02X".format(it) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
