package com.litehelm.mobile;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1115, SURFACE=0xFF131D23, RAISED=0xFF1A2830, LINE=0xFF293A43;
    private static final int TEXT=0xFFECF3F5, SECONDARY=0xFFA6B8C2, MUTED=0xFF8096A2, MINT=0xFF7EE2C2, CYAN=0xFF74D6EF, WARN=0xFFF3C47C, RED=0xFFFF9A9F;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ApiClient api=new ApiClient();
    private CredentialStore vault;
    private volatile ApiClient.Config config;
    private ProfileBook book=new ProfileBook();
    private String editingId="";
    private EditText nameInput;
    private TextView hostChoice;
    private LinearLayout favoritesArea;
    private volatile int sheetGeneration;
    private LinearLayout root, chrome, nav, dashboard, settings, dashboardData, powerArea, processHeader, serviceHeader;
    private FrameLayout content;
    private final View[] pages=new View[5], navItems=new View[5];
    private final TextView[] navLabels=new TextView[5];
    private final Icon[] navIcons=new Icon[5];
    private TextView connection, dashMessage, dashStamp, settingsError, processMessage, serviceMessage;
    private TextView hostname, os, uptime, cpu, load, memory, memoryBytes, disk, diskBytes, swap, rx, tx, counters;
    private Meter cpuMeter, memMeter, diskMeter;
    private Button dashRefresh, processRefresh, serviceRefresh, saveButton;
    private EditText addressInput, tokenInput, processSearch, serviceSearch;
    private ListView processList, serviceList;
    private DataAdapter processAdapter, serviceAdapter;
    private final ArrayList<JSONObject> processes=new ArrayList<>(), services=new ArrayList<>();
    private JSONObject system;
    private long lastSystemSuccess, rxBase=-1, txBase=-1, timeBase=-1;
    private volatile boolean foreground, destroyed;
    private boolean busy, loadPending, connected, systemFresh, processesFresh, servicesFresh;
    private boolean processCpu, validating, dirty, networkSample;
    private int page=3, serviceFilter;
    private volatile int generation;
    private final int[] scrollY=new int[5];
    private String processQuery="", serviceQuery="", systemError="", notice="";
    private Dialog sheet;
    private State state;
    private final Runnable poll=()->{ if(foreground && page==0 && config!=null && sheet==null) loadCurrent(); };
    // Scripts page (index 4): editor + history list, or one job's detail when jobView is a job id.
    private LinearLayout scripts, jobListArea, jobDisabled, jobEditor, jobHistory, jobRows, jobDetail, jobActions;
    private TextView scriptMessage, jobIdentity, jobWarning, jobTitle, jobStatus, jobTimes, jobTrimmed, jobOut;
    private EditText jobNameInput, jobScriptInput;
    private Button scriptRefresh;
    private final Button[] timeoutChoices=new Button[JobOutput.TIMEOUTS.length];
    private JSONObject jobsData, jobCurrent;
    private String jobView="", jobUser="", jobOutput="", jobScriptText="", jobActionsKey="";
    private long jobNext=-1;
    private boolean jobFollow, jobTrimmedShown;
    private int jobDelay=2000;
    private final Runnable jobTick=()->{ if(RequestPolicy.jobPoll(foreground,page,config!=null,busy,ApiClient.MUTATING.get(),sheet!=null,jobFollow)) loadCurrent(); };

    private static final class State {
        String address="", token="", name="", editingId="", processQuery="", serviceQuery="", notice="";
        boolean draft, cpu; int page=3, filter;
        String jobName="", jobScript="", jobView=""; int jobTimeout=1800;
        volatile ApiClient.Config config;
        ProfileBook book;
        volatile MainActivity owner;
    }
    // Android 13+ stores the per-app language in the system; older versions use this override.
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if(Build.VERSION.SDK_INT<33) {
            String tag=base.getSharedPreferences("ui",MODE_PRIVATE).getString("language","");
            if(!tag.isEmpty()) { android.content.res.Configuration c=new android.content.res.Configuration(); c.setLocale(Locale.forLanguageTag(tag)); applyOverrideConfiguration(c); }
        }
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Text.use(this);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        state=(State)getLastNonConfigurationInstance();
        if(state==null) state=new State();
        state.owner=this;
        vault=new CredentialStore(this);
        book=state.book;
        if(book==null)try { book=vault.load(); } catch(Exception e) { notice=t(R.string.vault_unavailable); }
        if(book==null)book=new ProfileBook();
        config=book.active()==null?null:book.active().config;
        editingId=state.draft?state.editingId:book.activeId;
        page=config==null?3:(state.draft?state.page:0);
        processCpu=state.cpu; serviceFilter=state.filter;
        processQuery=state.processQuery; serviceQuery=state.serviceQuery; jobView=state.jobView;
        buildShell();
        switchPage(page,false);
    }
    @Override public Object onRetainNonConfigurationInstance() {
        captureDraft(); state.config=config; state.book=book; state.page=page; state.cpu=processCpu; state.filter=serviceFilter;
        state.processQuery=processQuery; state.serviceQuery=serviceQuery; state.jobView=jobView; state.owner=null;
        return state;
    }
    @Override protected void onResume() {
        super.onResume(); foreground=true; resetRates();
        if(!state.notice.isEmpty()) { toast(state.notice); state.notice=""; }
        if(!notice.isEmpty()) { toast(notice); notice=""; }
        if(config!=null) loadCurrent();
    }
    @Override protected void onPause() {
        foreground=false; generation++; stopPolls(); resetRates();
        if(sheet!=null)sheet.dismiss();
        systemFresh=false; processesFresh=false; servicesFresh=false;
        api.cancelRead(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true; handler.removeCallbacksAndMessages(null); if(sheet!=null)sheet.dismiss();
        api.cancelRead(); if(state.owner==this)state.owner=null; super.onDestroy();
    }
    @Override public void onBackPressed() { if(sheet!=null && sheet.isShowing())sheet.dismiss(); else if(page==4 && !jobView.isEmpty())showJobView(""); else if(page!=0 && config!=null)switchPage(0,true); else super.onBackPressed(); }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private boolean largeType() { return getResources().getConfiguration().fontScale>1.3f || getResources().getConfiguration().screenWidthDp<350; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color,int radius,boolean stroke) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if(stroke)d.setStroke(dp(1),LINE); return d; }
    private RippleDrawable ripple(int color,int radius) { return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x307EE2C2),shape(color,radius,false),shape(0xFFFFFFFF,radius,false)); }
    private TextView text(String value,int size,int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("tnum"); t.setIncludeFontPadding(false); t.setLineSpacing(dp(3),1f); return t;
    }
    private TextView label(String value) { return text(value,12,SECONDARY); }
    private void addText(LinearLayout target,String value,int size,int color) { target.addView(text(value,size,color)); }
    private void gap(LinearLayout target,int height) { View v=new View(this); target.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private void divider(LinearLayout target) { View v=new View(this); v.setBackgroundColor(LINE); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1)); p.topMargin=dp(16);p.bottomMargin=dp(16);target.addView(v,p); }
    private void weight(LinearLayout target,View child) { target.addView(child,new LinearLayout.LayoutParams(0,-2,1)); }
    private Button button(String title,boolean primary,boolean danger,Runnable action) {
        Button b=new Button(this); b.setText(title);b.setTextSize(14); b.setAllCaps(false);b.setMinHeight(dp(52));b.setMinimumHeight(dp(52));b.setMinWidth(0);b.setPadding(dp(12),dp(10),dp(12),dp(10));
        b.setTextColor(primary?0xFF102D27:danger?RED:TEXT); b.setBackground(ripple(primary?(danger?RED:MINT):RAISED,12));
        b.setStateListAnimator(null); b.setOnClickListener(v->action.run()); return b;
    }
    // Compact buttons grow with their label, so longer translations are never clipped.
    private Button compact(String title,Runnable action) { Button b=button(title,false,false,action); b.setMinWidth(dp(64));b.setMinimumWidth(dp(64));b.setContentDescription(title);return b; }
    private void enabled(View v,boolean yes) { v.setEnabled(yes);v.setAlpha(yes?1f:.38f); }
    private LinearLayout panel() { LinearLayout p=column();p.setPadding(dp(20),dp(20),dp(20),dp(20));p.setBackground(shape(SURFACE,16,false)); return p; }
    private ScrollView scroll(LinearLayout child) { ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setClipToPadding(false);s.addView(child,new ScrollView.LayoutParams(-1,-2));return s; }
    private LinearLayout pageColumn() { LinearLayout p=column();int pad=dp(getResources().getConfiguration().screenWidthDp<360?20:24);p.setPadding(pad,dp(20),pad,dp(28)); return p; }
    private TextView message(LinearLayout target) { TextView t=text("",14,RED);t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);t.setPadding(0,dp(12),0,dp(12));t.setVisibility(View.GONE);target.addView(t,new LinearLayout.LayoutParams(-1,-2));return t; }
    private void showMessage(TextView t,String msg,boolean error) { t.setText(msg);t.setTextColor(error?RED:SECONDARY);t.setVisibility(msg.isEmpty()?View.GONE:View.VISIBLE); }
    private void heading(LinearLayout p,String title,String subtitle,Button refresh) {
        // Large text stacks the action under the title so long words never break mid-word.
        LinearLayout r=largeType()?column():row();LinearLayout names=column();addText(names,title,24,TEXT);gap(names,6);addText(names,subtitle,12,SECONDARY);if(largeType())r.addView(names,new LinearLayout.LayoutParams(-1,-2));else weight(r,names);if(refresh!=null){LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);if(largeType())rp.topMargin=dp(12);r.addView(refresh,rp);}p.addView(r);gap(p,24);
    }
    private void buildShell() {
        root=column();root.setBackgroundColor(BG); root.setFitsSystemWindows(false);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{ android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime()); root.setPadding(sys.left,sys.top,sys.right,Math.max(sys.bottom,ime.bottom)); return insets; });
        else root.setOnApplyWindowInsetsListener((v,i)->{root.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        chrome=row(); chrome.setPadding(dp(24),dp(12),dp(24),dp(12));
        Icon logo=new Icon(4,MINT);logo.setBackground(shape(0xFF17362F,8,false));chrome.addView(logo,new LinearLayout.LayoutParams(dp(32),dp(32)));
        hostChoice=text(t(R.string.host_choice,t(R.string.host)),15,TEXT);hostChoice.setSingleLine(true);hostChoice.setEllipsize(TextUtils.TruncateAt.END);hostChoice.setMinHeight(dp(48));hostChoice.setGravity(Gravity.CENTER_VERTICAL);hostChoice.setOnClickListener(v->chooseHost());LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.leftMargin=dp(10);chrome.addView(hostChoice,bp);
        connection=text(t(R.string.not_connected),12,MUTED);chrome.addView(connection);root.addView(chrome,new LinearLayout.LayoutParams(-1,-2));
        View topLine=new View(this);topLine.setBackgroundColor(LINE);root.addView(topLine,new LinearLayout.LayoutParams(-1,dp(1)));
        FrameLayout center=new FrameLayout(this);content=new FrameLayout(this);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Math.min(dp(600),getResources().getDisplayMetrics().widthPixels),-1,Gravity.CENTER_HORIZONTAL);center.addView(content,cp);root.addView(center,new LinearLayout.LayoutParams(-1,0,1));
        View bottomLine=new View(this);bottomLine.setBackgroundColor(LINE);root.addView(bottomLine,new LinearLayout.LayoutParams(-1,dp(1)));
        nav=row();String[] titles={t(R.string.nav_overview),t(R.string.nav_processes),t(R.string.nav_services),t(R.string.nav_settings),t(R.string.nav_scripts)};
        // Page indexes stay stable (settings is 3); the bar shows scripts before settings.
        for(int index:new int[]{0,1,2,4,3}) { final int i=index;LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(5),0,dp(5));item.setBackground(ripple(BG,0));navIcons[i]=new Icon(i==4?5:i,MUTED);item.addView(navIcons[i],new LinearLayout.LayoutParams(dp(44),dp(28)));navLabels[i]=text(titles[i],11,MUTED);navLabels[i].setGravity(Gravity.CENTER);navLabels[i].setMaxLines(1);navLabels[i].setAutoSizeTextTypeUniformWithConfiguration(7,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);gap(item,4);item.addView(navLabels[i],new LinearLayout.LayoutParams(-1,navLabels[i].getLineHeight()));item.setOnClickListener(v->switchPage(index,true));item.setContentDescription(titles[i]);item.setMinimumHeight(dp(64));navItems[i]=item;nav.addView(item,new LinearLayout.LayoutParams(0,-2,1)); }
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));setContentView(root);root.requestApplyInsets();
        buildDashboard();buildProcesses();buildServices();buildSettings();buildScripts();
    }
    private void switchPage(int next,boolean loadNow) {
        if(next!=3 && config==null) { toast(t(R.string.connect_first));next=3; }
        if(pages[page] instanceof ScrollView)scrollY[page]=((ScrollView)pages[page]).getScrollY();
        if(sheet!=null)sheet.dismiss();api.cancelRead();loadPending=false;
        generation++;stopPolls();resetRates();page=next;
        for(int i=0;i<5;i++) {pages[i].setVisibility(i==page?View.VISIBLE:View.GONE);int color=i==page?MINT:MUTED;navIcons[i].color=color;navIcons[i].setBackground(shape(i==page?0xFF17362F:BG,10,false));navIcons[i].invalidate();navLabels[i].setTextColor(color);navItems[i].setSelected(i==page);}
        // Scripts and their output may contain secrets, like credentials on the settings page.
        if(page==3||page==4)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(pages[page] instanceof ScrollView) {final int position=scrollY[page];final ScrollView current=(ScrollView)pages[page];current.post(()->current.scrollTo(0,position));}
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        if(page==3 && config!=null){LinearLayout title=(LinearLayout)settings.getChildAt(0);LinearLayout labels=(LinearLayout)title.getChildAt(0);((TextView)labels.getChildAt(0)).setText(t(R.string.nav_settings));((TextView)labels.getChildAt(2)).setText(t(R.string.settings_subtitle));}
        if(page==0)updateDashboard(); if(page==3)updatePower(); if(page==4)resetJobDetail();
        if(loadNow && foreground && config!=null)loadCurrent();
    }
    private void attachPage(int index,View v) {pages[index]=v;content.addView(v,new FrameLayout.LayoutParams(-1,-1));v.setVisibility(View.GONE);}
    private void status(boolean ok) {connected=ok;connection.setText(ok?t(R.string.status_connected):t(R.string.status_failed));connection.setTextColor(ok?MINT:RED);updateControls();}
    private void updateControls() {
        if(dashRefresh!=null)enabled(dashRefresh,!busy && config!=null);if(processRefresh!=null)enabled(processRefresh,!busy && config!=null);if(serviceRefresh!=null)enabled(serviceRefresh,!busy && config!=null);if(scriptRefresh!=null)enabled(scriptRefresh,!busy && config!=null);
        if(saveButton!=null) {enabled(saveButton,!busy && !ApiClient.MUTATING.get());saveButton.setText(validating?t(R.string.validating):t(R.string.save_connect));}
        if(powerArea!=null && page==3)updatePower();
    }
    private interface Result {void accept(JSONObject data) throws Exception;}
    private void read(ApiClient.Config credentials,String path,boolean saving,Result success,java.util.function.Consumer<String> failure) {
        if(busy || ApiClient.MUTATING.get()) {loadPending=true;return;}busy=true; final int gen=generation; final int requestPage=page;updateControls();connection.setText(t(R.string.status_connecting));connection.setTextColor(WARN);
        final int requestSheet=sheetGeneration;final boolean inSheet=sheet!=null;final ApiClient.Config identity=config;
        ApiClient.IO.execute(()->{JSONObject value=null;String error=null;try {if(!RequestPolicy.current(foreground,generation,gen,config,identity,inSheet,sheetGeneration,requestSheet))throw new Exception(t(R.string.request_cancelled));value=api.request(credentials,path,null,()->RequestPolicy.current(foreground,generation,gen,config,identity,inSheet,sheetGeneration,requestSheet));validatePayload(path,value);}catch(Exception e){error=e.getMessage()==null?t(R.string.request_failed):ApiClient.redact(e.getMessage(),credentials);}final JSONObject result=value;final String problem=error;
            handler.post(()->{busy=false;validating=false;if(destroyed)return;
                boolean current=requestPage==page && RequestPolicy.current(foreground,generation,gen,config,identity,inSheet,sheetGeneration,requestSheet);
                if(saving)for(EditText field:new EditText[]{nameInput,addressInput,tokenInput})enabled(field,true);
                // Commit the small encrypted preference and active config in one main-loop turn;
                // rotation/navigation cannot interleave a persisted connection with the old owner.
                if(current) {if(problem==null) {try {status(true);success.accept(result);}catch(Exception e){status(false);failure.accept(saving?t(R.string.err_save_connection):t(R.string.err_data_format));}} else {status(false);failure.accept(problem);}}
                else if(saving)showMessage(settingsError,t(R.string.validation_cancelled),false);
                if(!current && !saving && foreground){connection.setText(connected?t(R.string.status_connected):t(R.string.status_disconnected));connection.setTextColor(connected?MINT:MUTED);}
                updateControls();if(loadPending && foreground){loadPending=false;loadCurrent();}else schedulePoll();
            });
        });
    }
    private static void validatePayload(String path,JSONObject j) throws Exception {
        if(path.equals("/api/v1/system")) {if(j.optString("hostname").isEmpty() || j.optJSONObject("capabilities")==null || !j.has("timestamp"))throw new Exception(t(R.string.err_host_info));}
        else if(path.startsWith("/api/v1/processes?"))j.getJSONArray("processes");
        else if(path.equals("/api/v1/services")) {j.getJSONArray("services");j.getBoolean("supported");}
        else if(path.endsWith("/logs"))j.getString("logs");
        else if(path.startsWith("/api/v1/jobs"))ToolData.validate(path,j);
    }
    private void stopPolls() {handler.removeCallbacks(poll);handler.removeCallbacks(jobTick);}
    private void schedulePoll() {
        stopPolls();boolean mutating=ApiClient.MUTATING.get(),open=sheet!=null;
        if(RequestPolicy.poll(foreground,page,config!=null,busy,mutating,open))handler.postDelayed(poll,5000);
        else if(RequestPolicy.jobPoll(foreground,page,config!=null,busy,mutating,open,jobFollow))handler.postDelayed(jobTick,jobDelay);
    }
    private void loadCurrent() {
        if(!foreground || config==null || sheet!=null)return;
        if(busy || ApiClient.MUTATING.get()){loadPending=true;return;}
        stopPolls();
        if(page==0 || page==3 || system==null || !systemFresh) {
            final int intended=page;
            if(page==0 && system==null)showMessage(dashMessage,t(R.string.loading_host),false);
            read(config,"/api/v1/system",false,j->{system=j;systemFresh=true;networkSample=intended==0;lastSystemSuccess=System.currentTimeMillis();systemError="";updateDashboard();updatePower();if(intended==1 || intended==2 || intended==4)loadCurrent();},e->{systemFresh=false;systemError=e;resetRates();updateDashboard();if(intended==3)showMessage(settingsError,e,true);else if(intended==1)showMessage(processMessage,e,true);else if(intended==2)showMessage(serviceMessage,e,true);else if(intended==4)showMessage(scriptMessage,e,true);updatePower();});
        } else if(page==1)loadProcesses();else if(page==2)loadServices();else if(page==4)loadJobs();
    }
    private boolean capability(String name) {JSONObject caps=system==null?null:system.optJSONObject("capabilities");return caps!=null && caps.optBoolean(name,false) && systemFresh && connected;}
    private void resetRates(){rxBase=txBase=timeBase=-1;networkSample=false;if(rx!=null) {rx.setText("—");tx.setText("—");}}

    private void buildDashboard() {
        dashboard=pageColumn();dashRefresh=compact(t(R.string.refresh),()->loadCurrent());
        LinearLayout top=largeType()?column():row();LinearLayout names=column();names.addView(label(t(R.string.current_host)));gap(names,6);hostname=text(t(R.string.waiting_connection),24,TEXT);names.addView(hostname);gap(names,6);os=text(t(R.string.tagline),12,SECONDARY);names.addView(os);if(largeType())top.addView(names,new LinearLayout.LayoutParams(-1,-2));else weight(top,names);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);if(largeType())rp.topMargin=dp(12);top.addView(dashRefresh,rp);dashboard.addView(top);
        dashMessage=message(dashboard);
        gap(dashboard,16);dashboard.addView(compact(t(R.string.switch_host),()->chooseHost()),new LinearLayout.LayoutParams(-1,-2));
        gap(dashboard,8);dashboard.addView(compact(t(R.string.host_tools),()->toolMenu()),new LinearLayout.LayoutParams(-1,-2));
        favoritesArea=column();dashboard.addView(favoritesArea);renderFavorites();
        LinearLayout help=row();help.addView(compact(t(R.string.retry),()->loadCurrent()),new LinearLayout.LayoutParams(0,-2,1));Button check=compact(t(R.string.check_connection),()->switchPage(3,true));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.leftMargin=dp(8);help.addView(check,hp);help.setTag("errorHelp");dashboard.addView(help);help.setVisibility(View.GONE);
        dashboardData=column();gap(dashboardData,24);LinearLayout up=row();weight(up,label(t(R.string.uptime)));uptime=text("—",14,TEXT);up.addView(uptime);dashboardData.addView(up);gap(dashboardData,24);
        LinearLayout resource=panel();TextView resourceTitle=label(t(R.string.resources));resourceTitle.setTag("cores");resource.addView(resourceTitle);gap(resource,14);
        LinearLayout cpuRow=column();LinearLayout cpuCol=column();addText(cpuCol,"CPU",12,SECONDARY);gap(cpuCol,4);cpu=text("—",48,MINT);cpuCol.addView(cpu);cpuRow.addView(cpuCol);
        load=text(t(R.string.load_value,"—"),12,SECONDARY);gap(cpuRow,10);cpuRow.addView(load);resource.addView(cpuRow);gap(resource,12);cpuMeter=new Meter(MINT,"CPU");resource.addView(cpuMeter,new LinearLayout.LayoutParams(-1,dp(3)));divider(resource);
        LinearLayout pair=largeType()?column():row();LinearLayout mem=column();mem.addView(label(t(R.string.memory)));gap(mem,8);memory=text("—",28,CYAN);mem.addView(memory);gap(mem,8);memoryBytes=text(t(R.string.unavailable_now),12,SECONDARY);mem.addView(memoryBytes);gap(mem,12);memMeter=new Meter(CYAN,t(R.string.memory));mem.addView(memMeter,new LinearLayout.LayoutParams(-1,dp(3)));
        LinearLayout disc=column();disc.addView(label(t(R.string.disk)));gap(disc,8);disk=text("—",28,MINT);disc.addView(disk);gap(disc,8);diskBytes=text(t(R.string.unavailable_now),12,SECONDARY);disc.addView(diskBytes);gap(disc,12);diskMeter=new Meter(MINT,t(R.string.disk));disc.addView(diskMeter,new LinearLayout.LayoutParams(-1,dp(3)));
        if(largeType()){pair.addView(mem);gap(pair,24);pair.addView(disc);}else {weight(pair,mem);View spacer=new View(this);pair.addView(spacer,new LinearLayout.LayoutParams(dp(20),1));weight(pair,disc);}resource.addView(pair);divider(resource);swap=text("Swap   —",12,SECONDARY);resource.addView(swap);dashboardData.addView(resource);gap(dashboardData,28);
        LinearLayout networkHeading=row();weight(networkHeading,text(t(R.string.network_rate),15,TEXT));networkHeading.addView(text(t(R.string.current_sample),11,MUTED));dashboardData.addView(networkHeading);gap(dashboardData,20);
        LinearLayout network=largeType()?column():row();LinearLayout receive=column();addText(receive,t(R.string.receive),12,SECONDARY);gap(receive,8);rx=text("—",28,CYAN);receive.addView(rx);LinearLayout send=column();addText(send,t(R.string.send),12,SECONDARY);gap(send,8);tx=text("—",28,TEXT);send.addView(tx);
        if(largeType()){network.addView(receive);gap(network,20);network.addView(send);}else{weight(network,receive);View line=new View(this);line.setBackgroundColor(LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(1),dp(60));lp.leftMargin=dp(16);lp.rightMargin=dp(20);network.addView(line,lp);weight(network,send);}dashboardData.addView(network);gap(dashboardData,14);counters=text(t(R.string.waiting_sample),11,MUTED);dashboardData.addView(counters);gap(dashboardData,28);addText(dashboardData,t(R.string.refresh_note),12,SECONDARY);gap(dashboardData,8);dashStamp=text(t(R.string.not_updated),11,MUTED);dashboardData.addView(dashStamp);dashboard.addView(dashboardData);attachPage(0,scroll(dashboard));updateDashboard();
    }
    private void updateDashboard() {
        if(hostname==null)return;dashboardData.setVisibility(system==null?View.GONE:View.VISIBLE);
        View help=dashboard.findViewWithTag("errorHelp");help.setVisibility(systemError.isEmpty()?View.GONE:View.VISIBLE);
        if(system==null){hostname.setText(config==null?t(R.string.connect_server):t(R.string.waiting_host));showMessage(dashMessage,systemError.isEmpty()?t(R.string.loading_host):t(R.string.cannot_connect,systemError),true);return;}
        showMessage(dashMessage,systemError.isEmpty()?"":t(R.string.stale_last_attempt,systemError,clock(System.currentTimeMillis())),true);
        hostname.setText(system.optString("hostname","—"));os.setText(system.optString("os","—")+" · "+system.optString("arch","—"));uptime.setText(duration(number(system,"uptime_seconds")));
        TextView cores=dashboard.findViewWithTag("cores");cores.setText(system.has("cpu_cores")?Text.plural(R.plurals.resources_cores,system.optInt("cpu_cores"),system.optInt("cpu_cores")):t(R.string.resources_cores_unknown));
        double cpuValue=number(system,"cpu_percent");cpu.setText(percent(cpuValue));cpuMeter.set(cpuValue);
        JSONArray loads=system.optJSONArray("load");load.setText(t(R.string.load_value,loads!=null&&loads.length()>=3?decimal(loads.optDouble(0,Double.NaN))+" / "+decimal(loads.optDouble(1,Double.NaN))+" / "+decimal(loads.optDouble(2,Double.NaN)):"—"));
        resource(system.optJSONObject("memory"),memory,memoryBytes,memMeter);resource(system.optJSONObject("disk"),disk,diskBytes,diskMeter);
        JSONObject sw=system.optJSONObject("swap");double st=number(sw,"total");swap.setText("Swap   "+(st==0?t(R.string.swap_off):bytes(number(sw,"used"))+" / "+bytes(st)));
        if(networkSample){
            JSONObject net=system.optJSONObject("network");long timestamp=system.optLong("timestamp",-1);long nr=net==null?-1:net.optLong("rx_bytes",-1), nt=net==null?-1:net.optLong("tx_bytes",-1);
            boolean valid=timestamp>timeBase && timeBase>=0 && nr>=rxBase && nt>=txBase && rxBase>=0 && txBase>=0 && systemFresh;
            if(valid){rx.setText(bytes((nr-rxBase)/(double)(timestamp-timeBase))+"/s");tx.setText(bytes((nt-txBase)/(double)(timestamp-timeBase))+"/s");}else {rx.setText("—");tx.setText("—");}
            counters.setText((valid?"":t(R.string.waiting_sample)+" · ")+t(R.string.totals,bytes(nr),bytes(nt)));
            if(systemFresh && page==0 && foreground && timestamp>0 && nr>=0 && nt>=0){rxBase=nr;txBase=nt;timeBase=timestamp;}else resetRates();
            networkSample=false;
        }
        dashStamp.setText(t(systemFresh?R.string.updated_at:R.string.stale_last_success,clock(lastSystemSuccess)));dashStamp.setTextColor(systemFresh?MUTED:WARN);dashboardData.setAlpha(systemFresh?1f:.58f);
    }
    private void resource(JSONObject source,TextView value,TextView detail,Meter meter){double total=number(source,"total"),used=number(source,"used");double ratio=total>0&&used>=0?100*used/total:Double.NaN;value.setText(percent(ratio));detail.setText(Double.isFinite(ratio)?bytes(used)+" / "+bytes(total):t(R.string.unavailable_now));meter.set(ratio);}
    private static double number(JSONObject j,String key){return j==null?Double.NaN:j.optDouble(key,Double.NaN);}
    private static String decimal(double x){return Double.isFinite(x)?String.format(Locale.CHINA,"%.2f",x):"—";}
    private static String percent(double x){return Double.isFinite(x)&&x>=0?String.format(Locale.CHINA,"%.1f\u00a0%%",x):"—";}
    private static String bytes(double x){if(!Double.isFinite(x)||x<0)return "—";String[] units={"B","KiB","MiB","GiB","TiB"};int index=0;while(x>=1024 && index<4){x/=1024;index++;}return String.format(Locale.CHINA,index==0?"%.0f\u00a0%s":"%.1f\u00a0%s",x,units[index]);}
    private static String clock(long t){return t<=0?"—":new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(t));}
    private static String duration(double seconds){if(!Double.isFinite(seconds)||seconds<0)return "—";long m=(long)seconds/60,h=m/60,d=h/24;return d>0?t(R.string.duration_dh,d,h%24):h>0?t(R.string.duration_hm,h,m%60):m>0?t(R.string.duration_m,m):t(R.string.duration_lt1);}

    private void buildProcesses() {
        LinearLayout outer=column(), header=pageColumn();processHeader=header;header.setPadding(0,dp(20),0,dp(8));processRefresh=compact(t(R.string.refresh),()->{processList.setSelection(0);loadCurrent();});heading(header,t(R.string.nav_processes),t(R.string.top_memory),processRefresh);((LinearLayout)((LinearLayout)header.getChildAt(0)).getChildAt(0)).getChildAt(2).setTag("processSubtitle");
        LinearLayout sort=row();Button mem=compact(t(R.string.memory),()->setSort(false)), cp=compact(t(R.string.cpu_average),()->setSort(true));sort.addView(mem,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);sp.leftMargin=dp(8);sort.addView(cp,sp);mem.setTag("sortMem");cp.setTag("sortCpu");header.addView(sort);gap(header,12);
        processSearch=search(t(R.string.search_processes),processQuery,q->{processQuery=q;filterProcesses();});header.addView(searchRow(processSearch));gap(header,12);addText(header,t(R.string.cpu_note),12,MUTED);processMessage=message(header);
        processList=new ListView(this);processList.setDivider(new android.graphics.drawable.ColorDrawable(LINE));processList.setDividerHeight(dp(1));processList.setCacheColorHint(BG);processList.setClipToPadding(false);processList.setPadding(dp(24),0,dp(24),dp(20));processList.addHeaderView(header,null,false);processAdapter=new DataAdapter(false);processList.setAdapter(processAdapter);processList.setOnItemClickListener((p,v,pos,id)->{int index=pos-processList.getHeaderViewsCount();if(index>=0&&index<processAdapter.items.size())processSheet(processAdapter.items.get(index));});outer.addView(processList,new LinearLayout.LayoutParams(-1,0,1));attachPage(1,outer);paintSort();
    }
    private EditText search(String hint,String value,java.util.function.Consumer<String> change){EditText input=input(hint,false);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setText(value);watch(input,()->change.accept(input.getText().toString()));return input;}
    private View searchRow(EditText input){LinearLayout r=row();weight(r,input);Button clear=compact(t(R.string.clear),()->input.setText(""));clear.setContentDescription(t(R.string.clear_search));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(8);r.addView(clear,p);return r;}
    private void setSort(boolean cpu){if(busy)return;processCpu=cpu;processesFresh=false;processes.clear();filterProcesses();paintSort();if(foreground&&page==1)loadCurrent();}
    private void paintSort(){View h=processHeader;if(h==null)return;Button a=h.findViewWithTag("sortMem"),b=h.findViewWithTag("sortCpu");a.setTextColor(processCpu?SECONDARY:MINT);b.setTextColor(processCpu?MINT:SECONDARY);TextView subtitle=h.findViewWithTag("processSubtitle");if(subtitle!=null)subtitle.setText(processCpu?t(R.string.top_cpu):t(R.string.top_memory));}
    private void loadProcesses(){showMessage(processMessage,t(R.string.loading_processes),false);read(config,"/api/v1/processes?limit=100"+(processCpu?"&sort=cpu":""),false,j->{processes.clear();JSONArray list=j.getJSONArray("processes");for(int i=0;i<Math.min(100,list.length());i++)processes.add(list.getJSONObject(i));processesFresh=true;filterProcesses();},e->{processesFresh=false;showMessage(processMessage,(processes.isEmpty()?e:t(R.string.error_stale,e)),true);});}
    private void filterProcesses(){if(processAdapter==null)return;ArrayList<JSONObject> result=new ArrayList<>();String query=processQuery.toLowerCase(Locale.ROOT).trim();for(JSONObject p:processes)if((p.optString("name")+" "+p.optString("pid")+" "+p.optString("user")).toLowerCase(Locale.ROOT).contains(query))result.add(p);processAdapter.set(result);if(processesFresh)showMessage(processMessage,result.isEmpty()?(query.isEmpty()?t(R.string.no_processes):t(R.string.no_matching_processes)):"",false);}
    private String processBlock(JSONObject p){if(!processesFresh||!systemFresh||!connected)return t(R.string.stale_list);if(!capability("process_signal"))return t(R.string.no_process_control);if(p.optInt("pid",0)<=1)return t(R.string.process_protected);if(p.isNull("start_time")||p.optString("start_time").isEmpty())return t(R.string.missing_start);return "";}
    private void processSheet(JSONObject p){
        LinearLayout box=sheetBox(p.optString("name",t(R.string.process)));addText(box,"PID "+p.optInt("pid")+" · "+p.optString("user")+" · "+p.optString("state"),14,SECONDARY);gap(box,8);String start=p.optString("start_time");addText(box,t(R.string.start_id,start.length()>24?start.substring(0,24)+"…":start),12,MUTED);gap(box,24);
        addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);
        RadioGroup choices=new RadioGroup(this);int termId=View.generateViewId(),killId=View.generateViewId();RadioButton term=radio(t(R.string.term_option),termId),kill=radio(t(R.string.kill_option),killId);choices.addView(term);choices.addView(kill);choices.check(termId);box.addView(choices);gap(box,16);TextView error=message(box);String blocked=processBlock(p);showMessage(error,blocked,true);
        Button send=button(t(R.string.send_term),true,false,()->{String reason=processBlock(p);if(!reason.isEmpty()){showMessage(error,reason,true);return;}String signal=choices.getCheckedRadioButtonId()==killId?"KILL":"TERM";JSONObject body=json("signal",signal,"start_time",p.optString("start_time"));mutate("/api/v1/processes/"+p.optInt("pid")+"/signal",body,t(R.string.signal_sent,signal),()->{processesFresh=false;if(page==1)loadCurrent();},false);});choices.setOnCheckedChangeListener((g,id)->{boolean danger=id==killId;send.setText(danger?t(R.string.send_kill):t(R.string.send_term));send.setBackground(ripple(danger?RED:MINT,12));});box.addView(send,new LinearLayout.LayoutParams(-1,-2));enabled(send,blocked.isEmpty()&&!busy&&!ApiClient.MUTATING.get());showSheet(box);
    }
    private RadioButton radio(String text,int id){RadioButton b=new RadioButton(this);b.setId(id);b.setText(text);b.setTextColor(TEXT);b.setTextSize(14);b.setMinHeight(dp(64));b.setPadding(0,dp(8),0,dp(8));b.setButtonTintList(android.content.res.ColorStateList.valueOf(MINT));return b;}

    private void buildServices(){
        LinearLayout outer=column(),header=pageColumn();serviceHeader=header;header.setPadding(0,dp(20),0,dp(8));serviceRefresh=compact(t(R.string.refresh),()->{serviceList.setSelection(0);loadCurrent();});heading(header,t(R.string.nav_services),t(R.string.systemd_services),serviceRefresh);serviceSearch=search(t(R.string.search_services),serviceQuery,q->{serviceQuery=q;filterServices();});header.addView(searchRow(serviceSearch));gap(header,12);LinearLayout filters=column(),line=null;int perRow=largeType()?2:4;String[] labels={t(R.string.filter_all),t(R.string.filter_running),t(R.string.filter_failed),t(R.string.filter_inactive)};for(int i=0;i<4;i++){final int index=i;Button b=compact(labels[i],()->{serviceFilter=index;paintFilters();filterServices();});b.setTextSize(12);b.setPadding(dp(2),dp(10),dp(2),dp(10));b.setTag("filter"+i);if(i%perRow==0){if(i>0)gap(filters,4);line=row();filters.addView(line,new LinearLayout.LayoutParams(-1,-2));}LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,-2,1);if(i%perRow>0)fp.leftMargin=dp(4);line.addView(b,fp);}header.addView(filters);serviceMessage=message(header);
        header.addView(compact(t(R.string.host_tools),()->toolMenu()),new LinearLayout.LayoutParams(-1,-2));
        serviceList=new ListView(this);serviceList.setDivider(new android.graphics.drawable.ColorDrawable(LINE));serviceList.setDividerHeight(dp(1));serviceList.setPadding(dp(24),0,dp(24),dp(20));serviceList.setClipToPadding(false);serviceList.addHeaderView(header,null,false);serviceAdapter=new DataAdapter(true);serviceList.setAdapter(serviceAdapter);serviceList.setOnItemClickListener((p,v,pos,id)->{int index=pos-serviceList.getHeaderViewsCount();if(index>=0&&index<serviceAdapter.items.size())serviceSheet(serviceAdapter.items.get(index));});outer.addView(serviceList,new LinearLayout.LayoutParams(-1,0,1));attachPage(2,outer);paintFilters();
    }
    private void paintFilters(){if(serviceHeader==null)return;for(int i=0;i<4;i++){Button b=serviceHeader.findViewWithTag("filter"+i);b.setTextColor(i==serviceFilter?MINT:SECONDARY);}}
    private boolean supported=true;
    private void loadServices(){showMessage(serviceMessage,t(R.string.loading_services),false);read(config,"/api/v1/services",false,j->{supported=j.getBoolean("supported");services.clear();JSONArray list=j.getJSONArray("services");for(int i=0;i<list.length();i++)services.add(list.getJSONObject(i));servicesFresh=true;filterServices();},e->{servicesFresh=false;showMessage(serviceMessage,(services.isEmpty()?e:t(R.string.error_stale,e)),true);});}
    private void filterServices(){if(serviceAdapter==null)return;ArrayList<JSONObject> result=new ArrayList<>();String q=serviceQuery.trim().toLowerCase(Locale.ROOT);for(JSONObject s:services){String active=s.optString("active_state");boolean state=serviceFilter==0 || serviceFilter==1&&active.equals("active") || serviceFilter==2&&active.equals("failed") || serviceFilter==3&&active.equals("inactive");if(state && (s.optString("name")+" "+s.optString("description")).toLowerCase(Locale.ROOT).contains(q))result.add(s);}serviceAdapter.set(result);if(servicesFresh)showMessage(serviceMessage,!supported?t(R.string.no_systemd):result.isEmpty()?(services.isEmpty()?t(R.string.no_services):t(R.string.no_matching_services)):Text.plural(R.plurals.services_count,services.size(),services.size()),false);}
    private String serviceStatus(JSONObject s){String active=s.optString("active_state","unknown"),sub=s.optString("sub_state","unknown");if(active.equals("active"))return sub.equals("running")?t(R.string.status_running):t(R.string.status_active,sub);if(active.equals("inactive"))return t(R.string.status_inactive,sub);if(active.equals("failed"))return t(R.string.status_failed_sub,sub);return active+" · "+sub;}
    private int serviceColor(JSONObject s){String a=s.optString("active_state");return a.equals("active")?MINT:a.equals("failed")?RED:a.equals("inactive")?SECONDARY:WARN;}
    private void serviceSheet(JSONObject s){
        LinearLayout box=sheetBox(s.optString("name"));addText(box,s.optString("description"),14,SECONDARY);gap(box,12);addText(box,serviceStatus(s),14,serviceColor(s));gap(box,8);addText(box,"load_state  "+s.optString("load_state")+"\nactive_state  "+s.optString("active_state")+"\nsub_state  "+s.optString("sub_state"),12,MUTED);gap(box,24);
        addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);
        Button favorite=compact(isFavorite(s.optString("name"))?t(R.string.unfavorite):t(R.string.favorite_add),()->{toggleFavorite(s.optString("name"));serviceSheet(s);});box.addView(favorite,new LinearLayout.LayoutParams(-1,-2));gap(box,12);
        String active=s.optString("active_state");boolean allowed=capability("service_control")&&servicesFresh&&!busy&&!ApiClient.MUTATING.get();
        String[] acts={"start","restart","stop"},labels={t(R.string.action_start),t(R.string.action_restart),t(R.string.action_stop)};
        for(int i=0;i<3;i++){final String action=acts[i],label=labels[i];Button b=button(label,false,action.equals("stop"),()->{if(!capability("service_control")||!servicesFresh){toast(t(R.string.service_stale));return;}confirmService(s,action,label);});enabled(b,allowed&&(i==0?(active.equals("inactive")||active.equals("failed")):active.equals("active")));box.addView(b,new LinearLayout.LayoutParams(-1,-2));gap(box,8);}
        if(!allowed)addText(box,t(R.string.control_unavailable),12,WARN);else if(!active.equals("active")&&!active.equals("inactive")&&!active.equals("failed"))addText(box,t(R.string.state_changing),12,WARN);
        gap(box,12);Button logs=compact(t(R.string.view_logs),()->{if(busy){toast(t(R.string.busy));return;}if(sheet!=null)sheet.dismiss();showLogs(s.optString("name"));});box.addView(logs,new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private String servicePath(String name){return "/api/v1/services/"+encodeSegment(name);}
    private static String encodeSegment(String name){try{return URLEncoder.encode(name,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    private void confirmService(JSONObject s,String action,String label){LinearLayout box=sheetBox(t(R.string.confirm_service_title,label));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);addText(box,s.optString("name"),16,TEXT);gap(box,12);addText(box,action.equals("start")?t(R.string.will_start):t(R.string.will_interrupt),14,SECONDARY);gap(box,24);Button send=button(t(R.string.confirm_action,label),true,!action.equals("start"),()->serviceAction(s,action));box.addView(send,new LinearLayout.LayoutParams(-1,-2));showSheet(box);}
    private void serviceAction(JSONObject s,String action){if(!capability("service_control")||!servicesFresh){toast(t(R.string.service_stale));return;}mutate(servicePath(s.optString("name"))+"/action",json("action",action),t(R.string.service_done),()->{servicesFresh=false;if(page==2)loadCurrent();else if(page==0)openFavorite(s.optString("name"));},false);}
    private void showLogs(String name){
        LinearLayout box=sheetBox(name);addText(box,t(R.string.host_line,hostLabel())+"\n"+t(R.string.logs_note),12,SECONDARY);gap(box,12);
        TextView info=message(box),count=label(t(R.string.not_loaded));TextView logs=text(t(R.string.loading_logs),12,TEXT);logs.setTypeface(Typeface.MONOSPACE);logs.setTextIsSelectable(true);
        final String[] original={""},visible={""};final boolean[] loaded={false};
        EditText query=search(t(R.string.search_logs), "",q->{});
        Runnable filter=()->{String q=query.getText().toString();LogFilter.Result r=LogFilter.filter(original[0],q);visible[0]=r.visible;count.setText(t(R.string.log_matches,r.matches,r.lines));logs.setText(visible[0].isEmpty()?t(R.string.no_matching_logs):visible[0]);};watch(query,filter);
        Runnable fetch=()->{if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}showMessage(info,t(R.string.loading),false);read(config,servicePath(name)+"/logs",false,j->{original[0]=j.getString("logs");loaded[0]=true;filter.run();showMessage(info,j.optBoolean("truncated")?t(R.string.logs_truncated):"",false);},e->showMessage(info,e,true));};
        box.addView(compact(t(R.string.refresh_logs),fetch),new LinearLayout.LayoutParams(-1,-2));gap(box,8);box.addView(searchRow(query));gap(box,8);box.addView(count);gap(box,12);
        addText(box,t(R.string.clipboard_warning),12,WARN);
        box.addView(compact(t(R.string.copy_visible),()->{if(loaded[0])copyLogs(name,visible[0]);}),new LinearLayout.LayoutParams(-1,-2));
        box.addView(compact(t(R.string.copy_all),()->{if(loaded[0])copyLogs(name,original[0]);}),new LinearLayout.LayoutParams(-1,-2));gap(box,12);box.addView(logs,new LinearLayout.LayoutParams(-1,-2));showSheet(box);expandSheet();fetch.run();
    }
    private void copyLogs(String name,String value){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(name,value));toast(t(R.string.copied));}

    private final class DataAdapter extends BaseAdapter {
        final boolean service;final ArrayList<JSONObject> items=new ArrayList<>();DataAdapter(boolean service){this.service=service;}
        void set(ArrayList<JSONObject> rows){items.clear();items.addAll(rows);notifyDataSetChanged();}
        public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return service?p:items.get(p).optLong("pid",p);}
        public View getView(int position,View convert,android.view.ViewGroup parent){
            Holder h;if(convert==null){h=new Holder();LinearLayout r=largeType()?column():row();r.setMinimumHeight(dp(service?84:80));r.setPadding(0,dp(14),0,dp(14));r.setBackground(ripple(BG,0));LinearLayout names=column();h.title=text("",15,TEXT);h.title.setSingleLine();h.title.setEllipsize(TextUtils.TruncateAt.END);h.detail=text("",12,SECONDARY);h.detail.setMaxLines(2);h.detail.setEllipsize(TextUtils.TruncateAt.END);names.addView(h.title);gap(names,6);names.addView(h.detail);if(largeType())r.addView(names,new LinearLayout.LayoutParams(-1,-2));else weight(r,names);h.value=text("",service?12:16,service?MINT:TEXT);h.value.setGravity(largeType()?Gravity.LEFT:Gravity.RIGHT);h.value.setMaxLines(3);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(largeType()?-1:dp(service?110:100),-2);if(!largeType())vp.leftMargin=dp(12);r.addView(h.value,vp);r.setTag(h);convert=r;}else h=(Holder)convert.getTag();
            JSONObject item=items.get(position);h.title.setText(item.optString("name","—"));
            if(service){h.detail.setText(item.optString("description"));h.value.setText(serviceStatus(item)+"  ›");h.value.setTextColor(serviceColor(item));}else{h.detail.setText("PID "+item.optInt("pid")+" · "+item.optString("user")+" · "+item.optString("state"));h.value.setText(processCpu?percent(number(item,"cpu_percent"))+"\n"+bytes(number(item,"memory_bytes")):bytes(number(item,"memory_bytes"))+"\n"+percent(number(item,"cpu_percent")));}
            convert.setContentDescription(t(R.string.row_description,item.optString("name"),h.detail.getText(),h.value.getText()));return convert;
        }
    }
    private static final class Holder {TextView title,detail,value;}

    private EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(15);e.setHint(hint);e.setSingleLine(true);e.setMinHeight(dp(52));e.setPadding(dp(12),dp(12),dp(12),dp(12));e.setBackground(shape(RAISED,12,false));e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);e.setInputType(secret?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);if(secret)e.setTransformationMethod(PasswordTransformationMethod.getInstance());return e;}
    private void watch(EditText e,Runnable changed){e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.run();}public void afterTextChanged(Editable s){}});}
    private void field(LinearLayout box,String title,EditText e){box.addView(label(title));gap(box,8);box.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(box,20);}
    private void secretField(LinearLayout box,String title,EditText e){box.addView(label(title));gap(box,8);LinearLayout r=row();weight(r,e);Button toggle=compact(t(R.string.show),()->{boolean hidden=e.getTransformationMethod()!=null;e.setTransformationMethod(hidden?null:PasswordTransformationMethod.getInstance());e.setSelection(e.length());Button b=(Button)((LinearLayout)e.getParent()).getChildAt(1);b.setText(hidden?t(R.string.hide):t(R.string.show));b.setContentDescription(t(hidden?R.string.hide_field:R.string.show_field,title));});toggle.setContentDescription(t(R.string.show_field,title));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(8);r.addView(toggle,p);box.addView(r);gap(box,20);}
    private void buildSettings(){
        settings=pageColumn();heading(settings,config==null?t(R.string.connect_server):t(R.string.nav_settings),config==null?t(R.string.brand_tagline):t(R.string.settings_subtitle),null);LinearLayout form=panel();
        settings.addView(compact(t(R.string.manage_hosts),()->chooseHost()),new LinearLayout.LayoutParams(-1,-2));gap(settings,12);settings.addView(compact(t(R.string.language_button,languageName(languageTag())),()->chooseLanguage()),new LinearLayout.LayoutParams(-1,-2));gap(settings,12);
        nameInput=input(t(R.string.hint_name),false);addressInput=input("https://node.example.com",false);tokenInput=input(t(R.string.hint_token),true);
        nameInput.setText(state.draft?state.name:book.active()==null?"":book.active().name);
        addressInput.setText(state.draft?state.address:config==null?"":config.origin);tokenInput.setText(state.draft?state.token:config==null?"":config.token);
        field(form,t(R.string.host_name),nameInput);field(form,t(R.string.server_address),addressInput);secretField(form,t(R.string.access_token),tokenInput);
        settingsError=message(form);saveButton=button(t(R.string.save_connect),true,false,()->saveConnection());form.addView(saveButton,new LinearLayout.LayoutParams(-1,-2));settings.addView(form);gap(settings,28);powerArea=column();settings.addView(powerArea);gap(settings,28);TextView footer=text(t(R.string.brand_tagline),11,MUTED);footer.setGravity(Gravity.CENTER);settings.addView(footer);
        for(EditText e:new EditText[]{nameInput,addressInput,tokenInput})watch(e,()->{dirty=true;captureDraft();if(!busy)showMessage(settingsError,"",false);});attachPage(3,scroll(settings));updatePower();updateHostLabel();
    }
    private void captureDraft(){if(addressInput==null)return;state.draft=true;state.address=addressInput.getText().toString();state.token=tokenInput.getText().toString();state.name=nameInput.getText().toString();state.editingId=editingId;}
    private void saveConnection(){
        if(busy||ApiClient.MUTATING.get())return;final ApiClient.Config candidate;
        try{candidate=ApiClient.validate(addressInput.getText().toString(),tokenInput.getText().toString());}catch(Exception e){showMessage(settingsError,e.getMessage(),true);return;}
        final String profileName=nameInput.getText().toString().trim(),profileId=editingId.isEmpty()?ProfileBook.newId():editingId;
        if(profileName.isEmpty()||profileName.length()>80){showMessage(settingsError,t(R.string.err_name_length),true);return;}
        captureDraft();validating=true;showMessage(settingsError,t(R.string.verifying_server),false);
        // Prevent editing a credential draft while its exact values are being verified.
        for(EditText e:new EditText[]{nameInput,addressInput,tokenInput})enabled(e,false);
        read(candidate,"/api/v1/system",true,j->{
            // This callback runs only in the validated current main-loop turn; save and activation are atomic to navigation.
            ProfileBook next=book.copy();ProfileBook.Profile previous=next.find(profileId);
            ProfileBook.Profile profile=new ProfileBook.Profile(profileId,profileName,candidate);
            if(previous!=null){profile.favorites.addAll(previous.favorites);next.profiles.set(next.profiles.indexOf(previous),profile);}else next.profiles.add(profile);
            next.activeId=profileId;vault.save(next);book=next;state.book=book;editingId=profileId;config=candidate;state.config=config;
            clearHostData();system=j;systemFresh=true;connected=true;systemError="";lastSystemSuccess=System.currentTimeMillis();dirty=false;
            addressInput.setText(candidate.origin);updateHostLabel();renderFavorites();updateDashboard();updatePower();toast(t(R.string.connected_toast));switchPage(0,false);loadCurrent();
        },e->{for(EditText field:new EditText[]{nameInput,addressInput,tokenInput})enabled(field,true);showMessage(settingsError,e,true);});
    }
    private void updatePower(){if(powerArea==null)return;powerArea.removeAllViews();addText(powerArea,t(R.string.host_control),15,TEXT);gap(powerArea,12);if(config==null){addText(powerArea,t(R.string.host_control_hint),12,MUTED);return;}addText(powerArea,system==null?t(R.string.host_info_missing):system.optString("hostname"),14,SECONDARY);gap(powerArea,16);JSONObject caps=system==null?null:system.optJSONObject("capabilities");if(caps==null||!caps.optBoolean("power",false)){addText(powerArea,t(R.string.power_disabled),14,SECONDARY);gap(powerArea,6);addText(powerArea,t(R.string.power_enable_hint),12,MUTED);return;}Button reboot=button(t(R.string.reboot_server),false,false,()->powerConfirm("reboot")),off=button(t(R.string.shutdown_server),false,true,()->powerConfirm("poweroff"));powerArea.addView(reboot,new LinearLayout.LayoutParams(-1,-2));gap(powerArea,8);powerArea.addView(off,new LinearLayout.LayoutParams(-1,-2));boolean can=capability("power")&&!busy&&!ApiClient.MUTATING.get();enabled(reboot,can);enabled(off,can);if(!can){gap(powerArea,8);addText(powerArea,t(R.string.refresh_before_action),12,WARN);}}
    private void powerConfirm(String action){if(!capability("power")){toast(t(R.string.verify_permission_first));return;}String host=system.optString("hostname");boolean off=action.equals("poweroff");LinearLayout box=sheetBox(off?t(R.string.shutdown_title):t(R.string.reboot_title));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);addText(box,host,18,TEXT);gap(box,12);addText(box,off?t(R.string.shutdown_warning):t(R.string.reboot_warning),14,SECONDARY);gap(box,20);EditText confirm=input(t(R.string.hint_type_hostname),false);confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);box.addView(confirm,new LinearLayout.LayoutParams(-1,-2));gap(box,20);Button send=button(off?t(R.string.confirm_shutdown):t(R.string.confirm_reboot),true,true,()->{if(!capability("power")||!host.equals(confirm.getText().toString()))return;mutate("/api/v1/power",json("action",action,"confirm",host),off?t(R.string.shutdown_scheduled):t(R.string.reboot_scheduled),()->{systemFresh=false;processesFresh=false;servicesFresh=false;systemError=t(R.string.power_scheduled);connected=false;connection.setText(t(R.string.status_waiting_host));connection.setTextColor(WARN);updatePower();updateDashboard();},true);});enabled(send,false);watch(confirm,()->enabled(send,host.equals(confirm.getText().toString())&&!busy&&!ApiClient.MUTATING.get()));box.addView(send,new LinearLayout.LayoutParams(-1,-2));showSheet(box);}

    private String hostLabel(){ProfileBook.Profile p=book.active();return p==null?t(R.string.not_configured):p.name+" · "+p.config.origin;}
    private void updateHostLabel(){if(hostChoice!=null){hostChoice.setText(t(R.string.host_choice,book.active()==null?t(R.string.host):book.active().name));hostChoice.setContentDescription(t(R.string.switch_host_current,hostLabel()));}}
    private boolean canChangeHost(){if(ApiClient.MUTATING.get()){toast(t(R.string.write_pending));return false;}return true;}
    private void clearHostData(){
        generation++;stopPolls();api.cancelRead();if(sheet!=null)sheet.dismiss();loadPending=false;
        system=null;lastSystemSuccess=0;systemFresh=false;processesFresh=false;servicesFresh=false;connected=false;supported=true;systemError="";resetRates();
        processes.clear();services.clear();processAdapter.set(new ArrayList<>());serviceAdapter.set(new ArrayList<>());
        showMessage(processMessage,t(R.string.processes_not_loaded),false);showMessage(serviceMessage,t(R.string.services_not_loaded),false);
        processQuery="";serviceQuery="";processSearch.setText("");serviceSearch.setText("");serviceFilter=0;paintFilters();Arrays.fill(scrollY,0);
        jobsData=null;jobView="";jobUser="";resetJobDetail();renderScripts();showMessage(scriptMessage,"",false);
        connection.setText(t(R.string.status_disconnected));connection.setTextColor(MUTED);updateDashboard();updatePower();
    }
    private void chooseHost(){
        if(!canChangeHost())return;
        generation++;api.cancelRead();loadPending=false;
        LinearLayout box=sheetBox(t(R.string.host));addText(box,t(R.string.only_current),12,SECONDARY);gap(box,16);
        for(ProfileBook.Profile p:book.profiles){
            addText(box,(p.id.equals(book.activeId)?t(R.string.current_suffix,p.name):p.name),16,p.id.equals(book.activeId)?MINT:TEXT);gap(box,6);addText(box,p.config.origin,12,SECONDARY);gap(box,8);
            box.addView(compact(t(R.string.connect_host),()->activateProfile(p.id)),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
            box.addView(compact(t(R.string.edit),()->editProfile(p)),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
            box.addView(button(t(R.string.delete_host),false,true,()->confirmDeleteProfile(p)),new LinearLayout.LayoutParams(-1,-2));divider(box);
        }
        box.addView(button(t(R.string.add_host),true,false,()->editProfile(null)),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void editProfile(ProfileBook.Profile profile){
        if(!canChangeHost())return;if(sheet!=null)sheet.dismiss();switchPage(3,false);
        editingId=profile==null?"":profile.id;nameInput.setText(profile==null?"":profile.name);addressInput.setText(profile==null?"":profile.config.origin);tokenInput.setText(profile==null?"":profile.config.token);captureDraft();
        showMessage(settingsError,profile==null?t(R.string.new_host_hint):t(R.string.edit_host_hint,profile.name),false);((ScrollView)pages[3]).smoothScrollTo(0,0);
    }
    private void confirmDeleteProfile(ProfileBook.Profile profile){
        if(!canChangeHost())return;LinearLayout box=sheetBox(t(R.string.delete_title));addText(box,profile.name+"\n"+profile.config.origin,16,TEXT);gap(box,16);addText(box,t(R.string.delete_warning),14,SECONDARY);gap(box,24);
        box.addView(button(t(R.string.confirm_delete),true,true,()->{if(!canChangeHost())return;try{ProfileBook next=book.copy();ProfileBook.Profile removed=next.find(profile.id);if(removed==null)return;boolean active=next.activeId.equals(profile.id);next.profiles.remove(removed);if(active)next.activeId=next.profiles.isEmpty()?"":next.profiles.get(0).id;vault.save(next);book=next;state.book=book;
            if(active){config=book.active()==null?null:book.active().config;state.config=config;clearHostData();}else if(sheet!=null)sheet.dismiss();
            updateHostLabel();renderFavorites();if(active||editingId.equals(profile.id))editProfile(book.active());if(active&&config!=null)loadCurrent();
        }catch(Exception e){toast(t(R.string.err_delete));}}),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void activateProfile(String id){
        if(!canChangeHost())return;if(id.equals(book.activeId)){if(sheet!=null)sheet.dismiss();return;}
        try{ProfileBook next=book.copy();if(next.find(id)==null)return;next.activeId=id;vault.save(next);book=next;state.book=book;config=book.active().config;state.config=config;clearHostData();
            editingId=id;nameInput.setText(book.active().name);addressInput.setText(config.origin);tokenInput.setText(config.token);captureDraft();updateHostLabel();renderFavorites();switchPage(0,true);
        }catch(Exception e){toast(t(R.string.err_save_host));}
    }
    private boolean isFavorite(String name){return book.active()!=null&&book.active().favorites.contains(name);}
    private void toggleFavorite(String name){
        if(book.active()==null)return;try{ProfileBook next=book.copy();ArrayList<String> favorites=next.active().favorites;if(!favorites.remove(name)){if(favorites.size()>=ProfileBook.FAVORITE_LIMIT){toast(t(R.string.favorite_limit));return;}favorites.add(name);}vault.save(next);
            // Keep the exact connection object used by in-flight identity guards.
            book.active().favorites.clear();book.active().favorites.addAll(favorites);state.book=book;renderFavorites();
        }catch(Exception e){toast(t(R.string.err_favorite));}
    }
    private void renderFavorites(){
        if(favoritesArea==null)return;favoritesArea.removeAllViews();gap(favoritesArea,20);addText(favoritesArea,t(R.string.favorites_title),15,TEXT);gap(favoritesArea,8);addText(favoritesArea,t(R.string.favorites_note),12,MUTED);gap(favoritesArea,8);
        ProfileBook.Profile profile=book.active();if(profile==null||profile.favorites.isEmpty()){addText(favoritesArea,t(R.string.favorites_empty),12,SECONDARY);return;}
        for(String name:profile.favorites){favoritesArea.addView(compact(name+"  ›",()->openFavorite(name)),new LinearLayout.LayoutParams(-1,-2));gap(favoritesArea,6);}
    }
    private void openFavorite(String name){
        if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        LinearLayout box=sheetBox(name);TextView info=message(box);showMessage(info,t(R.string.loading_service_state),false);showSheet(box);servicesFresh=false;
        read(config,"/api/v1/system",false,j->{system=j;systemFresh=true;lastSystemSuccess=System.currentTimeMillis();systemError="";
            read(config,"/api/v1/services",false,data->{supported=data.getBoolean("supported");services.clear();JSONArray list=data.getJSONArray("services");JSONObject target=null;for(int i=0;i<list.length();i++){JSONObject service=list.getJSONObject(i);services.add(service);if(name.equals(service.optString("name")))target=service;}servicesFresh=true;filterServices();
                if(target==null)showMessage(info,supported?t(R.string.service_not_found):t(R.string.no_systemd),false);else serviceSheet(target);
            },e->{servicesFresh=false;showMessage(info,e,true);});
        },e->{systemFresh=false;showMessage(info,e,true);});
    }

    private void toolMenu(){
        if(config==null){toast(t(R.string.connect_host_first));return;}if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        LinearLayout box=sheetBox(t(R.string.host_tools));addText(box,hostLabel()+"\n"+t(R.string.tools_note),12,SECONDARY);gap(box,20);
        box.addView(compact(t(R.string.listening_ports),()->openTool("ports")),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
        box.addView(compact(t(R.string.systemd_timers),()->openTool("timers")),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
        box.addView(compact(t(R.string.disks_scan_menu),()->openTool("disks")),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void toolRead(String path,JSONObject body,Result success,java.util.function.Consumer<String> failure){
        if(busy||ApiClient.MUTATING.get()||config==null||!foreground||sheet==null){toast(t(R.string.busy));return;}
        busy=true;stopPolls();updateControls();final int gen=generation,sg=sheetGeneration;final ApiClient.Config identity=config;
        ApiClient.IO.execute(()->{JSONObject data=null;String error=null;try{if(!RequestPolicy.current(foreground,generation,gen,config,identity,true,sheetGeneration,sg))throw new Exception(t(R.string.request_cancelled));data=api.request(identity,path,body,()->RequestPolicy.current(foreground,generation,gen,config,identity,true,sheetGeneration,sg));ToolData.validate(path,data);}catch(Exception e){error=e.getMessage()==null?t(R.string.tool_read_failed):ApiClient.redact(e.getMessage(),identity);}final JSONObject result=data;final String problem=error;
            handler.post(()->{busy=false;if(destroyed)return;if(sheet!=null&&RequestPolicy.current(foreground,generation,gen,config,identity,true,sheetGeneration,sg)){if(problem==null)try{status(true);success.accept(result);}catch(Exception e){failure.accept(t(R.string.err_tool_format));}else{status(false);failure.accept(problem);}}updateControls();if(loadPending&&foreground){loadPending=false;loadCurrent();}else schedulePoll();});
        });
    }
    private void openTool(String kind){
        if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        String title=kind.equals("ports")?t(R.string.listening_ports):kind.equals("timers")?t(R.string.systemd_timers):t(R.string.disks_scan_title);
        LinearLayout box=sheetBox(title);addText(box,t(R.string.host_line,hostLabel())+"\n"+t(R.string.no_auto_refresh),12,SECONDARY);gap(box,12);TextView info=message(box);LinearLayout rows=column();final JSONObject[] cached={null};
        EditText query=search(kind.equals("ports")?t(R.string.search_ports):t(R.string.search_name_path),"",q->{});
        Runnable render=()->{rows.removeAllViews();if(cached[0]==null)return;renderTool(kind,cached[0],query.getText().toString(),rows);};watch(query,render);
        Runnable fetch=()->{showMessage(info,t(R.string.reading),false);toolRead("/api/v1/"+kind,null,j->{cached[0]=j;render.run();showMessage(info,j.optBoolean("truncated")?t(R.string.tool_truncated):"",false);},e->{cached[0]=null;rows.removeAllViews();showMessage(info,e,true);});};
        box.addView(compact(t(R.string.refresh),fetch),new LinearLayout.LayoutParams(-1,-2));gap(box,8);box.addView(searchRow(query));gap(box,16);box.addView(rows);showSheet(box);expandSheet();fetch.run();
    }
    private void renderTool(String kind,JSONObject data,String query,LinearLayout rows){
        if(!kind.equals("disks")&&!data.optBoolean("supported",false)){addText(rows,kind.equals("timers")?t(R.string.timers_unsupported):t(R.string.ports_unsupported),15,WARN);gap(rows,8);addText(rows,data.optString("message",t(R.string.capability_missing)),12,SECONDARY);return;}
        JSONArray items=data.optJSONArray(kind);String q=query.trim().toLowerCase(Locale.ROOT);int shown=0;
        if(items!=null)for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;if(!ToolData.searchText(kind,item).toLowerCase(Locale.ROOT).contains(q))continue;shown++;
            if(kind.equals("ports")){
                addText(rows,item.optString("protocol").toUpperCase(Locale.ROOT)+" · "+item.optString("address"),16,TEXT);gap(rows,8);
                addText(rows,t(R.string.port_detail,item.optInt("pid")>0?String.valueOf(item.optInt("pid")):t(R.string.unavailable),ToolData.available(item.optString("process")),ToolData.available(item.optString("service"))),12,SECONDARY);
            }else if(kind.equals("timers")){
                addText(rows,item.optString("name"),16,TEXT);gap(rows,6);addText(rows,item.optString("description")+"\n"+item.optString("active_state")+" · "+item.optString("sub_state"),12,SECONDARY);gap(rows,8);
                addText(rows,t(R.string.timer_detail,ToolData.available(item.optString("next_run")),ToolData.available(item.optString("last_run")),ToolData.available(item.optString("unit"))),12,SECONDARY);
                if(item.optBoolean("can_run")){gap(rows,12);rows.addView(compact(t(R.string.run_now),()->confirmTimer(item)),new LinearLayout.LayoutParams(-1,-2));}else{gap(rows,8);addText(rows,t(R.string.run_not_allowed),12,MUTED);}
            }else{
                addText(rows,item.optString("mountpoint"),16,TEXT);gap(rows,8);double total=number(item,"total"),used=number(item,"used"),inodes=number(item,"inodes_total");
                addText(rows,t(R.string.disk_detail,item.optString("device"),item.optString("filesystem"),bytes(used),bytes(total),percent(total>0?used/total*100:Double.NaN),bytes(number(item,"available")),inodes>0?item.optLong("inodes_used")+" / "+item.optLong("inodes_total")+" · "+percent(number(item,"inodes_used")/inodes*100):t(R.string.unavailable_now)),12,SECONDARY);
            }divider(rows);
        }
        if(shown==0)addText(rows,q.isEmpty()?t(R.string.no_data):t(R.string.no_results),14,SECONDARY);
        if(kind.equals("disks")){
            gap(rows,12);addText(rows,t(R.string.limited_scan),15,TEXT);gap(rows,8);addText(rows,t(R.string.scan_note),12,SECONDARY);gap(rows,12);
            JSONArray roots=data.optJSONArray("scan_roots");if(roots==null||roots.length()==0)addText(rows,t(R.string.scan_disabled),12,MUTED);
            if(roots!=null)for(int i=0;i<roots.length();i++){String path=roots.optString(i);rows.addView(compact(t(R.string.scan_path,path),()->confirmScan(path)),new LinearLayout.LayoutParams(-1,-2));gap(rows,8);}
        }
    }
    private void confirmTimer(JSONObject timer){
        if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}if(!timer.optBoolean("can_run"))return;
        LinearLayout box=sheetBox(t(R.string.run_title));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,16);addText(box,t(R.string.timer_unit,timer.optString("name"),timer.optString("unit")),16,TEXT);gap(box,16);addText(box,t(R.string.run_warning),14,SECONDARY);gap(box,24);
        box.addView(button(t(R.string.confirm_run),true,false,()->{String name=timer.optString("name");String path="/api/v1/timers/"+encodeSegment(name)+"/run";mutate(path,json("confirm",name),t(R.string.run_accepted),()->openTool("timers"),false);}),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void confirmScan(String path){
        if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}LinearLayout box=sheetBox(t(R.string.scan_title));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,16);addText(box,path,18,TEXT);gap(box,16);
        addText(box,t(R.string.scan_warning),14,SECONDARY);gap(box,24);
        box.addView(button(t(R.string.confirm_scan),true,false,()->runScan(path)),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void runScan(String path){
        if(busy||ApiClient.MUTATING.get())return;LinearLayout box=sheetBox(t(R.string.scan_header,path));addText(box,t(R.string.host_line,hostLabel()),12,SECONDARY);gap(box,12);TextView info=message(box);showMessage(info,t(R.string.scanning),false);LinearLayout result=column();box.addView(result);showSheet(box);expandSheet();
        toolRead("/api/v1/disk-scan",json("path",path),j->{showMessage(info,j.getBoolean("partial")?t(R.string.scan_partial,scanReasons(j.getString("reason"))):t(R.string.scan_complete),j.getBoolean("partial"));
            addText(result,j.getString("path"),16,TEXT);gap(result,12);addText(result,t(R.string.scan_summary,bytes(number(j,"bytes")),files(j.optLong("files")),j.optLong("elapsed_ms")),14,SECONDARY);divider(result);
            JSONArray entries=j.getJSONArray("entries");ArrayList<JSONObject> sorted=new ArrayList<>();for(int i=0;i<entries.length();i++)sorted.add(entries.getJSONObject(i));sorted.sort((a,b)->Long.compare(b.optLong("bytes"),a.optLong("bytes")));addText(result,t(R.string.scan_entries),12,MUTED);gap(result,12);for(int i=0;i<Math.min(20,sorted.size());i++){JSONObject entry=sorted.get(i);addText(result,entry.optString("name")+"\n"+bytes(number(entry,"bytes"))+" · "+files(entry.optLong("files")),14,TEXT);divider(result);}
        },e->showMessage(info,t(R.string.scan_failed,e),true));
    }

    // Scripts page: the server decides whether execution is enabled; drafts live in the retained State.
    // The editor and history share one page; a job opens in place and is followed only while visible.
    private void buildScripts(){
        scripts=pageColumn();scriptRefresh=compact(t(R.string.refresh),()->loadCurrent());heading(scripts,t(R.string.nav_scripts),t(R.string.scripts_subtitle),scriptRefresh);scriptMessage=message(scripts);
        jobListArea=column();scripts.addView(jobListArea);
        jobDisabled=column();jobListArea.addView(jobDisabled);
        jobEditor=panel();addText(jobEditor,t(R.string.new_script),15,TEXT);gap(jobEditor,8);jobIdentity=text("",14,MINT);jobEditor.addView(jobIdentity);gap(jobEditor,8);jobWarning=text("",12,WARN);jobEditor.addView(jobWarning);gap(jobEditor,20);
        jobNameInput=input(t(R.string.hint_job_name),false);jobNameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);jobNameInput.setText(state.jobName);watch(jobNameInput,()->state.jobName=jobNameInput.getText().toString());field(jobEditor,t(R.string.job_name),jobNameInput);
        jobScriptInput=input(t(R.string.hint_script),false);jobScriptInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);jobScriptInput.setSingleLine(false);jobScriptInput.setHorizontallyScrolling(false);jobScriptInput.setMinLines(6);jobScriptInput.setMaxLines(14);jobScriptInput.setGravity(Gravity.TOP|Gravity.START);jobScriptInput.setTypeface(Typeface.MONOSPACE);jobScriptInput.setTextSize(13);
        jobScriptInput.setText(state.jobScript);watch(jobScriptInput,()->state.jobScript=jobScriptInput.getText().toString());field(jobEditor,t(R.string.job_script),jobScriptInput);
        jobEditor.addView(label(t(R.string.job_timeout)));gap(jobEditor,8);LinearLayout times=row();
        for(int i=0;i<timeoutChoices.length;i++){final int seconds=JobOutput.TIMEOUTS[i];timeoutChoices[i]=compact(timeoutLabel(seconds),()->{state.jobTimeout=seconds;paintTimeouts();});timeoutChoices[i].setTextSize(12);timeoutChoices[i].setPadding(dp(2),dp(10),dp(2),dp(10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(4);times.addView(timeoutChoices[i],p);}
        paintTimeouts();jobEditor.addView(times);gap(jobEditor,16);
        LinearLayout run=row();Button clear=compact(t(R.string.clear),()->{jobNameInput.setText("");jobScriptInput.setText("");});clear.setContentDescription(t(R.string.clear_script));run.addView(clear,new LinearLayout.LayoutParams(-2,-2));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.leftMargin=dp(8);run.addView(button(t(R.string.run_script),true,false,()->confirmJob(jobUser)),rp);jobEditor.addView(run);
        jobListArea.addView(jobEditor);
        jobHistory=column();gap(jobHistory,28);LinearLayout head=row();weight(head,text(t(R.string.recent_jobs),15,TEXT));head.addView(text(t(R.string.manual_refresh),11,MUTED));jobHistory.addView(head);gap(jobHistory,6);addText(jobHistory,t(R.string.jobs_kept_note),12,MUTED);gap(jobHistory,8);jobRows=column();jobHistory.addView(jobRows);jobListArea.addView(jobHistory);
        jobDetail=column();Button back=compact(t(R.string.back_to_jobs),()->showJobView(""));back.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);jobDetail.addView(back,new LinearLayout.LayoutParams(-1,-2));gap(jobDetail,16);
        LinearLayout summary=panel();jobTitle=text("",18,TEXT);jobTitle.setTextIsSelectable(true);summary.addView(jobTitle);gap(summary,8);jobStatus=text("",14,SECONDARY);summary.addView(jobStatus);gap(summary,6);jobTimes=text("",12,MUTED);summary.addView(jobTimes);jobDetail.addView(summary);
        jobActions=column();jobDetail.addView(jobActions);gap(jobDetail,20);
        addText(jobDetail,t(R.string.job_output),15,TEXT);gap(jobDetail,8);jobTrimmed=text(t(R.string.job_output_trimmed),12,WARN);jobTrimmed.setVisibility(View.GONE);jobDetail.addView(jobTrimmed);
        jobOut=text("",12,TEXT);jobOut.setTypeface(Typeface.MONOSPACE);jobOut.setTextIsSelectable(true);jobOut.setPadding(dp(12),dp(12),dp(12),dp(12));jobOut.setBackground(shape(SURFACE,12,false));jobDetail.addView(jobOut,new LinearLayout.LayoutParams(-1,-2));
        scripts.addView(jobDetail);attachPage(4,scroll(scripts));resetJobDetail();renderScripts();
    }
    private void paintTimeouts(){for(int i=0;i<timeoutChoices.length;i++){boolean on=JobOutput.TIMEOUTS[i]==JobOutput.TIMEOUTS[JobOutput.timeoutIndex(state.jobTimeout)];timeoutChoices[i].setTextColor(on?MINT:SECONDARY);timeoutChoices[i].setSelected(on);}}
    private void resetJobDetail(){
        jobNext=-1;jobOutput="";jobScriptText="";jobCurrent=null;jobFollow=false;jobTrimmedShown=false;jobDelay=2000;jobActionsKey="";
        if(jobOut==null)return;jobTitle.setText(jobView);jobStatus.setText(t(R.string.loading));jobStatus.setTextColor(SECONDARY);jobTimes.setText("");jobActions.removeAllViews();jobTrimmed.setVisibility(View.GONE);jobOut.setText(t(R.string.loading));
    }
    /** Switch between the job list ("") and one job in place, dropping any read for the previous view. */
    private void showJobView(String id){
        generation++;stopPolls();api.cancelRead();loadPending=false;jobView=id;resetJobDetail();renderScripts();
        if(pages[4] instanceof ScrollView){scrollY[4]=0;((ScrollView)pages[4]).scrollTo(0,0);}
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        if(page==4&&foreground&&config!=null)loadCurrent();
    }
    private void loadJobs(){
        if(jobView.isEmpty()){
            if(jobsData==null)showMessage(scriptMessage,t(R.string.reading),false);
            read(config,"/api/v1/jobs",false,j->{jobsData=j;showMessage(scriptMessage,"",false);renderScripts();},e->showMessage(scriptMessage,jobsData==null?e:t(R.string.error_stale,e),true));
            return;
        }
        final String id=jobView;
        read(config,"/api/v1/jobs/"+id+(jobNext<0?"":"?offset="+jobNext),false,j->{
            JSONObject job=j.getJSONObject("job");String chunk=j.getString("output");long offset=j.getLong("offset"),size=j.getLong("size");
            if(jobNext<0||offset!=jobNext){jobOutput=chunk;if(jobNext<0)jobScriptText=j.optString("script");if(offset>0)jobTrimmedShown=true;}
            else{String joined=JobOutput.append(jobOutput,chunk,JobOutput.KEEP_CHARS);if(joined.length()<jobOutput.length()+chunk.length())jobTrimmedShown=true;jobOutput=joined;}
            jobNext=j.getLong("next_offset");boolean more=size-jobNext>3,running=!JobOutput.finished(job.getString("state"));
            jobCurrent=job;jobFollow=more||running;jobDelay=more?100:2000;showMessage(scriptMessage,"",false);renderJobDetail(running);
        },e->{jobFollow=false;showMessage(scriptMessage,e,true);});
    }
    private void renderScripts(){
        if(jobListArea==null)return;boolean detail=!jobView.isEmpty();jobListArea.setVisibility(detail?View.GONE:View.VISIBLE);jobDetail.setVisibility(detail?View.VISIBLE:View.GONE);
        if(detail)return;JSONObject data=jobsData;jobDisabled.removeAllViews();jobRows.removeAllViews();
        boolean supported=data!=null&&data.optBoolean("supported",false);
        jobEditor.setVisibility(supported?View.VISIBLE:View.GONE);jobHistory.setVisibility(supported?View.VISIBLE:View.GONE);jobDisabled.setVisibility(data!=null&&!supported?View.VISIBLE:View.GONE);
        if(data==null)return;
        if(!supported){LinearLayout box=panel();addText(box,t(R.string.exec_disabled),15,WARN);gap(box,8);String reason=data.optString("message");if(!reason.isEmpty()){addText(box,reason,12,SECONDARY);gap(box,8);}TextView hint=text(t(R.string.exec_enable_hint),12,MUTED);hint.setTextIsSelectable(true);box.addView(hint);jobDisabled.addView(box);return;}
        jobUser=data.optString("user");boolean unit=data.optString("runner").equals("systemd");
        jobIdentity.setText(t(R.string.exec_identity,jobUser,t(unit?R.string.runner_systemd:R.string.runner_direct)));jobWarning.setText(t(R.string.exec_warning,jobUser));
        JSONArray jobs=data.optJSONArray("jobs");if(jobs==null||jobs.length()==0){gap(jobRows,8);addText(jobRows,t(R.string.jobs_empty),12,SECONDARY);return;}
        for(int i=0;i<jobs.length();i++){JSONObject job=jobs.optJSONObject(i);if(job==null)continue;if(i>0){View line=new View(this);line.setBackgroundColor(LINE);jobRows.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}jobRows.addView(jobRow(job),new LinearLayout.LayoutParams(-1,-2));}
    }
    private View jobRow(JSONObject job){
        String id=job.optString("id"),name=job.optString("name",id),state=job.optString("state");
        LinearLayout r=largeType()?column():row();r.setMinimumHeight(dp(72));r.setPadding(0,dp(14),0,dp(14));r.setBackground(ripple(BG,0));
        LinearLayout names=column();TextView title=text(name.isEmpty()?id:name,15,TEXT);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);names.addView(title);gap(names,6);addText(names,t(R.string.job_row_detail,stamp(job.optLong("started_at")),timeoutLabel(job.optInt("timeout_seconds"))),12,SECONDARY);
        if(largeType())r.addView(names,new LinearLayout.LayoutParams(-1,-2));else weight(r,names);
        TextView value=text(t(R.string.row_chevron,jobState(job)),12,jobColor(state));value.setGravity(largeType()?Gravity.START:Gravity.END);value.setMaxLines(3);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(largeType()?-1:dp(120),-2);if(largeType())vp.topMargin=dp(6);else vp.leftMargin=dp(12);r.addView(value,vp);
        r.setOnClickListener(v->showJobView(id));r.setContentDescription(t(R.string.row_description,title.getText(),stamp(job.optLong("started_at")),jobState(job)));return r;
    }
    private void renderJobDetail(boolean running){
        JSONObject job=jobCurrent;if(job==null)return;String id=job.optString("id"),name=job.optString("name",id),state=job.optString("state");
        jobTitle.setText(name.isEmpty()?id:name);jobStatus.setText(jobState(job));jobStatus.setTextColor(jobColor(state));
        String started=t(R.string.job_started_limit,stamp(job.optLong("started_at")),timeoutLabel(job.optInt("timeout_seconds")));
        jobTimes.setText(running?t(R.string.two_lines,started,t(R.string.job_polling)):job.optLong("finished_at")>0?t(R.string.two_lines,started,t(R.string.job_finished_at,stamp(job.optLong("finished_at")))):started);
        jobTrimmed.setVisibility(jobTrimmedShown?View.VISIBLE:View.GONE);jobOut.setText(jobOutput.isEmpty()?t(R.string.job_output_empty):jobOutput);
        // Rebuild buttons only when what they offer changes, so polling never steals a press.
        String key=state+"|"+jobScriptText.isEmpty();if(key.equals(jobActionsKey))return;jobActionsKey=key;jobActions.removeAllViews();gap(jobActions,12);
        if(running){jobActions.addView(button(t(R.string.cancel_job),false,true,()->confirmCancelJob(id,name)),new LinearLayout.LayoutParams(-1,-2));gap(jobActions,8);}
        Button copy=compact(t(R.string.copy_output),()->copyLogs(name,jobOutput));
        Button rerun=jobScriptText.isEmpty()?null:compact(t(R.string.rerun_job),()->rerunJob(job));
        pair(jobActions,copy,rerun);
        if(!running){gap(jobActions,8);jobActions.addView(button(t(R.string.delete_job),false,true,()->confirmDeleteJob(id,name)),new LinearLayout.LayoutParams(-1,-2));}
        gap(jobActions,8);addText(jobActions,t(R.string.clipboard_output_warning),12,MUTED);
    }
    private void pair(LinearLayout target,Button a,Button b){
        if(b==null){target.addView(a,new LinearLayout.LayoutParams(-1,-2));return;}
        if(largeType()){target.addView(a,new LinearLayout.LayoutParams(-1,-2));gap(target,8);target.addView(b,new LinearLayout.LayoutParams(-1,-2));return;}
        LinearLayout r=row();r.addView(a,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(8);r.addView(b,p);target.addView(r,new LinearLayout.LayoutParams(-1,-2));
    }
    private void rerunJob(JSONObject job){
        state.jobTimeout=JobOutput.TIMEOUTS[JobOutput.timeoutIndex(job.optInt("timeout_seconds"))];String name=job.optString("name");
        jobNameInput.setText(name);jobScriptInput.setText(jobScriptText);paintTimeouts();showJobView("");jobScriptInput.requestFocus();toast(t(R.string.rerun_loaded));
    }
    private String timeoutLabel(int seconds){int[] labels={R.string.timeout_5m,R.string.timeout_30m,R.string.timeout_2h,R.string.timeout_24h};return t(labels[JobOutput.timeoutIndex(seconds)]);}
    private void confirmJob(String user){
        // Opening a confirmation sends nothing; a background job read may be in flight, and mutate() still refuses overlap.
        if(ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        if(user.isEmpty()){toast(t(R.string.refresh_before_action));return;}
        final String script=JobOutput.normalize(state.jobScript),name=state.jobName.trim();final int timeout=JobOutput.TIMEOUTS[JobOutput.timeoutIndex(state.jobTimeout)];
        if(!JobOutput.fits(script)){toast(t(R.string.err_script_invalid));return;}
        LinearLayout box=sheetBox(t(R.string.confirm_script_title));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);
        addText(box,t(R.string.confirm_script_summary,name.isEmpty()?"—":name,timeoutLabel(timeout),bytes(script.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)),14,SECONDARY);gap(box,12);
        TextView preview=text(script.length()>2000?script.substring(0,2000)+"\n…":script,12,TEXT);preview.setTypeface(Typeface.MONOSPACE);preview.setPadding(dp(12),dp(12),dp(12),dp(12));preview.setBackground(shape(RAISED,12,false));box.addView(preview,new LinearLayout.LayoutParams(-1,-2));gap(box,12);
        addText(box,t(R.string.exec_warning,user),12,WARN);gap(box,24);
        box.addView(button(t(R.string.confirm_script_run,user),true,true,()->{JSONObject body=new JSONObject();try{body.put("name",name).put("script",script).put("timeout_seconds",timeout);}catch(JSONException e){return;}
            mutate("/api/v1/jobs",body,t(R.string.job_started),j->showJobView(j.getJSONObject("job").getString("id")),false);}),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void confirmCancelJob(String id,String name){
        // Opening a confirmation sends nothing; a background job read may be in flight, and mutate() still refuses overlap.
        if(ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        LinearLayout box=sheetBox(t(R.string.cancel_job_title));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);addText(box,name,16,TEXT);gap(box,12);addText(box,t(R.string.cancel_job_warning),14,SECONDARY);gap(box,24);
        box.addView(button(t(R.string.cancel_job),true,true,()->mutate("/api/v1/jobs/"+id+"/cancel",new JSONObject(),t(R.string.cancel_requested),()->{if(page==4&&jobView.equals(id))loadCurrent();},false)),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void confirmDeleteJob(String id,String name){
        // Opening a confirmation sends nothing; a background job read may be in flight, and mutate() still refuses overlap.
        if(ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        LinearLayout box=sheetBox(t(R.string.delete_job_title));addText(box,t(R.string.host_line,hostLabel()),14,MINT);gap(box,12);addText(box,name,16,TEXT);gap(box,12);addText(box,t(R.string.delete_job_warning),14,SECONDARY);gap(box,24);
        box.addView(button(t(R.string.delete_job),true,true,()->mutate("/api/v1/jobs/"+id+"/delete",new JSONObject(),t(R.string.job_deleted),()->{if(jobView.equals(id))showJobView("");else if(page==4)loadCurrent();},false)),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private static String jobState(JSONObject job){
        switch(job.optString("state")){
            case "running":return t(R.string.job_state_running);case "succeeded":return t(R.string.job_state_succeeded);case "failed":return t(R.string.job_state_failed,job.optInt("exit_code"));
            case "cancelled":return t(R.string.job_state_cancelled);case "timeout":return t(R.string.job_state_timeout);case "killed":return t(R.string.job_state_killed,job.optString("signal"));
            case "lost":return t(R.string.job_state_lost);default:return t(R.string.job_state_unknown);
        }
    }
    private static int jobColor(String state){return state.equals("succeeded")?MINT:state.equals("running")?CYAN:state.equals("failed")||state.equals("killed")||state.equals("lost")?RED:WARN;}
    private static String stamp(long seconds){return seconds<=0?"—":new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(seconds*1000));}

    private void expandSheet(){if(sheet!=null&&sheet.getWindow()!=null)sheet.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),(int)(getResources().getDisplayMetrics().heightPixels*.85));}
    private LinearLayout sheetBox(String title){LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(24));View handle=new View(this);handle.setBackground(shape(MUTED,2,false));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(12);box.addView(handle,hp);LinearLayout top=row();TextView name=text(title,18,TEXT);name.setTextIsSelectable(true);weight(top,name);Button close=compact(t(R.string.close),()->{if(sheet!=null)sheet.dismiss();});close.setContentDescription(t(R.string.close_sheet));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=dp(8);top.addView(close,cp);box.addView(top);gap(box,20);return box;}
    private void showSheet(LinearLayout box){
        if(sheet!=null)sheet.dismiss();stopPolls();resetRates();sheetGeneration++;
        Dialog d=new Dialog(this);d.setContentView(scroll(box));Window w=d.getWindow();
        if(w!=null){w.setBackgroundDrawable(shape(SURFACE,16,false));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND|WindowManager.LayoutParams.FLAG_SECURE);WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.55f;p.gravity=Gravity.BOTTOM;p.width=Math.min(getResources().getDisplayMetrics().widthPixels,dp(600));p.height=-2;w.setAttributes(p);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        d.setOnDismissListener(dialog->{if(sheet==d){sheet=null;sheetGeneration++;loadPending=false;api.cancelRead();schedulePoll();}});sheet=d;d.show();
        int max=(int)(getResources().getDisplayMetrics().heightPixels*.85);box.measure(View.MeasureSpec.makeMeasureSpec(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));if(w!=null)w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),Math.min(box.getMeasuredHeight(),max));
    }
    private void mutate(String path,JSONObject body,String success,Runnable after,boolean power){mutate(path,body,success,j->after.run(),power);}
    private void mutate(String path,JSONObject body,String success,Result after,boolean power){
        if(busy || config==null || !foreground || !ApiClient.MUTATING.compareAndSet(false,true)){toast(t(R.string.busy_existing));return;}
        busy=true;stopPolls();final ApiClient.Config credentials=config;final State targetState=state;updateControls();if(sheet!=null)sheet.dismiss();toast(t(R.string.submitting));
        final int gen=generation;
        ApiClient.IO.execute(()->{String error=null;JSONObject response=null;try{if(!RequestPolicy.current(foreground,generation,gen,config,credentials,false,0,0))throw new Exception(t(R.string.op_cancelled));response=api.request(credentials,path,body,()->RequestPolicy.current(foreground,generation,gen,config,credentials,false,0,0));}catch(Exception e){error=e.getMessage()==null?t(R.string.op_unconfirmed):ApiClient.redact(e.getMessage(),credentials);}final String problem=error;final JSONObject result=response;
            runOnUiThread(()->{ApiClient.MUTATING.set(false);busy=false;MainActivity owner=targetState.owner;if(owner==null||owner.destroyed||!owner.foreground){targetState.notice=problem==null?success:problem;return;}
                owner.processesFresh=false;owner.servicesFresh=false;
                if(owner!=this||owner.config!=credentials||gen!=generation){owner.toast(problem==null?success:problem);owner.loadCurrent();return;}
                if(problem==null){toast(success);try{after.accept(result);}catch(Exception e){toast(t(R.string.err_tool_format));}}else{status(false);systemFresh=false;processesFresh=false;servicesFresh=false;systemError=problem;toast(problem);if(page==1)showMessage(processMessage,t(R.string.error_refresh_check,problem),true);if(page==2)showMessage(serviceMessage,t(R.string.error_refresh_check,problem),true);if(page==3)showMessage(settingsError,problem,true);if(page==4)showMessage(scriptMessage,t(R.string.error_refresh_check,problem),true);updateDashboard();updatePower();}
                updateControls();if(!power){if(loadPending){loadPending=false;loadCurrent();}else schedulePoll();}
            });
        });
    }
    private JSONObject json(String... pairs){JSONObject object=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)object.put(pairs[i],pairs[i+1]);}catch(Exception ignored){}return object;}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private static String t(int id,Object... args){return Text.get(id,args);}
    private static String files(long count){return Text.plural(R.plurals.files_count,(int)Math.min(count,Integer.MAX_VALUE),count);}
    private static final String[] SCAN_REASONS={"cancelled","time limit","entry limit","depth limit","permission or changed entry","changed entry","filesystem boundary","symlinks skipped","special files skipped","directory read error"};
    private static final int[] SCAN_REASON_TEXT={R.string.reason_cancelled,R.string.reason_time,R.string.reason_entries,R.string.reason_depth,R.string.reason_permission,R.string.reason_changed,R.string.reason_boundary,R.string.reason_symlinks,R.string.reason_special,R.string.reason_read_error};
    // Server reasons are stable English tokens; unknown ones are shown unchanged.
    private static String scanReasons(String raw){StringBuilder out=new StringBuilder();for(String part:raw.split(",\\s*")){if(part.isEmpty())continue;String label=part;for(int i=0;i<SCAN_REASONS.length;i++)if(SCAN_REASONS[i].equals(part))label=t(SCAN_REASON_TEXT[i]);if(out.length()>0)out.append(t(R.string.list_separator));out.append(label);}return out.toString();}

    private static final String[] LANGUAGES={"","zh-Hans","zh-Hant","en"};
    private static String normalizeLanguage(Locale locale){
        String language=locale.getLanguage();if(language.equals("en"))return "en";if(!language.equals("zh"))return locale.toLanguageTag();
        String script=locale.getScript(),region=locale.getCountry();
        return script.equals("Hant")||script.isEmpty()&&(region.equals("TW")||region.equals("HK")||region.equals("MO"))?"zh-Hant":"zh-Hans";
    }
    private String languageTag(){
        if(Build.VERSION.SDK_INT>=33){LocaleList list=getSystemService(LocaleManager.class).getApplicationLocales();return list.isEmpty()?"":normalizeLanguage(list.get(0));}
        return getSharedPreferences("ui",MODE_PRIVATE).getString("language","");
    }
    private String languageName(String tag){return tag.isEmpty()?t(R.string.language_system):tag.equals("zh-Hans")?t(R.string.lang_zh_hans):tag.equals("zh-Hant")?t(R.string.lang_zh_hant):tag.equals("en")?t(R.string.lang_en):Locale.forLanguageTag(tag).getDisplayName(Locale.forLanguageTag(tag));}
    private void chooseLanguage(){
        if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        LinearLayout box=sheetBox(t(R.string.language));addText(box,t(R.string.language_note),12,SECONDARY);gap(box,16);String current=languageTag();
        for(String tag:LANGUAGES){boolean selected=tag.equals(current);Button choice=compact(languageName(tag)+(selected?"  ✓":""),()->setLanguage(tag));if(selected)choice.setTextColor(MINT);box.addView(choice,new LinearLayout.LayoutParams(-1,-2));gap(box,8);}
        showSheet(box);
    }
    // Recreation keeps drafts and saved hosts through the retained State object.
    private void setLanguage(String tag){
        if(busy||ApiClient.MUTATING.get()){toast(t(R.string.busy));return;}
        if(sheet!=null)sheet.dismiss();if(tag.equals(languageTag()))return;
        if(Build.VERSION.SDK_INT>=33){getSystemService(LocaleManager.class).setApplicationLocales(tag.isEmpty()?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(tag));return;}
        getSharedPreferences("ui",MODE_PRIVATE).edit().putString("language",tag).commit();recreate();
    }

    private final class Meter extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final int color;final String name;double value=Double.NaN;
        Meter(int color,String name){super(MainActivity.this);this.color=color;this.name=name;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        void set(double value){this.value=value;setContentDescription(name+" "+percent(value));invalidate();}
        @Override protected void onDraw(Canvas c){paint.setColor(LINE);c.drawRoundRect(0,0,getWidth(),getHeight(),dp(2),dp(2),paint);if(Double.isFinite(value)){paint.setColor(color);c.drawRoundRect(0,0,(float)(getWidth()*Math.max(0,Math.min(100,value))/100),getHeight(),dp(2),dp(2),paint);}}
    }
    private final class Icon extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final int type;int color;
        Icon(int type,int color){super(MainActivity.this);this.type=type;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();float size=dp(22);c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.75f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(type==0){c.drawRoundRect(3,3,21,21,3,3,p);c.drawLine(3,14,8,14,p);c.drawLine(8,14,11,8,p);c.drawLine(11,8,14,17,p);c.drawLine(14,17,17,11,p);c.drawLine(17,11,21,11,p);}
            else if(type==1){for(int i=0;i<3;i++){float y=5+7*i;c.drawLine(4,y,6,y,p);c.drawLine(10,y,20,y,p);}}
            else if(type==2||type==4){c.drawRoundRect(3,3,21,10,2,2,p);c.drawRoundRect(3,14,21,21,2,2,p);c.drawPoint(7,6.5f,p);c.drawPoint(7,17.5f,p);c.drawLine(12,6.5f,17,6.5f,p);c.drawLine(12,17.5f,17,17.5f,p);}
            else if(type==5){c.drawRoundRect(3,4,21,20,3,3,p);c.drawLine(7,9,10,12,p);c.drawLine(10,12,7,15,p);c.drawLine(12.5f,15,17,15,p);}
            else{c.drawCircle(12,12,7,p);c.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,12+(float)Math.cos(a)*11,12+(float)Math.sin(a)*11,p);}}c.restore();}
    }
}
