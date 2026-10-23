package com.skv.formfill

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
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

    /**
     * Executes a real Android touch on the WebView at a CSS viewport coordinate.
     * The web page reports coordinates from getBoundingClientRect(); WebView scale
     * maps those CSS pixels back to the view's physical pixel coordinates.
     */
    private inner class NativeTouchBridge {
        @JavascriptInterface
        fun tapCss(xCss: Double, yCss: Double, token: String) {
            runOnUiThread {
                if (!::web.isInitialized || web.width <= 1 || web.height <= 1 ||
                    !xCss.isFinite() || !yCss.isFinite()) {
                    notifyNativeTap(token, false)
                    return@runOnUiThread
                }

                val scale = web.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
                val x = (xCss.toFloat() * scale).coerceIn(1f, (web.width - 1).toFloat())
                val y = (yCss.toFloat() * scale).coerceIn(1f, (web.height - 1).toFloat())
                val downTime = SystemClock.uptimeMillis()
                val down = createFingerTouchEvent(
                    downTime, downTime, MotionEvent.ACTION_DOWN, x, y
                )
                web.dispatchTouchEvent(down)
                down.recycle()

                web.postDelayed({
                    if (!::web.isInitialized) {
                        return@postDelayed
                    }
                    val upTime = SystemClock.uptimeMillis()
                    val up = createFingerTouchEvent(
                        downTime, upTime, MotionEvent.ACTION_UP, x, y
                    )
                    web.dispatchTouchEvent(up)
                    up.recycle()
                    notifyNativeTap(token, true)
                }, 65L)
            }
        }
    }

    private fun createFingerTouchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
            touchMajor = 1f
            touchMinor = 1f
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private fun notifyNativeTap(token: String, delivered: Boolean) {
        if (!::web.isInitialized) return
        val quotedToken = JSONObject.quote(token)
        web.evaluateJavascript(
            "(function(){const m=window.__formFillNativeTapWaiters;" +
                "const f=m&&m[$quotedToken];if(f){delete m[$quotedToken];f($delivered);}})()",
            null
        )
    }

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
            addJavascriptInterface(NativeTouchBridge(), "FormFillNativeTouch")
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
              const dropdownResults = [];
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
              // Include element-center coordinates. The older helper sent all events at (0,0).
              // Synthetic DOM events are not trusted by all React dropdowns.
              // Use an Android MotionEvent tap and wait for the native bridge response.
              const nativeTap = el => new Promise(resolve => {
                if (!el || !window.FormFillNativeTouch ||
                    typeof window.FormFillNativeTouch.tapCss !== 'function') {
                  resolve(false);
                  return;
                }
                const rect = el.getBoundingClientRect();
                if (!rect.width || !rect.height) { resolve(false); return; }
                window.__formFillNativeTapWaiters ||= Object.create(null);
                window.__formFillNativeTapDone = (token, delivered) => {
                  const pending = window.__formFillNativeTapWaiters;
                  const finish = pending && pending[token];
                  if (finish) finish(!!delivered);
                };
                const token = 'fftap_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                let settled = false;
                const finish = ok => {
                  if (settled) return;
                  settled = true;
                  delete window.__formFillNativeTapWaiters[token];
                  resolve(!!ok);
                };
                window.__formFillNativeTapWaiters[token] = finish;
                setTimeout(() => finish(false), 1800);
                window.FormFillNativeTouch.tapCss(
                  rect.left + rect.width / 2,
                  rect.top + rect.height / 2,
                  token
                );
              });
              const tapLikeUser = el => {
                if (!el) return;
                const rect = el.getBoundingClientRect();
                const x = Math.round(rect.left + rect.width / 2);
                const y = Math.round(rect.top + rect.height / 2);
                const opts = { bubbles:true, cancelable:true, view:window,
                  clientX:x, clientY:y, screenX:x, screenY:y, buttons:1 };
                try { el.dispatchEvent(new PointerEvent('pointerdown', {
                  ...opts, pointerId:1, pointerType:'touch', isPrimary:true, button:0
                })); } catch (_) {}
                el.dispatchEvent(new MouseEvent('mousedown', opts));
                try { el.dispatchEvent(new PointerEvent('pointerup', {
                  ...opts, pointerId:1, pointerType:'touch', isPrimary:true, button:0, buttons:0
                })); } catch (_) {}
                el.dispatchEvent(new MouseEvent('mouseup', {...opts, buttons:0}));
                el.dispatchEvent(new MouseEvent('click', {...opts, buttons:0, detail:1}));
              };
              const optionLabel = el => norm(el?.innerText || el?.textContent || el?.getAttribute?.('aria-label') || '');
              const wantedLabels = (value, kind) => {
                if (kind === 'idType' && norm(value).includes('aadhaar')) return ['aadhaarcard','aadhaar','aadharcard','aadhar'];
                if (kind === 'gender' && norm(value) === 'm') return ['male'];
                if (kind === 'gender' && norm(value) === 'f') return ['female'];
                return [norm(value)];
              };
              const chooseCustom = async (input, value, kind) => {
                const result = {ok:false, field:kind, actual:String(input?.value || ''),
                  reason:'', optionCount:0, attempts:[]};
                if (!input) { result.reason = 'control-not-found'; return result; }
                if (!value) { result.reason = 'saved-value-empty'; return result; }
                const wanted = wantedLabels(value, kind);
                const matchesValue = () => {
                  const actual = norm(input.value);
                  return wanted.includes(actual) ||
                    (kind === 'idType' &&
                     (actual.includes('aadhaar') || actual.includes('aadhar')) &&
                     wanted.some(w => w.includes('aadhaar') || w.includes('aadhar')));
                };
                if (matchesValue()) {
                  result.ok = true; result.actual = String(input.value || '');
                  result.reason = 'already-selected';
                  return result;
                }
                const rowFor = el => {
                  let node = el;
                  for (let depth = 0; node && depth < 8; depth++, node = node.parentElement) {
                    const hasName = !!node.querySelector('input[name="name"],input[name="fname"]');
                    const hasGender = !!node.querySelector('input[name="gender"]');
                    if (hasName && hasGender) return node;
                  }
                  return input.closest('form') || input.parentElement || document;
                };
                const row = rowFor(input);
                const findOptions = () => {
                  const all = [...document.querySelectorAll(
                    'li.floatingDropdown_listItem__tU_5x, [role="option"], li'
                  )].filter(visible).filter(el => wanted.includes(optionLabel(el)));
                  result.optionCount = all.length;
                  // The popup may be rendered inside the row or portalled elsewhere.
                  const inRow = all.filter(el => row.contains(el));
                  const pool = inRow.length ? inRow : all;
                  const rect = input.getBoundingClientRect();
                  const cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
                  return pool.sort((a,b) => {
                    const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
                    const da = Math.abs(ra.left + ra.width/2 - cx) +
                               Math.abs(ra.top + ra.height/2 - cy);
                    const db = Math.abs(rb.left + rb.width/2 - cx) +
                               Math.abs(rb.top + rb.height/2 - cy);
                    return da - db;
                  });
                };

                // Important: don't tap again if the correct option is already visible;
                // doing so can close a dropdown that the user has already opened.
                let options = findOptions();
                let triggerDelivered = false;
                if (!options.length) {
                  input.scrollIntoView({block:'nearest', inline:'nearest'});
                  await sleep(80);
                  triggerDelivered = await nativeTap(input);
                  await sleep(280);
                  options = findOptions();
                }
                if (!options.length) {
                  // Fallback for WebView/device combinations that refuse touch injection.
                  tapLikeUser(input);
                  await sleep(300);
                  options = findOptions();
                }
                for (let attempt = 0; attempt < 12 && !options.length; attempt++) {
                  await sleep(100);
                  options = findOptions();
                }
                if (!options.length) {
                  result.reason = triggerDelivered
                    ? 'android-tap-delivered-but-dropdown-options-not-rendered'
                    : 'dropdown-did-not-open-after-native-and-dom-taps';
                  result.actual = String(input.value || '');
                  return result;
                }

                const option = options[0];
                option.scrollIntoView({block:'nearest', inline:'nearest'});
                await sleep(80);
                const optionTapDelivered = await nativeTap(option);
                await sleep(300);
                result.attempts.push({
                  text:optionLabel(option),
                  method:optionTapDelivered ? 'android-touch' : 'dom-fallback',
                  actual:String(input.value || '')
                });
                if (!matchesValue()) {
                  // Last fallback uses the option's own click handler/delegation.
                  option.click();
                  await sleep(300);
                }
                result.actual = String(input.value || '');
                result.ok = matchesValue();
                result.reason = result.ok ? 'selected-and-verified' :
                  (optionTapDelivered ? 'android-option-tap-did-not-update-input' :
                   'option-click-did-not-update-input');
                return result;
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
              const rowCount = Math.min(
                saved.length,
                Math.max(names.length, ages.length, genders.length, idTypes.length, idNumbers.length)
              );
              for (let i = 0; i < rowCount; i++) {
                const p = saved[i] || {};
                if (names[i] && setValue(names[i], p.fullName)) filled++;
                if (ages[i] && setValue(ages[i], p.age)) filled++;
                const genderResult = await chooseCustom(genders[i], p.gender, 'gender');
                const idTypeResult = await chooseCustom(idTypes[i], p.idType, 'idType');
                dropdownResults.push({
                  row:i + 1, gender:genderResult, photoId:idTypeResult,
                  idNumberReady:!!idNumbers[i] && !idNumbers[i].disabled
                });
                if (genderResult.ok) filled++;
                if (idTypeResult.ok) filled++;
                if (idNumbers[i]) {
                  for (let wait = 0; wait < 12 && idNumbers[i].disabled; wait++) await sleep(150);
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
              return JSON.stringify({filled, dropdownResults, rowCount, controls:{gender:genders.length, photoId:idTypes.length}});
            })()
        """.trimIndent()
        status.text = "Checking visible TTD form fields…"
        web.evaluateJavascript(js) { result ->
            val decoded = try {
                org.json.JSONTokener(result ?: "null").nextValue().toString()
            } catch (_: Exception) { "" }
            val report = try { JSONObject(decoded) } catch (_: Exception) { null }
            if (report != null) {
                val count = report.optInt("filled", 0)
                val rows = report.optJSONArray("dropdownResults")
                val lines = mutableListOf("Filled $count field(s).", "Review all details before continuing.")
                if (rows == null || rows.length() == 0) {
                    lines.add("No pilgrim rows detected. Open the pilgrim form and retry.")
                } else {
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val gender = row.optJSONObject("gender")
                        val photo = row.optJSONObject("photoId")
                        val genderStatus = if (gender?.optBoolean("ok") == true) "OK" else "NOT SELECTED"
                        val photoStatus = if (photo?.optBoolean("ok") == true) "OK" else "NOT SELECTED"
                        val idReady = if (row.optBoolean("idNumberReady")) "ready" else "still disabled"
                        lines.add("Row ${row.optInt("row")}: Gender $genderStatus; Photo ID $photoStatus; ID number $idReady.")
                        if (gender?.optBoolean("ok") != true) {
                            lines.add("  Gender detail: ${gender?.optString("reason", "unknown") ?: "unknown"}")
                        }
                        if (photo?.optBoolean("ok") != true) {
                            lines.add("  Photo ID detail: ${photo?.optString("reason", "unknown") ?: "unknown"}")
                        }
                    }
                }
                val summary = lines.joinToString("\n")
                status.text = "Filled $count field(s). Review dropdown status below."
                android.app.AlertDialog.Builder(this)
                    .setTitle("TTD fill report")
                    .setMessage(summary)
                    .setPositiveButton("OK", null)
                    .show()
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
