package com.example.floatingwebview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.floatingwebview.home.AppDatabase
import com.example.floatingwebview.home.VisitedPage
import com.example.floatingwebview.home.VisitedPageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class FloatingWebViewService : Service() {

    @Volatile
    private var lastTouchedRootView: View? = null

    @Volatile
    private var lastTouchedWebView: WebView? = null

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val activeWindows = mutableMapOf<Int, Pair<View, WindowManager.LayoutParams>>()
    private var nextWindowId = 0
    private val openedUrls = mutableSetOf<String>()
    private val CHANNEL_ID = "FloatingWebViewChannel"
    private val NOTIFICATION_ID = 101

    private var isJavaScriptEnabled = true
    private lateinit var visitedPageDao: VisitedPageDao
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var openurlmacther = ""
    var count = 0

    private var lastVisitedUrl: String? = null
    private var currentSelectionPopup: PopupWindow? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSelectionPopup: Runnable? = null
    private val enableCustomSelectionPopup = true

    // Javascript bridge for selection
    private inner class SelectionBridge {
        @JavascriptInterface
        fun onDebug(msg: String?) {
            Log.d("FWV", "JS> $msg")
        }

        @JavascriptInterface
        fun onSelection(selectionJson: String?) {
            if (!enableCustomSelectionPopup || selectionJson.isNullOrBlank()) return
            Log.d("FWV", "SelectionBridge.onSelection -> $selectionJson")
            Handler(Looper.getMainLooper()).post {
                try {
                    val obj = JSONObject(selectionJson)
                    val left = obj.optDouble("left", 0.0).toFloat()
                    val top = obj.optDouble("top", 0.0).toFloat()
                    val width = obj.optDouble("width", 0.0).toFloat()
                    val height = obj.optDouble("height", 0.0).toFloat()
                    val text = obj.optString("text", "")

                    if (text.isNotEmpty()) {
                        scheduleSelectionPopup(left, top, width, height, text)
                    }
                } catch (e: Exception) {
                    Log.e("FWV", "onSelection parse error", e)
                }
            }
        }

    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        visitedPageDao = AppDatabase.getInstance(applicationContext).visitedPageDao()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Floating WebView Service Channel", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Channel for Floating WebView service"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating WebView")
            .setContentText("Displaying floating web content")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val url = intent?.getStringExtra("url") ?: "https://www.google.com"
            val size = intent?.getStringExtra("size") ?: "medium"
            openurlmacther = url
            if (openedUrls.contains(url)) return START_NOT_STICKY
            openedUrls.add(url)
            showFloatingWebView(url, size)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showFloatingWebView(url: String, size: String = "medium") {
        count++
        val windowId = nextWindowId++
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val rootView = inflater.inflate(R.layout.floating_web_view_layout, null)

        val mainLayout = rootView.findViewById<LinearLayout>(R.id.mainLayout)
        val webView = rootView.findViewById<WebView>(R.id.webView)
        val homeButton = rootView.findViewById<ImageButton>(R.id.homeButton)
        val closeButton = rootView.findViewById<ImageButton>(R.id.closeButton)
        val headerView = rootView.findViewById<View>(R.id.headerView)
        val moreOptionsButton = rootView.findViewById<ImageButton>(R.id.moreOptionsButton)
        val resizeHandle = rootView.findViewById<ImageView>(R.id.resizeHandle)
        val jsToggle = rootView.findViewById<LinearLayout>(R.id.jsToggle)
        val jsStatusIcon = rootView.findViewById<View>(R.id.jsStatusIcon)
        val minimizeButton = rootView.findViewById<ImageButton>(R.id.minimizeButton)
        val minimizedIcon = rootView.findViewById<ImageView>(R.id.minimizedIcon)

        val displayMetrics = resources.displayMetrics
        val (width, height) = when (size) {
            "small" -> Pair((displayMetrics.widthPixels * 0.5).toInt(), (displayMetrics.heightPixels * 0.4).toInt())
            "medium" -> Pair((displayMetrics.widthPixels * 0.7).toInt(), (displayMetrics.heightPixels * 0.6).toInt())
            "large" -> Pair((displayMetrics.widthPixels * 0.9).toInt(), (displayMetrics.heightPixels * 0.8).toInt())
            else -> Pair((displayMetrics.widthPixels * 0.7).toInt(), (displayMetrics.heightPixels * 0.6).toInt())
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100 + (nextWindowId * 30)
            y = 100 + (nextWindowId * 30)
        }

        rootView.setTag(R.id.tag_original_width, width)
        rootView.setTag(R.id.tag_original_height, height)

        setupWebView(webView, url, rootView, params, windowManager, this)
        setupHomeButton(homeButton, webView, this, windowId, windowManager)
        setupCloseButton(closeButton, windowId)
        setupDragListener(headerView, windowId)
        setupMoreOptionsButton(moreOptionsButton, webView)
        setupResizeListener(resizeHandle, rootView, params)
        setupJsToggle(jsToggle, jsStatusIcon, webView)
        setupMinimizeButton(minimizeButton, mainLayout, minimizedIcon, params, rootView)
        setupMinimizedIconDragListener(minimizedIcon, params, rootView, windowId)

        jsStatusIcon.setBackgroundResource(if (isJavaScriptEnabled) R.drawable.circle_green else R.drawable.circle_red)

        try {
            windowManager.addView(rootView, params)
            webView.requestFocus()
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.isLongClickable = true
            webView.isHapticFeedbackEnabled = true
            activeWindows[windowId] = Pair(rootView, params)
        } catch (e: Exception) {
            webView.destroy()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun setupWebView(
        webView: WebView,
        url: String,
        rootView: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
        context: Context
    ) {
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = isJavaScriptEnabled
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
        }

        if (enableCustomSelectionPopup) {
            webView.addJavascriptInterface(SelectionBridge(), "AndroidSelection")
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isLongClickable = true
        webView.isHapticFeedbackEnabled = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Enable WebAuthn/Passkey support
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
            )
        }

        // Handle downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimetype)
            val cookies = CookieManager.getInstance().getCookie(url)
            request.addRequestHeader("cookie", cookies)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("Downloading file...")
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }

        // Track touches
        webView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchedRootView = rootView
                    lastTouchedWebView = webView

                    // Make window focusable
                    if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        try { windowManager.updateViewLayout(rootView, params) } catch (_: Exception) {}
                    }

                    v.post {
                        v.requestFocus()
                        v.isFocusable = true
                        v.isFocusableInTouchMode = true
                    }

                    // Dismiss any existing popup
                    cancelSelectionPopup()
                }
                MotionEvent.ACTION_MOVE -> {
                    cancelSelectionPopup()
                }
            }
            false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    // Detect login/auth pages and open in Chrome Custom Tabs for passkey support
                    if (isLoginPage(url)) {
                        minimizeAllWindows()
                        ChromeCustomTabHelper.openUrl(context, url)
                        return true
                    }
                    
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return false // Let the WebView handle HTTP/HTTPS URLs
                    }

                    // Handle custom schemes and intents
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        return true // The URL is handled
                    } catch (e: Exception) {
                        return true
                    }
                }
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val actualUrl = url ?: return
                if (actualUrl == lastVisitedUrl) return
                lastVisitedUrl = actualUrl

                val title = view?.title ?: actualUrl
                val faviconUrl = "https://www.google.com/s2/favicons?domain=${Uri.parse(actualUrl).host}&sz=64"
                saveVisitedPage(actualUrl, title, faviconUrl)

                if (!enableCustomSelectionPopup) return

                // Inject selection detection script
                val js = """
(function() {
  if (window._fwv_sel_injected) return;
  window._fwv_sel_injected = true;
  
  try { AndroidSelection.onDebug('injection-start'); } catch(e){}
  
  var lastSelection = '';
  var selectionTimeout = null;
  var longPressTimer = null;
  var touchStartX = 0;
  var touchStartY = 0;
  var longPressTriggered = false;
  var MOVE_THRESHOLD = 12;
  var LONG_PRESS_MS = 450;
  var HANDLE_SIZE = 22;
  var currentRange = null;
  var startHandle = null;
  var endHandle = null;
  var activeHandle = null;
  var isDraggingHandle = false;
  
  function cloneRange(range) {
    return range ? range.cloneRange() : null;
  }

  function getEffectiveRange() {
    try {
      var sel = window.getSelection();
      if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {
        currentRange = cloneRange(sel.getRangeAt(0));
        return cloneRange(currentRange);
      }
      if (currentRange && !currentRange.collapsed) {
        return cloneRange(currentRange);
      }
    } catch (e) {
      try { AndroidSelection.onDebug('getEffectiveRange error: ' + e.message); } catch(_) {}
    }
    return null;
  }

  function applyRange(range) {
    if (!range) return false;
    try {
      var sel = window.getSelection();
      if (!sel) return false;
      sel.removeAllRanges();
      sel.addRange(range);
      currentRange = cloneRange(range);
      return true;
    } catch (e) {
      try { AndroidSelection.onDebug('applyRange error: ' + e.message); } catch(_) {}
      return false;
    }
  }

  function getSelectionInfo() {
    try {
      var range = getEffectiveRange();
      if (!range) return null;
      var text = range.toString().trim();
      if (!text || text.length === 0) return null;
      
      var rect = range.getBoundingClientRect();
      return {
        left: rect.left + window.pageXOffset,
        top: rect.top + window.pageYOffset,
        width: rect.width,
        height: rect.height,
        text: text
      };
    } catch(e) { 
      try { AndroidSelection.onDebug('getSelectionInfo error: ' + e.message); } catch(_){}
      return null; 
    }
  }

  function comparePoints(nodeA, offsetA, nodeB, offsetB) {
    var a = document.createRange();
    a.setStart(nodeA, offsetA);
    a.collapse(true);
    var b = document.createRange();
    b.setStart(nodeB, offsetB);
    b.collapse(true);
    return a.compareBoundaryPoints(Range.START_TO_START, b);
  }

  function createNormalizedRange(startNode, startOffset, endNode, endOffset) {
    if (!startNode || !endNode) return null;
    var cmp = comparePoints(startNode, startOffset, endNode, endOffset);
    var range = document.createRange();
    if (cmp <= 0) {
      range.setStart(startNode, startOffset);
      range.setEnd(endNode, endOffset);
    } else {
      range.setStart(endNode, endOffset);
      range.setEnd(startNode, startOffset);
    }
    return range;
  }

  function ensureHandles() {
    function wireHandle(handle, kind) {
      handle.dataset.kind = kind;
      handle.addEventListener('touchstart', beginHandleDrag, {passive: false});
      handle.addEventListener('touchmove', moveHandleDrag, {passive: false});
      handle.addEventListener('touchend', endHandleDrag, {passive: false});
      handle.addEventListener('touchcancel', endHandleDrag, {passive: false});
      handle.addEventListener('mousedown', beginHandleDrag, false);
    }

    if (!startHandle) {
      startHandle = document.createElement('div');
      startHandle.id = '_fwv_start_handle';
      startHandle.style.cssText = 'position:absolute;width:22px;height:22px;border-radius:11px;background:#1A73E8;border:3px solid #FFFFFF;box-shadow:0 1px 5px rgba(0,0,0,.35);z-index:2147483647;pointer-events:auto;touch-action:none;display:none;';
      wireHandle(startHandle, 'start');
      document.documentElement.appendChild(startHandle);
    }
    if (!endHandle) {
      endHandle = document.createElement('div');
      endHandle.id = '_fwv_end_handle';
      endHandle.style.cssText = 'position:absolute;width:22px;height:22px;border-radius:11px;background:#1A73E8;border:3px solid #FFFFFF;box-shadow:0 1px 5px rgba(0,0,0,.35);z-index:2147483647;pointer-events:auto;touch-action:none;display:none;';
      wireHandle(endHandle, 'end');
      document.documentElement.appendChild(endHandle);
    }
  }

  function hideHandles() {
    if (startHandle) startHandle.style.display = 'none';
    if (endHandle) endHandle.style.display = 'none';
  }

  function updateHandles() {
    try {
      var range = getEffectiveRange();
      if (!range || range.collapsed) {
        hideHandles();
        return;
      }

      ensureHandles();
      var startRange = document.createRange();
      startRange.setStart(range.startContainer, range.startOffset);
      startRange.setEnd(range.startContainer, range.startOffset);
      var endRange = document.createRange();
      endRange.setStart(range.endContainer, range.endOffset);
      endRange.setEnd(range.endContainer, range.endOffset);

      var startRect = startRange.getBoundingClientRect();
      var endRect = endRange.getBoundingClientRect();
      var mainRect = range.getBoundingClientRect();

      var startX = ((startRect && (startRect.left || startRect.right)) ? startRect.left : mainRect.left) + window.pageXOffset - HANDLE_SIZE / 2;
      var startY = ((startRect && (startRect.bottom || startRect.top)) ? startRect.bottom : mainRect.bottom) + window.pageYOffset - HANDLE_SIZE / 2;
      var endX = ((endRect && (endRect.right || endRect.left)) ? endRect.right : mainRect.right) + window.pageXOffset - HANDLE_SIZE / 2;
      var endY = ((endRect && (endRect.bottom || endRect.top)) ? endRect.bottom : mainRect.bottom) + window.pageYOffset - HANDLE_SIZE / 2;

      startHandle.style.left = startX + 'px';
      startHandle.style.top = startY + 'px';
      startHandle.style.display = 'block';

      endHandle.style.left = endX + 'px';
      endHandle.style.top = endY + 'px';
      endHandle.style.display = 'block';
    } catch (e) {
      try { AndroidSelection.onDebug('updateHandles error: ' + e.message); } catch(_) {}
      hideHandles();
    }
  }

  function getCaretRange(x, y) {
    if (document.caretRangeFromPoint) {
      return document.caretRangeFromPoint(x, y);
    }

    if (document.caretPositionFromPoint) {
      var pos = document.caretPositionFromPoint(x, y);
      if (!pos) return null;
      var range = document.createRange();
      range.setStart(pos.offsetNode, pos.offset);
      range.collapse(true);
      return range;
    }

    return null;
  }

  function isWordChar(ch) {
    return /[\p{L}\p{N}_]/u.test(ch);
  }

  function expandRangeToWord(range) {
    if (!range) return null;

    var node = range.startContainer;
    if (!node) return null;

    if (node.nodeType !== Node.TEXT_NODE) {
      if (node.childNodes && node.childNodes.length > 0) {
        var childIndex = Math.min(range.startOffset, node.childNodes.length - 1);
        node = node.childNodes[childIndex];
        if (node && node.nodeType !== Node.TEXT_NODE && node.firstChild && node.firstChild.nodeType === Node.TEXT_NODE) {
          node = node.firstChild;
        }
      }
      if (!node || node.nodeType !== Node.TEXT_NODE) return null;
    }

    var text = node.textContent || '';
    if (!text.length) return null;

    var index = Math.min(range.startOffset, text.length - 1);
    if (index < 0) return null;

    if (!isWordChar(text.charAt(index)) && index > 0 && isWordChar(text.charAt(index - 1))) {
      index -= 1;
    }

    if (!isWordChar(text.charAt(index))) return null;

    var start = index;
    var end = index + 1;

    while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
    while (end < text.length && isWordChar(text.charAt(end))) end++;

    var wordRange = document.createRange();
    wordRange.setStart(node, start);
    wordRange.setEnd(node, end);
    return wordRange;
  }

  function selectWordAtPoint(x, y) {
    try {
      var caret = getCaretRange(x, y);
      var wordRange = expandRangeToWord(caret);
      if (!wordRange) return false;
      applyRange(wordRange);
      updateHandles();
      notifySelection(true);
      return true;
    } catch (e) {
      try { AndroidSelection.onDebug('selectWordAtPoint error: ' + e.message); } catch(_) {}
      return false;
    }
  }
  
  function updateRangeFromPoint(clientX, clientY, kind) {
    var caret = getCaretRange(clientX, clientY);
    if (!caret || !currentRange) return false;

    var nextRange = null;
    if (kind === 'start') {
      nextRange = createNormalizedRange(
        caret.startContainer,
        caret.startOffset,
        currentRange.endContainer,
        currentRange.endOffset
      );
    } else {
      nextRange = createNormalizedRange(
        currentRange.startContainer,
        currentRange.startOffset,
        caret.startContainer,
        caret.startOffset
      );
    }

    if (!nextRange || nextRange.collapsed) return false;
    applyRange(nextRange);
    updateHandles();
    return true;
  }

  function getPointFromEvent(e) {
    if (e.touches && e.touches.length) {
      return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    }
    if (e.changedTouches && e.changedTouches.length) {
      return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    }
    return { x: e.clientX, y: e.clientY };
  }

  function beginHandleDrag(e) {
    if (!currentRange) return;
    if (longPressTimer) clearTimeout(longPressTimer);
    longPressTimer = null;
    activeHandle = e.currentTarget && e.currentTarget.dataset ? e.currentTarget.dataset.kind : null;
    isDraggingHandle = true;
    e.preventDefault();
    e.stopPropagation();
    applyRange(currentRange);
  }

  function moveHandleDrag(e) {
    if (!isDraggingHandle || !activeHandle) return;
    var point = getPointFromEvent(e);
    if (updateRangeFromPoint(point.x, point.y, activeHandle)) {
      notifySelection(false);
    }
    e.preventDefault();
    e.stopPropagation();
  }

  function endHandleDrag(e) {
    if (!isDraggingHandle) return;
    var point = getPointFromEvent(e);
    if (activeHandle) {
      updateRangeFromPoint(point.x, point.y, activeHandle);
    }
    isDraggingHandle = false;
    activeHandle = null;
    applyRange(currentRange);
    updateHandles();
    notifySelection(true);
    e.preventDefault();
    e.stopPropagation();
  }

  document.addEventListener('mousemove', function(e) {
    if (!isDraggingHandle || !activeHandle) return;
    if (updateRangeFromPoint(e.clientX, e.clientY, activeHandle)) {
      notifySelection(false);
    }
    e.preventDefault();
  }, true);

  document.addEventListener('mouseup', function(e) {
    if (!isDraggingHandle) return;
    endHandleDrag(e);
  }, true);

  function notifySelection(shouldNotifyAndroid) {
    if (shouldNotifyAndroid === undefined) shouldNotifyAndroid = true;
    var info = getSelectionInfo();
    if (info && info.text !== lastSelection) {
      lastSelection = info.text;
      updateHandles();
      if (shouldNotifyAndroid) try {
        AndroidSelection.onSelection(JSON.stringify(info));
        AndroidSelection.onDebug('selection-sent len=' + info.text.length);
      } catch(e) {
        AndroidSelection.onDebug('notify error: ' + e.message);
      }
    } else if (info) {
      updateHandles();
      if (shouldNotifyAndroid) try {
        AndroidSelection.onSelection(JSON.stringify(info));
      } catch (e) {}
    } else if (!info && currentRange && !currentRange.collapsed) {
      applyRange(currentRange);
      updateHandles();
    } else if (!info) {
      lastSelection = '';
      currentRange = null;
      hideHandles();
    }
  }
  
  // Listen to selection changes with debouncing
  document.addEventListener('selectionchange', function() {
    if (selectionTimeout) clearTimeout(selectionTimeout);
    selectionTimeout = setTimeout(function() {
      notifySelection();
    }, 300);
  }, {passive: true});

  document.addEventListener('touchstart', function(e) {
    if (e.target === startHandle || e.target === endHandle || isDraggingHandle) return;
    if (!e.touches || e.touches.length !== 1) return;
    var touch = e.touches[0];
    touchStartX = touch.clientX;
    touchStartY = touch.clientY;
    longPressTriggered = false;
    if (longPressTimer) clearTimeout(longPressTimer);
    longPressTimer = setTimeout(function() {
      longPressTriggered = selectWordAtPoint(touchStartX, touchStartY);
    }, LONG_PRESS_MS);
  }, {passive: true});

  document.addEventListener('touchmove', function(e) {
    if (isDraggingHandle) return;
    if (!e.touches || e.touches.length !== 1) return;
    var touch = e.touches[0];
    if (Math.abs(touch.clientX - touchStartX) > MOVE_THRESHOLD || Math.abs(touch.clientY - touchStartY) > MOVE_THRESHOLD) {
      if (longPressTimer) clearTimeout(longPressTimer);
      longPressTimer = null;
    }
  }, {passive: true});

  document.addEventListener('touchend', function() {
    if (isDraggingHandle) return;
    if (longPressTimer) clearTimeout(longPressTimer);
    longPressTimer = null;
    if (longPressTriggered) {
      setTimeout(function(){ notifySelection(true); }, 50);
    } else {
      setTimeout(function(){ notifySelection(true); }, 350);
    }
  }, {passive: true});

  document.addEventListener('touchcancel', function() {
    if (longPressTimer) clearTimeout(longPressTimer);
    longPressTimer = null;
  }, {passive: true});
  
  // Also check on touchend/mouseup
  document.addEventListener('mouseup', function() {
    if (isDraggingHandle) return;
    setTimeout(function(){ notifySelection(true); }, 350);
  }, {passive: true});

  window.addEventListener('scroll', function() {
    setTimeout(function() {
      applyRange(currentRange);
      updateHandles();
    }, 0);
  }, {passive: true});
  
  try { AndroidSelection.onDebug('injection-done'); } catch(e){}
})();
""".trimIndent()

                // Inject with multiple attempts to ensure it works
                view?.postDelayed({
                    view.evaluateJavascript(js, null)
                    Log.d("FWV", "Selection script injected")
                }, 300)

                view?.postDelayed({
                    view.evaluateJavascript(js, null)
                }, 1000)
            }
        }

        webView.loadUrl(url)
    }

    private fun scheduleSelectionPopup(
        rectLeft: Float,
        rectTop: Float,
        rectWidth: Float,
        rectHeight: Float,
        selectedText: String
    ) {
        cancelSelectionPopup(dismissCurrent = false)

        val popupTask = Runnable {
            showSelectionPopup(
                lastTouchedRootView,
                lastTouchedWebView,
                rectLeft,
                rectTop,
                rectWidth,
                rectHeight,
                selectedText
            )
        }

        pendingSelectionPopup = popupTask
        mainHandler.postDelayed(popupTask, 250)
    }

    private fun cancelSelectionPopup(dismissCurrent: Boolean = true) {
        pendingSelectionPopup?.let(mainHandler::removeCallbacks)
        pendingSelectionPopup = null
        if (dismissCurrent) {
            currentSelectionPopup?.dismiss()
        }
    }

    private fun showSelectionPopup(
        anchorRoot: View?,
        webView: WebView?,
        rectLeft: Float,
        rectTop: Float,
        rectWidth: Float,
        rectHeight: Float,
        selectedText: String
    ) {
        if (anchorRoot == null || webView == null) {
            Log.w("FWV", "showSelectionPopup: anchorRoot or webView is null")
            return
        }

        Log.d("FWV", "showSelectionPopup: textLen=${selectedText.length} left=$rectLeft top=$rectTop")

        // Dismiss any existing popup
        currentSelectionPopup?.dismiss()
        pendingSelectionPopup = null

        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.selection_popup, null)
        val copyBtn = popupView.findViewById<TextView>(R.id.sel_copy)
        val selectAllBtn = popupView.findViewById<TextView>(R.id.sel_select_all)

        // Measure popup
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupW = popupView.measuredWidth
        val popupH = popupView.measuredHeight

        // Get WebView location on screen
        val webViewLoc = IntArray(2)
        webView.getLocationOnScreen(webViewLoc)

        // Calculate popup position (center above selection)
        val screenX = webViewLoc[0] + rectLeft.toInt()
        val screenY = webViewLoc[1] + rectTop.toInt()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Position centered above selection
        var showX = (screenX + rectWidth / 2 - popupW / 2).toInt()
        var showY = (screenY - popupH - 20).toInt()

        // Keep within screen bounds
        showX = showX.coerceIn(10, screenWidth - popupW - 10)
        showY = showY.coerceIn(10, screenHeight - popupH - 10)

        Log.d("FWV", "Popup position: x=$showX y=$showY webViewLoc=${webViewLoc.contentToString()}")

        val popup = PopupWindow(
            popupView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            elevation = 12f
            animationStyle = android.R.style.Animation_Dialog
        }

        currentSelectionPopup = popup

        try {
            popup.showAtLocation(anchorRoot, Gravity.NO_GRAVITY, showX, showY)
            Log.d("FWV", "Popup shown successfully")
        } catch (e: Exception) {
            Log.e("FWV", "Failed to show popup", e)
            try {
                popup.showAtLocation(anchorRoot, Gravity.CENTER, 0, 0)
            } catch (e2: Exception) {
                Log.e("FWV", "Failed to show popup at center", e2)
            }
        }

        copyBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", selectedText))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            popup.dismiss()
        }

        selectAllBtn.setOnClickListener {
            webView.evaluateJavascript(
                "(function(){return document.body.innerText || document.documentElement.innerText;})()") { allText ->
                val text = try {
                    JSONObject("{\"v\":$allText}").getString("v")
                } catch (e: Exception) {
                    allText?.trim('\'') ?: ""
                }
                if (text.isNotEmpty()) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
                    Toast.makeText(this, "All text copied", Toast.LENGTH_SHORT).show()
                }
                popup.dismiss()
            }
        }

        // Auto-dismiss after 8 seconds
        popupView.postDelayed({
            if (popup.isShowing) popup.dismiss()
        }, 8000)

        popup.setOnDismissListener {
            currentSelectionPopup = null
        }
    }

    private fun saveVisitedPage(url: String, title: String, faviconUrl: String) {
        val page = VisitedPage(url = url, title = title, faviconUrl = faviconUrl, timestamp = System.currentTimeMillis())
        serviceScope.launch {
            try {
                visitedPageDao.insert(page)
            } catch (e: Exception) {
                Log.e("FloatingWebViewService", "Error saving visited page", e)
            }
        }
    }

    private fun setupCloseButton(closeButton: ImageButton, windowId: Int) {
        closeButton.setOnClickListener {
            removeWindow(windowId)
            openedUrls.clear()
        }
    }

    private fun setupHomeButton(homeButton: ImageButton, webView: WebView, context: Context, windowId: Int, windowManager: WindowManager) {
        homeButton.setOnClickListener {
            val currentUrl = webView.url ?: "https://www.google.com"
            activeWindows[windowId]?.first?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                activeWindows.remove(windowId)
            }
            openedUrls.clear()
            val intent = Intent(context, Simpleweb::class.java)
            intent.putExtra("url", currentUrl)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun setupDragListener(headerView: View, windowId: Int) {
        headerView.setOnTouchListener { view, event ->
            val windowPair = activeWindows[windowId] ?: return@setOnTouchListener false
            val params = windowPair.second
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.tag = listOf(params.x.toFloat(), params.y.toFloat(), event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    (view.tag as? List<Float>)?.let { (initialX, initialY, initialTouchX, initialTouchY) ->
                        params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                        params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                        windowManager.updateViewLayout(windowPair.first, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupMoreOptionsButton(moreButton: ImageButton, webView: WebView) {
        moreButton.setOnClickListener {
            val popup = PopupMenu(this, moreButton)
            popup.menuInflater.inflate(R.menu.webview_options_menu, popup.menu)
            val backItem = popup.menu.findItem(R.id.go_back)
            val forwardItem = popup.menu.findItem(R.id.go_forward)
            backItem.isEnabled = webView.canGoBack()
            forwardItem.isEnabled = webView.canGoForward()

            val parentView = moreButton.rootView
            val windowId = activeWindows.entries.find { it.value.first == parentView }?.key
            val params = activeWindows[windowId]?.second
            params?.let {
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                windowManager.updateViewLayout(parentView, it)
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.go_back -> { if (webView.canGoBack()) webView.goBack(); true }
                    R.id.go_forward -> { if (webView.canGoForward()) webView.goForward(); true }
                    R.id.reload -> { webView.reload(); true }
                    R.id.new_tab -> { showFloatingWebView(webView.url ?: "https://www.google.com", "medium"); true }
                    R.id.copy_selected_text -> {
                        webView.evaluateJavascript("(function(){var s=window.getSelection();return s?s.toString():'';})()") { selected ->
                            val text = try {
                                JSONObject("{\"v\":$selected}").getString("v")
                            } catch (e: Exception) {
                                selected?.trim('"') ?: ""
                            }
                            if (text.isNotBlank()) {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
                                Toast.makeText(this, "Selected text copied", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "No selected text", Toast.LENGTH_SHORT).show()
                            }
                        }
                        true
                    }
                    R.id.copy_all -> {
                        webView.evaluateJavascript("(function(){return document.body.innerText;})()") { allText ->
                            val text = allText?.trim('\'') ?: ""
                            if (text.isNotEmpty()) {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
                                Toast.makeText(this, "Page text copied", Toast.LENGTH_SHORT).show()
                            }
                        }
                        true
                    }
                    R.id.open_in_chrome -> {
                        val currentUrl = webView.url
                        if (!currentUrl.isNullOrEmpty()) {
                            minimizeAllWindows()
                            ChromeCustomTabHelper.openUrl(this, currentUrl)
                        } else {
                            Toast.makeText(this, "No URL to open", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    else -> false
                }
            }

            // Note: Removed setOnDismissListener that re-added FLAG_NOT_FOCUSABLE
            // as it was causing touch unresponsiveness after system dialogs (passkey prompts)

            popup.show()
        }
    }

    private fun setupResizeListener(resizeHandle: ImageView, rootView: View, params: WindowManager.LayoutParams) {
        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0
            private var initialHeight = 0
            private var initialX = 0f
            private var initialY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params.width
                        initialHeight = params.height
                        initialX = event.rawX
                        initialY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialX).toInt()
                        val dy = (event.rawY - initialY).toInt()
                        params.width = (initialWidth + dx).coerceAtLeast(300)
                        params.height = (initialHeight + dy).coerceAtLeast(300)
                        windowManager.updateViewLayout(rootView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupJsToggle(jsToggle: LinearLayout, jsStatusIndicator: View, webView: WebView) {
        jsToggle.setOnClickListener {
            isJavaScriptEnabled = !isJavaScriptEnabled
            webView.settings.javaScriptEnabled = isJavaScriptEnabled
            webView.reload()
            jsStatusIndicator.setBackgroundResource(if (isJavaScriptEnabled) R.drawable.circle_green else R.drawable.circle_red)
            Toast.makeText(this, "JavaScript ${if (isJavaScriptEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMinimizeButton(
        minimizeButton: ImageButton,
        mainLayout: View,
        minimizedIcon: View,
        params: WindowManager.LayoutParams,
        rootView: View
    ) {
        minimizeButton.setOnClickListener {
            mainLayout.visibility = View.GONE
            minimizedIcon.visibility = View.VISIBLE
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            windowManager.updateViewLayout(rootView, params)
        }

        minimizedIcon.setOnClickListener {
            mainLayout.visibility = View.VISIBLE
            minimizedIcon.visibility = View.GONE
            params.width = rootView.getTag(R.id.tag_original_width) as Int
            params.height = rootView.getTag(R.id.tag_original_height) as Int
            windowManager.updateViewLayout(rootView, params)
        }
    }

    private fun setupMinimizedIconDragListener(minimizedIcon: View, params: WindowManager.LayoutParams, rootView: View, windowId: Int) {
        minimizedIcon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (dx > 10 || dy > 10) { // Start dragging only after a small movement
                            isDragging = true
                        }
                        if(isDragging) {
                            params.x = (initialX + dx).toInt()
                            params.y = (initialY + dy).toInt()
                            windowManager.updateViewLayout(rootView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }


    private fun removeWindow(windowId: Int) {
        try {
            currentSelectionPopup?.dismiss()
            cancelSelectionPopup(dismissCurrent = false)
            activeWindows[windowId]?.let { (view, _) ->
                windowManager.removeView(view)
                (view.findViewById<WebView>(R.id.webView))?.destroy()
                activeWindows.remove(windowId)
                openedUrls.clear()
            }
            if (activeWindows.isEmpty()) stopSelf()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSelectionPopup()
        CookieManager.getInstance().flush()
        activeWindows.keys.toList().forEach { removeWindow(it) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        activeWindows.keys.firstOrNull()?.let { removeWindow(it) }
    }

    /**
     * Detects if a URL is a login/authentication page that should be opened
     * in Chrome Custom Tabs for passkey support
     */
    private fun isLoginPage(url: String): Boolean {
        val loginPatterns = listOf(
            "/login",
            "/signin",
            "/sign-in",
            "/sign_in",
            "/authenticate",
            "/auth/",
            "/oauth",
            "/sso/",
            "/accounts/login",
            "/session/new",
            "login.php",
            "signin.php",
            "passkey=true"
        )
        val lowerUrl = url.lowercase()
        return loginPatterns.any { lowerUrl.contains(it) }
    }

    /**
     * Minimizes all floating windows to allow Chrome Custom Tab to be used
     */
    private fun minimizeAllWindows() {
        activeWindows.values.forEach { (rootView, params) ->
            try {
                val mainLayout = rootView.findViewById<View>(R.id.mainLayout)
                val minimizedIcon = rootView.findViewById<View>(R.id.minimizedIcon)
                if (mainLayout != null && minimizedIcon != null) {
                    mainLayout.visibility = View.GONE
                    minimizedIcon.visibility = View.VISIBLE
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    windowManager.updateViewLayout(rootView, params)
                }
            } catch (e: Exception) {
                Log.e("FloatingWebView", "Error minimizing window", e)
            }
        }
    }
}

