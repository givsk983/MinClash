package io.minclash.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit, opt-in controls; networking and persistence never depend on this Activity. */
public final class ProtectionActivity extends ThemedActivity {
    private static final class Model extends ProtectionEdits {
        JSONObject remote;
        Module.Status status;
        List<ProtectionInventory.App> installed = new ArrayList<>();
        final ProtectionInventory inventory = new ProtectionInventory();
        List<String> targets = new ArrayList<>();
        boolean busy, inventoryChanged, advanced;
        java.util.Set<String> bypassed = java.util.Set.of();
        long lastRead;
        String error = "", inventoryError = "";
        ProtectionActivity activity;
        final Handler main = new Handler(Looper.getMainLooper());
        void render() { if (activity != null && !activity.isFinishing()) activity.render(); }
    }
    private Model model;
    private AppUi ui;
    private LinearLayout content;
    private ScrollView scroll;
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            model.inventory.invalidate();
            if (!model.busy) refresh(false); else model.inventoryChanged = true;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Object previous = getLastNonConfigurationInstance();
        model = previous instanceof Model old ? old : new Model(); model.activity = this;
        ui = new AppUi(this);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(AppUi.BG);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                int bottom=Math.max(safe.bottom,insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);
                view.setPadding(safe.left,safe.top,safe.right,bottom);
            }
            return insets;
        });
        content = ui.column(); content.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(28)); scroll.addView(content); setContentView(scroll);
        if (model.draft == null && state != null && state.containsKey("draft")) try {
            model.draft = ProtectionPolicy.parse(new JSONObject(state.getString("draft"))); model.dirty = state.getBoolean("dirty");
        } catch (Exception ignored) { }
        if (state != null) model.advanced = state.getBoolean("advanced");
        render();
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packageChanges, packages, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageChanges, packages);
        if (!model.busy) refresh(false);
    }
    @Override public Object onRetainNonConfigurationInstance() { return model; }
    @Override public void onResume() {
        super.onResume();
        if (model != null && model.status != null && !model.busy
            && android.os.SystemClock.elapsedRealtime() - model.lastRead >= 1500) refresh(false);
    }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("advanced", model.advanced);
        if (model.draft != null) try { state.putString("draft", model.draft.json().toString()); state.putBoolean("dirty", model.dirty); } catch (Exception ignored) { }
    }
    @Override public void onDestroy() {
        unregisterReceiver(packageChanges);
        if (model.activity == this) model.activity = null;
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (model.dirty && !model.busy) new AlertDialog.Builder(this).setTitle("放弃未保存的保护设置？")
            .setMessage("已生效的保护不会改变。").setNegativeButton("继续编辑", null).setPositiveButton("放弃", (d,w) -> finish()).show();
        else super.onBackPressed();
    }
    private void action(String title, Runnable run) { Button b=ui.button(title);b.setEnabled(!model.busy);b.setOnClickListener(v->run.run());ui.add(content,b,10); }
    private void note(String text) { ui.add(content,ui.label(text,13,AppUi.MUTED,false),8); }
    private void toggle(String title, boolean value, java.util.function.Consumer<Boolean> changed) {
        Switch s=new Switch(this);s.setText(title);s.setTextSize(15);s.setTextColor(AppUi.TEXT);s.setMinHeight(ui.dp(48));s.setChecked(value);s.setEnabled(!model.busy);
        s.setOnCheckedChangeListener((b,on)->{changed.accept(on);model.dirty=true;render();});ui.add(content,s,12);
    }
    private void render() {
        int y=scroll.getScrollY(); content.removeAllViews();
        action("‹ 返回",this::onBackPressed);
        ui.add(content,ui.label("应用防泄漏保护 · 开发中",23,AppUi.TEXT,true),0);
        if (!ExperimentalFeatures.enabled(this)) {
            note("请先在「设置 → 开发中功能」开启总开关，再设置应用防泄漏保护。");
            return;
        }
        note("为应用指定代理；代理不可用或 TUN 停止时，所选应用会暂停联网，直到代理恢复或你关闭保护。");
        if (!model.error.isEmpty()) ui.add(content,ui.label(model.error,13,AppUi.AMBER,false),10);
        if (model.busy) note("正在读取或应用，请稍候…");
        if (model.status == null) { action("重新读取",()->refresh(false));return; }
        if (model.status.functionalRevision()<9 || !model.status.protection) {
            note("请先在「设置 → 内置模块」更新模块并重启手机，再使用此功能。暂不更新也可继续使用原有代理功能。");
            action("返回设置",this::finish);return;
        }
        if (model.status.functionalRevision() < 22) {
            note("当前模块缺少新版防泄漏修复。可在「设置 → 内置模块」更新。");
        }
        if (model.remote==null || model.draft==null) {
            note("暂时无法读取保护设置，请重试。之前开启的保护可能仍在限制联网；需要恢复正常使用时，可选择关闭全部保护。");
            action("重新读取",()->refresh(false));
            action("关闭全部保护并恢复普通联网",()->confirmApply(true));return;
        }
        JSONObject remote=model.remote; boolean enabled=remote.optJSONObject("policy")!=null &&
            (remote.optJSONObject("policy").optBoolean("strictDns") || remote.optJSONObject("policy").optJSONArray("apps")!=null && remote.optJSONObject("policy").optJSONArray("apps").length()>0);
        boolean privateDnsConflict = remote.optBoolean("privateDNSConflict");
        String status=!enabled ? remote.optBoolean("residual") ? "部分联网限制尚未解除，请关闭保护后重试" : "保护未开启"
            : privateDnsConflict ? "严格 DNS 未就绪 · 请关闭系统「私人 DNS」"
            : !remote.optBoolean("binding") ? "应用安装信息已变化，请移除后重新添加保护"
            : !remote.optBoolean("firewall") ? "保护规则尚未确认 · 请检查或重新应用"
            : !model.status.running() ? "已暂停联网 · 等待代理恢复"
            : !remote.optBoolean("corePolicy") ? "保护设置尚未确认生效，请重新应用"
            : "保护已就绪 · 代理不可用时暂停联网";
        boolean ready=enabled&&!privateDnsConflict&&remote.optBoolean("binding")&&remote.optBoolean("firewall")&&(!model.status.running()||remote.optBoolean("corePolicy"));
        ui.add(content,ui.label(status,15,ready?AppUi.MINT:enabled||remote.optBoolean("residual")?AppUi.AMBER:AppUi.MUTED,true),14);
        if (privateDnsConflict) action("打开系统私人 DNS 设置", this::openPrivateDnsSettings);
        if (model.dirty) ui.add(content,ui.label("有未应用的修改 · 当前保护仍按原设置运行",13,AppUi.AMBER,true),8);
        if (!model.inventoryError.isEmpty()) note(model.inventoryError);
        action(model.draft.apps.isEmpty() ? "选择要保护的应用" : "添加应用 · 可多选",this::chooseApp);
        if (!model.draft.apps.isEmpty()) {
            note("已选择 " + model.draft.apps.size() + " 个应用 · 点击应用可单独改出口或移除");
            action("统一更换所有应用的代理", () -> chooseTarget("", target -> {
                List<ProtectionPolicy.App> changes = new ArrayList<>();
                for (ProtectionPolicy.App app : model.draft.apps) changes.add(new ProtectionPolicy.App(app.packageName(), app.uid(), target));
                stageApps(changes);
            }));
        } else note("一次勾选多个应用，再为它们选择同一个代理。");
        if (model.dirty || !enabled && model.draft.enabled()) {
            Button save = ui.button(!model.draft.enabled() ? "保存并关闭保护" : enabled ? "保存并应用修改" : "开启保护");
            save.setTextColor(AppUi.MINT); save.setEnabled(!model.busy);
            save.setOnClickListener(v -> confirmApply(false)); ui.add(content, save, 14);
            note("所有修改统一应用一次，保存时会短暂重连。");
        } else if (enabled && !ready) action("重新应用保护", () -> confirmApply(false));
        if (enabled || remote.optBoolean("residual")) action("关闭全部保护", () -> confirmApply(true));
        for (int i=0;i<model.draft.apps.size();i++) {
            ProtectionPolicy.App app=model.draft.apps.get(i);final int index=i;
            String name=model.installed.stream().filter(a->a.packageName().equals(app.packageName())&&a.uid()==app.uid()).map(ProtectionInventory.App::name).findFirst().orElse(app.packageName());
            Button b=ui.button(name+"\n"+"代理："+app.target());b.setEnabled(!model.busy);
            b.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"更改代理出口","移除保护（保存后生效）"},(d,w)->{
                if(w==0)chooseTarget(app.target(),t->setProtectedApp(new ProtectionPolicy.App(app.packageName(),app.uid(),t)));
                else{model.draft.apps.remove(index);model.dirty=true;render();}
            }).show());ui.add(content,b,8);
        }
        action((model.advanced ? "收起高级设置" : "高级设置") + " · 域名查询保护" + (model.draft.strictDns ? "已开启" : "未开启"), () -> { model.advanced = !model.advanced; render(); });
        if (model.advanced) {
            note("可选设置，会影响手机上其他应用的域名查询；不支持与绕过 TUN 同时使用。");
        toggle("保护域名查询（严格 DNS）",model.draft.strictDns,on->{model.draft.strictDns=on;if(!on)model.draft.bootstrap=false;});
        if(model.draft.strictDns){
            action("域名查询使用的代理："+(model.draft.dnsProxy.isEmpty()?"请选择":model.draft.dnsProxy),()->chooseTarget(model.draft.dnsProxy,t->{model.draft.dnsProxy=t;model.dirty=true;render();}));
            note("应用访问网站前，通常需要先查询网站的网络地址。开启后，这类查询会通过所选代理发送至 Cloudflare 加密服务；代理不可用时停止查询，不改为直接联网。此设置也会影响手机上其他应用的常规查询，可能改变访问速度，部分局域网服务也可能无法访问。请先关闭系统「私人 DNS」，避免设置冲突。");
            toggle("允许先查询代理服务器地址",model.draft.bootstrap,on->model.draft.bootstrap=on);
            note(model.draft.bootstrap ? "已允许启动所需的例外：连接代理前，可直接向 Cloudflare 查询代理服务器地址。Cloudflare 能看到你的真实网络地址和所查询的服务器名称；应用访问网站时的常规查询仍通过代理。"
                : "默认关闭。如果节点只提供服务器名称，且未配置对应的网络地址，可能无法连接。遇到这种情况，可按需开启上方选项；未开启时会继续暂停联网。");
            if (!privateDnsConflict) action("打开系统私人 DNS 设置", this::openPrivateDnsSettings);
        }
        }
        action("刷新状态",()->{model.inventory.invalidate();refresh(false);});
        action("使用说明与联网排查",this::showHelp);
        scroll.post(()->scroll.scrollTo(0,y));
    }

    private void openPrivateDnsSettings() {
        try {
            startActivity(new Intent("android.settings.PRIVATE_DNS_SETTINGS"));
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            message("请到系统网络设置手动关闭私人 DNS。");
        }
    }
    private void message(String text){new AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了",null).show();}
    private void chooseTarget(String current,java.util.function.Consumer<String> selected){
        if(model.targets.isEmpty()){message("没有可选的代理出口，请先导入订阅或配置。保护需要指定代理，不能选择直连。");return;}
        String[] names=model.targets.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("选择代理出口").setSingleChoiceItems(names,model.targets.indexOf(current),(d,w)->{d.dismiss();selected.accept(names[w]);}).setNegativeButton("取消",null).show();
    }
    private void chooseApp(){
        if (model.targets.isEmpty()) { message("请先导入订阅或配置，准备一个可用的代理出口。"); return; }
        LinearLayout box=ui.column();box.setPadding(ui.dp(16),0,ui.dp(16),0);
        EditText search=ui.search("搜索应用名称或包名");ui.add(box,search,0);
        android.widget.TextView count=ui.label("",13,AppUi.MUTED,false);ui.add(box,count,8);
        ListView list=new ListView(this);AppUi.Summaries adapter=ui.new Summaries();list.setAdapter(adapter);box.addView(list,new LinearLayout.LayoutParams(-1,ui.dp(320)));
        java.util.Map<String,ProtectionInventory.App> selected=new java.util.LinkedHashMap<>();
        java.util.Set<String> existing=new java.util.HashSet<>();
        for(ProtectionPolicy.App app:model.draft.apps)existing.add(app.packageName());
        List<ProtectionInventory.App> filtered=new ArrayList<>();
        Runnable filter=()->{
            filtered.clear();String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);List<AppUi.Summary> rows=new ArrayList<>();
            for(ProtectionInventory.App app:model.installed)if(!existing.contains(app.packageName())&&(app.name()+" "+app.packageName()).toLowerCase(java.util.Locale.ROOT).contains(query)){
                filtered.add(app);rows.add(new AppUi.Summary(app.name(),app.packageName(),model.bypassed.contains(app.packageName())?"已绕过 TUN":selected.containsKey(app.packageName())?"✓ 已选":"选择",filtered.size()-1));
            }
            count.setText("已选 "+selected.size()+" 个"+(filtered.isEmpty()?" · 没有可添加的匹配应用":" · 可连续搜索和勾选"));adapter.set(rows);
        };
        filter.run();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){filter.run();}public void afterTextChanged(Editable e){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("1 · 选择应用").setView(box).setNegativeButton("取消",null).setPositiveButton("下一步：选择代理",null).create();
        list.setOnItemClickListener((p,v,pos,id)->{
            if(pos>=filtered.size())return;ProtectionInventory.App app=filtered.get(pos);
            if(model.bypassed.contains(app.packageName())){message("此应用已绕过 TUN。请先在应用分流中取消绕过并保存，再添加保护。");return;}
            if(selected.containsKey(app.packageName()))selected.remove(app.packageName());else selected.put(app.packageName(),app);
            filter.run();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!selected.isEmpty());
        });
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> chooseTarget("",target->{
            List<ProtectionPolicy.App> changes=new ArrayList<>();
            for(ProtectionInventory.App app:selected.values())changes.add(new ProtectionPolicy.App(app.packageName(),app.uid(),target));
            if(stageApps(changes))dialog.dismiss();
        }));
    }
    private boolean stageApps(List<ProtectionPolicy.App> apps) {
        try { model.draft.putApps(apps,model.targets); model.dirty=true;render();return true; }
        catch(Exception error){message(error.getMessage());return false;}
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("使用说明与联网排查")
            .setMessage("选择应用 → 选择代理 → 开启保护。添加、移除或更换出口都先暂存，最后统一保存。已选应用可以单独设置不同代理。\n\n代理不可用或 TUN 停止时，受保护应用会暂停联网。需要恢复普通联网时，点击「关闭全部保护」。页面状态核对规则，不代表代理已连通。\n\n绕过 TUN 的应用不能同时受保护；请先在应用分流中取消绕过并保存。启用保护前也需关闭热点共享代理。\n\n应用卸载重装后，请移除再重新添加。共用 UID 的应用必须使用同一代理；无法识别来源的连接也可能暂停。\n\n保护仅针对选定应用直接发起的连接，不保证覆盖分身、工作资料或系统代发请求。它不会隐藏定位、SIM 卡地区、设备或账号身份。手机开机、模块禁用或规则被其他 Root 工具改变时，保护可能尚未生效。\n\n设置仅保存在本机，不随订阅或备份迁移。高级域名查询保护不覆盖其他应用自行发起的加密 DNS。")
            .setNeutralButton("后台连接恢复指南",(d,w)->startActivity(new Intent(this,MainActivity.class).putExtra("background_connection_guide",true)))
            .setPositiveButton("知道了",null).show();
    }
    private void setProtectedApp(ProtectionPolicy.App app) {
        try {
            model.draft.putApp(app, model.targets);
            model.dirty = true;
            render();
        } catch (Exception error) { message(error.getMessage()); }
    }
    private void confirmApply(boolean off){
        if (!off) try { model.draft.validate(model.targets); }
        catch (Exception error) { message(error.getMessage()); return; }
        new AlertDialog.Builder(this).setTitle(off?"关闭全部保护？":"应用防泄漏保护？")
            .setMessage(off?"关闭后，应用恢复按原有分流设置联网，部分连接可能直接使用本机网络。"
                :"将保护 " + model.draft.apps.size() + " 个应用，域名查询保护" + (model.draft.strictDns ? "开启" : "关闭") + "。\n\n所有修改统一应用，代理会短暂重连。代理不可用时，受保护应用暂停联网，直到代理恢复或你关闭保护。")
            .setNegativeButton("取消",null).setPositiveButton(off?"关闭并恢复联网":"应用",(d,w)->apply(off)).show();
    }
    private void apply(boolean off){
        if (!off && !ExperimentalFeatures.enabled(this)) {
            message("请先在「设置 → 开发中功能」开启尝鲜。");
            return;
        }
        final Model m=model;final byte[] request;final ProtectionPolicy attempted;
        try {
            request=off?null:m.draft.request(m.remote.getString("digest"));
            attempted=off?null:ProtectionPolicy.parse(new JSONObject(new String(request,java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("policy"));
        } catch(Exception e){message(e.getMessage());return;}
        m.busy=true;m.error="";render();
        final Context context = getApplicationContext();
        RoutingStore.WORKER.execute(()->{
            String failure="";
            boolean persisted = false;
            try {
                failure = RoutingMutations.run(context, () -> applyToModule(context, off, request, attempted));
                persisted = true;
            } catch (Exception error) { failure = error.getMessage(); }
            final boolean saved = persisted;
            final String error = failure == null ? "应用失败，请刷新状态后重试。" : failure;
            m.main.post(() -> {
                m.busy = false;
                m.finishApply(saved, attempted);
                m.error = (saved && !error.isEmpty() ? off ? "保护已关闭，但后续恢复未完成：\n" : "设置已保存，但后续恢复未完成：\n" : "")
                    + error + (!saved && m.dirty ? "\n本次编辑已保留，请检查状态后重试。" : "");
                if (m.activity != null) m.activity.refresh(saved);
            });
        });
    }

    private static String applyToModule(Context context, boolean off, byte[] request, ProtectionPolicy attempted) throws Exception {
        if (!off && !ExperimentalFeatures.enabled(context)) {
            throw new java.io.IOException("请先在「设置 → 开发中功能」开启总开关。");
        }
        if (!off) RoutingMutations.recoverPending(context);
        Module.Status before = Module.status();
        List<Controller.Group> selections = List.of();
        if (before.running()) {
            try { selections = Controller.groups().values(); }
            catch (Exception ignored) { }
        }
        if (!off) {
            RoutingStore.State routing = new RoutingStore(context).loadIfAvailable(before);
            if (routing != null) TunBypass.checkCurrent(context, routing.bypassedApps(), attempted);
        }
        Module.run(off ? Module.Command.PROTECTION_OFF : Module.Command.PROTECTION_SET, request);
        if (!before.running()) return "";
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
            while (!Module.status().running()) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    return off ? "保护已关闭，代理尚未恢复，请回到概览检查。" : "受保护应用将继续暂停联网，等待代理恢复。";
                }
                Thread.sleep(500);
            }
            Controller.restoreSelections(selections);
            return "";
        } catch (Exception error) {
            return error.getMessage() == null ? "请检查当前代理状态和节点选择。" : error.getMessage();
        }
    }
    private void refresh(boolean replace){
        if(model.busy)return;final Model m=model;final android.content.Context context=getApplicationContext();m.busy=true;render();
        RoutingStore.WORKER.execute(()->{
            try{
                Module.Status status=Module.status();m.main.post(()->m.status=status);JSONObject remote=null;ProtectionPolicy policy=null;List<String> targets=new ArrayList<>();List<ProtectionInventory.App> installed=m.installed;String inventoryError="";
                if(status.functionalRevision()>=9 && status.protection){
                    remote=new JSONObject(Module.run(Module.Command.PROTECTION_GET,null));policy=ProtectionPolicy.parse(remote.getJSONObject("policy"));
                    try {
                        if(status.profile){
                            RoutingStore.State routing = new RoutingStore(context).load();
                            m.main.post(() -> m.bypassed = routing.bypassedApps());
                            for(String t:Profile.routingTargets(routing.source()))if(ProtectionPolicy.safeTarget(t))targets.add(t);
                        }
                    } catch (Exception e) { inventoryError="代理出口暂时无法读取，请刷新后再添加保护；已有保护设置仍可查看或关闭。"; }
                    final JSONObject snapshot = remote;
                    final ProtectionPolicy snapshotPolicy = policy;
                    final String targetWarning = inventoryError;
                    m.main.post(() -> {
                        m.status = status; m.remote = snapshot; m.targets = targets; m.inventoryError = targetWarning;
                        if(m.acceptSnapshot(snapshotPolicy,replace))m.error="设置已保存，请核对保护状态。\n"+m.error;
                        m.render();
                    });
                    if (!remote.optBoolean("binding")) m.inventory.invalidate();
                    try { installed = m.inventory.load(context); }
                    catch (Exception e) { inventoryError="无法完整读取应用列表，请检查 Root 授权后刷新；已有保护和上次列表仍保留。"; }
                }
                final JSONObject r=remote;final ProtectionPolicy p=policy;final String warning=inventoryError;
                final List<ProtectionInventory.App> apps = installed;
                m.main.post(()->{m.status=status;m.remote=r;m.targets=targets;m.installed=apps;m.inventoryError=warning;
                    if(r == null && m.acceptSnapshot(p,replace))m.error="设置已在模块保存，但本次应用尚未确认完成。请核对保护状态。\n"+m.error;
                    m.lastRead=android.os.SystemClock.elapsedRealtime();m.busy=false;m.render();
                    if(m.inventoryChanged && m.activity!=null){m.inventoryChanged=false;m.activity.refresh(false);}
                });
            }catch(Exception e){m.main.post(()->{m.error="读取失败："+e.getMessage();m.busy=false;m.render();});}
        });
    }
}
