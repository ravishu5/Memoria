package dev.miniscreenpipe

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var db: HistoryDb
    private lateinit var page: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var state: TextView
    private lateinit var toggle: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var preparingEvidence = false
    private val handler = Handler(Looper.getMainLooper())
    private var tab = "History"
    private var viewedEntry:Entry?=null
    private var viewerReturn="Timeline"
    private var selectedDay: LocalDate? = LocalDate.now()
    private var query = ""
    private var answer = ""
    private var asking = false
    private var lastQuestion = ""
    private var activeRequest: Gemini.RequestControl? = null
    private var renderGeneration = 0
    private var filter: TextView? = null
    private var unlocked=false
    private var authenticating=false
    private var authCancellation:CancellationSignal?=null
    private val dialogs=mutableListOf<Dialog>()
    private val dark get()=prefs.appearance=="dark" || (prefs.appearance=="system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)
    private val surface get()=if(dark)Color.rgb(18,23,34) else Color.WHITE
    private val backgroundColor get()=if(dark)Color.rgb(11,14,20) else Color.rgb(247,248,253)
    private val ink get()=if(dark)Color.rgb(249,250,251) else Color.rgb(24,30,48)
    private val green get()=if(dark)Color.rgb(165,164,255) else Color.rgb(99,102,241)
    private val muted get()=if(dark)Color.rgb(156,163,175) else Color.rgb(105,115,137)
    private val ticker = object : Runnable {
        override fun run() { if (::state.isInitialized) { state.text = CaptureService.status; toggle.text = if(prefs.recording) "Pause capture" else "Start capture" }; handler.postDelayed(this, 1500) }
    }
    override fun onCreate(saved: Bundle?) {
        prefs=Prefs(this)
        setTheme(if(dark)R.style.AppTheme else R.style.AppThemeLight)
        super.onCreate(saved)
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { navigateBack() }
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        prefs = Prefs(this); db = HistoryDb.get(this);RetentionJob.schedule(this); background { db.prune(prefs.retention) }
        if (saved != null) { tab = saved.getString("tab", "History");if(tab=="Viewer")tab="Timeline"; query = saved.getString("query", ""); selectedDay = saved.getString("day")?.let(LocalDate::parse); answer = saved.getString("answer", "");lastQuestion=saved.getString("question", "") }
        // Target 35 draws edge-to-edge; account for system bars explicitly.
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            if (::page.isInitialized) { val bars = insets.getInsets(WindowInsets.Type.systemBars()); page.setPadding(dp(20), bars.top + dp(4), dp(20), bars.bottom + dp(4)) }; insets
        }
        if(prefs.privacy)lockPage() else { unlocked=true;render() }; handler.post(ticker)
    }
    private fun navigateBack() { tab=when(tab) { "Viewer" -> viewerReturn;"Gemini" -> "Settings";"History" -> { finish();return };else -> "History" };render() }
    @Deprecated("Legacy Android back") override fun onBackPressed() { navigateBack() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("tab", tab); out.putString("query", query); out.putString("day", selectedDay?.toString()); out.putString("answer", answer);out.putString("question",lastQuestion) }
    override fun onResume() { super.onResume();if(prefs.privacy && !unlocked) { lockPage();authenticate { unlocked=true;render() } } else if(::body.isInitialized && !asking)render() }
    override fun onStop() { super.onStop();if(prefs.privacy) { unlocked=false;activeRequest?.cancel();activeRequest=null;asking=false;dialogs.toList().forEach { it.dismiss() };lockPage() } }
    private fun toast(value:String) { Toast.makeText(this,value,Toast.LENGTH_LONG).show() }
    private fun showDialog(dialog:Dialog) { dialogs.add(dialog);dialog.setOnDismissListener { dialogs.remove(dialog) };dialog.show() }
    private fun lockPage() {
        renderGeneration++
        val locked=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(48),dp(24),dp(24));setBackgroundColor(backgroundColor) }
        locked.addView(text("History is locked",26f,ink,true));locked.addView(button("Unlock") { authenticate { unlocked=true;render() } });setContentView(locked)
    }
    private fun authenticate(success:()->Unit) {
        if(authenticating)return
        val authenticators=android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(getSystemService(android.hardware.biometrics.BiometricManager::class.java).canAuthenticate(authenticators)!=android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) { toast("Set up a device screen lock or supported biometrics first.");if(!prefs.privacy)render();return }
        authenticating=true
        authCancellation=CancellationSignal()
        android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle("Unlock Mini Screenpipe").setAllowedAuthenticators(authenticators).build().authenticate(authCancellation!!,mainExecutor,object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { authenticating=false;if(!isDestroyed)success() }
            override fun onAuthenticationError(code:Int,message:CharSequence) { authenticating=false;if(!isDestroyed) { toast(message.toString());if(!prefs.privacy)render() } }
        })
    }
    override fun onDestroy() { authCancellation?.cancel();dialogs.toList().forEach { it.dismiss() };activeRequest?.cancel(); handler.removeCallbacksAndMessages(null); executor.shutdownNow(); networkExecutor.shutdownNow(); super.onDestroy() }
    private fun background(work:()->Unit) { executor.execute { try { work() } catch(_:Exception) { runOnUiThread { if(!isDestroyed)toast("Could not load this memory. Check available storage.") } } } }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0,dp(2),0,dp(2));includeFontPadding=false;setLineSpacing(dp(2).toFloat(),1f)
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply {
        text=label;isAllCaps=false;textSize=13f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(ink);minHeight=dp(48);minimumHeight=dp(48);setPadding(dp(12),dp(10),dp(12),dp(10));background=ripple(outlined(elevated,12));stateListAnimator=null;setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) }
    }
    private fun primary(label:String,action:()->Unit)=button(label,action).apply { setTextColor(Color.WHITE);background=ripple(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.rgb(105,99,255),Color.rgb(126,137,255))).apply { cornerRadius=dp(12).toFloat() }) }
    private fun input(hintText: String, value: String = "", multiline: Boolean = false) = EditText(this).apply {
        hint = hintText; setText(value); textSize = 14f; setTextColor(ink); setSingleLine(!multiline); setPadding(dp(12), dp(12), dp(12), dp(12))
        background=outlined(elevated,12);setHintTextColor(muted)
    }
    private fun card(parent: LinearLayout = body): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background=outlined(surface,14)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun range(): Pair<Long, Long> = selectedDay?.let { it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() to it.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } ?: (0L to Long.MAX_VALUE)
    private fun entries(limit: Int = 250): List<Entry> { val (start, end) = range(); return db.search(query, start, end, limit) }
    private val elevated get()=if(dark)Color.rgb(26,34,48) else Color.rgb(236,239,252)
    private val border get()=if(dark)Color.rgb(42,51,69) else Color.rgb(216,221,235)
    private val success=Color.rgb(93,204,139)
    private fun icon(name:String,size:Int=22,color:Int=green)=UiIcon(this,name,color).apply { layoutParams=LinearLayout.LayoutParams(dp(size),dp(size));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun outlined(color:Int,radius:Int=14,stroke:Int=border)=shape(color,radius).apply { setStroke(dp(1),stroke) }
    private fun ripple(drawable:android.graphics.drawable.Drawable)=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(dark)0x336366F1 else 0x226366F1),drawable,null)
    private fun space(parent:LinearLayout,height:Int=12) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun section(title:String,action:String?=null,onClick:(()->Unit)?=null) {
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(6),0,dp(12)) }
        row.addView(text(title,16f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
        if(action!=null)row.addView(text(action,12f,green).apply { setPadding(dp(8),dp(8),0,dp(8));setOnClickListener { onClick?.invoke() } })
        body.addView(row)
    }
    private fun pill(value:String,active:Boolean=false,action:()->Unit):TextView=text(value,12f,if(active)Color.WHITE else muted).apply {
        gravity=Gravity.CENTER;setPadding(dp(14),dp(9),dp(14),dp(9));background=ripple(if(active)shape(Color.rgb(99,102,241),20) else outlined(surface,20));setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(8) };minHeight=dp(40)
    }
    private fun appIcon(pkg:String,size:Int=28):View = ImageView(this).apply {
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))
        try { setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch(_:Exception) { setImageDrawable(outlined(elevated,8));contentDescription="App" }
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun thumbnail(e:Entry,width:Int,height:Int):ImageView=ImageView(this).apply {
        layoutParams=LinearLayout.LayoutParams(dp(width),dp(height));scaleType=ImageView.ScaleType.CENTER_CROP;background=outlined(elevated,8);clipToOutline=true
        if(e.image.isBlank()) { setImageDrawable(null);contentDescription="Screenshot no longer stored";return@apply }
        val target=this;val generation=renderGeneration
        background {
            val file=File(db.images,e.image)
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.absolutePath,options)
            var sample=1;while(options.outHeight/sample>dp(height)*2 && options.outWidth/sample>dp(width)*2)sample*=2
            val bitmap=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample })
            runOnUiThread { if(!isDestroyed && generation==renderGeneration)target.setImageBitmap(bitmap) else bitmap?.recycle() }
        }
    }
    private fun render() {
        if(prefs.privacy && !unlocked) { lockPage();return }
        renderGeneration++
        window.insetsController?.setSystemBarsAppearance(if(dark)0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(backgroundColor);setPadding(dp(20),dp(36),dp(20),dp(12)) }
        setContentView(page);page.requestApplyInsets()
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(20)) }
        val subpage=tab in listOf("Gemini","Viewer","Permissions")
        if(subpage)header.addView(FrameLayout(this).apply { addView(icon("back"));setPadding(0,dp(10),dp(16),dp(10));contentDescription="Back";setOnClickListener { navigateBack() } })
        val title=when(tab) { "History" -> "Mini Screenpipe";"Ask Gemini" -> "Search";"Viewer" -> "Memory";else -> tab }
        header.addView(text(title,22f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
        val badge=FrameLayout(this).apply { background=outlined(surface,12);setPadding(dp(10),dp(10),dp(10),dp(10));addView(icon(if(tab=="History")"lock" else "spark",18));contentDescription=if(tab=="History")"Privacy information" else "Gemini settings";setOnClickListener { tab=if(tab=="History")"Settings" else "Gemini";render() } }
        if(!subpage)header.addView(badge);page.addView(header)
        state=text(CaptureService.status,11f,muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        toggle=primary(if(prefs.recording)"Pause capture" else "Start capture") { if(prefs.recording)CaptureService.instance?.pause() ?: run { prefs.recording=false } else startCapture();render() }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(body);isFillViewport=true;isVerticalScrollBarEnabled=false;clipToPadding=false },LinearLayout.LayoutParams(-1,0,1f))
        when(tab) { "History" -> dashboard();"Timeline" -> history();"Ask Gemini" -> askPage();"Gemini" -> geminiSettings();"Viewer" -> viewedEntry?.let { viewer(it) };"Permissions" -> permissionsPage();else -> settings() }
        if(!subpage)bottomNav()
    }
    private fun bottomNav() {
        val line=View(this).apply { setBackgroundColor(border) };page.addView(line,LinearLayout.LayoutParams(-1,dp(1)))
        val nav=LinearLayout(this).apply { gravity=Gravity.CENTER;setPadding(0,dp(10),0,0) }
        for((label,name,symbol) in listOf(Triple("Home","History","home"),Triple("Search","Ask Gemini","search"),Triple("Timeline","Timeline","timeline"),Triple("Settings","Settings","settings"))) {
            val selected=tab==name;val item=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(0,dp(6),0,dp(6));background=ripple(shape(backgroundColor,12));contentDescription=label;setOnClickListener { tab=name;render() } }
            item.addView(FrameLayout(this).apply { layoutParams=LinearLayout.LayoutParams(dp(34),dp(34));setPadding(dp(6),dp(6),dp(6),dp(6));background=shape(if(selected) { if(dark)Color.rgb(31,29,65) else Color.rgb(229,227,255) } else backgroundColor,10);addView(icon(symbol,21,if(selected)green else muted)) });item.addView(text(label,11f,if(selected)green else muted,selected).apply { gravity=Gravity.CENTER });nav.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        page.addView(nav)
    }
    private fun dashboard() {
        val statusCard=card();val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val dot=FrameLayout(this).apply { background=shape(if(prefs.recording)Color.rgb(24,57,41) else elevated,10);setPadding(dp(10),dp(10),dp(10),dp(10));addView(icon(if(prefs.recording)"check" else "pause",20,if(prefs.recording)success else muted)) }
        row.addView(dot);val labels=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0);addView(text(if(prefs.recording)"Capture is active" else "Capture is paused",14f,ink,true));addView(state) };row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));toggle.textSize=11f;row.addView(toggle,LinearLayout.LayoutParams(dp(92),dp(48)).apply { leftMargin=dp(8) });statusCard.addView(row)
        questionBar()
        val generation=renderGeneration
        val today=LocalDate.now();val from=today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();val until=today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        background {
            val summary=db.summary("",from,until);val recent=db.search("",0,Long.MAX_VALUE,6);val packages=db.search("",from,until,80).associate { it.app to it.pkg };val bytes=db.imageBytes()
            runOnUiThread {
                if(isDestroyed || generation!=renderGeneration)return@runOnUiThread
                section("Today")
                val stats=card();val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                val seconds=summary.appSeconds.sumOf { it.second };val duration=if(seconds>=3600)"${seconds/3600}h ${(seconds%3600)/60}m" else "${seconds/60}m ${seconds%60}s"
                top.addView(text(duration,27f,ink,true),LinearLayout.LayoutParams(0,-2,1f));top.addView(text("${summary.count} captures",12f,green));stats.addView(top)
                stats.addView(text("Sampled app time · ${summary.samples} observations",11f,muted));space(stats,10)
                val bar=LinearLayout(this).apply { background=shape(elevated,8);clipToOutline=true }
                val colors=listOf(Color.rgb(129,155,255),Color.rgb(159,142,255),Color.rgb(108,105,244),Color.rgb(76,72,196),Color.rgb(186,173,253))
                summary.appSeconds.forEachIndexed { i,pair -> bar.addView(View(this).apply { setBackgroundColor(colors[i%colors.size]) },LinearLayout.LayoutParams(0,dp(10),pair.second.coerceAtLeast(1).toFloat())) };stats.addView(bar,LinearLayout.LayoutParams(-1,dp(10)))
                if(summary.appSeconds.isNotEmpty()) { space(stats,12);val apps=LinearLayout(this);summary.appSeconds.forEach { (name,time) -> val item=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;addView(packages[name]?.let { appIcon(it,24) } ?: icon("apps",24,muted));space(this,6);addView(text(name,10f,ink,true).apply { gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END });addView(text("${time/60}m",10f,green).apply { gravity=Gravity.CENTER }) };apps.addView(item,LinearLayout.LayoutParams(0,-2,1f)) };stats.addView(apps) }
                section("Recent memories","See all") { tab="Timeline";selectedDay=null;query="";render() }
                if(recent.isEmpty()) {
                    val empty=card();empty.addView(icon("image",32));space(empty,10);empty.addView(text("Your story starts here",18f,ink,true));empty.addView(text("Start capture and use your phone. Your screenshots become a searchable memory.",13f,muted));empty.addView(button("Set up capture") { tab="Permissions";render() })
                } else {
                    recent.chunked(2).forEach { pair -> val grid=LinearLayout(this);pair.forEachIndexed { index,e ->
                        val item=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10));background=ripple(outlined(surface,14));setOnClickListener { detail(e) } }
                        item.addView(thumbnail(e,138,104),LinearLayout.LayoutParams(-1,dp(104)));space(item,8);item.addView(text(e.app,12f,ink,true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END });item.addView(text(formatTime(e.time),11f,muted));grid.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0)rightMargin=dp(10);bottomMargin=dp(10) })
                    };if(pair.size==1)grid.addView(View(this),LinearLayout.LayoutParams(0,1,1f));body.addView(grid) }
                }
                body.addView(text("On this device · ${bytes/1024/1024} MB of screenshots",11f,muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(12)) })
            }
        }
    }
    private fun formatTime(time:Long)=java.text.SimpleDateFormat("d MMM · HH:mm",java.util.Locale.getDefault()).format(time)
    private fun filters() {
        val dates=LinearLayout(this)
        dates.addView(pill("Today",selectedDay==LocalDate.now()) { selectedDay=LocalDate.now();render() })
        dates.addView(pill("Yesterday",selectedDay==LocalDate.now().minusDays(1)) { selectedDay=LocalDate.now().minusDays(1);render() })
        dates.addView(pill("All",selectedDay==null) { selectedDay=null;render() })
        dates.addView(pill("Date",false) { val date=selectedDay ?: LocalDate.now();showDialog(DatePickerDialog(this,{ _,y,m,d -> selectedDay=LocalDate.of(y,m+1,d);render() },date.year,date.monthValue-1,date.dayOfMonth)) })
        body.addView(HorizontalScrollView(this).apply { addView(dates);isHorizontalScrollBarEnabled=false });space(body,12)
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;background=outlined(surface,12);setPadding(dp(12),0,dp(8),0) }
        row.addView(icon("search",18,muted));val field=input("Filter words or app",query).apply { background=null;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        row.addView(field,LinearLayout.LayoutParams(0,dp(48),1f));val apply=icon("arrow",20);apply.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;apply.isFocusable=true;apply.contentDescription="Filter timeline";apply.setOnClickListener { query=field.text.toString();render() };row.addView(FrameLayout(this).apply { setPadding(dp(12),dp(14),dp(12),dp(14));addView(apply);contentDescription="Filter timeline";setOnClickListener { query=field.text.toString();render() } })
        field.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { query=field.text.toString();render();true } else false };body.addView(row);space(body,12)
        body.addView(text(selectedDay?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) ?: "All retained history",12f,muted));space(body,8)
    }
    private fun history() {
        filters();val target=body;val generation=renderGeneration;val (start,end)=range();val search=query
        background {
            val rows=db.search(search,start,end,80);val summary=db.summary(search,start,end)
            runOnUiThread {
                if(isDestroyed || generation!=renderGeneration)return@runOnUiThread
                if(rows.isEmpty()) { val empty=card();empty.addView(icon("timeline",32));space(empty);empty.addView(text("No memories here yet",18f,ink,true));empty.addView(text("Try another date or filter, or start capturing your activity.",13f,muted)) }
                var day=""
                rows.forEach { e ->
                    val date=java.text.SimpleDateFormat("EEE, d MMM",java.util.Locale.getDefault()).format(e.time)
                    if(date!=day) { section(date);day=date }
                    val row=LinearLayout(this).apply { gravity=Gravity.TOP }
                    val rail=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,dp(10),dp(10),0);addView(text(java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(e.time),10f,muted));addView(icon("timeline",13,green));addView(View(this@MainActivity).apply { setBackgroundColor(border) },LinearLayout.LayoutParams(dp(1),dp(80))) }
                    row.addView(rail,LinearLayout.LayoutParams(dp(44),-1))
                    val item=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=ripple(outlined(surface,14));setOnClickListener { detail(e) };contentDescription="Open moment #${e.id}" }
                    val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                    val app=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;addView(appIcon(e.pkg,18));addView(text(e.app,11f,muted).apply { setPadding(dp(6),0,0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }) };copy.addView(app);space(copy,8)
                    copy.addView(text(e.text.ifBlank { "Screenshot without readable text" }.take(180),13f,ink,true).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END })
                    if(e.samples>1)copy.addView(text("${e.samples} samples · until ${java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(e.lastSeen)}",10f,muted))
                    item.addView(copy,LinearLayout.LayoutParams(0,-2,1f));item.addView(thumbnail(e,64,84),LinearLayout.LayoutParams(dp(64),dp(84)).apply { leftMargin=dp(10) });row.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply { bottomMargin=dp(10) });target.addView(row)
                }
                if(summary.count>80)target.addView(text("Showing 80 newest moments. Narrow the date or filter to find more.",12f,muted))
                target.addView(button("Export matching text as JSON") { export() })
            }
        }
    }
    private fun startCapture() {
        if (!prefs.p.getBoolean("consent", false)) {
            AlertDialog.Builder(this).setTitle("Allow your phone to remember?")
                .setMessage("Mini Screenpipe uses Android Accessibility to sample visible screens and app names, even while you use other apps. Screenshots and recognized text stay in private app storage. Password fields, locked screens, and excluded packages are skipped; filters cannot identify every sensitive screen. Android 14+ captures the active window; Android 11–13 captures the display and may include overlays.\n\nNo audio or keystrokes are recorded. Capture is visible in a notification and can be paused. Only a Gemini request you confirm sends selected text to Google.\n\nNext, enable Mini Screenpipe capture in Accessibility settings, then return and tap Start capture.")
                .setNegativeButton("Cancel", null).setPositiveButton("Agree & enable") { _, _ -> prefs.p.edit().putBoolean("consent", true).apply(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show(); return
        }
        if (CaptureService.instance == null) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2); return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { Toast.makeText(this, "Enable notifications so capture remains visible.", Toast.LENGTH_LONG).show(); startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)); return }
        CaptureService.instance?.resume()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if(requestCode == 2 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) { startCapture(); render() }
    }
    private fun askPage() {
        body.addView(text("Find a moment. Connect the dots.",14f,muted));space(body,16)
        questionBar(true)
        if(answer.isBlank() && !asking) {
            section("Try searching for")
            for((symbol,prompt) in listOf("text" to "What was I reading yesterday?","timeline" to "What did I look at earlier this week?","apps" to "Which apps appeared in my history?","spark" to "Summarize my day with evidence.")) {
                val row=card();row.orientation=LinearLayout.HORIZONTAL;row.gravity=Gravity.CENTER_VERTICAL;row.background=ripple(outlined(surface,12));row.addView(icon(symbol,20,muted));row.addView(text(prompt,13f).apply { setPadding(dp(12),0,0,0) },LinearLayout.LayoutParams(0,-2,1f));row.addView(icon("chevron",14,muted));row.setOnClickListener { lastQuestion=prompt;ask(prompt,true) }
            }
            body.addView(text("Powered by your configured Gemini model. Selected text is shared only after you review it.",11f,muted).apply { setPadding(0,dp(12),0,dp(12)) })
        }
        if(asking) { val thinking=card();thinking.addView(text("Connecting your memories…",14f,green,true));thinking.addView(text("Looking for an answer supported by your captured history.",12f,muted));thinking.addView(button("Cancel request") { activeRequest?.cancel();activeRequest=null;asking=false;answer="Request cancelled.";render() }) }
        if(answer.isNotBlank()) {
            section("Your conversation")
            val question=text(lastQuestion,14f,ink).apply { setPadding(dp(16),dp(14),dp(16),dp(14));background=outlined(elevated,14) }
            body.addView(question,LinearLayout.LayoutParams(-1,-2).apply { leftMargin=dp(34);bottomMargin=dp(16) })
            val reply=card();val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;addView(icon("spark",18));addView(text("From your memories",12f,green,true).apply { setPadding(dp(8),0,0,0) }) };reply.addView(heading);space(reply,12);reply.addView(answerView(answer));space(reply,12);reply.addView(text("Tap a reference to see the original moment. AI interpretation may be wrong.",11f,muted))
        }
    }
    private var questionScope=0
    private fun questionBar(expanded:Boolean=false) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(16)) };body.addView(box)
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;background=outlined(elevated,14,if(expanded)green else border);setPadding(dp(12),dp(4),dp(8),dp(4)) }
        row.addView(icon("search",19,muted))
        val field=input(if(expanded)"Ask anything about your past activity…" else "Search your memories…",lastQuestion,expanded).apply { background=null;if(expanded) { minLines=3;maxLines=5 };imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        row.addView(field,LinearLayout.LayoutParams(0,-2,1f))
        fun submit() { val q=field.text.toString().trim();if(q.isBlank()) { tab="Ask Gemini";render();return };lastQuestion=q;(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken,0);ask(q,if(expanded)questionScope==0 else true) }
        val send=FrameLayout(this).apply { minimumWidth=dp(48);minimumHeight=dp(48);setPadding(dp(15),dp(15),dp(15),dp(15));background=ripple(shape(Color.rgb(99,102,241),10));addView(icon("arrow",18,Color.WHITE));contentDescription="Search with Gemini";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;isFocusable=true;setOnClickListener { submit() };isEnabled=!asking }
        row.addView(send);box.addView(row)
        field.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { submit();true } else false }
        if(expanded) {
            space(box,12)
            val scopes=LinearLayout(this);scopes.addView(pill("All history",questionScope==0) { questionScope=0;lastQuestion=field.text.toString();render() });scopes.addView(pill("Selected day",questionScope==1) { questionScope=1;lastQuestion=field.text.toString();render() });scopes.addView(pill("Keyword filter",false) { tab="Timeline";render() });box.addView(HorizontalScrollView(this).apply { addView(scopes);isHorizontalScrollBarEnabled=false })
            if(questionScope==1) { space(box,12);filters() }
        }
    }
    private fun detail(e:Entry) { viewerReturn=tab;viewedEntry=e;tab="Viewer";render() }
    private fun viewer(e:Entry) {
        val meta=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;addView(appIcon(e.pkg,24));addView(text("${e.app} · ${formatTime(e.time)}",12f,muted).apply { setPadding(dp(8),0,0,0) }) };body.addView(meta);space(body,16)
        val container=card();val image=ImageView(this).apply { adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;background=shape(elevated,12);clipToOutline=true;contentDescription="Captured screenshot" };container.addView(image,LinearLayout.LayoutParams(-1,-2))
        val generation=renderGeneration
        background { val bitmap=if(e.image.isBlank())null else BitmapFactory.decodeFile(File(db.images,e.image).absolutePath);runOnUiThread { if(!isDestroyed && generation==renderGeneration) { if(bitmap!=null)image.setImageBitmap(bitmap) else container.addView(text("Image no longer stored. Recognized text is still available.",12f,muted)) } else bitmap?.recycle() } }
        section("Recognized text")
        val transcript=card();transcript.addView(text(e.text.ifBlank { "No readable text was found." },14f).apply { setTextIsSelectable(true) });space(transcript,10);transcript.addView(text("${e.source} · ${e.quality} · ${e.samples} samples",11f,muted))
        val actions=LinearLayout(this);actions.addView(button("Copy text") { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Recognized text",e.text));toast("Text copied") },LinearLayout.LayoutParams(0,-2,1f));actions.addView(button("Ask about this") { lastQuestion="What can you tell me about moment #${e.id}?";tab="Ask Gemini";askAboutEntry(e) },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) });body.addView(actions)
        body.addView(button("Delete this moment") { showDialog(AlertDialog.Builder(this).setTitle("Delete this moment?").setMessage("Remove its local text and screenshot.").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> background { db.delete(e.id);runOnUiThread { viewedEntry=null;tab="Timeline";render() } } }.create()) }.apply { setTextColor(Color.rgb(244,113,121)) })
    }
    private fun askAboutEntry(e:Entry) {
        render();val context=Gemini.context(listOf(e),lastQuestion)
        confirmEvidence(lastQuestion,context)
    }
    private fun permissionsPage() {
        body.addView(text("Give your memory a place to start.",26f,ink,true));space(body,12)
        body.addView(text("Capture readable screens locally. Search your history and ask Gemini when you need help remembering.",14f,muted));space(body,24)
        val access=card();access.addView(icon("screen",28));space(access);access.addView(text("Accessibility",17f,ink,true));access.addView(text(if(CaptureService.instance!=null)"Connected. Capture starts only when you choose." else "Required to capture visible screens and app names.",13f,muted));access.addView(button("Open accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val notice=card();notice.addView(icon("bell",28));space(notice);notice.addView(text("Visible capture controls",17f,ink,true));notice.addView(text("Notifications keep capture visible and give you a quick Pause button.",13f,muted))
        val privacy=card();privacy.addView(icon("lock",28));space(privacy);privacy.addView(text("Your memory, your control",17f,ink,true));privacy.addView(text("Local screenshots and OCR. Password and excluded screens are skipped. Gemini receives selected text only after you confirm.",13f,muted))
        body.addView(primary("Get started") { startCapture();if(CaptureService.instance!=null) { tab="History";render() } })
    }
    private fun answerView(value: String): TextView {
        val styled = SpannableString(value)
        Regex("\\[(\\d+)\\]").findAll(value).forEach { match ->
            val id = match.groupValues[1].toLongOrNull() ?: return@forEach
            styled.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    background { val e=db.getEntry(id); runOnUiThread { if(!isDestroyed) { if(e!=null)detail(e) else Toast.makeText(this@MainActivity,"This moment has been deleted or expired",Toast.LENGTH_LONG).show() } } }
                }
            },match.range.first,match.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text(value).apply { text=styled; movementMethod=LinkMovementMethod.getInstance(); setLinkTextColor(green) }
    }
    private fun ask(question: String,allHistory:Boolean=false) {
        if(preparingEvidence || asking) return
        if(question.length>8000) { Toast.makeText(this,"Keep the question under 8,000 characters",Toast.LENGTH_LONG).show();return }
        preparingEvidence=true
        val generation=renderGeneration; val (start,end)=if(allHistory)(0L to Long.MAX_VALUE) else range(); val querySnapshot=if(allHistory)"" else query
        background {
            val rows=try { db.evidence(querySnapshot,start,end,question) } catch(_:Exception) { emptyList() }
            val context=Gemini.context(rows,question)
            runOnUiThread {
                preparingEvidence=false
                if(isDestroyed || generation!=renderGeneration)return@runOnUiThread
                if(context.isBlank()) { Toast.makeText(this,"No matching history to summarize",Toast.LENGTH_LONG).show();return@runOnUiThread }
                confirmEvidence(question,context)
            }
        }
    }
    private fun confirmEvidence(question:String,rawContext:String) {
                val context=if(prefs.redactText)TextPrivacy.redact(rawContext).take(40000) else rawContext
                val preview=text(context).apply { setPadding(dp(16),dp(8),dp(16),dp(8));setTextIsSelectable(true) }
                showDialog(AlertDialog.Builder(this).setTitle("Send selected evidence to Gemini?")
                    .setMessage("This shares up to 160 relevant/time-distributed moments, capped at 40,000 characters, and your question. Screenshots stay local. Temporary server failures may be retried, up to three requests total; API charges may apply. Review the actual evidence below.")
                    .setView(ScrollView(this).apply { addView(preview) }).setNegativeButton("Cancel",null).setPositiveButton("Send to Gemini") { _, _ ->
                        activeRequest?.cancel()
                        val control=Gemini.RequestControl();activeRequest=control;asking=true;answer="";tab="Ask Gemini";render()
                        networkExecutor.execute {
                            val result=try { Gemini.ask(prefs.key(),prefs.model,question,context,control) } catch(e:Exception) { "Request failed: ${e.message}" }
                            runOnUiThread { if(!isDestroyed && activeRequest===control && !control.cancelled) { answer=result;asking=false;activeRequest=null;render() } }
                        }
                    }.create())
    }
    private fun settingSymbol(title:String)=when(title) { "Appearance" -> "moon";"Privacy mode","Privacy information" -> "lock";"Gemini" -> "spark";"Apps to record","Excluded apps" -> "apps";"Image resolution","Grayscale images" -> "image";"Auto delete","Delete all history" -> "trash";"Capture interval" -> "timeline";"Export all screenshots","Export diagnostics" -> "download";"Text recognition" -> "text";else -> "screen" }
    private fun settingsIcon(title:String)=FrameLayout(this).apply { background=shape(elevated,10);setPadding(dp(8),dp(8),dp(8),dp(8));addView(icon(settingSymbol(title),20,muted));layoutParams=LinearLayout.LayoutParams(dp(36),dp(36)).apply { rightMargin=dp(12) } }
    private fun settingRow(parent:LinearLayout,title:String,description:String,action:()->Unit) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(10));background=ripple(shape(surface,10));isClickable=true;setOnClickListener { action() } }
        row.addView(settingsIcon(title));val label=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(text(title,14f,ink,true));addView(text(description,11f,muted).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }) }
        row.addView(label,LinearLayout.LayoutParams(0,-2,1f));row.addView(icon("chevron",15,muted));parent.addView(row)
    }
    private fun styledSwitch(title:String,checked:Boolean,change:(Boolean)->Unit)=Switch(this).apply {
        contentDescription=title;isChecked=checked;setPadding(dp(10),0,0,0)
        thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(215,213,255),Color.rgb(157,165,182)))
        trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(99,102,241),border))
        setOnCheckedChangeListener { _,value -> change(value) }
    }
    private fun settingSwitch(parent:LinearLayout,title:String,description:String,checked:Boolean,change:(Boolean)->Unit) {
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(10)) };row.addView(settingsIcon(title))
        val label=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(text(title,14f,ink,true));addView(text(description,11f,muted).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }) }
        row.addView(label,LinearLayout.LayoutParams(0,-2,1f));row.addView(styledSwitch(title,checked,change));parent.addView(row)
    }
    private fun choices(title:String,labels:Array<String>,selected:Int,onSelect:(Int)->Unit) {
        showDialog(AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,selected) { dialog,index -> dialog.dismiss();onSelect(index) }.setNegativeButton("Cancel",null).create())
    }
    private fun saveCapture(name:String,value:Int) { CaptureService.instance?.pause();prefs.p.edit().putInt(name,value).apply();db.breakSession();render() }
    private fun settings() {
        section("General");val general=card()
        settingSwitch(general,"Foreground service","Keep capture visible in the background",prefs.foreground) { value -> CaptureService.instance?.pause();prefs.p.edit().putBoolean("foreground",value).apply();render() }
        settingRow(general,"Appearance",prefs.appearance.replaceFirstChar { it.uppercase() }) {
            val modes=listOf("system","light","dark");choices("Appearance",arrayOf("Follow system","Light","Dark"),modes.indexOf(prefs.appearance)) { prefs.p.edit().putString("appearance",modes[it]).apply();recreate() }
        }
        settingSwitch(general,"Privacy mode","Biometric or device-lock authentication",prefs.privacy) { value ->
            if(value) authenticate { prefs.p.edit().putBoolean("privacy",true).apply();unlocked=true;render() }
            else { prefs.p.edit().putBoolean("privacy",false).apply();render() }
        }
        settingRow(general,"Gemini",if(prefs.p.contains("key")) "API key saved · ${prefs.model}" else "Configure your API key and model") { tab="Gemini";render() }
        section("Capture & storage");val storage=card()
        settingSwitch(storage,"Adaptive capture","3s visual checks · 250ms scroll settling · idle at most 15s; uses more battery",prefs.adaptive) { value -> CaptureService.instance?.pause();prefs.p.edit().putBoolean("adaptive",value).apply();render() }
        settingSwitch(storage,"Redact sensitive text","Best effort for emails, phone/card numbers and keys. Images and old stored text remain unchanged; Gemini evidence is filtered.",prefs.redactText) { value -> CaptureService.instance?.pause();prefs.p.edit().putBoolean("redact_text",value).apply();db.breakSession();render() }
        settingRow(storage,"Blocked websites","Skip detected browser addresses · private-tab labels are also filtered") {
            val field=input("Domains, one per line (example.com)",prefs.blockedDomains,true)
            showDialog(AlertDialog.Builder(this).setTitle("Blocked websites").setMessage("Matches a domain and its subdomains when the browser exposes its address bar. Private tabs are detected from visible labels. Hidden addresses and private modes cannot always be detected; exclude the entire browser for stronger protection.").setView(field).setPositiveButton("Save") { _,_ -> CaptureService.instance?.pause();prefs.p.edit().putString("blocked_domains",field.text.toString()).apply();render() }.setNegativeButton("Cancel",null).create())
        }
        settingSwitch(storage,"Grayscale images","Save color-free screenshots after OCR",prefs.grayscale) { value -> CaptureService.instance?.pause();prefs.p.edit().putBoolean("grayscale",value).apply();db.breakSession();render() }
        settingRow(storage,"Image resolution",if(prefs.resolution==0) "Original" else "${prefs.resolution}p · short edge") {
            val resolutions=listOf(480,720,1080,0);choices("New screenshot resolution",arrayOf("480p · smallest","720p · balanced","1080p · detailed","Original · most storage"),resolutions.indexOf(prefs.resolution)) { saveCapture("resolution",resolutions[it]) }
        }
        settingRow(storage,"Text recognition","Latin · bundled on-device OCR") { showDialog(AlertDialog.Builder(this).setTitle("Text recognition").setMessage("Latin-script recognition is bundled and runs on your phone at full screenshot resolution. Other scripts are not included in this build.").setPositiveButton("OK",null).create()) }
        settingRow(storage,"Auto delete",if(prefs.retention==0) "Never · 500 MB screenshot cap still applies" else "After ${prefs.retention} days · screenshots and text") {
            val days=listOf(1,7,14,30,60,90,0);choices("Auto delete history",days.map { if(it==0)"Never" else "$it days" }.toTypedArray(),days.indexOf(prefs.retention)) { index ->
                val value=days[index]
                showDialog(AlertDialog.Builder(this).setTitle("Apply auto delete?").setMessage(if(value==0)"Keep text until you delete it. Old images may still be evicted at 500 MB." else "History older than $value days will be deleted now and during maintenance. Export first if needed.").setNegativeButton("Cancel",null).setPositiveButton("Apply") { _,_ -> saveCapture("retention",value);background { db.prune(value) } }.create())
            }
        }
        settingRow(storage,"Capture interval","${prefs.interval} seconds idle${if(prefs.adaptive)" (adaptive caps at 15s)" else ""} + screen changes") {
            val field=input("10–600 seconds",prefs.interval.toString()).apply { inputType=InputType.TYPE_CLASS_NUMBER }
            showDialog(AlertDialog.Builder(this).setTitle("Capture interval").setView(field).setPositiveButton("Save") { _,_ -> val n=field.text.toString().toIntOrNull();if(n!=null && n in 10..600)saveCapture("interval",n) else toast("Use 10–600 seconds") }.setNegativeButton("Cancel",null).create())
        }
        settingRow(storage,"Apps to record",if(prefs.onlySelected) "${prefs.selectedApps.size} selected apps; exclusions still apply" else "All eligible apps; exclusions still apply") { appPicker() }
        settingRow(storage,"Excluded apps","Manage private and banking package exclusions") {
            val field=input("Package names/fragments, one per line",prefs.exclusions,true)
            showDialog(AlertDialog.Builder(this).setTitle("Excluded apps").setView(field).setPositiveButton("Save") { _,_ -> CaptureService.instance?.pause();prefs.p.edit().putString("exclusions",field.text.toString()).apply();render() }.setNegativeButton("Cancel",null).create())
        }
        settingRow(storage,"Android accessibility settings","Enable or reconnect screen capture") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        section("Your data");val data=card()
        settingRow(data,"Export all screenshots","ZIP with unique images and history.json") { prepareExport("zip") }
        settingRow(data,"Privacy information","Local storage, capture filters, and Gemini sharing") { showDialog(AlertDialog.Builder(this).setTitle("Your privacy").setMessage("Screenshots and OCR stay in private device storage with no automatic cloud backup. Locked, password, secure and excluded screens are skipped, but filters cannot identify every sensitive screen. Pause when needed. Gemini receives only selected text after you confirm a request. Your API key is encrypted with Android Keystore. Exports are managed separately. Privacy mode protects access to the app; it does not encrypt the history database independently of Android device encryption.").setPositiveButton("OK",null).create()) }
        settingRow(data,"Export diagnostics","Version, capture status, settings and counts; no key, OCR or screenshots") { prepareExport("diagnostics") }
        settingRow(data,"Version","0.5.0 (5)") { toast("Mini Screenpipe · Kotlin · Android") }
        settingRow(data,"Delete all history","Permanently remove local text and screenshots") {
            showDialog(AlertDialog.Builder(this).setTitle("Delete all local history?").setMessage("All screenshots and text will be removed. Capture will pause.").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> CaptureService.instance?.pause();prefs.recording=false;background { db.clear();runOnUiThread { answer="";render() } } }.create())
        }
    }
    private fun geminiSettings() {
        val intro=card();intro.addView(icon("spark",32));space(intro,12);intro.addView(text("A little intelligence for your memory",19f,ink,true));intro.addView(text("Ask questions grounded in your captured history.",13f,muted));val ai=card()
        ai.addView(text("${if(prefs.p.contains("key"))"Key saved securely. " else ""}Leave the key blank to keep it. Your key stays encrypted on this phone.",14f,muted))
        val key=input("Gemini API key").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO;setSelectAllOnFocus(true) }
        ai.addView(key)
        var revealed=false
        ai.addView(button("Show / hide entered key") { revealed=!revealed;key.transformationMethod=if(revealed)null else android.text.method.PasswordTransformationMethod.getInstance();key.setSelection(key.text.length) })
        ai.addView(button("Get from AI Studio ↗") { try { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://aistudio.google.com/app/apikey"))) } catch(_:Exception) { toast("No browser available") } })
        ai.addView(text("Create or copy your key in Google AI Studio, then paste it above.",13f,muted))
        val model=input("Gemini model ID",prefs.model);ai.addView(text("Model",14f,ink,true));ai.addView(model)
        fun save():Boolean = try { require(model.text.toString().trim().matches(Regex("[a-zA-Z0-9._-]+"))) { "Enter a valid model ID" };if(key.text.isNotBlank())prefs.saveKey(key.text.toString());prefs.p.edit().putString("model",model.text.toString().trim()).apply();key.setText("");true } catch(e:Exception) { toast(e.message ?: "Could not save key");false }
        ai.addView(primary("Save Gemini settings") { if(save()) { toast("Gemini settings saved");render() } })
        ai.addView(button("Say hello to Gemini · test connection") { if(save())testGemini() })
        ai.addView(button("Remove API key") { showDialog(AlertDialog.Builder(this).setTitle("Remove saved key?").setNegativeButton("Cancel",null).setPositiveButton("Remove") { _,_ -> prefs.saveKey("");render() }.create()) })
    }
    private fun testGemini() {
        if(asking)return
        val control=Gemini.RequestControl();activeRequest=control;asking=true;toast("Testing Gemini with a synthetic greeting…")
        networkExecutor.execute {
            val result=try { Gemini.ask(prefs.key(),prefs.model,"Say hello and confirm that the connection works. Cite the test observation.",Gemini.context(listOf(Entry(1,System.currentTimeMillis(),"Connection test","test","Hello from Mini Screenpipe. This is synthetic test data, not user history.","",1))),control);"Gemini connected successfully." } catch(e:Exception) { "Connection failed: ${e.message}" }
            runOnUiThread { if(!isDestroyed && activeRequest===control) { activeRequest=null;asking=false;showDialog(AlertDialog.Builder(this).setTitle("Gemini connection").setMessage(result).setPositiveButton("OK",null).create()) } }
        }
    }
    private fun appPicker() {
        val selected=prefs.selectedApps.toMutableSet()
        val container=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
        val only=styledSwitch("Record only selected apps",prefs.onlySelected) {}.apply { text="Record only selected apps";textSize=14f;setTextColor(ink) };container.addView(only)
        container.addView(text("Exclusions and protected-screen filters always apply. No selected apps means no capture when this switch is on.",13f,muted))
        val search=input("Find an installed app");container.addView(search)
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        container.addView(ScrollView(this).apply { addView(list) },LinearLayout.LayoutParams(-1,dp(340)))
        val apps=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        fun populate(q:String) {
            list.removeAllViews()
            apps.filter { it.loadLabel(packageManager).toString().contains(q,true) || it.activityInfo.packageName.contains(q,true) }.forEach { app ->
                val pkg=app.activityInfo.packageName
                val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(5),0,dp(5)) }
                row.addView(ImageView(this).apply { setImageDrawable(app.loadIcon(packageManager)) },LinearLayout.LayoutParams(dp(36),dp(36)))
                val labels=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(6),0);addView(text(app.loadLabel(packageManager).toString(),13f,ink,true));addView(text(pkg,10f,muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }) };row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));row.addView(styledSwitch(app.loadLabel(packageManager).toString(),pkg in selected) { v -> if(v)selected.add(pkg) else selected.remove(pkg) });list.addView(row)
            }
        }
        populate("");search.addTextChangedListener(object:android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){populate(s.toString())};override fun afterTextChanged(s:android.text.Editable?){} })
        showDialog(AlertDialog.Builder(this).setTitle("Apps to record").setView(container).setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ -> CaptureService.instance?.pause();prefs.p.edit().putStringSet("selected_apps",selected).putBoolean("only_selected",only.isChecked).apply();render() }.create())
    }
    private var exporting=false
    private fun export() { prepareExport("json") }
    private fun exportFile(code:Int)=File(cacheDir,when(code) { 4 -> "pending-screenshots.zip";5 -> "pending-diagnostics.json";else -> "pending-export.json" })
    private fun prepareExport(kind:String) {
        if(exporting) { toast("An export is already being prepared");return };exporting=true;toast("Preparing export…")
        val code=when(kind) { "zip" -> 4;"diagnostics" -> 5;else -> 3 }
        val file=exportFile(code);val (start,end)=range();val search=query
        background {
            try {
                when(kind) {
                    "zip" -> db.exportZip(file)
                    "diagnostics" -> file.writeText(org.json.JSONObject().put("version","0.5.0").put("android_api",Build.VERSION.SDK_INT).put("service_connected",CaptureService.instance!=null).put("recording",prefs.recording).put("interval",prefs.interval).put("retention_days",prefs.retention).put("resolution_short_edge",prefs.resolution).put("grayscale",prefs.grayscale).put("foreground",prefs.foreground).put("moment_count",db.summary("",0,Long.MAX_VALUE).count).put("image_bytes",db.imageBytes()).toString(2))
                    else -> file.outputStream().use { db.writeExport(it,search,start,end) }
                }
                runOnUiThread { exporting=false;if(!isDestroyed)startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if(code==4)"application/zip" else "application/json").putExtra(Intent.EXTRA_TITLE,if(code==4)"mini-screenpipe-screenshots.zip" else if(code==5)"mini-screenpipe-diagnostics.json" else "mini-screenpipe-${selectedDay ?: "all-days"}.json"),code) }
            } catch(_:Exception) { file.delete();runOnUiThread { exporting=false;toast("Could not prepare export. Check free storage.") } }
        }
    }
    @Deprecated("Platform result API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode !in 3..5)return
        val file=exportFile(requestCode)
        if(resultCode!=RESULT_OK) { file.delete();return }
        background {
            try { val uri=data?.data ?: error("No export destination");file.inputStream().use { source -> contentResolver.openOutputStream(uri)?.use { source.copyTo(it) } ?: error("No output stream") };runOnUiThread { toast("Export saved") } }
            catch(_:Exception) { runOnUiThread { toast("Export failed. Check destination storage.") } }
            finally { file.delete() }
        }
    }
}
