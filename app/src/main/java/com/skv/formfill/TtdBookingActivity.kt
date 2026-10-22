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
                  className: typeof el.className === 'string' ? el.className.slice(0,180) : '',
                  value: el.matches('input,textarea,select') ? (el.value || '').slice(0,80) : '',
                  text: text(el),
                  parentText: text(el.parentElement || el),
                  outerHTML: (el.outerHTML || '').slice(0,500),
                  options: el.tagName === 'SELECT' ? [...el.options].map(o => text(o)).slice(0,30) : []
                })),
                popupCandidates: [...document.querySelectorAll('body *')].filter(el => {
                  if (!visible(el)) return false;
                  const t = (el.innerText || el.textContent || '').trim().replace(/\\s+/g,' ');
                  return t && t.length < 80 && /^(male|female|transgender|aadhaar card|passport)$/i.test(t);
                }).slice(0,40).map(el => ({
                  tag: el.tagName,
                  className: typeof el.className === 'string' ? el.className.slice(0,180) : '',
                  role: el.getAttribute('role') || '',
                  text: text(el),
                  outerHTML: (el.outerHTML || '').slice(0,500)
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
        val profiles = SecureProfileStore.loadPilgrims(this)
        val profile = profiles.firstOrNull() ?: PilgrimProfile()
        val general = SecureProfileStore.loadGeneral(this)
        val values = JSONObject().apply {
            put("pilgrims", org.json.JSONArray().apply { profiles.forEach { p -> put(JSONObject().apply {
                put("fullName", p.fullName); put("age", p.age); put("gender", p.gender)
                put("idType", p.idType); put("idNumber", p.idNumber)
            }) } })
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
                if (value == null || value === '' || !el || el.disabled || el.readOnly) return false;
                const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype :
                              el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(el, value); else el.value = value;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return true;
              };
              const sleep = ms => new Promise(r => setTimeout(r, ms));
              const tapLikeUser = el => {
                if (!el) return;
                const opts = { bubbles: true, cancelable: true, view: window };
                try { el.dispatchEvent(new PointerEvent('pointerdown', {...opts, pointerType:'touch', isPrimary:true})); } catch (_) {}
                el.dispatchEvent(new MouseEvent('mousedown', opts));
                try { el.dispatchEvent(new PointerEvent('pointerup', {...opts, pointerType:'touch', isPrimary:true})); } catch (_) {}
                el.dispatchEvent(new MouseEvent('mouseup', opts));
                el.dispatchEvent(new MouseEvent('click', opts));
              };
              const chooseCustom = async (input, value, kind) => {
                if (!input || !value) return false;
                const wanted = kind === 'idType' && norm(value).includes('aadhaar')
                  ? ['aadhaarcard','aadhaar','aadharcard','aadhar']
                  : [norm(value)];
                const findOption = () => {
                  const candidates = [...document.querySelectorAll('li.floatingDropdown_listItem__tU_5x, li, [role=option]')]
                    .filter(visible)
                    .filter(el => wanted.includes(norm(el.innerText || el.textContent || '')));
                  return candidates.filter(el => el.matches('li.floatingDropdown_listItem__tU_5x')).slice(-1)[0]
                    || candidates.slice(-1)[0] || null;
                };
                const matchesValue = () => {
                  const actual = norm(input.value);
                  return actual === norm(value) ||
                    (kind === 'idType' && norm(value).includes('aadhaar') && actual.includes('aadhaar'));
                };
                if (matchesValue()) return true;

                // Reuse an already-open TTD popup first. Clicking the input when its
                // popup is open can close it, which made the previous build miss options.
                let option = findOption();
                if (!option) {
                  input.focus();
                  tapLikeUser(input);
                  await sleep(300);
                }
                for (let attempt = 0; attempt < 12; attempt++) {
                  option = findOption();
                  if (option) break;
                  await sleep(150);
                }
                if (!option) return false;

                tapLikeUser(option);
                await sleep(350);
                if (matchesValue()) return true;

                // Some React dropdowns listen on the list item's parent/container.
                // Try a normal click only if the first user-like tap did not update input.
                option.click();
                await sleep(250);
                return matchesValue();
              };
              // The Arjitha Seva page repeats pilgrim fields in DOM order. Fill each
              // saved profile against the same-index controls; never copy profile 1
              // into every pilgrim row.
              const saved = Array.isArray(data.pilgrims) ? data.pilgrims : [];
              const names = [...document.querySelectorAll('input[name="name"], input[name="fname"]')].filter(visible);
              const ages = [...document.querySelectorAll('input[name="age"]')].filter(visible);
              const genders = [...document.querySelectorAll('input[name="gender"]')].filter(visible);
              const idTypes = [...document.querySelectorAll('input[name="idType"], input[name="photoIdType"]')].filter(visible);
              const idNumbers = [...document.querySelectorAll('input[name="idNumber"], input[name="idProofNumber"]')].filter(visible);
              for (let i = 0; i < Math.min(saved.length, Math.max(names.length, ages.length, genders.length, idTypes.length, idNumbers.length)); i++) {
                const p = saved[i] || {};
                if (names[i] && setValue(names[i], p.fullName)) filled++;
                if (ages[i] && setValue(ages[i], p.age)) filled++;
                if (genders[i] && await chooseCustom(genders[i], p.gender, 'gender')) filled++;
                if (idTypes[i] && await chooseCustom(idTypes[i], p.idType, 'idType')) filled++;
                if (idNumbers[i]) {
                  for (let wait = 0; wait < 10 && idNumbers[i].disabled; wait++) await sleep(150);
                  if (setValue(idNumbers[i], p.idNumber)) filled++;
                }
              }
              for (const [key, aliases] of fields) {
                if (['fullName','age','gender','idType','idNumber'].includes(key)) continue;
                const value = data[key];
                if (!value) continue;
                const candidates = [...document.querySelectorAll('input:not([type=hidden]), textarea, select, [role=combobox], [aria-haspopup=listbox]')].filter(visible);
                // Prefer the exact TTD field name before broad label/parent-text matching.
                // Support both TTD booking flows: SPAT uses fname/photoIdType/idProofNumber,
                // while Arjitha Seva uses name/idType/idNumber and may repeat pilgrim rows.
                const exactNames = key === 'gender' ? ['gender'] :
                                   key === 'idType' ? ['photoIdType','idType'] :
                                   key === 'fullName' ? ['fname','name'] :
                                   key === 'idNumber' ? ['idProofNumber','idNumber'] :
                                   key === 'age' ? ['age'] :
                                   key === 'email' ? ['pilgrimEmail','email'] :
                                   key === 'city' ? ['pilgrimCity','city'] :
                                   key === 'state' ? ['pilgrimState','state'] :
                                   key === 'country' ? ['pilgrimCountry','country'] :
                                   key === 'postalCode' ? ['pilgrimPincode','pincode','postalCode'] : [];
                const exactMatches = candidates.filter(el => exactNames.some(n => norm(el.getAttribute('name')) === norm(n)));
                const target = exactMatches[0] || candidates.find(el => {
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
                      '[role=option], [role=menuitem], [role=listbox] *, [data-value], li, [class*="option"], [class*="Option"], [class*="item"], [class*="Item"], div, span'
                    )].filter(visible);
                    // TTD sometimes renders popup choices as plain div/span elements without ARIA roles.
                    // Match the smallest visible element with an exact option label, not just known selectors.
                    option = options.find(el => {
                      const text = norm(el.innerText || el.textContent || el.getAttribute('aria-label') || '');
                      const own = norm(el.getAttribute('data-value') || el.getAttribute('value') || '');
                      const exact = desired.includes(text) || desired.includes(own);
                      const childHasExact = [...el.children].some(child =>
                        desired.includes(norm(child.innerText || child.textContent || child.getAttribute('aria-label') || '')) ||
                        desired.includes(norm(child.getAttribute('data-value') || child.getAttribute('value') || ''))
                      );
                      return exact && !childHasExact;
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
