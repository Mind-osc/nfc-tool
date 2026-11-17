package com.example.nfctool

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.FormatException
import java.io.IOException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfc: NfcAdapter? = null
    private var pendingWrite: NdefMessage? = null
    private var pendingDiagnose = false

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var pwd: EditText
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
        pwd = EditText(this).apply {
            hint = "Κωδικός tag (8 hex, π.χ. FFFFFFFF) — προαιρετικό"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val writeBtn = Button(this).apply {
            text = "✍️ Εγγραφή σε tag"
            setOnClickListener { armWrite() }
        }
        val eraseBtn = Button(this).apply {
            text = "🗑️ Διαγραφή tag"
            setOnClickListener {
                pendingDiagnose = false
                pendingWrite = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)))
                status.text = "🗑️ Έτοιμο για διαγραφή — πλησίασε το tag"
            }
        }
        val diagBtn = Button(this).apply {
            text = "🔍 Διάγνωση κλειδώματος"
            setOnClickListener {
                pendingWrite = null
                pendingDiagnose = true
                status.text = "🔍 Πλησίασε το tag για διάγνωση"
            }
        }
        val cancelBtn = Button(this).apply {
            text = "Ακύρωση"
            setOnClickListener {
                pendingWrite = null; pendingDiagnose = false
                status.text = "📖 Λειτουργία ανάγνωσης — πλησίασε ένα tag"
            }
        }
        output = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        listOf(status, typeGroup, input, pwd, writeBtn, eraseBtn, diagBtn, cancelBtn, output)
            .forEach(root::addView)
        return ScrollView(this).apply { addView(root) }
    }

    private fun armWrite() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast("Γράψε κάτι πρώτα"); return }
        val record = if (typeGroup.checkedRadioButtonId == 2)
            NdefRecord.createUri(if (text.contains("://")) text else "https://$text")
        else
            NdefRecord.createTextRecord("el", text)
        pendingDiagnose = false
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
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) }
        adapter.enableReaderMode(this, this, flags, extras)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val result = when {
            pendingDiagnose -> diagnose(tag)
            pendingWrite != null -> writeTag(tag, pendingWrite!!)
            else -> readTag(tag)
        }
        runOnUiThread {
            output.text = result
            if (result.startsWith("✅")) {
                pendingWrite = null
                status.text = "📖 Λειτουργία ανάγνωσης — πλησίασε ένα tag"
            }
        }
    }

    // ---------- ΑΝΑΓΝΩΣΗ ----------
    private fun readTag(tag: Tag): String {
        val sb = StringBuilder()
        sb.append("UID: ").append(tag.id.toHex()).append('\n')
        sb.append("Τεχνολογίες: ").append(tag.techList.joinToString { it.substringAfterLast('.') }).append("\n\n")
        val ndef = Ndef.get(tag) ?: return sb.append("Το tag δεν είναι NDEF (κενό/μη φορμαρισμένο)").toString()
        try {
            ndef.connect()
            sb.append("Τύπος: ${ndef.type}\n")
            sb.append("Χωρητικότητα: ${ndef.maxSize} bytes\n")
            sb.append("Εγγράψιμο: ${if (ndef.isWritable) "Ναι" else "Όχι"}\n\n")
            val message = ndef.ndefMessage
            if (message == null || message.records.all { it.tnf == NdefRecord.TNF_EMPTY }) {
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
        if (r.tnf == NdefRecord.TNF_MIME_MEDIA) return "MIME ${String(r.type)}: ${String(r.payload)}"
        return "Άγνωστο (TNF=${r.tnf}) ${r.payload.toHex()}"
    }

    // ---------- ΔΙΑΓΝΩΣΗ (NTAG21x) ----------
    private fun diagnose(tag: Tag): String {
        val a = NfcA.get(tag) ?: return "❌ Το tag δεν είναι NfcA — δεν γίνεται διάγνωση."
        return try {
            a.connect()
            val cc = a.transceive(byteArrayOf(0x30, 0x03))          // pages 3..6
            val sizeByte = cc[2].toInt() and 0xFF
            val (cfg0, lastPage, name) = when (sizeByte) {
                0x12 -> Triple(0x29, 0x27, "NTAG213")
                0x3E -> Triple(0x83, 0x81, "NTAG215")
                0x6D -> Triple(0xE3, 0xE1, "NTAG216")
                else -> Triple(-1, 0, "Άγνωστο/Ultralight")
            }
            val sb = StringBuilder("🔍 Διάγνωση ($name)\n\n")
            val lock = a.transceive(byteArrayOf(0x30, 0x02))        // page2 bytes2,3 = static lock
            val sLock = ((lock[2].toInt() and 0xFF) shl 8) or (lock[3].toInt() and 0xFF)
            sb.append("Static lock: ${"%04X".format(sLock)}\n")

            if (cfg0 < 0) {
                sb.append("\nΔεν αναγνωρίστηκε NTAG213/215/216. ")
                sb.append(if (sLock and 0x7FFF != 0) "Υπάρχουν κλειδωμένες σελίδες (lock bits)."
                          else "Μάλλον δεν είναι password-protected.")
                return sb.toString()
            }

            val c = a.transceive(byteArrayOf(0x30, cfg0.toByte()))  // CFG0 + CFG1
            val auth0 = c[3].toInt() and 0xFF                       // CFG0 byte3
            val prot = (c[4].toInt() and 0x80) != 0                 // CFG1 byte0 bit7
            sb.append("AUTH0: ${"%02X".format(auth0)} (σελίδα $auth0)\n")
            sb.append("PROT: ${if (prot) "ανάγνωση + εγγραφή" else "μόνο εγγραφή"}\n\n")

            val locked = sLock and 0x7FFF != 0
            val passworded = auth0 <= lastPage
            when {
                passworded -> sb.append("➡️ ΚΩΔΙΚΟΣ. Προστατεύεται με password από τη σελίδα $auth0.\n" +
                        "Βάλε τον κωδικό στο πεδίο (8 hex) και πάτα Εγγραφή/Διαγραφή. Αν δεν τον ξέρεις, δεν γράφεται.")
                locked -> sb.append("➡️ ΜΟΝΙΜΑ ΚΛΕΙΔΩΜΕΝΟ (lock bits). Δεν ξεκλειδώνει ποτέ — αυτό το tag δεν ξαναγράφεται.")
                else -> sb.append("➡️ Θα έπρεπε να γράφεται. Κράτα το κινητό ακίνητο και ξαναδοκίμασε Εγγραφή.")
            }
            sb.toString()
        } catch (e: Exception) {
            explain(e)
        } finally {
            runCatching { a.close() }
        }
    }

    // ---------- ΕΓΓΡΑΦΗ ----------
    private fun writeTag(tag: Tag, msg: NdefMessage): String {
        val pw = try { pwdBytes() } catch (e: Exception) { return "⚠️ ${e.message}" }
        if (pw != null) return rawWrite(tag, msg, pw)            // password → raw NfcA path

        val size = msg.toByteArray().size
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> "❌ Το tag είναι κλειδωμένο (read-only)"
                    ndef.maxSize < size -> "❌ Δεν χωράει: $size / ${ndef.maxSize} bytes"
                    else -> {
                        ndef.writeNdefMessage(msg)
                        if (msg.records[0].tnf == NdefRecord.TNF_EMPTY) "✅ Το tag διαγράφηκε"
                        else "✅ Γράφτηκε επιτυχώς ($size bytes)"
                    }
                }
            } catch (e: Exception) { explain(e) } finally { runCatching { ndef.close() } }
        }
        NdefFormatable.get(tag)?.let { f ->
            return try {
                f.connect(); f.format(msg); "✅ Φορμαρίστηκε και γράφτηκε ($size bytes)"
            } catch (e: Exception) { explain(e) } finally { runCatching { f.close() } }
        }
        return "❌ Το tag δεν υποστηρίζει NDEF"
    }

    // Εγγραφή με κωδικό: PWD_AUTH και μετά raw WRITE σελίδα-σελίδα (NTAG21x)
    private fun rawWrite(tag: Tag, msg: NdefMessage, pw: ByteArray): String {
        val a = NfcA.get(tag) ?: return "❌ Το tag δεν είναι NfcA — ο κωδικός δεν εφαρμόζεται."
        return try {
            a.connect()
            try {
                a.transceive(byteArrayOf(0x1B, pw[0], pw[1], pw[2], pw[3]))   // PWD_AUTH
            } catch (e: IOException) {
                return "❌ Λάθος κωδικός — το tag απέρριψε το password."
            }
            val nd = msg.toByteArray()
            if (nd.size > 252) return "❌ Πολύ μεγάλο μήνυμα για αυτή τη μέθοδο."
            val tlv = byteArrayOf(0x03, nd.size.toByte()) + nd + byteArrayOf(0xFE.toByte())
            val padded = tlv + ByteArray((4 - tlv.size % 4) % 4)
            var page = 0x04
            var i = 0
            while (i < padded.size) {
                a.transceive(byteArrayOf(0xA2.toByte(), page.toByte(),
                    padded[i], padded[i + 1], padded[i + 2], padded[i + 3]))  // WRITE
                page++; i += 4
            }
            if (msg.records[0].tnf == NdefRecord.TNF_EMPTY) "✅ Το tag διαγράφηκε (με κωδικό)"
            else "✅ Γράφτηκε με κωδικό (${nd.size} bytes)"
        } catch (e: Exception) { explain(e) } finally { runCatching { a.close() } }
    }

    private fun pwdBytes(): ByteArray? {
        val h = pwd.text.toString().trim().replace(" ", "")
        if (h.isEmpty()) return null
        if (h.length != 8 || !h.matches(Regex("[0-9A-Fa-f]{8}")))
            throw IllegalArgumentException("Ο κωδικός πρέπει να είναι 8 hex ψηφία (π.χ. FFFFFFFF)")
        return ByteArray(4) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun explain(e: Exception): String = when (e) {
        is TagLostException -> "❌ Το tag χάθηκε. Κράτα το κινητό ακίνητο 2–3 δευτερόλεπτα και ξαναδοκίμασε."
        is FormatException -> "❌ Λάθος μορφή δεδομένων."
        is IOException -> "❌ Το tag αρνήθηκε. Συνήθως προστατεύεται με κωδικό — πάτα «🔍 Διάγνωση» για να δεις αν είναι password ή μόνιμο κλείδωμα.\n(${e.javaClass.simpleName})"
        else -> "❌ Σφάλμα: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
    }

    private fun ByteArray.toHex() = joinToString(":") { "%02X".format(it) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
