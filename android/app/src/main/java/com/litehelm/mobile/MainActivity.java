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
    private final View[] pages=new View[4];
    private final TextView[] navLabels=new TextView[4];
    private final Icon[] navIcons=new Icon[4];
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
    private final int[] scrollY=new int[4];
    private String processQuery="", serviceQuery="", systemError="", notice="";
    private Dialog sheet;
    private State state;
    private final Runnable poll=()->{ if(foreground && page==0 && config!=null && sheet==null) loadCurrent(); };

    private static final class State {
        String address="", token="", name="", editingId="", processQuery="", serviceQuery="", notice="";
        boolean draft, cpu; int page=3, filter;
        volatile ApiClient.Config config;
        ProfileBook book;
        volatile MainActivity owner;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        state=(State)getLastNonConfigurationInstance();
        if(state==null) state=new State();
        state.owner=this;
        vault=new CredentialStore(this);
        book=state.book;
        if(book==null)try { book=vault.load(); } catch(Exception e) { notice="本机加密凭据不可用，请重新填写连接信息"; }
        if(book==null)book=new ProfileBook();
        config=book.active()==null?null:book.active().config;
        editingId=state.draft?state.editingId:book.activeId;
        page=config==null?3:(state.draft?state.page:0);
        processCpu=state.cpu; serviceFilter=state.filter;
        processQuery=state.processQuery; serviceQuery=state.serviceQuery;
        buildShell();
        switchPage(page,false);
    }
    @Override public Object onRetainNonConfigurationInstance() {
        captureDraft(); state.config=config; state.book=book; state.page=page; state.cpu=processCpu; state.filter=serviceFilter;
        state.processQuery=processQuery; state.serviceQuery=serviceQuery; state.owner=null;
        return state;
    }
    @Override protected void onResume() {
        super.onResume(); foreground=true; resetRates();
        if(!state.notice.isEmpty()) { toast(state.notice); state.notice=""; }
        if(!notice.isEmpty()) { toast(notice); notice=""; }
        if(config!=null) loadCurrent();
    }
    @Override protected void onPause() {
        foreground=false; generation++; handler.removeCallbacks(poll); resetRates();
        if(sheet!=null)sheet.dismiss();
        systemFresh=false; processesFresh=false; servicesFresh=false;
        api.cancelRead(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true; handler.removeCallbacksAndMessages(null); if(sheet!=null)sheet.dismiss();
        api.cancelRead(); if(state.owner==this)state.owner=null; super.onDestroy();
    }
    @Override public void onBackPressed() { if(sheet!=null && sheet.isShowing())sheet.dismiss(); else if(page!=0 && config!=null)switchPage(0,true); else super.onBackPressed(); }

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
    private Button compact(String title,Runnable action) { Button b=button(title,false,false,action); b.setContentDescription(title);return b; }
    private void enabled(View v,boolean yes) { v.setEnabled(yes);v.setAlpha(yes?1f:.38f); }
    private LinearLayout panel() { LinearLayout p=column();p.setPadding(dp(20),dp(20),dp(20),dp(20));p.setBackground(shape(SURFACE,16,false)); return p; }
    private ScrollView scroll(LinearLayout child) { ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setClipToPadding(false);s.addView(child,new ScrollView.LayoutParams(-1,-2));return s; }
    private LinearLayout pageColumn() { LinearLayout p=column();int pad=dp(getResources().getConfiguration().screenWidthDp<360?20:24);p.setPadding(pad,dp(20),pad,dp(28)); return p; }
    private TextView message(LinearLayout target) { TextView t=text("",14,RED);t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);t.setPadding(0,dp(12),0,dp(12));t.setVisibility(View.GONE);target.addView(t,new LinearLayout.LayoutParams(-1,-2));return t; }
    private void showMessage(TextView t,String msg,boolean error) { t.setText(msg);t.setTextColor(error?RED:SECONDARY);t.setVisibility(msg.isEmpty()?View.GONE:View.VISIBLE); }
    private void heading(LinearLayout p,String title,String subtitle,Button refresh) {
        LinearLayout r=row();LinearLayout names=column();addText(names,title,24,TEXT);gap(names,6);addText(names,subtitle,12,SECONDARY);weight(r,names);if(refresh!=null)r.addView(refresh,new LinearLayout.LayoutParams(dp(64),-2));p.addView(r);gap(p,24);
    }
    private void buildShell() {
        root=column();root.setBackgroundColor(BG); root.setFitsSystemWindows(false);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{ android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime()); root.setPadding(sys.left,sys.top,sys.right,Math.max(sys.bottom,ime.bottom)); return insets; });
        else root.setOnApplyWindowInsetsListener((v,i)->{root.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        chrome=row(); chrome.setPadding(dp(24),dp(12),dp(24),dp(12));
        Icon logo=new Icon(4,MINT);logo.setBackground(shape(0xFF17362F,8,false));chrome.addView(logo,new LinearLayout.LayoutParams(dp(32),dp(32)));
        hostChoice=text("LiteHelm · 主机 ▾",15,TEXT);hostChoice.setMinHeight(dp(48));hostChoice.setGravity(Gravity.CENTER_VERTICAL);hostChoice.setOnClickListener(v->chooseHost());LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.leftMargin=dp(10);chrome.addView(hostChoice,bp);
        connection=text("未连接",12,MUTED);chrome.addView(connection);root.addView(chrome,new LinearLayout.LayoutParams(-1,-2));
        View topLine=new View(this);topLine.setBackgroundColor(LINE);root.addView(topLine,new LinearLayout.LayoutParams(-1,dp(1)));
        FrameLayout center=new FrameLayout(this);content=new FrameLayout(this);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Math.min(dp(600),getResources().getDisplayMetrics().widthPixels),-1,Gravity.CENTER_HORIZONTAL);center.addView(content,cp);root.addView(center,new LinearLayout.LayoutParams(-1,0,1));
        View bottomLine=new View(this);bottomLine.setBackgroundColor(LINE);root.addView(bottomLine,new LinearLayout.LayoutParams(-1,dp(1)));
        nav=row();String[] titles={"概览","进程","服务","设置"};
        for(int i=0;i<4;i++) { final int index=i;LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(5),0,dp(5));item.setBackground(ripple(BG,0));navIcons[i]=new Icon(i,MUTED);item.addView(navIcons[i],new LinearLayout.LayoutParams(dp(44),dp(28)));navLabels[i]=text(titles[i],11,MUTED);gap(item,4);item.addView(navLabels[i]);item.setOnClickListener(v->switchPage(index,true));item.setContentDescription(titles[i]);item.setMinimumHeight(dp(64));nav.addView(item,new LinearLayout.LayoutParams(0,-2,1)); }
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));setContentView(root);root.requestApplyInsets();
        buildDashboard();buildProcesses();buildServices();buildSettings();
    }
    private void switchPage(int next,boolean loadNow) {
        if(next!=3 && config==null) { toast("请先连接你的服务器");next=3; }
        if(pages[page] instanceof ScrollView)scrollY[page]=((ScrollView)pages[page]).getScrollY();
        if(sheet!=null)sheet.dismiss();api.cancelRead();loadPending=false;
        generation++;handler.removeCallbacks(poll);resetRates();page=next;
        for(int i=0;i<4;i++) {pages[i].setVisibility(i==page?View.VISIBLE:View.GONE);int color=i==page?MINT:MUTED;navIcons[i].color=color;navIcons[i].setBackground(shape(i==page?0xFF17362F:BG,10,false));navIcons[i].invalidate();navLabels[i].setTextColor(color);nav.getChildAt(i).setSelected(i==page);}
        if(page==3)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(pages[page] instanceof ScrollView) {final int position=scrollY[page];final ScrollView current=(ScrollView)pages[page];current.post(()->current.scrollTo(0,position));}
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        if(page==3 && config!=null){LinearLayout title=(LinearLayout)settings.getChildAt(0);LinearLayout labels=(LinearLayout)title.getChildAt(0);((TextView)labels.getChildAt(0)).setText("设置");((TextView)labels.getChildAt(2)).setText("连接与主机控制");}
        if(page==0)updateDashboard(); if(page==3)updatePower();
        if(loadNow && foreground && config!=null)loadCurrent();
    }
    private void attachPage(int index,View v) {pages[index]=v;content.addView(v,new FrameLayout.LayoutParams(-1,-1));v.setVisibility(View.GONE);}
    private void status(boolean ok) {connected=ok;connection.setText(ok?"● 已连接":"● 连接失败");connection.setTextColor(ok?MINT:RED);updateControls();}
    private void updateControls() {
        if(dashRefresh!=null)enabled(dashRefresh,!busy && config!=null);if(processRefresh!=null)enabled(processRefresh,!busy && config!=null);if(serviceRefresh!=null)enabled(serviceRefresh,!busy && config!=null);
        if(saveButton!=null) {enabled(saveButton,!busy && !ApiClient.MUTATING.get());saveButton.setText(validating?"正在验证…":"保存并连接");}
        if(powerArea!=null && page==3)updatePower();
    }
    private interface Result {void accept(JSONObject data) throws Exception;}
    private void read(ApiClient.Config credentials,String path,boolean saving,Result success,java.util.function.Consumer<String> failure) {
        if(busy || ApiClient.MUTATING.get()) {loadPending=true;return;}busy=true; final int gen=generation; final int requestPage=page;updateControls();connection.setText("● 连接中");connection.setTextColor(WARN);
        final int requestSheet=sheetGeneration;final boolean inSheet=sheet!=null;final ApiClient.Config identity=config;
        ApiClient.IO.execute(()->{JSONObject value=null;String error=null;try {if(!RequestPolicy.current(foreground,generation,gen,config,identity,inSheet,sheetGeneration,requestSheet))throw new Exception("请求已取消");value=api.request(credentials,path,null,()->RequestPolicy.current(foreground,generation,gen,config,identity,inSheet,sheetGeneration,requestSheet));validatePayload(path,value);}catch(Exception e){error=e.getMessage()==null?"请求失败":ApiClient.redact(e.getMessage(),credentials);}final JSONObject result=value;final String problem=error;
            handler.post(()->{busy=false;validating=false;if(destroyed)return;
                boolean current=requestPage==page && RequestPolicy.current(foreground,generation,gen,config,identity,inSheet,sheetGeneration,requestSheet);
                if(saving)for(EditText field:new EditText[]{nameInput,addressInput,tokenInput})enabled(field,true);
                // Commit the small encrypted preference and active config in one main-loop turn;
                // rotation/navigation cannot interleave a persisted connection with the old owner.
                if(current) {if(problem==null) {try {status(true);success.accept(result);}catch(Exception e){status(false);failure.accept(saving?"无法保存连接，请重试":"服务器数据格式异常，请检查 API 版本");}} else {status(false);failure.accept(problem);}}
                else if(saving)showMessage(settingsError,"连接验证已取消，草稿已保留",false);
                if(!current && !saving && foreground){connection.setText(connected?"● 已连接":"● 未连接");connection.setTextColor(connected?MINT:MUTED);}
                updateControls();if(loadPending && foreground){loadPending=false;loadCurrent();}else schedulePoll();
            });
        });
    }
    private static void validatePayload(String path,JSONObject j) throws Exception {
        if(path.equals("/api/v1/system")) {if(j.optString("hostname").isEmpty() || j.optJSONObject("capabilities")==null || !j.has("timestamp"))throw new Exception("服务器主机信息格式不完整");}
        else if(path.startsWith("/api/v1/processes?"))j.getJSONArray("processes");
        else if(path.equals("/api/v1/services")) {j.getJSONArray("services");j.getBoolean("supported");}
        else if(path.endsWith("/logs"))j.getString("logs");
    }
    private void schedulePoll() {handler.removeCallbacks(poll);if(RequestPolicy.poll(foreground,page,config!=null,busy,ApiClient.MUTATING.get(),sheet!=null))handler.postDelayed(poll,5000);}
    private void loadCurrent() {
        if(!foreground || config==null || sheet!=null)return;
        if(busy || ApiClient.MUTATING.get()){loadPending=true;return;}
        handler.removeCallbacks(poll);
        if(page==0 || page==3 || system==null || !systemFresh) {
            final int intended=page;
            if(page==0 && system==null)showMessage(dashMessage,"正在获取主机信息…",false);
            read(config,"/api/v1/system",false,j->{system=j;systemFresh=true;networkSample=intended==0;lastSystemSuccess=System.currentTimeMillis();systemError="";updateDashboard();updatePower();if(intended==1 || intended==2)loadCurrent();},e->{systemFresh=false;systemError=e;resetRates();updateDashboard();if(intended==3)showMessage(settingsError,e,true);else if(intended==1)showMessage(processMessage,e,true);else if(intended==2)showMessage(serviceMessage,e,true);updatePower();});
        } else if(page==1)loadProcesses();else if(page==2)loadServices();
    }
    private boolean capability(String name) {JSONObject caps=system==null?null:system.optJSONObject("capabilities");return caps!=null && caps.optBoolean(name,false) && systemFresh && connected;}
    private void resetRates(){rxBase=txBase=timeBase=-1;networkSample=false;if(rx!=null) {rx.setText("—");tx.setText("—");}}

    private void buildDashboard() {
        dashboard=pageColumn();dashRefresh=compact("刷新",()->loadCurrent());
        LinearLayout top=row();LinearLayout names=column();names.addView(label("当前主机"));gap(names,6);hostname=text("等待连接",24,TEXT);names.addView(hostname);gap(names,6);os=text("轻量服务器管家",12,SECONDARY);names.addView(os);weight(top,names);top.addView(dashRefresh,new LinearLayout.LayoutParams(dp(64),-2));dashboard.addView(top);
        dashMessage=message(dashboard);
        dashboard.addView(compact("切换主机",()->chooseHost()),new LinearLayout.LayoutParams(-1,-2));
        dashboard.addView(compact("主机工具",()->toolMenu()),new LinearLayout.LayoutParams(-1,-2));
        favoritesArea=column();dashboard.addView(favoritesArea);renderFavorites();
        LinearLayout help=row();help.addView(compact("重试",()->loadCurrent()),new LinearLayout.LayoutParams(0,-2,1));Button check=compact("检查连接",()->switchPage(3,true));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.leftMargin=dp(8);help.addView(check,hp);help.setTag("errorHelp");dashboard.addView(help);help.setVisibility(View.GONE);
        dashboardData=column();gap(dashboardData,24);LinearLayout up=row();weight(up,label("连续运行"));uptime=text("—",14,TEXT);up.addView(uptime);dashboardData.addView(up);gap(dashboardData,24);
        LinearLayout resource=panel();TextView resourceTitle=label("资源");resourceTitle.setTag("cores");resource.addView(resourceTitle);gap(resource,14);
        LinearLayout cpuRow=column();LinearLayout cpuCol=column();addText(cpuCol,"CPU",12,SECONDARY);gap(cpuCol,4);cpu=text("—",48,MINT);cpuCol.addView(cpu);cpuRow.addView(cpuCol);
        load=text("负载 —\n1 / 5 / 15 分钟",12,SECONDARY);gap(cpuRow,10);cpuRow.addView(load);resource.addView(cpuRow);gap(resource,12);cpuMeter=new Meter(MINT,"CPU");resource.addView(cpuMeter,new LinearLayout.LayoutParams(-1,dp(3)));divider(resource);
        LinearLayout pair=largeType()?column():row();LinearLayout mem=column();mem.addView(label("内存"));gap(mem,8);memory=text("—",28,CYAN);mem.addView(memory);gap(mem,8);memoryBytes=text("暂不可用",12,SECONDARY);mem.addView(memoryBytes);gap(mem,12);memMeter=new Meter(CYAN,"内存");mem.addView(memMeter,new LinearLayout.LayoutParams(-1,dp(3)));
        LinearLayout disc=column();disc.addView(label("磁盘"));gap(disc,8);disk=text("—",28,MINT);disc.addView(disk);gap(disc,8);diskBytes=text("暂不可用",12,SECONDARY);disc.addView(diskBytes);gap(disc,12);diskMeter=new Meter(MINT,"磁盘");disc.addView(diskMeter,new LinearLayout.LayoutParams(-1,dp(3)));
        if(largeType()){pair.addView(mem);gap(pair,24);pair.addView(disc);}else {weight(pair,mem);View spacer=new View(this);pair.addView(spacer,new LinearLayout.LayoutParams(dp(20),1));weight(pair,disc);}resource.addView(pair);divider(resource);swap=text("Swap   —",12,SECONDARY);resource.addView(swap);dashboardData.addView(resource);gap(dashboardData,28);
        LinearLayout networkHeading=row();weight(networkHeading,text("网络速率",15,TEXT));networkHeading.addView(text("当前采样",11,MUTED));dashboardData.addView(networkHeading);gap(dashboardData,20);
        LinearLayout network=largeType()?column():row();LinearLayout receive=column();addText(receive,"↓ 接收",12,SECONDARY);gap(receive,8);rx=text("—",28,CYAN);receive.addView(rx);LinearLayout send=column();addText(send,"↑ 发送",12,SECONDARY);gap(send,8);tx=text("—",28,TEXT);send.addView(tx);
        if(largeType()){network.addView(receive);gap(network,20);network.addView(send);}else{weight(network,receive);View line=new View(this);line.setBackgroundColor(LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(1),dp(60));lp.leftMargin=dp(16);lp.rightMargin=dp(20);network.addView(line,lp);weight(network,send);}dashboardData.addView(network);gap(dashboardData,14);counters=text("等待下一次采样",11,MUTED);dashboardData.addView(counters);gap(dashboardData,28);addText(dashboardData,"●  前台每 5 秒刷新",12,SECONDARY);gap(dashboardData,8);dashStamp=text("尚未更新",11,MUTED);dashboardData.addView(dashStamp);dashboard.addView(dashboardData);attachPage(0,scroll(dashboard));updateDashboard();
    }
    private void updateDashboard() {
        if(hostname==null)return;dashboardData.setVisibility(system==null?View.GONE:View.VISIBLE);
        View help=dashboard.findViewWithTag("errorHelp");help.setVisibility(systemError.isEmpty()?View.GONE:View.VISIBLE);
        if(system==null){hostname.setText(config==null?"连接你的服务器":"等待主机信息");showMessage(dashMessage,systemError.isEmpty()?"正在获取主机信息…":"无法连接服务器\n"+systemError,true);return;}
        showMessage(dashMessage,systemError.isEmpty()?"":systemError+"\n数据已过期 · 上次尝试 "+clock(System.currentTimeMillis()),true);
        hostname.setText(system.optString("hostname","—"));os.setText(system.optString("os","—")+" · "+system.optString("arch","—"));uptime.setText(duration(number(system,"uptime_seconds")));
        TextView cores=dashboard.findViewWithTag("cores");cores.setText("资源 / "+(system.has("cpu_cores")?system.optInt("cpu_cores")+" 核":"核数暂不可用"));
        double cpuValue=number(system,"cpu_percent");cpu.setText(percent(cpuValue));cpuMeter.set(cpuValue);
        JSONArray loads=system.optJSONArray("load");load.setText("负载 "+(loads!=null&&loads.length()>=3?decimal(loads.optDouble(0,Double.NaN))+" / "+decimal(loads.optDouble(1,Double.NaN))+" / "+decimal(loads.optDouble(2,Double.NaN)):"—")+"\n1 / 5 / 15 分钟");
        resource(system.optJSONObject("memory"),memory,memoryBytes,memMeter);resource(system.optJSONObject("disk"),disk,diskBytes,diskMeter);
        JSONObject sw=system.optJSONObject("swap");double st=number(sw,"total");swap.setText("Swap   "+(st==0?"未启用":bytes(number(sw,"used"))+" / "+bytes(st)));
        if(networkSample){
            JSONObject net=system.optJSONObject("network");long timestamp=system.optLong("timestamp",-1);long nr=net==null?-1:net.optLong("rx_bytes",-1), nt=net==null?-1:net.optLong("tx_bytes",-1);
            boolean valid=timestamp>timeBase && timeBase>=0 && nr>=rxBase && nt>=txBase && rxBase>=0 && txBase>=0 && systemFresh;
            if(valid){rx.setText(bytes((nr-rxBase)/(double)(timestamp-timeBase))+"/s");tx.setText(bytes((nt-txBase)/(double)(timestamp-timeBase))+"/s");}else {rx.setText("—");tx.setText("—");}
            counters.setText((valid?"":"等待下一次采样 · ")+"累计接收 "+bytes(nr)+" / 发送 "+bytes(nt));
            if(systemFresh && page==0 && foreground && timestamp>0 && nr>=0 && nt>=0){rxBase=nr;txBase=nt;timeBase=timestamp;}else resetRates();
            networkSample=false;
        }
        dashStamp.setText((systemFresh?"更新于 ":"数据已过期 · 上次成功 ")+clock(lastSystemSuccess));dashStamp.setTextColor(systemFresh?MUTED:WARN);dashboardData.setAlpha(systemFresh?1f:.58f);
    }
    private void resource(JSONObject source,TextView value,TextView detail,Meter meter){double total=number(source,"total"),used=number(source,"used");double ratio=total>0&&used>=0?100*used/total:Double.NaN;value.setText(percent(ratio));detail.setText(Double.isFinite(ratio)?bytes(used)+" / "+bytes(total):"暂不可用");meter.set(ratio);}
    private static double number(JSONObject j,String key){return j==null?Double.NaN:j.optDouble(key,Double.NaN);}
    private static String decimal(double x){return Double.isFinite(x)?String.format(Locale.CHINA,"%.2f",x):"—";}
    private static String percent(double x){return Double.isFinite(x)&&x>=0?String.format(Locale.CHINA,"%.1f\u00a0%%",x):"—";}
    private static String bytes(double x){if(!Double.isFinite(x)||x<0)return "—";String[] units={"B","KiB","MiB","GiB","TiB"};int index=0;while(x>=1024 && index<4){x/=1024;index++;}return String.format(Locale.CHINA,index==0?"%.0f\u00a0%s":"%.1f\u00a0%s",x,units[index]);}
    private static String clock(long t){return t<=0?"—":new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(t));}
    private static String duration(double seconds){if(!Double.isFinite(seconds)||seconds<0)return "—";long m=(long)seconds/60,h=m/60,d=h/24;return d>0?d+"天 "+h%24+"小时":h>0?h+"小时 "+m%60+"分":m>0?m+"分钟":"不足 1 分钟";}

    private void buildProcesses() {
        LinearLayout outer=column(), header=pageColumn();processHeader=header;header.setPadding(0,dp(20),0,dp(8));processRefresh=compact("刷新",()->{processList.setSelection(0);loadCurrent();});heading(header,"进程","内存占用前 100 项",processRefresh);((LinearLayout)((LinearLayout)header.getChildAt(0)).getChildAt(0)).getChildAt(2).setTag("processSubtitle");
        LinearLayout sort=row();Button mem=compact("内存",()->setSort(false)), cp=compact("CPU 平均",()->setSort(true));sort.addView(mem,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);sp.leftMargin=dp(8);sort.addView(cp,sp);mem.setTag("sortMem");cp.setTag("sortCpu");header.addView(sort);gap(header,12);
        processSearch=search("搜索进程名称、PID 或用户",processQuery,q->{processQuery=q;filterProcesses();});header.addView(searchRow(processSearch));gap(header,12);addText(header,"CPU 为进程运行期间平均值",12,MUTED);processMessage=message(header);
        processList=new ListView(this);processList.setDivider(new android.graphics.drawable.ColorDrawable(LINE));processList.setDividerHeight(dp(1));processList.setCacheColorHint(BG);processList.setClipToPadding(false);processList.setPadding(dp(24),0,dp(24),dp(20));processList.addHeaderView(header,null,false);processAdapter=new DataAdapter(false);processList.setAdapter(processAdapter);processList.setOnItemClickListener((p,v,pos,id)->{int index=pos-processList.getHeaderViewsCount();if(index>=0&&index<processAdapter.items.size())processSheet(processAdapter.items.get(index));});outer.addView(processList,new LinearLayout.LayoutParams(-1,0,1));attachPage(1,outer);paintSort();
    }
    private EditText search(String hint,String value,java.util.function.Consumer<String> change){EditText input=input(hint,false);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setText(value);watch(input,()->change.accept(input.getText().toString()));return input;}
    private View searchRow(EditText input){LinearLayout r=row();weight(r,input);Button clear=compact("清除",()->input.setText(""));clear.setContentDescription("清除搜索");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(64),-2);p.leftMargin=dp(8);r.addView(clear,p);return r;}
    private void setSort(boolean cpu){if(busy)return;processCpu=cpu;processesFresh=false;processes.clear();filterProcesses();paintSort();if(foreground&&page==1)loadCurrent();}
    private void paintSort(){View h=processHeader;if(h==null)return;Button a=h.findViewWithTag("sortMem"),b=h.findViewWithTag("sortCpu");a.setTextColor(processCpu?SECONDARY:MINT);b.setTextColor(processCpu?MINT:SECONDARY);TextView subtitle=h.findViewWithTag("processSubtitle");if(subtitle!=null)subtitle.setText(processCpu?"CPU 平均占用前 100 项":"内存占用前 100 项");}
    private void loadProcesses(){showMessage(processMessage,"正在获取进程…",false);read(config,"/api/v1/processes?limit=100"+(processCpu?"&sort=cpu":""),false,j->{processes.clear();JSONArray list=j.getJSONArray("processes");for(int i=0;i<Math.min(100,list.length());i++)processes.add(list.getJSONObject(i));processesFresh=true;filterProcesses();},e->{processesFresh=false;showMessage(processMessage,e+(processes.isEmpty()?"":" · 数据已过期，请刷新后操作"),true);});}
    private void filterProcesses(){if(processAdapter==null)return;ArrayList<JSONObject> result=new ArrayList<>();String query=processQuery.toLowerCase(Locale.ROOT).trim();for(JSONObject p:processes)if((p.optString("name")+" "+p.optString("pid")+" "+p.optString("user")).toLowerCase(Locale.ROOT).contains(query))result.add(p);processAdapter.set(result);if(processesFresh)showMessage(processMessage,result.isEmpty()?(query.isEmpty()?"暂无进程":"没有匹配的进程 · 清空搜索可查看全部"):"",false);}
    private String processBlock(JSONObject p){if(!processesFresh||!systemFresh||!connected)return "列表或连接已过期，请刷新后操作";if(!capability("process_signal"))return "服务器未开放进程控制权限";if(p.optInt("pid",0)<=1)return "此进程受保护";if(p.isNull("start_time")||p.optString("start_time").isEmpty())return "缺少启动标识，请刷新列表";return "";}
    private void processSheet(JSONObject p){
        LinearLayout box=sheetBox(p.optString("name","进程"));addText(box,"PID "+p.optInt("pid")+" · "+p.optString("user")+" · "+p.optString("state"),14,SECONDARY);gap(box,8);String start=p.optString("start_time");addText(box,"启动标识 "+(start.length()>24?start.substring(0,24)+"…":start),12,MUTED);gap(box,24);
        addText(box,"主机 · "+hostLabel(),14,MINT);gap(box,12);
        RadioGroup choices=new RadioGroup(this);int termId=View.generateViewId(),killId=View.generateViewId();RadioButton term=radio("正常终止 · TERM\n请求进程正常退出",termId),kill=radio("强制结束 · KILL\n立即结束，可能丢失未保存的数据",killId);choices.addView(term);choices.addView(kill);choices.check(termId);box.addView(choices);gap(box,16);TextView error=message(box);String blocked=processBlock(p);showMessage(error,blocked,true);
        Button send=button("发送 TERM",true,false,()->{String reason=processBlock(p);if(!reason.isEmpty()){showMessage(error,reason,true);return;}String signal=choices.getCheckedRadioButtonId()==killId?"KILL":"TERM";JSONObject body=json("signal",signal,"start_time",p.optString("start_time"));mutate("/api/v1/processes/"+p.optInt("pid")+"/signal",body,"已发送 "+signal+"，正在刷新进程列表",()->{processesFresh=false;if(page==1)loadCurrent();},false);});choices.setOnCheckedChangeListener((g,id)->{boolean danger=id==killId;send.setText(danger?"发送 KILL":"发送 TERM");send.setBackground(ripple(danger?RED:MINT,12));});box.addView(send,new LinearLayout.LayoutParams(-1,-2));enabled(send,blocked.isEmpty()&&!busy&&!ApiClient.MUTATING.get());showSheet(box);
    }
    private RadioButton radio(String text,int id){RadioButton b=new RadioButton(this);b.setId(id);b.setText(text);b.setTextColor(TEXT);b.setTextSize(14);b.setMinHeight(dp(64));b.setPadding(0,dp(8),0,dp(8));b.setButtonTintList(android.content.res.ColorStateList.valueOf(MINT));return b;}

    private void buildServices(){
        LinearLayout outer=column(),header=pageColumn();serviceHeader=header;header.setPadding(0,dp(20),0,dp(8));serviceRefresh=compact("刷新",()->{serviceList.setSelection(0);loadCurrent();});heading(header,"服务","systemd 服务管理",serviceRefresh);serviceSearch=search("搜索服务名称或描述",serviceQuery,q->{serviceQuery=q;filterServices();});header.addView(searchRow(serviceSearch));gap(header,12);LinearLayout filters=row();String[] labels={"全部","运行","失败","未运行"};for(int i=0;i<4;i++){final int index=i;Button b=compact(labels[i],()->{serviceFilter=index;paintFilters();filterServices();});b.setTextSize(12);b.setPadding(dp(2),dp(10),dp(2),dp(10));b.setTag("filter"+i);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)fp.leftMargin=dp(4);filters.addView(b,fp);}header.addView(filters);serviceMessage=message(header);
        header.addView(compact("主机工具",()->toolMenu()),new LinearLayout.LayoutParams(-1,-2));
        serviceList=new ListView(this);serviceList.setDivider(new android.graphics.drawable.ColorDrawable(LINE));serviceList.setDividerHeight(dp(1));serviceList.setPadding(dp(24),0,dp(24),dp(20));serviceList.setClipToPadding(false);serviceList.addHeaderView(header,null,false);serviceAdapter=new DataAdapter(true);serviceList.setAdapter(serviceAdapter);serviceList.setOnItemClickListener((p,v,pos,id)->{int index=pos-serviceList.getHeaderViewsCount();if(index>=0&&index<serviceAdapter.items.size())serviceSheet(serviceAdapter.items.get(index));});outer.addView(serviceList,new LinearLayout.LayoutParams(-1,0,1));attachPage(2,outer);paintFilters();
    }
    private void paintFilters(){if(serviceHeader==null)return;for(int i=0;i<4;i++){Button b=serviceHeader.findViewWithTag("filter"+i);b.setTextColor(i==serviceFilter?MINT:SECONDARY);}}
    private boolean supported=true;
    private void loadServices(){showMessage(serviceMessage,"正在获取服务…",false);read(config,"/api/v1/services",false,j->{supported=j.getBoolean("supported");services.clear();JSONArray list=j.getJSONArray("services");for(int i=0;i<list.length();i++)services.add(list.getJSONObject(i));servicesFresh=true;filterServices();},e->{servicesFresh=false;showMessage(serviceMessage,e+(services.isEmpty()?"":" · 数据已过期，请刷新后操作"),true);});}
    private void filterServices(){if(serviceAdapter==null)return;ArrayList<JSONObject> result=new ArrayList<>();String q=serviceQuery.trim().toLowerCase(Locale.ROOT);for(JSONObject s:services){String active=s.optString("active_state");boolean state=serviceFilter==0 || serviceFilter==1&&active.equals("active") || serviceFilter==2&&active.equals("failed") || serviceFilter==3&&active.equals("inactive");if(state && (s.optString("name")+" "+s.optString("description")).toLowerCase(Locale.ROOT).contains(q))result.add(s);}serviceAdapter.set(result);if(servicesFresh)showMessage(serviceMessage,!supported?"此主机不支持 systemd 服务管理":result.isEmpty()?(services.isEmpty()?"暂无服务":"没有匹配的服务 · 清空搜索或选择全部"):"systemd · "+services.size()+" 个服务",false);}
    private String serviceStatus(JSONObject s){String active=s.optString("active_state","unknown"),sub=s.optString("sub_state","unknown");if(active.equals("active"))return sub.equals("running")?"运行中":"已激活 · "+sub;if(active.equals("inactive"))return "未运行 · "+sub;if(active.equals("failed"))return "失败 · "+sub;return active+" · "+sub;}
    private int serviceColor(JSONObject s){String a=s.optString("active_state");return a.equals("active")?MINT:a.equals("failed")?RED:a.equals("inactive")?SECONDARY:WARN;}
    private void serviceSheet(JSONObject s){
        LinearLayout box=sheetBox(s.optString("name"));addText(box,s.optString("description"),14,SECONDARY);gap(box,12);addText(box,serviceStatus(s),14,serviceColor(s));gap(box,8);addText(box,"load_state  "+s.optString("load_state")+"\nactive_state  "+s.optString("active_state")+"\nsub_state  "+s.optString("sub_state"),12,MUTED);gap(box,24);
        addText(box,"主机 · "+hostLabel(),14,MINT);gap(box,12);
        Button favorite=compact(isFavorite(s.optString("name"))?"取消收藏":"收藏服务",()->{toggleFavorite(s.optString("name"));serviceSheet(s);});box.addView(favorite,new LinearLayout.LayoutParams(-1,-2));gap(box,12);
        String active=s.optString("active_state");boolean allowed=capability("service_control")&&servicesFresh&&!busy&&!ApiClient.MUTATING.get();
        String[] acts={"start","restart","stop"},labels={"启动","重启","停止"};
        for(int i=0;i<3;i++){final String action=acts[i],label=labels[i];Button b=button(label,false,action.equals("stop"),()->{if(!capability("service_control")||!servicesFresh){toast("连接或服务状态已过期，请刷新");return;}confirmService(s,action,label);});enabled(b,allowed&&(i==0?(active.equals("inactive")||active.equals("failed")):active.equals("active")));box.addView(b,new LinearLayout.LayoutParams(-1,-2));gap(box,8);}
        if(!allowed)addText(box,"控制权限未开放或数据已过期，请刷新连接",12,WARN);else if(!active.equals("active")&&!active.equals("inactive")&&!active.equals("failed"))addText(box,"状态切换中，请刷新",12,WARN);
        gap(box,12);Button logs=compact("查看日志",()->{if(busy){toast("正在处理请求，请稍候");return;}if(sheet!=null)sheet.dismiss();showLogs(s.optString("name"));});box.addView(logs,new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private String servicePath(String name){return "/api/v1/services/"+encodeSegment(name);}
    private static String encodeSegment(String name){try{return URLEncoder.encode(name,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    private void confirmService(JSONObject s,String action,String label){LinearLayout box=sheetBox(label+"服务？");addText(box,"主机 · "+hostLabel(),14,MINT);gap(box,12);addText(box,s.optString("name"),16,TEXT);gap(box,12);addText(box,action.equals("start")?"将请求启动此服务。":"将中断该服务当前连接。请确认后继续。",14,SECONDARY);gap(box,24);Button send=button("确认"+label,true,!action.equals("start"),()->serviceAction(s,action));box.addView(send,new LinearLayout.LayoutParams(-1,-2));showSheet(box);}
    private void serviceAction(JSONObject s,String action){if(!capability("service_control")||!servicesFresh){toast("连接或服务状态已过期，请刷新");return;}mutate(servicePath(s.optString("name"))+"/action",json("action",action),"操作完成，正在更新服务状态",()->{servicesFresh=false;if(page==2)loadCurrent();else if(page==0)openFavorite(s.optString("name"));},false);}
    private void showLogs(String name){
        LinearLayout box=sheetBox(name);addText(box,"主机 · "+hostLabel()+"\n最近最多 80 行 · 不自动刷新",12,SECONDARY);gap(box,12);
        TextView info=message(box),count=label("尚未加载");TextView logs=text("正在获取日志…",12,TEXT);logs.setTypeface(Typeface.MONOSPACE);logs.setTextIsSelectable(true);
        final String[] original={""},visible={""};final boolean[] loaded={false};
        EditText query=search("本地搜索日志", "",q->{});
        Runnable filter=()->{String q=query.getText().toString();LogFilter.Result r=LogFilter.filter(original[0],q);visible[0]=r.visible;count.setText("匹配 "+r.matches+" 处 · 显示 "+r.lines+" 行");logs.setText(visible[0].isEmpty()?"暂无匹配日志":visible[0]);};watch(query,filter);
        Runnable fetch=()->{if(busy||ApiClient.MUTATING.get()){toast("正在处理请求，请稍候");return;}showMessage(info,"正在加载…",false);read(config,servicePath(name)+"/logs",false,j->{original[0]=j.getString("logs");loaded[0]=true;filter.run();showMessage(info,j.optBoolean("truncated")?"内容已截断（最多 64 KiB）":"",false);},e->showMessage(info,e,true));};
        box.addView(compact("刷新日志",fetch),new LinearLayout.LayoutParams(-1,-2));gap(box,8);box.addView(searchRow(query));gap(box,8);box.addView(count);gap(box,12);
        addText(box,"复制会把日志放入系统剪贴板，可能被其他应用读取。",12,WARN);
        box.addView(compact("复制可见日志",()->{if(loaded[0])copyLogs(name,visible[0]);}),new LinearLayout.LayoutParams(-1,-2));
        box.addView(compact("复制全部原始日志",()->{if(loaded[0])copyLogs(name,original[0]);}),new LinearLayout.LayoutParams(-1,-2));gap(box,12);box.addView(logs,new LinearLayout.LayoutParams(-1,-2));showSheet(box);expandSheet();fetch.run();
    }
    private void copyLogs(String name,String value){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(name,value));toast("已复制到系统剪贴板");}

    private final class DataAdapter extends BaseAdapter {
        final boolean service;final ArrayList<JSONObject> items=new ArrayList<>();DataAdapter(boolean service){this.service=service;}
        void set(ArrayList<JSONObject> rows){items.clear();items.addAll(rows);notifyDataSetChanged();}
        public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return service?p:items.get(p).optLong("pid",p);}
        public View getView(int position,View convert,android.view.ViewGroup parent){
            Holder h;if(convert==null){h=new Holder();LinearLayout r=largeType()?column():row();r.setMinimumHeight(dp(service?84:80));r.setPadding(0,dp(14),0,dp(14));r.setBackground(ripple(BG,0));LinearLayout names=column();h.title=text("",15,TEXT);h.title.setSingleLine();h.title.setEllipsize(TextUtils.TruncateAt.END);h.detail=text("",12,SECONDARY);h.detail.setMaxLines(2);h.detail.setEllipsize(TextUtils.TruncateAt.END);names.addView(h.title);gap(names,6);names.addView(h.detail);if(largeType())r.addView(names,new LinearLayout.LayoutParams(-1,-2));else weight(r,names);h.value=text("",service?12:16,service?MINT:TEXT);h.value.setGravity(largeType()?Gravity.LEFT:Gravity.RIGHT);h.value.setMaxLines(3);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(largeType()?-1:dp(service?110:100),-2);if(!largeType())vp.leftMargin=dp(12);r.addView(h.value,vp);r.setTag(h);convert=r;}else h=(Holder)convert.getTag();
            JSONObject item=items.get(position);h.title.setText(item.optString("name","—"));
            if(service){h.detail.setText(item.optString("description"));h.value.setText(serviceStatus(item)+"  ›");h.value.setTextColor(serviceColor(item));}else{h.detail.setText("PID "+item.optInt("pid")+" · "+item.optString("user")+" · "+item.optString("state"));h.value.setText(processCpu?percent(number(item,"cpu_percent"))+"\n"+bytes(number(item,"memory_bytes")):bytes(number(item,"memory_bytes"))+"\n"+percent(number(item,"cpu_percent")));}
            convert.setContentDescription(item.optString("name")+"，"+h.detail.getText()+"，"+h.value.getText()+"，查看操作");return convert;
        }
    }
    private static final class Holder {TextView title,detail,value;}

    private EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(15);e.setHint(hint);e.setSingleLine(true);e.setMinHeight(dp(52));e.setPadding(dp(12),dp(12),dp(12),dp(12));e.setBackground(shape(RAISED,12,false));e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);e.setInputType(secret?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);if(secret)e.setTransformationMethod(PasswordTransformationMethod.getInstance());return e;}
    private void watch(EditText e,Runnable changed){e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.run();}public void afterTextChanged(Editable s){}});}
    private void field(LinearLayout box,String title,EditText e){box.addView(label(title));gap(box,8);box.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(box,20);}
    private void secretField(LinearLayout box,String title,EditText e){box.addView(label(title));gap(box,8);LinearLayout r=row();weight(r,e);Button toggle=compact("显示",()->{boolean hidden=e.getTransformationMethod()!=null;e.setTransformationMethod(hidden?null:PasswordTransformationMethod.getInstance());e.setSelection(e.length());Button b=(Button)((LinearLayout)e.getParent()).getChildAt(1);b.setText(hidden?"隐藏":"显示");b.setContentDescription((hidden?"隐藏":"显示")+title);});toggle.setContentDescription("显示"+title);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(64),-2);p.leftMargin=dp(8);r.addView(toggle,p);box.addView(r);gap(box,20);}
    private void buildSettings(){
        settings=pageColumn();heading(settings,config==null?"连接你的服务器":"设置",config==null?"LiteHelm · 轻量服务器管家":"连接与主机控制",null);LinearLayout form=panel();
        settings.addView(compact("管理与切换主机",()->chooseHost()),new LinearLayout.LayoutParams(-1,-2));gap(settings,12);
        nameInput=input("例如：生产主机",false);addressInput=input("https://node.example.com",false);tokenInput=input("服务器访问令牌",true);
        nameInput.setText(state.draft?state.name:book.active()==null?"":book.active().name);
        addressInput.setText(state.draft?state.address:config==null?"":config.origin);tokenInput.setText(state.draft?state.token:config==null?"":config.token);
        field(form,"主机名称",nameInput);field(form,"服务器地址",addressInput);secretField(form,"访问令牌",tokenInput);
        settingsError=message(form);saveButton=button("保存并连接",true,false,()->saveConnection());form.addView(saveButton,new LinearLayout.LayoutParams(-1,-2));settings.addView(form);gap(settings,28);powerArea=column();settings.addView(powerArea);gap(settings,28);TextView footer=text("LiteHelm · 轻量服务器管家",11,MUTED);footer.setGravity(Gravity.CENTER);settings.addView(footer);
        for(EditText e:new EditText[]{nameInput,addressInput,tokenInput})watch(e,()->{dirty=true;captureDraft();if(!busy)showMessage(settingsError,"",false);});attachPage(3,scroll(settings));updatePower();updateHostLabel();
    }
    private void captureDraft(){if(addressInput==null)return;state.draft=true;state.address=addressInput.getText().toString();state.token=tokenInput.getText().toString();state.name=nameInput.getText().toString();state.editingId=editingId;}
    private void saveConnection(){
        if(busy||ApiClient.MUTATING.get())return;final ApiClient.Config candidate;
        try{candidate=ApiClient.validate(addressInput.getText().toString(),tokenInput.getText().toString());}catch(Exception e){showMessage(settingsError,e.getMessage(),true);return;}
        final String profileName=nameInput.getText().toString().trim(),profileId=editingId.isEmpty()?ProfileBook.newId():editingId;
        if(profileName.isEmpty()||profileName.length()>80){showMessage(settingsError,"请填写 1–80 字主机名称",true);return;}
        captureDraft();validating=true;showMessage(settingsError,"正在验证服务器…",false);
        // Prevent editing a credential draft while its exact values are being verified.
        for(EditText e:new EditText[]{nameInput,addressInput,tokenInput})enabled(e,false);
        read(candidate,"/api/v1/system",true,j->{
            // This callback runs only in the validated current main-loop turn; save and activation are atomic to navigation.
            ProfileBook next=book.copy();ProfileBook.Profile previous=next.find(profileId);
            ProfileBook.Profile profile=new ProfileBook.Profile(profileId,profileName,candidate);
            if(previous!=null){profile.favorites.addAll(previous.favorites);next.profiles.set(next.profiles.indexOf(previous),profile);}else next.profiles.add(profile);
            next.activeId=profileId;vault.save(next);book=next;state.book=book;editingId=profileId;config=candidate;state.config=config;
            clearHostData();system=j;systemFresh=true;connected=true;systemError="";lastSystemSuccess=System.currentTimeMillis();dirty=false;
            addressInput.setText(candidate.origin);updateHostLabel();renderFavorites();updateDashboard();updatePower();toast("连接成功");switchPage(0,false);loadCurrent();
        },e->{for(EditText field:new EditText[]{nameInput,addressInput,tokenInput})enabled(field,true);showMessage(settingsError,e,true);});
    }
    private void updatePower(){if(powerArea==null)return;powerArea.removeAllViews();addText(powerArea,"主机控制",15,TEXT);gap(powerArea,12);if(config==null){addText(powerArea,"连接服务器后可查看控制权限",12,MUTED);return;}addText(powerArea,system==null?"尚未获取主机信息":system.optString("hostname"),14,SECONDARY);gap(powerArea,16);JSONObject caps=system==null?null:system.optJSONObject("capabilities");if(caps==null||!caps.optBoolean("power",false)){addText(powerArea,"电源操作未启用",14,SECONDARY);gap(powerArea,6);addText(powerArea,"需在服务器配置中开启",12,MUTED);return;}Button reboot=button("重启服务器",false,false,()->powerConfirm("reboot")),off=button("关闭服务器",false,true,()->powerConfirm("poweroff"));powerArea.addView(reboot,new LinearLayout.LayoutParams(-1,-2));gap(powerArea,8);powerArea.addView(off,new LinearLayout.LayoutParams(-1,-2));boolean can=capability("power")&&!busy&&!ApiClient.MUTATING.get();enabled(reboot,can);enabled(off,can);if(!can){gap(powerArea,8);addText(powerArea,"请刷新连接后再操作",12,WARN);}}
    private void powerConfirm(String action){if(!capability("power")){toast("请先验证连接和主机权限");return;}String host=system.optString("hostname");boolean off=action.equals("poweroff");LinearLayout box=sheetBox(off?"关闭服务器？":"重启服务器？");addText(box,"主机 · "+hostLabel(),14,MINT);gap(box,12);addText(box,host,18,TEXT);gap(box,12);addText(box,off?"将断开当前连接。关机后需通过其他方式开机。":"将断开当前连接，请确认没有正在进行的重要任务。",14,SECONDARY);gap(box,20);EditText confirm=input("输入主机名以确认",false);confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);box.addView(confirm,new LinearLayout.LayoutParams(-1,-2));gap(box,20);Button send=button(off?"确认关机":"确认重启",true,true,()->{if(!capability("power")||!host.equals(confirm.getText().toString()))return;mutate("/api/v1/power",json("action",action,"confirm",host),off?"服务器已安排关机":"服务器已安排重启",()->{systemFresh=false;processesFresh=false;servicesFresh=false;systemError="电源操作已安排，连接将断开，请稍后重新检查";connected=false;connection.setText("● 等待主机");connection.setTextColor(WARN);updatePower();updateDashboard();},true);});enabled(send,false);watch(confirm,()->enabled(send,host.equals(confirm.getText().toString())&&!busy&&!ApiClient.MUTATING.get()));box.addView(send,new LinearLayout.LayoutParams(-1,-2));showSheet(box);}

    private String hostLabel(){ProfileBook.Profile p=book.active();return p==null?"未配置":p.name+" · "+p.config.origin;}
    private void updateHostLabel(){if(hostChoice!=null){hostChoice.setText("LiteHelm · "+(book.active()==null?"主机":book.active().name)+" ▾");hostChoice.setContentDescription("切换主机，当前 "+hostLabel());}}
    private boolean canChangeHost(){if(ApiClient.MUTATING.get()){toast("写入尚未完成，暂时不能切换或修改主机");return false;}return true;}
    private void clearHostData(){
        generation++;handler.removeCallbacks(poll);api.cancelRead();if(sheet!=null)sheet.dismiss();loadPending=false;
        system=null;lastSystemSuccess=0;systemFresh=false;processesFresh=false;servicesFresh=false;connected=false;supported=true;systemError="";resetRates();
        processes.clear();services.clear();processAdapter.set(new ArrayList<>());serviceAdapter.set(new ArrayList<>());
        showMessage(processMessage,"尚未加载当前主机进程",false);showMessage(serviceMessage,"尚未加载当前主机服务",false);
        processQuery="";serviceQuery="";processSearch.setText("");serviceSearch.setText("");serviceFilter=0;paintFilters();Arrays.fill(scrollY,0);
        connection.setText("● 未连接");connection.setTextColor(MUTED);updateDashboard();updatePower();
    }
    private void chooseHost(){
        if(!canChangeHost())return;
        generation++;api.cancelRead();loadPending=false;
        LinearLayout box=sheetBox("主机");addText(box,"仅连接当前主机，不自动查询其他主机。",12,SECONDARY);gap(box,16);
        for(ProfileBook.Profile p:book.profiles){
            addText(box,p.name+(p.id.equals(book.activeId)?" · 当前":""),16,p.id.equals(book.activeId)?MINT:TEXT);gap(box,6);addText(box,p.config.origin,12,SECONDARY);gap(box,8);
            box.addView(compact("连接此主机",()->activateProfile(p.id)),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
            box.addView(compact("编辑",()->editProfile(p)),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
            box.addView(button("删除主机",false,true,()->confirmDeleteProfile(p)),new LinearLayout.LayoutParams(-1,-2));divider(box);
        }
        box.addView(button("添加主机",true,false,()->editProfile(null)),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void editProfile(ProfileBook.Profile profile){
        if(!canChangeHost())return;if(sheet!=null)sheet.dismiss();switchPage(3,false);
        editingId=profile==null?"":profile.id;nameInput.setText(profile==null?"":profile.name);addressInput.setText(profile==null?"":profile.config.origin);tokenInput.setText(profile==null?"":profile.config.token);captureDraft();
        showMessage(settingsError,profile==null?"新增主机 · 验证成功后保存":"编辑 "+profile.name+" · 验证成功后替换",false);((ScrollView)pages[3]).smoothScrollTo(0,0);
    }
    private void confirmDeleteProfile(ProfileBook.Profile profile){
        if(!canChangeHost())return;LinearLayout box=sheetBox("删除已保存主机？");addText(box,profile.name+"\n"+profile.config.origin,16,TEXT);gap(box,16);addText(box,"此主机的本机凭据和收藏将一并删除。",14,SECONDARY);gap(box,24);
        box.addView(button("确认删除",true,true,()->{if(!canChangeHost())return;try{ProfileBook next=book.copy();ProfileBook.Profile removed=next.find(profile.id);if(removed==null)return;boolean active=next.activeId.equals(profile.id);next.profiles.remove(removed);if(active)next.activeId=next.profiles.isEmpty()?"":next.profiles.get(0).id;vault.save(next);book=next;state.book=book;
            if(active){config=book.active()==null?null:book.active().config;state.config=config;clearHostData();}else if(sheet!=null)sheet.dismiss();
            updateHostLabel();renderFavorites();if(active||editingId.equals(profile.id))editProfile(book.active());if(active&&config!=null)loadCurrent();
        }catch(Exception e){toast("无法保存删除结果，请重试");}}),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void activateProfile(String id){
        if(!canChangeHost())return;if(id.equals(book.activeId)){if(sheet!=null)sheet.dismiss();return;}
        try{ProfileBook next=book.copy();if(next.find(id)==null)return;next.activeId=id;vault.save(next);book=next;state.book=book;config=book.active().config;state.config=config;clearHostData();
            editingId=id;nameInput.setText(book.active().name);addressInput.setText(config.origin);tokenInput.setText(config.token);captureDraft();updateHostLabel();renderFavorites();switchPage(0,true);
        }catch(Exception e){toast("无法保存当前主机，请重试");}
    }
    private boolean isFavorite(String name){return book.active()!=null&&book.active().favorites.contains(name);}
    private void toggleFavorite(String name){
        if(book.active()==null)return;try{ProfileBook next=book.copy();ArrayList<String> favorites=next.active().favorites;if(!favorites.remove(name)){if(favorites.size()>=ProfileBook.FAVORITE_LIMIT){toast("每台主机最多收藏 12 个服务");return;}favorites.add(name);}vault.save(next);
            // Keep the exact connection object used by in-flight identity guards.
            book.active().favorites.clear();book.active().favorites.addAll(favorites);state.book=book;renderFavorites();
        }catch(Exception e){toast("无法保存收藏，请重试");}
    }
    private void renderFavorites(){
        if(favoritesArea==null)return;favoritesArea.removeAllViews();gap(favoritesArea,20);addText(favoritesArea,"收藏服务",15,TEXT);gap(favoritesArea,8);addText(favoritesArea,"状态未查询 · 点击获取最新详情",12,MUTED);gap(favoritesArea,8);
        ProfileBook.Profile profile=book.active();if(profile==null||profile.favorites.isEmpty()){addText(favoritesArea,"在服务详情中添加收藏",12,SECONDARY);return;}
        for(String name:profile.favorites){favoritesArea.addView(compact(name+"  ›",()->openFavorite(name)),new LinearLayout.LayoutParams(-1,-2));gap(favoritesArea,6);}
    }
    private void openFavorite(String name){
        if(busy||ApiClient.MUTATING.get()){toast("正在处理请求，请稍候");return;}
        LinearLayout box=sheetBox(name);TextView info=message(box);showMessage(info,"正在获取最新服务状态…",false);showSheet(box);servicesFresh=false;
        read(config,"/api/v1/system",false,j->{system=j;systemFresh=true;lastSystemSuccess=System.currentTimeMillis();systemError="";
            read(config,"/api/v1/services",false,data->{supported=data.getBoolean("supported");services.clear();JSONArray list=data.getJSONArray("services");JSONObject target=null;for(int i=0;i<list.length();i++){JSONObject service=list.getJSONObject(i);services.add(service);if(name.equals(service.optString("name")))target=service;}servicesFresh=true;filterServices();
                if(target==null)showMessage(info,supported?"当前主机未找到该服务，可在服务页管理收藏":"此主机不支持 systemd 服务管理",false);else serviceSheet(target);
            },e->{servicesFresh=false;showMessage(info,e,true);});
        },e->{systemFresh=false;showMessage(info,e,true);});
    }

    private void toolMenu(){
        if(config==null){toast("请先连接主机");return;}if(busy||ApiClient.MUTATING.get()){toast("正在处理请求，请稍候");return;}
        LinearLayout box=sheetBox("主机工具");addText(box,hostLabel()+"\n按需读取 · 手动刷新",12,SECONDARY);gap(box,20);
        box.addView(compact("监听端口",()->openTool("ports")),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
        box.addView(compact("systemd 定时任务",()->openTool("timers")),new LinearLayout.LayoutParams(-1,-2));gap(box,8);
        box.addView(compact("磁盘与受限目录扫描",()->openTool("disks")),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void toolRead(String path,JSONObject body,Result success,java.util.function.Consumer<String> failure){
        if(busy||ApiClient.MUTATING.get()||config==null||!foreground||sheet==null){toast("正在处理请求，请稍候");return;}
        busy=true;handler.removeCallbacks(poll);updateControls();final int gen=generation,sg=sheetGeneration;final ApiClient.Config identity=config;
        ApiClient.IO.execute(()->{JSONObject data=null;String error=null;try{if(!RequestPolicy.current(foreground,generation,gen,config,identity,true,sheetGeneration,sg))throw new Exception("请求已取消");data=api.request(identity,path,body,()->RequestPolicy.current(foreground,generation,gen,config,identity,true,sheetGeneration,sg));ToolData.validate(path,data);}catch(Exception e){error=e.getMessage()==null?"工具读取失败":ApiClient.redact(e.getMessage(),identity);}final JSONObject result=data;final String problem=error;
            handler.post(()->{busy=false;if(destroyed)return;if(sheet!=null&&RequestPolicy.current(foreground,generation,gen,config,identity,true,sheetGeneration,sg)){if(problem==null)try{status(true);success.accept(result);}catch(Exception e){failure.accept("工具数据格式异常，请检查服务端版本");}else{status(false);failure.accept(problem);}}updateControls();if(loadPending&&foreground){loadPending=false;loadCurrent();}else schedulePoll();});
        });
    }
    private void openTool(String kind){
        if(busy||ApiClient.MUTATING.get()){toast("正在处理请求，请稍候");return;}
        String title=kind.equals("ports")?"监听端口":kind.equals("timers")?"systemd 定时任务":"磁盘与受限扫描";
        LinearLayout box=sheetBox(title);addText(box,"主机 · "+hostLabel()+"\n不自动刷新",12,SECONDARY);gap(box,12);TextView info=message(box);LinearLayout rows=column();final JSONObject[] cached={null};
        EditText query=search(kind.equals("ports")?"搜索地址、进程或服务":"本地搜索名称或路径","",q->{});
        Runnable render=()->{rows.removeAllViews();if(cached[0]==null)return;renderTool(kind,cached[0],query.getText().toString(),rows);};watch(query,render);
        Runnable fetch=()->{showMessage(info,"正在读取…",false);toolRead("/api/v1/"+kind,null,j->{cached[0]=j;render.run();showMessage(info,j.optBoolean("truncated")?"结果已截断，请缩小本地筛选范围；未显示部分需在主机检查":"",false);},e->{cached[0]=null;rows.removeAllViews();showMessage(info,e,true);});};
        box.addView(compact("刷新",fetch),new LinearLayout.LayoutParams(-1,-2));gap(box,8);box.addView(searchRow(query));gap(box,16);box.addView(rows);showSheet(box);expandSheet();fetch.run();
    }
    private void renderTool(String kind,JSONObject data,String query,LinearLayout rows){
        if(!kind.equals("disks")&&!data.optBoolean("supported",false)){addText(rows,kind.equals("timers")?"此主机不支持 systemd 定时任务":"此主机无法读取监听端口",15,WARN);gap(rows,8);addText(rows,data.optString("message","所需系统能力不可用"),12,SECONDARY);return;}
        JSONArray items=data.optJSONArray(kind);String q=query.trim().toLowerCase(Locale.ROOT);int shown=0;
        if(items!=null)for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;if(!ToolData.searchText(kind,item).toLowerCase(Locale.ROOT).contains(q))continue;shown++;
            if(kind.equals("ports")){
                addText(rows,item.optString("protocol").toUpperCase(Locale.ROOT)+" · "+item.optString("address"),16,TEXT);gap(rows,8);
                addText(rows,"PID "+(item.optInt("pid")>0?item.optInt("pid"):"不可用")+" · "+ToolData.available(item.optString("process"))+"\n服务 · "+ToolData.available(item.optString("service")),12,SECONDARY);
            }else if(kind.equals("timers")){
                addText(rows,item.optString("name"),16,TEXT);gap(rows,6);addText(rows,item.optString("description")+"\n"+item.optString("active_state")+" · "+item.optString("sub_state"),12,SECONDARY);gap(rows,8);
                addText(rows,"下次 · "+ToolData.available(item.optString("next_run"))+"\n上次 · "+ToolData.available(item.optString("last_run"))+"\n触发单元 · "+ToolData.available(item.optString("unit")),12,SECONDARY);
                if(item.optBoolean("can_run")){gap(rows,12);rows.addView(compact("手动触发",()->confirmTimer(item)),new LinearLayout.LayoutParams(-1,-2));}else{gap(rows,8);addText(rows,"手动触发未获服务器授权",12,MUTED);}
            }else{
                addText(rows,item.optString("mountpoint"),16,TEXT);gap(rows,8);double total=number(item,"total"),used=number(item,"used"),inodes=number(item,"inodes_total");
                addText(rows,item.optString("device")+" · "+item.optString("filesystem")+"\n已用 "+bytes(used)+" / 总计 "+bytes(total)+" · "+percent(total>0?used/total*100:Double.NaN)+"\n可用 "+bytes(number(item,"available"))+"\ninode "+(inodes>0?decimal(number(item,"inodes_used"))+" / "+decimal(inodes)+" · "+percent(number(item,"inodes_used")/inodes*100):"暂不可用"),12,SECONDARY);
            }divider(rows);
        }
        if(shown==0)addText(rows,q.isEmpty()?"暂无数据":"没有匹配结果",14,SECONDARY);
        if(kind.equals("disks")){
            gap(rows,12);addText(rows,"受限目录扫描",15,TEXT);gap(rows,8);addText(rows,"仅服务器允许的目录 · 每次单独确认 · 刷新磁盘不会扫描",12,SECONDARY);gap(rows,12);
            JSONArray roots=data.optJSONArray("scan_roots");if(roots==null||roots.length()==0)addText(rows,"服务器未开放目录扫描",12,MUTED);
            if(roots!=null)for(int i=0;i<roots.length();i++){String path=roots.optString(i);rows.addView(compact("扫描 "+path,()->confirmScan(path)),new LinearLayout.LayoutParams(-1,-2));gap(rows,8);}
        }
    }
    private void confirmTimer(JSONObject timer){
        if(busy||ApiClient.MUTATING.get()){toast("正在处理请求，请稍候");return;}if(!timer.optBoolean("can_run"))return;
        LinearLayout box=sheetBox("手动触发定时任务？");addText(box,"主机 · "+hostLabel(),14,MINT);gap(box,16);addText(box,timer.optString("name")+"\n触发单元 · "+timer.optString("unit"),16,TEXT);gap(box,16);addText(box,"服务器将重新检查触发单元和授权。接受启动不表示任务已完成。",14,SECONDARY);gap(box,24);
        box.addView(button("确认触发",true,false,()->{String name=timer.optString("name");String path="/api/v1/timers/"+encodeSegment(name)+"/run";mutate(path,json("confirm",name),"任务启动已接受，不代表任务已完成",()->openTool("timers"),false);}),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void confirmScan(String path){
        if(busy||ApiClient.MUTATING.get()){toast("正在处理请求，请稍候");return;}LinearLayout box=sheetBox("确认只读目录扫描？");addText(box,"主机 · "+hostLabel(),14,MINT);gap(box,16);addText(box,path,18,TEXT);gap(box,16);
        addText(box,"扫描可能产生磁盘 IO。最多 2 秒 / 10000 项 / 深度 6，不跟随符号链接或跨文件系统；服务器限制同时一次、冷却 15 秒。\n结果为逻辑字节数，可能仅是下界，不代表物理占用或可释放空间。",14,SECONDARY);gap(box,24);
        box.addView(button("确认扫描一次",true,false,()->runScan(path)),new LinearLayout.LayoutParams(-1,-2));showSheet(box);
    }
    private void runScan(String path){
        if(busy||ApiClient.MUTATING.get())return;LinearLayout box=sheetBox("扫描 · "+path);addText(box,"主机 · "+hostLabel(),12,SECONDARY);gap(box,12);TextView info=message(box);showMessage(info,"正在扫描 · 离开会取消本次读取，不自动重试",false);LinearLayout result=column();box.addView(result);showSheet(box);expandSheet();
        toolRead("/api/v1/disk-scan",json("path",path),j->{showMessage(info,j.getBoolean("partial")?"部分结果 · 逻辑字节数仅为下界\n"+j.getString("reason"):"扫描完成 · 逻辑字节数",j.getBoolean("partial"));
            addText(result,j.getString("path"),16,TEXT);gap(result,12);addText(result,bytes(number(j,"bytes"))+" · "+j.optLong("files")+" 个文件\n耗时 "+j.optLong("elapsed_ms")+" ms\n不代表物理占用或可释放空间；结果不会自动重试。",14,SECONDARY);divider(result);
            JSONArray entries=j.getJSONArray("entries");ArrayList<JSONObject> sorted=new ArrayList<>();for(int i=0;i<entries.length();i++)sorted.add(entries.getJSONObject(i));sorted.sort((a,b)->Long.compare(b.optLong("bytes"),a.optLong("bytes")));addText(result,"目录条目 · 最大 20 项",12,MUTED);gap(result,12);for(int i=0;i<Math.min(20,sorted.size());i++){JSONObject entry=sorted.get(i);addText(result,entry.optString("name")+"\n"+bytes(number(entry,"bytes"))+" · "+entry.optLong("files")+" 个文件",14,TEXT);divider(result);}
        },e->showMessage(info,e+"\n本次扫描不会自动重试。请返回磁盘页，确认后再发起。",true));
    }

    private void expandSheet(){if(sheet!=null&&sheet.getWindow()!=null)sheet.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),(int)(getResources().getDisplayMetrics().heightPixels*.85));}
    private LinearLayout sheetBox(String title){LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(24));View handle=new View(this);handle.setBackground(shape(MUTED,2,false));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(12);box.addView(handle,hp);LinearLayout top=row();TextView name=text(title,18,TEXT);name.setTextIsSelectable(true);weight(top,name);Button close=compact("关闭",()->{if(sheet!=null)sheet.dismiss();});close.setContentDescription("关闭操作层");LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(64),-2);cp.leftMargin=dp(8);top.addView(close,cp);box.addView(top);gap(box,20);return box;}
    private void showSheet(LinearLayout box){
        if(sheet!=null)sheet.dismiss();handler.removeCallbacks(poll);resetRates();sheetGeneration++;
        Dialog d=new Dialog(this);d.setContentView(scroll(box));Window w=d.getWindow();
        if(w!=null){w.setBackgroundDrawable(shape(SURFACE,16,false));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND|WindowManager.LayoutParams.FLAG_SECURE);WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.55f;p.gravity=Gravity.BOTTOM;p.width=Math.min(getResources().getDisplayMetrics().widthPixels,dp(600));p.height=-2;w.setAttributes(p);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        d.setOnDismissListener(dialog->{if(sheet==d){sheet=null;sheetGeneration++;loadPending=false;api.cancelRead();schedulePoll();}});sheet=d;d.show();
        int max=(int)(getResources().getDisplayMetrics().heightPixels*.85);box.measure(View.MeasureSpec.makeMeasureSpec(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));if(w!=null)w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),Math.min(box.getMeasuredHeight(),max));
    }
    private void mutate(String path,JSONObject body,String success,Runnable after,boolean power){
        if(busy || config==null || !foreground || !ApiClient.MUTATING.compareAndSet(false,true)){toast("已有请求正在处理，请稍候");return;}
        busy=true;handler.removeCallbacks(poll);final ApiClient.Config credentials=config;final State targetState=state;updateControls();if(sheet!=null)sheet.dismiss();toast("正在提交…");
        final int gen=generation;
        ApiClient.IO.execute(()->{String error=null;try{if(!RequestPolicy.current(foreground,generation,gen,config,credentials,false,0,0))throw new Exception("操作已取消，未发送");api.request(credentials,path,body,()->RequestPolicy.current(foreground,generation,gen,config,credentials,false,0,0));}catch(Exception e){error=e.getMessage()==null?"操作结果未确认，请刷新检查":ApiClient.redact(e.getMessage(),credentials);}final String problem=error;
            runOnUiThread(()->{ApiClient.MUTATING.set(false);busy=false;MainActivity owner=targetState.owner;if(owner==null||owner.destroyed||!owner.foreground){targetState.notice=problem==null?success:problem;return;}
                owner.processesFresh=false;owner.servicesFresh=false;
                if(owner!=this||owner.config!=credentials||gen!=generation){owner.toast(problem==null?success:problem);owner.loadCurrent();return;}
                if(problem==null){toast(success);after.run();}else{status(false);systemFresh=false;processesFresh=false;servicesFresh=false;systemError=problem;toast(problem);if(page==1)showMessage(processMessage,problem+" · 请刷新后检查",true);if(page==2)showMessage(serviceMessage,problem+" · 请刷新后检查",true);if(page==3)showMessage(settingsError,problem,true);updateDashboard();updatePower();}
                updateControls();if(!power){if(loadPending){loadPending=false;loadCurrent();}else schedulePoll();}
            });
        });
    }
    private JSONObject json(String... pairs){JSONObject object=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)object.put(pairs[i],pairs[i+1]);}catch(Exception ignored){}return object;}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}

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
            else{c.drawCircle(12,12,7,p);c.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,12+(float)Math.cos(a)*11,12+(float)Math.sin(a)*11,p);}}c.restore();}
    }
}
