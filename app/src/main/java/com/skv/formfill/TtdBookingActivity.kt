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
        fun autofillDetected() {
            runOnUiThread {
                val running = SystemClock.uptimeMillis() - fillStartedAt < 60_000L
                if (!running) fillCurrentPage(dropdownsOnly = true)
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

    private fun fillCurrentPage(dropdownsOnly: Boolean = false) {
        autoRun = dropdownsOnly
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
    
