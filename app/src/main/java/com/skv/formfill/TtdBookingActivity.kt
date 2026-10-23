package com.skv.formfill

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.content.ClipData
import android.content.ClipboardManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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
     * Executes a real Android touch on the WebView.
     *
     * The page sends the tap position as a FRACTION of the visible viewport
     * (0..1). Converting a fraction to view pixels only needs web.width/height,
     * so it is correct on every density / zoom level. The old CSS-px * density *
     * web.scale maths double-counted density on most devices (getScale() already
     * includes it), which pushed every tap to the bottom-right corner, so the
     * dropdown never opened.
     */
    inner class NativeTouchBridge {
        @JavascriptInterface
        fun progress(message: String) {
            runOnUiThread { status.text = message }
        }

        @JavascriptInterface
        fun pasteText(value: String, token: String) {
            runOnUiThread {
                try {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("FormFill", value))
                    web.requestFocus()
                    val downOk = web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE))
                    val upOk = web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE))
                    web.postDelayed({ notifyNativeTap(token, downOk || upOk) }, 180L)
                } catch (_: Exception) { notifyNativeTap(token, false) }
            }
        }

        @JavascriptInterface
        fun autofillDetected() {
            runOnUiThread {
                val running = SystemClock.uptimeMillis() - fillStartedAt < 60_000L
                if (!running) fillCurrentPage(dropdownsOnly = false, auto = true)
            }
        }

        @JavascriptInterface
        fun fillDone(json: String) {
            runOnUiThread { showFillReport(json) }
        }

        @JavascriptInterface
        fun tapFraction(fx: Double, fy: Double, token: String) {
            runOnUiThread {
                if (!::web.isInitialized || web.width <= 1 || web.height <= 1 ||
                    !fx.isFinite() || !fy.isFinite() || fx < 0.0 || fx > 1.0 || fy < 0.0 || fy > 1.0) {
                    notifyNativeTap(token, false)
                    return@runOnUiThread
                }
                val x = (fx.toFloat() * web.width).coerceIn(1f, (web.width - 1).toFloat())
                val y = (fy.toFloat() * web.height).coerceIn(1f, (web.height - 1).toFloat())
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

    @Suppress("DEPRECATION")
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
        // Android 15 draws the app behind the status bar, which hid the buttons. Push the
        // purple bar down by the status-bar height (plus a little extra) and keep the page
        // above the navigation bar / keyboard.
        val extraTop = (8 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            bar.setPadding(10, 8 + top + extraTop, 10, 8)
            root.setPadding(0, 0, 0, bottom)
            insets
        }
        root.requestApplyInsets()
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
                    view?.evaluateJavascript(traceJs, null)
                }
            }
            loadUrl("https://$ttdHost/")
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    /**
     * Recorder installed on every page load (and again on Inspect). It logs every real
     * touch/pointer/mouse/click event and, 400 ms after a tap on Gender / Photo ID, a
     * snapshot of the list items that appeared. Tap the field yourself, then press Inspect.
     */
    private val traceJs = """
        (function() {
          const key = '__formFillGenderTapTrace';
          if (window[key]) return;
          window[key] = [];
          const visible = el => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
          const txt = el => (el.innerText || el.textContent || '').trim().replace(/ +/g,' ').slice(0,60);
          const record = ev => {
            const trace = window[key];
            if (!trace || trace.length >= 80) return;
            const t = ev.target;
            const ctl = t && t.closest ? t.closest('input[name="gender"],input[name="idType"]') : null;
            trace.push({
              type: ev.type, tag: t && t.tagName || '', cls: typeof t.className === 'string' ? t.className.slice(0,80) : '',
              control: ctl ? ctl.getAttribute('name') : '', trusted: !!ev.isTrusted,
              x: typeof ev.clientX === 'number' ? Math.round(ev.clientX) : null,
              y: typeof ev.clientY === 'number' ? Math.round(ev.clientY) : null
            });
            if (ctl && ev.type === 'click') {
              setTimeout(() => {
                const items = [...document.querySelectorAll('li,[role="option"]')].filter(visible);
                trace.push({
                  type: 'snapshot', control: ctl.getAttribute('name'), visibleItems: items.length,
                  items: items.slice(0,6).map(li => ({
                    text: txt(li), cls: String(li.className || '').slice(0,80),
                    parent: li.parentElement ? li.parentElement.tagName + '.' + String(li.parentElement.className || '').slice(0,60) : '',
                    html: (li.outerHTML || '').slice(0,160)
                  }))
                });
              }, 400);
            }
          };
          for (const type of ['touchstart','touchend','pointerdown','pointerup','mousedown','mouseup','click','focus','input','change']) {
            document.addEventListener(type, record, true);
          }
          // Android Autofill fills several inputs within a few ms. Typing never does.
          // When that burst is seen, ask the app to pick the dropdowns too.
          const burst = new Map();
          let burstTimer = null;
          const onFill = ev => {
            if (window.__ffBusy || Date.now() - (window.__ffLastFill || 0) < 6000) return;
            const t = ev.target;
            if (!t || t.tagName !== 'INPUT') return;
            const now = Date.now();
            burst.set(t, now);
            for (const [el, ts] of burst) if (now - ts > 600) burst.delete(el);
            if (burst.size >= 3 && !burstTimer) {
              burstTimer = setTimeout(() => {
                burstTimer = null; burst.clear();
                if (window.__ffBusy) return;
                if (window.FormFillNativeTouch && window.FormFillNativeTouch.autofillDetected) {
                  window.FormFillNativeTouch.autofillDetected();
                }
              }, 900);
            }
          };
          document.addEventListener('input', onFill, true);
          document.addEventListener('change', onFill, true);
        })()
    """.trimIndent()

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
                genderTapTrace: (window.__formFillGenderTapTrace || []).slice(-80),
                tapTargets: ['gender','idType'].map(name => {
                  const el = document.querySelector('input[name="' + name + '"]');
                  if (!el) return {name, found:false};
                  const r = el.getBoundingClientRect();
                  const x = r.left + r.width / 2, y = r.top + r.height / 2;
                  const hit = document.elementFromPoint(x, y);
                  const ancestors = [];
                  for (let n = el, i = 0; n && i < 6; n = n.parentElement, i++) {
                    const s = getComputedStyle(n);
                    ancestors.push({tag:n.tagName, className:typeof n.className === 'string' ? n.className.slice(0,100) : '',
                      pointerEvents:s.pointerEvents, zIndex:s.zIndex, position:s.position,
                      rect:(() => { const q=n.getBoundingClientRect(); return {x:Math.round(q.x),y:Math.round(q.y),w:Math.round(q.width),h:Math.round(q.height)}; })()});
                  }
                  return {name, found:true, value:el.value, disabled:el.disabled, readOnly:el.readOnly,
                    rect:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)},
                    center:{x:Math.round(x),y:Math.round(y)},
                    hitTag:hit?.tagName || '', hitText:text(hit || document.body),
                    hitIsField:!!hit && (hit === el || el.contains(hit)),
                    hitOuterHTML:(hit?.outerHTML || '').slice(0,350), ancestors};
                }),
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
        web.evaluateJavascript(traceJs, null)
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

    private var fillStartedAt = -60_000L
    private var autoRun = false

    private fun fillCurrentPage(dropdownsOnly: Boolean = false, auto: Boolean = false) {
        autoRun = auto
        fillStartedAt = SystemClock.uptimeMillis()
        val profiles = SecureProfileStore.loadPilgrims(this)
        val profile = profiles.firstOrNull() ?: PilgrimProfile()
        val general = SecureProfileStore.loadGeneral(this)
        val values = JSONObject().apply {
            put("pilgrims", org.json.JSONArray().apply { profiles.forEach { p -> put(JSONObject().apply {
                put("fullName", p.fullName); put("age", p.age); put("gender", p.gender)
                put("idType", p.idType); put("idNumber", p.idNumber)
            }) } })
            put("dropdownsOnly", dropdownsOnly)
            put("auto", auto)
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
              window.__ffBusy = true;
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
              const checks = [];
              const dropdownResults = [];
              const visible = el => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
              const setValue = (el, value) => {
                if (value == null || value === '' || !el || el.disabled || el.readOnly) return false;
                // After the autofill suggestion, keep what Android already filled; only fill empty inputs.
                if (data.auto && String(el.value || '') !== '') return false;
                const oldValue = String(el.value || '');
                const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype :
                              el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                // React-controlled inputs track value changes. Use the native setter and
                // reset React's tracker to the previous value so its onChange sees the edit.
                // Focus first so React and form libraries observe an active field.
                try { el.focus({preventScroll:true}); } catch (_) { try { el.focus(); } catch (_) {} }
                // React's value tracker must retain the previous DOM value before input fires.
                try { if (el._valueTracker && typeof el._valueTracker.setValue === 'function') el._valueTracker.setValue(oldValue); } catch (_) {}
                if (setter) setter.call(el, String(value)); else el.value = String(value);
                // Reset the tracker to the old value after the native setter, then dispatch
                // bubbling input/change events so React's delegated handler detects the change.
                try { if (el._valueTracker && typeof el._valueTracker.setValue === 'function') el._valueTracker.setValue(oldValue); } catch (_) {}
                try { el.dispatchEvent(new InputEvent('input', {bubbles:true, composed:true, inputType:'insertText', data:String(value)})); }
                catch (_) { el.dispatchEvent(new Event('input', {bubbles:true, composed:true})); }
                el.dispatchEvent(new Event('change', {bubbles:true, composed:true}));
                // TTD's React-controlled inputs may not commit synthetic native events
                // consistently inside Android WebView. Invoke the element's React onChange
                // prop as an additional fallback after setting the DOM value.
                try {
                  if (typeof reactInvoke === 'function') {
                    reactInvoke(el, ['onChange']);
                  }
                } catch (_) {}
                try { el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, key:'Unidentified'})); } catch (_) {}
                checks.push([el, String(value)]);
                // Some controlled fields restore the old value on the next render; verify
                // again after React has had a chance to process its state update.
                setTimeout(() => {
                  if (!el.isConnected || String(el.value || '') === String(value)) return;
                  const current = String(el.value || '');
                  try { if (el._valueTracker && typeof el._valueTracker.setValue === 'function') el._valueTracker.setValue(current); } catch (_) {}
                  if (setter) setter.call(el, String(value)); else el.value = String(value);
                  try { if (el._valueTracker && typeof el._valueTracker.setValue === 'function') el._valueTracker.setValue(current); } catch (_) {}
                  el.dispatchEvent(new Event('input', {bubbles:true, composed:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true, composed:true}));
                }, 120);
                return String(el.value || '') === String(value);
              };
              const sleep = ms => new Promise(r => setTimeout(r, ms));
              // Include element-center coordinates. The older helper sent all events at (0,0).
              // Synthetic DOM events are not trusted by all React dropdowns.
              // Use an Android MotionEvent tap and wait for the native bridge response.
              const nativeTap = el => new Promise(resolve => {
                if (!el || !window.FormFillNativeTouch ||
                    typeof window.FormFillNativeTouch.tapFraction !== 'function') {
                  resolve(false);
                  return;
                }
                const rect = el.getBoundingClientRect();
                if (!rect.width || !rect.height) { resolve(false); return; }
                // Pick a point inside the element that the element itself really owns
                // (not covered by a footer / overlay). Falls back to the centre.
                let px = rect.left + rect.width / 2, py = rect.top + rect.height / 2;
                for (const [fx, fy] of [[.5,.5],[.25,.5],[.75,.5],[.5,.3],[.5,.7],[.1,.5],[.9,.5]]) {
                  const x = rect.left + rect.width * fx, y = rect.top + rect.height * fy;
                  const h = document.elementFromPoint(x, y);
                  if (h && (h === el || el.contains(h))) { px = x; py = y; break; }
                }
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
                // Position as a fraction of the VISIBLE viewport (handles zoom/scroll).
                const vv = window.visualViewport;
                const vl = vv ? vv.offsetLeft : 0, vt = vv ? vv.offsetTop : 0;
                const vw = vv ? vv.width : window.innerWidth;
                const vh = vv ? vv.height : window.innerHeight;
                window.FormFillNativeTouch.tapFraction(
                  (px - vl) / vw,
                  (py - vt) / vh,
                  token
                );
              });
              const nativePaste = async (el, value) => {
                const result = {attempted:false, tapped:false, bridgeCalled:false, bridgeDelivered:false,
                  valueImmediately:'', valueAfterWait:'', activeAfterTap:'', error:''};
                if (!el || !value || !window.FormFillNativeTouch ||
                    typeof window.FormFillNativeTouch.pasteText !== 'function') {
                  result.error = 'paste-bridge-unavailable'; return result;
                }
                result.attempted = true;
                try {
                  result.tapped = await nativeTap(el);
                  await sleep(120);
                  result.activeAfterTap = document.activeElement
                    ? (document.activeElement.tagName + '.' + (document.activeElement.getAttribute('name') || '')) : '';
                  const token = 'ffpaste_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                  const pasted = new Promise(resolve => {
                    window.__formFillNativeTapWaiters ||= Object.create(null);
                    let settled = false;
                    const finish = ok => {
                      if (settled) return;
                      settled = true;
                      delete window.__formFillNativeTapWaiters[token];
                      resolve(!!ok);
                    };
                    window.__formFillNativeTapWaiters[token] = finish;
                    setTimeout(() => finish(false), 1800);
                    result.bridgeCalled = true;
                    window.FormFillNativeTouch.pasteText(String(value), token);
                  });
                  result.bridgeDelivered = await pasted;
                  result.valueImmediately = String(el.value || '');
                  await sleep(450);
                  result.valueAfterWait = String(el.value || '');
                } catch (e) { result.error = String(e); }
                return result;
              };
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
              // TTD's custom list items sometimes contain nested labels/duplicate text
              // (for example a LI plus a child span). Compare the shortest visible text
              // line and accessible labels instead of requiring the entire LI text to match.
              const optionLabel = el => {
                if (!el) return '';
                const attrs = [el.getAttribute?.('aria-label'), el.getAttribute?.('data-value'),
                               el.getAttribute?.('title')].filter(Boolean);
                const lines = String(el.innerText || el.textContent || '')
                  .split(/\n+/).map(s => s.trim()).filter(Boolean);
                const text = lines.sort((a,b) => a.length - b.length)[0] || '';
                return norm(attrs[0] || text || el.textContent || '');
              };
              const optionMatchesWanted = (el, wanted) => {
                const label = optionLabel(el);
                if (!label) return false;
                return wanted.some(w => label === w || label.includes(w) || w.includes(label));
              };
              const wantedLabels = (value, kind) => {
                if (kind === 'idType' && norm(value).includes('aadhaar')) return ['aadhaarcard','aadhaar','aadharcard','aadhar'];
                if (kind === 'gender' && norm(value) === 'm') return ['male'];
                if (kind === 'gender' && norm(value) === 'f') return ['female'];
                return [norm(value)];
              };
              // Fallback that does not depend on trusted touch events: call the React
              // handlers (onClick/onMouseDown/...) attached to the element or its parents.
              const reactInvoke = (el, names) => {
                const prefix = '__reactProps' + String.fromCharCode(36);
                let called = 0;
                for (let n = el, depth = 0; n && depth < 4; n = n.parentElement, depth++) {
                  const key = Object.keys(n).find(k => k.indexOf(prefix) === 0);
                  const props = key ? n[key] : null;
                  if (!props) continue;
                  for (const name of names) {
                    if (typeof props[name] === 'function') {
                      try {
                        props[name]({
                          type: name.slice(2).toLowerCase(), target: el, currentTarget: n,
                          preventDefault() {}, stopPropagation() {}, persist() {},
                          isTrusted: true, nativeEvent: {}, button: 0, detail: 1
                        });
                        called++;
                      } catch (_) {}
                    }
                  }
                  if (called) break;
                }
                return called;
              };
              const describeAround = el => {
                const wrap = (el.parentElement && el.parentElement.parentElement) || el.parentElement;
                const dd = [...document.querySelectorAll('[class*="ropdown"],ul')].slice(0, 6).map(n => {
                  const cs = getComputedStyle(n), r = n.getBoundingClientRect();
                  return n.tagName + '.' + String(n.className).slice(0, 40) + ' d:' + cs.display +
                    ' v:' + cs.visibility + ' o:' + cs.opacity + ' h:' + Math.round(r.height) +
                    ' li:' + n.querySelectorAll('li').length;
                });
                const a = document.activeElement;
                return {disabled: !!el.disabled, active: a ? a.tagName + '.' + (a.getAttribute('name') || '') : '',
                        dropdowns: dd, wrap: (wrap ? wrap.outerHTML : '').slice(0, 300)};
              };
              const seenOptions = () => [...document.querySelectorAll('li,[role="option"]')]
                .filter(visible).map(optionLabel).filter(Boolean).slice(0, 12);
              const chooseCustom = async (input, value, kind, forceSelection = false) => {
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
                // A visible value can be only a UI default, not React's committed
                // selection. When a dependent field (Photo ID) is still disabled,
                // re-select Gender even if the input text already says "Male".
                if (matchesValue() && !forceSelection) {
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
                  const all = [...new Set(document.querySelectorAll(
                    'li.floatingDropdown_listItem__tU_5x, [role="option"], li, [data-value]'
                  ))].filter(visible).filter(el => optionMatchesWanted(el, wanted));
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
                // Wait for the list to render. Tapping/clicking again while the first tap is
                // still rendering TOGGLES the list shut, which is why the 1st press failed.
                const pollOptions = async ms => {
                  const end = Date.now() + ms;
                  let o = findOptions();
                  while (!o.length && Date.now() < end) { await sleep(100); o = findOptions(); }
                  return o;
                };
                for (let attempt = 0; attempt < 3 && !options.length; attempt++) {
                  // Centre the control (instant, not smooth) so a fixed footer or a
                  // smooth-scroll animation cannot make the tap land elsewhere.
                  input.scrollIntoView({block: attempt === 1 ? 'end' : 'center', inline:'nearest', behavior:'instant'});
                  await sleep(400);
                  {
                    const r = input.getBoundingClientRect();
                    const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
                    result.tapHit = hit ? (hit.tagName + '.' + String(hit.className || '').slice(0, 60)) : 'none-offscreen';
                    result.tapOnControl = !!hit && (hit === input || input.contains(hit));
                  }
                  if (attempt === 0 || attempt === 1) {
                    triggerDelivered = await nativeTap(input);
                    options = await pollOptions(1500);
                  } else {
                    // Last resort: the page's own React handlers, one at a time.
                    tapLikeUser(input);
                    options = await pollOptions(1200);
                    for (const h of ['onFocus','onPointerDown','onMouseDown','onClick']) {
                      if (options.length) break;
                      if (reactInvoke(input, [h])) {
                        result.reactOpened = h;
                        options = await pollOptions(900);
                      }
                    }
                  }
                  result.openAttempts = attempt + 1;
                }
                if (!options.length) {
                  result.reason = triggerDelivered
                    ? 'android-tap-delivered-but-dropdown-options-not-rendered'
                    : 'dropdown-did-not-open-after-native-and-dom-taps';
                  result.actual = String(input.value || '');
                  result.seen = seenOptions();
                  result.dom = describeAround(input);
                  return result;
                }

                // Prefer the most specific matching option, not a broad parent that
                // happens to contain the same label. This avoids choosing the wrong row
                // when the TTD page renders nested/duplicated option text.
                options.sort((a,b) => {
                  const score = el => {
                    const label = optionLabel(el);
                    return wanted.includes(label) ? 0 : 1;
                  };
                  return score(a) - score(b);
                });
                const option = options[0];
                option.scrollIntoView({block:'nearest', inline:'nearest', behavior:'instant'});
                await sleep(120);
                const optionTapDelivered = await nativeTap(option);
                await sleep(300);
                result.attempts.push({
                  text:optionLabel(option),
                  method:optionTapDelivered ? 'android-touch' : 'dom-fallback',
                  actual:String(input.value || '')
                });
                if (!matchesValue()) {
                  // Some React dropdowns listen on a nested element or on pointer/mouse
                  // events rather than the LI itself. Try the deepest visible text node
                  // target first, then a complete DOM pointer/mouse sequence.
                  const children = [...option.querySelectorAll('button,[role="option"],span,div')]
                    .filter(visible)
                    .filter(el => optionMatchesWanted(el, wanted));
                  const clickable = children.sort((a,b) => {
                    const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
                    return (ra.width * ra.height) - (rb.width * rb.height);
                  })[0] || option;
                  try { clickable.click(); } catch (_) {}
                  // Some React handlers live on the child label rather than the LI.
                  if (!matchesValue()) reactInvoke(clickable, ['onPointerDown','onMouseDown','onClick']);
                  await sleep(250);
                }
                if (!matchesValue()) {
                  const rect = option.getBoundingClientRect();
                  const x = Math.round(rect.left + rect.width / 2);
                  const y = Math.round(rect.top + rect.height / 2);
                  const opts = {bubbles:true, cancelable:true, view:window,
                    clientX:x, clientY:y, button:0, buttons:1, detail:1};
                  for (const type of ['pointerdown','mousedown','pointerup','mouseup','click']) {
                    try {
                      const ev = type.startsWith('pointer')
                        ? new PointerEvent(type, {...opts, pointerId:1, pointerType:'touch', isPrimary:true})
                        : new MouseEvent(type, {...opts, buttons:type.endsWith('up') || type === 'click' ? 0 : 1});
                      option.dispatchEvent(ev);
                    } catch (_) {}
                  }
                  await sleep(250);
                }
                if (!matchesValue()) {
                  // Call the li's own React handlers (works even if events are untrusted).
                  reactInvoke(option, ['onPointerDown','onMouseDown','onPointerUp','onMouseUp','onClick']);
                  await sleep(300);
                }
                if (!matchesValue()) {
                  // Last resort for accessible custom comboboxes: use their keyboard
                  // selection path. Never overwrite the input value directly; React
                  // must commit the selected option itself.
                  try { input.focus(); } catch (_) {}
                  for (const key of ['ArrowDown','Enter']) {
                    input.dispatchEvent(new KeyboardEvent('keydown', {key, code:key, bubbles:true, cancelable:true}));
                    input.dispatchEvent(new KeyboardEvent('keyup', {key, code:key, bubbles:true, cancelable:true}));
                    await sleep(100);
                  }
                  await sleep(250);
                }
                result.actual = String(input.value || '');
                result.ok = matchesValue();
                if (!result.ok) result.seen = seenOptions();
                result.reason = result.ok ? 'selected-and-verified' :
                  (optionTapDelivered ? 'android-option-tap-did-not-update-input' :
                   'option-click-did-not-update-input');
                return result;
              };
              // The Arjitha Seva page repeats pilgrim fields in DOM order. Fill each
              // saved profile against the same-index controls; never copy profile 1
              // into every pilgrim row.
              // 1) Text fields first: they are instant, so the user sees them fill immediately.
              for (const [key, aliases] of fields) {
                if (data.dropdownsOnly) break;
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
              // Android Autofill is started from a focused input, so the keyboard is open and the
              // page is resized. Blur it and let the layout settle before measuring anything.
              try { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); } catch (_) {}
              await sleep((data.dropdownsOnly || data.auto) ? 1300 : 300);
              const saved = Array.isArray(data.pilgrims) ? data.pilgrims : [];
              const names = [...document.querySelectorAll('input[name="name"], input[name="fname"]')].filter(visible);
              const ages = [...document.querySelectorAll('input[name="age"]')].filter(visible);
              const textFieldResults = [];
              const genders = [...document.querySelectorAll('input[name="gender"]')].filter(visible);
              const idTypes = [...document.querySelectorAll('input[name="idType"], input[name="photoIdType"]')].filter(visible);
              const idNumbers = [...document.querySelectorAll('input[name="idNumber"], input[name="idProofNumber"]')].filter(visible);
              const rowCount = Math.min(
                saved.length,
                Math.max(names.length, ages.length, genders.length, idTypes.length, idNumbers.length)
              );
              for (let i = 0; i < rowCount; i++) {
                const p = saved[i] || {};
                if (!data.dropdownsOnly) {
                  let nameOk = false;
                  let ageOk = false;
                  let namePaste = null, agePaste = null;
                  if (names[i] && p.fullName) {
                    namePaste = await nativePaste(names[i], p.fullName);
                    nameOk = String(names[i].value || '') === String(p.fullName);
                    if (!nameOk) nameOk = setValue(names[i], p.fullName);
                  }
                  if (ages[i] && p.age) {
                    agePaste = await nativePaste(ages[i], p.age);
                    ageOk = String(ages[i].value || '') === String(p.age);
                    if (!ageOk) ageOk = setValue(ages[i], p.age);
                  }
                  textFieldResults.push({row:i+1, diagnostic:{namePaste,agePaste}});
                  if (nameOk) filled++;
                  if (ageOk) filled++;
                  textFieldResults.push({
                    row:i + 1,
                    name:{found:!!names[i], saved:!!p.fullName, actual:String(names[i]?.value || ''), expected:String(p.fullName || ''), ok:!!names[i] && String(names[i].value || '') === String(p.fullName || '') && !!p.fullName},
                    age:{found:!!ages[i], saved:!!p.age, actual:String(ages[i]?.value || ''), expected:String(p.age || ''), ok:!!ages[i] && String(ages[i].value || '') === String(p.age || '') && !!p.age}
                  });
                }
              }
              try {
                if (window.FormFillNativeTouch && window.FormFillNativeTouch.progress) {
                  window.FormFillNativeTouch.progress('Text fields filled. Selecting dropdowns, please wait...');
                }
              } catch (_) {}
              // 2) Dropdowns last (slow: real taps).
              for (let i = 0; i < rowCount; i++) {
                const p = saved[i] || {};
                const genderResult = await chooseCustom(genders[i], p.gender, 'gender', !!idTypes[i]?.disabled);
                await sleep(700); // let the Gender list finish closing before touching Photo ID
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
              for (let pass = 0; pass < 2; pass++) {
                const bad = dropdownResults.filter(r => !r.gender.ok || !r.photoId.ok);
                if (!bad.length) break;
                await sleep(900);
                const g2 = [...document.querySelectorAll('input[name="gender"]')].filter(visible);
                const t2 = [...document.querySelectorAll('input[name="idType"], input[name="photoIdType"]')].filter(visible);
                const n2 = [...document.querySelectorAll('input[name="idNumber"], input[name="idProofNumber"]')].filter(visible);
                for (const r of bad) {
                  const i = r.row - 1, p = saved[i] || {};
                  if (!r.gender.ok) {
                    r.gender = await chooseCustom(g2[i], p.gender, 'gender');
                    if (r.gender.ok) filled++;
                    await sleep(700);
                  }
                  if (!r.photoId.ok) {
                    r.photoId = await chooseCustom(t2[i], p.idType, 'idType');
                    if (r.photoId.ok) filled++;
                  }
                  if (n2[i]) {
                    for (let wait = 0; wait < 12 && n2[i].disabled; wait++) await sleep(150);
                    r.idNumberReady = !n2[i].disabled;
                    if (r.photoId.ok && setValue(n2[i], p.idNumber)) filled++;
                  }
                }
              }
              // 'filled' used to count attempts. Re-read the page and count only values that stuck.
              await sleep(400);
              const stuck = c => c[0].isConnected && String(c[0].value) === String(c[1]);
              const textOk = checks.filter(stuck).length;
              const textMismatch = checks.filter(c => !stuck(c))
                .map(c => c[0].getAttribute('name') || c[0].getAttribute('label') || '?');
              const dropOk = dropdownResults.reduce((n, r) => n + (r.gender.ok ? 1 : 0) + (r.photoId.ok ? 1 : 0), 0);
              window.__ffBusy = false; window.__ffLastFill = Date.now();
              return JSON.stringify({filled: textOk + dropOk, attempted: filled, textMismatch, textFieldResults, dropdownResults, rowCount, controls:{name:names.length, age:ages.length, gender:genders.length, photoId:idTypes.length}});
            })().then(r => window.FormFillNativeTouch.fillDone(r))
                .catch(e => { window.__ffBusy = false; window.__ffLastFill = Date.now();
                              window.FormFillNativeTouch.fillDone(JSON.stringify({error:String(e)})); })
        """.trimIndent()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(web.windowToken, 0)
        if (auto) web.clearFocus()
        status.text = if (auto) "Autofill detected, selecting dropdowns…" else "Checking visible TTD form fields…"
        web.evaluateJavascript(js, null)
    }

    /** Called when the fill script has really finished (evaluateJavascript cannot await a Promise). */
    private fun showFillReport(decoded: String) {
        fillStartedAt = -60_000L
        val wasAuto = autoRun
        autoRun = false
        val report = try { JSONObject(decoded) } catch (_: Exception) { null }
        if (report != null && report.has("error")) {
            status.text = "Fill failed: ${report.optString("error")}"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            return
        }
            if (report != null) {
                val count = report.optInt("filled", 0)
                val rows = report.optJSONArray("dropdownResults")
                val textRows = report.optJSONArray("textFieldResults")
                val lines = mutableListOf("Confirmed on the page: $count field(s) (attempted ${report.optInt("attempted", count)}).", "Review all details before continuing.")
                if (textRows != null) {
                    for (i in 0 until textRows.length()) {
                        val tr = textRows.optJSONObject(i) ?: continue
                        val n = tr.optJSONObject("name")
                        val a = tr.optJSONObject("age")
                        lines.add("Row ${tr.optInt("row")}: Name " + (if (n?.optBoolean("ok") == true) "OK" else "NOT FILLED") + "; Age " + (if (a?.optBoolean("ok") == true) "OK" else "NOT FILLED") + ".")
                        if (n?.optBoolean("ok") != true) lines.add("  Name input found=${n?.optBoolean("found")}, saved=${n?.optBoolean("saved")}, actual='${n?.optString("actual")}', expected='${n?.optString("expected")}'.")
                        if (a?.optBoolean("ok") != true) lines.add("  Age input found=${a?.optBoolean("found")}, saved=${a?.optBoolean("saved")}, actual='${a?.optString("actual")}', expected='${a?.optString("expected")}'.")
                    }
                }
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
                            gender?.optJSONArray("seen")?.let { lines.add("  Visible list items: $it") }
                            gender?.optJSONObject("dom")?.let { lines.add("  DOM: ${it.toString().take(450)}") }
                            gender?.let { lines.add("  Tap hit: ${it.optString("tapHit", "-")} (on control: ${it.optBoolean("tapOnControl")}, react: ${it.optString("reactOpened", "-")})") }
                        }
                        if (photo?.optBoolean("ok") != true) {
                            lines.add("  Photo ID detail: ${photo?.optString("reason", "unknown") ?: "unknown"}")
                            photo?.optJSONArray("seen")?.let { lines.add("  Visible list items: $it") }
                            photo?.optJSONObject("dom")?.let { lines.add("  DOM: ${it.toString().take(450)}") }
                            photo?.let { lines.add("  Tap hit: ${it.optString("tapHit", "-")} (on control: ${it.optBoolean("tapOnControl")}, react: ${it.optString("reactOpened", "-")})") }
                        }
                    }
                }
                val bad = report.optJSONArray("textMismatch")
                if (bad != null && bad.length() > 0) lines.add("Text fields that did not stick: $bad")
                val allOk = (bad == null || bad.length() == 0) && rows != null && rows.length() > 0 && (0 until rows.length()).all { idx ->
                    val r = rows.optJSONObject(idx)
                    r != null &&
                        r.optJSONObject("gender")?.optBoolean("ok") == true &&
                        r.optJSONObject("photoId")?.optBoolean("ok") == true
                }
                if (wasAuto && allOk) {
                    status.text = "Dropdowns selected after autofill. Review before continuing."
                    Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
                    return
                }
                val summary = lines.joinToString("\n")
                status.text = "Confirmed $count field(s). Review dropdown status below."
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

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
