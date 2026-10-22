package com.skv.formfill

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.URI

class TtdBookingActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val ttdHost = "ttdevasthanams.ap.gov.in"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(92, 45, 145)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(92, 45, 145))
        }
        val back = Button(this).apply {
            text = "Back"; isAllCaps = false
            setOnClickListener { if (::web.isInitialized && web.canGoBack()) web.goBack() else finish() }
        }
        val fill = Button(this).apply {
            text = "Fill saved details"; isAllCaps = false
            setOnClickListener { fillCurrentPage() }
        }
        val inspect = Button(this).apply {
            text = "Inspect"; isAllCaps = false
            setOnClickListener { inspectCurrentPage() }
        }
        bar.addView(back)
        bar.addView(fill, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(inspect)
        root.addView(bar)
        status = TextView(this).apply {
            text = "Opening official TTD website…"
            textSize = 12f; setTextColor(Color.DKGRAY)
            setPadding(12, 6, 12, 6)
        }
        root.addView(status)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return true
                    val host = url.host.orEmpty().lowercase()
                    if (url.scheme == "https" && (host == ttdHost || host.endsWith(".$ttdHost"))) return false
                    Toast.makeText(this@TtdBookingActivity, "External page opened in your browser for safety.", Toast.LENGTH_SHORT).show()
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "TTD page loaded. Open the pilgrim form, then tap Fill saved details."
                }
            }
            loadUrl("https://$ttdHost/")
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun inspectCurrentPage() {
        val js = """
            (function() {
              const visible = el => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
              const text = el => (el.innerText || el.textContent || '').trim().replace(/\\s+/g,' ').slice(0,100);
              const nodes = [...document.querySelectorAll('input,textarea,select,button,[role], [aria-haspopup], [data-testid]')].filter(visible);
              return JSON.stringify({
                url: location.href,
                title: document.title,
                fields: nodes.slice(0,100).map(el => ({
                  tag: el.tagName,
                  type: el.getAttribute('type') || '',
                  role: el.getAttribute('role') || '',
                  name: el.getAttribute('name') || '',
                  id: el.id || '',
                  ariaLabel: el.getAttribute('aria-label') || '',
                  placeholder: el.getAttribute('placeholder') || '',
                  ariaHaspopup: el.getAttribute('aria-haspopup') || '',
                  text: text(el),
                  options: el.tagName === 'SELECT' ? [...el.options].map(o => text(o)).slice(0,30) : []
                }))
              });
            })()
        """.trimIndent()
        status.text = "Inspecting visible form controls…"
        web.evaluateJavascript(js) { result ->
            val decoded = try {
                org.json.JSONTokener(result ?: "null").nextValue().toString()
            } catch (_: Exception) {
                result ?: "No diagnostic result"
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("TTD form diagnostics")
                .setMessage(decoded.take(12000))
                .setPositiveButton("Copy diagnostics") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TTD form diagnostics", decoded))
                    Toast.makeText(this, "Diagnostics copied. Paste them into this chat.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun fillCurrentPage() {
        val profile = SecureProfileStore.loadPilgrims(this).firstOrNull() ?: PilgrimProfile()
        val general = SecureProfileStore.loadGeneral(this)
        val values = JSONObject().apply {
            put("fullName", profile.fullName)
            put("age", profile.age)
            put("gender", profile.gender)
            put("idType", profile.idType)
            put("idNumber", profile.idNumber)
            put("mobile", general.mobile.ifBlank { profile.mobile })
            put("email", general.email.ifBlank { profile.email })
            put("dateOfBirth", general.dateOfBirth.ifBlank { profile.dateOfBirth })
            put("address", general.address.ifBlank { profile.address })
            put("city", general.city.ifBlank { profile.city })
            put("state", general.state.ifBlank { profile.state })
            put("country", general.country.ifBlank { profile.country })
            put("postalCode", general.postalCode.ifBlank { profile.postalCode })
        }
        val payload = values.toString()
        val js = """
            (async function() {
              const data = $payload;
              const norm = s => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
              const labelText = el => {
                const parts = [];
                if (el.labels) for (const l of el.labels) parts.push(l.innerText || l.textContent || '');
                for (const a of ['aria-label','placeholder','name','id','autocomplete']) {
                  if (el.getAttribute && el.getAttribute(a)) parts.push(el.getAttribute(a));
                }
                const id = el.id && document.getElementById(el.id);
                if (id) parts.push(id.innerText || '');
                const parent = el.closest('label');
                if (parent) parts.push(parent.innerText || '');
                const p = el.parentElement;
                if (p) parts.push((p.innerText || '').slice(0, 160));
                return norm(parts.join(' '));
              };
              const fields = [
                ['fullName',['fullname','name','pilgrimname','applicantname']],
                ['age',['age','pilgrimage']],
                ['gender',['gender','sex']],
                ['idType',['photoidproof','photoidtype','idproof','identityproof','idtype','documenttype']],
                ['idNumber',['photoidnumber','idnumber','idno','identitynumber','documentnumber','aadhaarnumber','passportnumber']],
                ['mobile',['mobile','mobilenumber','phonenumber','contactnumber']],
                ['email',['email','emailaddress']],
                ['dateOfBirth',['dateofbirth','dob','birthdate']],
                ['address',['address','postaladdress']],
                ['city',['city','town','locality']],
                ['state',['state','province']],
                ['country',['country']],
                ['postalCode',['pincode','postalcode','zipcode']]
              ];
              let filled = 0;
              const visible = el => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
              const setValue = (el, value) => {
                if (!value || !el || el.disabled || el.readOnly) return false;
                const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype :
                              el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(el, value); else el.value = value;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return true;
              };
              for (const [key, aliases] of fields) {
                const value = data[key];
                if (!value) continue;
                const candidates = [...document.querySelectorAll('input:not([type=hidden]), textarea, select, [role=combobox], [aria-haspopup=listbox]')].filter(visible);
                // Prefer the exact TTD field name before broad label/parent-text matching.
                const exactNames = key === 'gender' ? ['gender'] :
                                   key === 'idType' ? ['photoIdType','photoidtype'] :
                                   key === 'fullName' ? ['fname'] :
                                   key === 'idNumber' ? ['idProofNumber'] :
                                   key === 'age' ? ['age'] : [];
                const target = candidates.find(el => exactNames.some(n => norm(el.getAttribute('name')) === norm(n))) ||
                  candidates.find(el => {
                    const t = labelText(el);
                    return aliases.some(a => t.includes(norm(a)));
                  });
                if (!target) continue;
                if (target instanceof HTMLSelectElement) {
                  const opts = [...target.options];
                  const match = opts.find(o => norm(o.textContent).includes(norm(value)) || norm(o.value) === norm(value));
                  if (match) { target.value = match.value; target.dispatchEvent(new Event('input',{bubbles:true})); target.dispatchEvent(new Event('change',{bubbles:true})); filled++; }
                } else if ((key === 'gender' || key === 'idType') && target.matches('input')) {
                  // TTD renders these dropdowns as input controls. Do not type into them:
                  // open the control and select the matching visible option from its popup.
                  target.click();
                  target.focus();
                  const desired = key === 'idType' && ['aadhaar','aadhaarcard'].includes(norm(value))
                    ? ['aadhaarcard','aadhaar'] : key === 'idType' && norm(value).includes('passport')
                    ? ['passport'] : [norm(value)];
                  let option = null;
                  for (let attempt = 0; attempt < 10 && !option; attempt++) {
                    await new Promise(r => setTimeout(r, 180));
                    const options = [...document.querySelectorAll(
                      '[role=option], [role=menuitem], [role=listbox] *, [data-value], li, [class*="option"], [class*="Option"]'
                    )].filter(visible);
                    option = options.find(el => {
                      const text = norm(el.innerText || el.textContent || el.getAttribute('aria-label') || '');
                      const own = norm(el.getAttribute('data-value') || el.getAttribute('value') || '');
                      return (desired.includes(text) || desired.includes(own)) &&
                        ![...el.children].some(child => norm(child.innerText || child.textContent) === text);
                    });
                  }
                  if (option) {
                    option.click();
                    target.dispatchEvent(new Event('change', {bubbles:true}));
                    filled++;
                  }
                } else if (target.matches('input,textarea')) {
                  if (setValue(target, value)) filled++;
                } else if (target instanceof HTMLSelectElement) {
                  const opts = [...target.options];
                  const match = opts.find(o => norm(o.textContent).includes(norm(value)) || norm(o.value) === norm(value));
                  if (match) { target.value = match.value; target.dispatchEvent(new Event('input',{bubbles:true})); target.dispatchEvent(new Event('change',{bubbles:true})); filled++; }
                } else {
                  target.click();
                  await new Promise(r => setTimeout(r, 350));
                  const optionText = key === 'idType' && norm(value).includes('aadhaar') ? 'Aadhaar Card' : value;
                  const options = [...document.querySelectorAll('[role=option], [role=menuitem], li, button, [data-value]')].filter(visible);
                  const option = options.find(el => norm(el.innerText || el.textContent) === norm(optionText));
                  if (option) { option.click(); filled++; }
                }
              }
              return filled;
            })()
        """.trimIndent()
        status.text = "Checking visible TTD form fields…"
        web.evaluateJavascript(js) { result ->
            val count = result?.trim('"')?.toIntOrNull()
            if (count != null) {
                status.text = "Filled $count field(s). Review every value before continuing."
                Toast.makeText(this, "Filled $count field(s). Please review the form.", Toast.LENGTH_LONG).show()
            } else {
                status.text = "Fill attempt finished. Check the form and correct any fields manually."
                Toast.makeText(this, "Please review the form; custom controls may need manual selection.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
