package io.codex.encofree4;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SizeF;
import android.widget.RemoteViews;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Both providers share rendering, actions and session state; their identities remain separate. */
public class EncoWidgets extends AppWidgetProvider {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable updateTask;
    private static Runnable frameTask;
    private static final WidgetMotion MOTION=new WidgetMotion();
    private static int displayedMode = -1;

    static int[] largeIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context,EncoWidgetLarge.class));
    }
    static int[] compactIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context,EncoWidgetCompact.class));
    }
    static boolean hasWidgets(Context context) { return largeIds(context).length+compactIds(context).length>0; }

    static PendingIntent openPanel(Context context) {
        Intent intent=new Intent(context,ControlActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context,1900,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent action(Context context,String value) {
        Intent intent=new Intent(context,EncoWidgetLarge.class).setAction(value).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        return PendingIntent.getBroadcast(context,value.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleUpdate(Context context) {
        Context app=context.getApplicationContext();
        if(updateTask!=null) MAIN.removeCallbacks(updateTask);
        updateTask=()->{
            updateTask=null;
            if (releaseIfUnused(app)) return;
            WidgetSnapshot state=EncoSession.get(app).snapshot();
            int previous=displayedMode;
            displayedMode=state.hasSelection()?state.mode:-1;
            boolean change=state.hasSelection() && previous>=1 && previous!=state.mode;
            long now=SystemClock.uptimeMillis();
            if(change) {
                MOTION.start(now,previous==EncoProtocol.MODE_TRANSPARENCY?1:0,state.position());
                if(frameTask!=null) MAIN.removeCallbacks(frameTask);
                frameTask=new Runnable() {
                    @Override public void run() {
                        long tick=SystemClock.uptimeMillis();
                        WidgetSnapshot live=EncoSession.get(app).snapshot();
                        if(!live.hasSelection() || !MOTION.running(tick) || !hasWidgets(app)) {
                            MOTION.snap(live.position());frameTask=null;
                            renderAll(app,live,live.position());return;
                        }
                        // Size-aware RemoteViews cannot merge partial updates on every launcher.
                        // Send a complete envelope while reusing the cached static pixels.
                        renderAll(app,live,MOTION.position(tick));
                        MAIN.postDelayed(this,Math.max(1,WidgetMotion.FRAME_MS-(SystemClock.uptimeMillis()-tick)));
                    }
                };
                MAIN.postDelayed(frameTask,WidgetMotion.FRAME_MS);
            }
            if(!state.hasSelection()) {
                MOTION.snap(state.position());
                if(frameTask!=null){MAIN.removeCallbacks(frameTask);frameTask=null;}
            } else if(!MOTION.running(now)) MOTION.snap(state.position());
            renderAll(app,state,MOTION.position(now));
        };
        MAIN.postDelayed(updateTask,60);
    }

    private static void renderAll(Context context,WidgetSnapshot state,float position) {
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        for(int id:largeIds(context)) renderOne(context,manager,id,false,state,position);
        for(int id:compactIds(context)) renderOne(context,manager,id,true,state,position);
    }

    private static void renderOne(Context context,AppWidgetManager manager,int id,boolean compact,WidgetSnapshot state,float position) {
        Bundle options=manager.getAppWidgetOptions(id);
        ArrayList<SizeF> sizes=options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,SizeF.class);
        Map<SizeF,RemoteViews> variants=new LinkedHashMap<>();
        if(sizes!=null) for(SizeF size:sizes) {
            if(size.getWidth()<100||size.getHeight()<48||variants.size()>=4) continue;
            variants.put(size,views(context,compact,size.getWidth(),size.getHeight(),state,position,sizes.size()));
        }
        RemoteViews remote;
        if(variants.isEmpty()) {
            int w=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180);
            int h=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,compact?86:180);
            remote=views(context,compact,Math.max(100,w),Math.max(48,h),state,position,1);
        } else remote=variants.size()==1?variants.values().iterator().next():new RemoteViews(variants);
        try { manager.updateAppWidget(id,remote); }
        catch(IllegalArgumentException error) {
            // A launcher can remove a widget during an in-flight animation frame.
            if(manager.getAppWidgetInfo(id)!=null) android.util.Log.w("EncoWidgets","Widget update rejected: "+error.getClass().getSimpleName());
        }
    }

    static RemoteViews views(Context context,boolean compact,float width,float height,WidgetSnapshot state,float position,int variants) {
        WidgetViewport viewport=new WidgetViewport(width,height,compact);
        WidgetGeometry g=new WidgetGeometry(viewport.modelWidth,viewport.modelHeight,compact);
        RemoteViews remote=new RemoteViews(context.getPackageName(),context.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE
                &&width/height>(compact?WidgetViewport.COMPACT_ASPECT:1f)
                ?R.layout.widget_enco_height:R.layout.widget_enco);
        // Bound total bitmap storage across responsive variants, even on tablets/foldables.
        float scale=Math.min(2,context.getResources().getDisplayMetrics().density)*viewport.displayScale;
        float pixels=g.width*g.height+2*g.track.w*g.track.h;
        scale=Math.min(scale,(float)Math.sqrt(700000f/(Math.max(1,variants)*pixels)));
        remote.setImageViewBitmap(R.id.widget_art,WidgetArtworkCache.image(context,g,state,scale,false));
        WidgetSlider.apply(context,remote,g,state,position,scale);
        remote.setImageViewBitmap(R.id.widget_slider_icons,WidgetArtworkCache.image(context,g,state,scale,true));
        remote.setOnClickPendingIntent(R.id.widget_root,openPanel(context));
        remote.setContentDescription(R.id.widget_root,"Enco 耳机，点击打开控制面板");
        hit(remote,g,R.id.widget_connection,g.statusTouch,state.connected||!EncoWidgetService.allowed(context)?openPanel(context):action(context,EncoWidgetService.CONNECT),
                state.connected?"已连接，打开连接控制":state.connecting?"连接中":"已断开，点击重新连接");
        String batteries="左耳"+batteryDescription(state.left,state.leftCharging)+"，右耳"+batteryDescription(state.right,state.rightCharging)+"，点击刷新电量";
        hit(remote,g,R.id.widget_battery,g.batteryTouch,action(context,EncoWidgetService.REFRESH),batteries);
        WidgetGeometry.Box anc=modeTouch(g,g.anc),transparency=modeTouch(g,g.transparency);
        hit(remote,g,R.id.widget_anc,anc,action(context,EncoWidgetService.ANC),modeDescription(state,EncoProtocol.MODE_ANC,"降噪"));
        hit(remote,g,R.id.widget_transparency,transparency,action(context,EncoWidgetService.TRANSPARENCY),modeDescription(state,EncoProtocol.MODE_TRANSPARENCY,"通透"));
        return remote;
    }
    private static WidgetGeometry.Box modeTouch(WidgetGeometry g,WidgetGeometry.Box b) {
        float height=Math.min(g.height,Math.max(48,b.h));
        return new WidgetGeometry.Box(b.x,WidgetGeometry.clamp(b.cy()-height/2,0,g.height-height),b.w,height);
    }
    private static String batteryDescription(int value,boolean charging) { return value<0?"电量未读取":value+"%"+(charging?"，充电中":""); }
    private static String modeDescription(WidgetSnapshot s,int mode,String label) {
        return label+(!s.connected?"，连接后可用":s.pendingMode>=0?"，正在切换":s.mode==mode?"，已选中":"，点击切换");
    }
    private static void hit(RemoteViews remote,WidgetGeometry g,int id,WidgetGeometry.Box b,PendingIntent intent,String description) {
        int[] anchors;
        if(id==R.id.widget_connection) anchors=new int[]{R.id.connection_y_extent,R.id.connection_y_size,R.id.connection_x_extent,R.id.connection_x_size};
        else if(id==R.id.widget_battery) anchors=new int[]{R.id.battery_y_extent,R.id.battery_y_size,R.id.battery_x_extent,R.id.battery_x_size};
        else if(id==R.id.widget_anc) anchors=new int[]{R.id.anc_y_extent,R.id.anc_y_size,R.id.anc_x_extent,R.id.anc_x_size};
        else anchors=new int[]{R.id.transparency_y_extent,R.id.transparency_y_size,R.id.transparency_x_extent,R.id.transparency_x_size};
        // Each nested weighted group defines an extent and a size within that extent.
        // The launcher's real measurement scales these fractions, including all tap targets.
        anchor(remote,g,b,anchors);
        remote.setOnClickPendingIntent(id,intent);
        remote.setContentDescription(id,description);
    }
    static void anchor(RemoteViews remote,WidgetGeometry g,WidgetGeometry.Box b,int[] anchors) {
        remote.setFloat(anchors[0],"setWeightSum",Math.max(1,g.height/(b.y+b.h)));
        remote.setFloat(anchors[1],"setWeightSum",Math.max(1,(b.y+b.h)/b.h));
        remote.setFloat(anchors[2],"setWeightSum",Math.max(1,g.width/(b.x+b.w)));
        remote.setFloat(anchors[3],"setWeightSum",Math.max(1,(b.x+b.w)/b.w));
    }

    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) {
        EncoSession session=EncoSession.get(context);
        WidgetSnapshot state=session.snapshot();
        renderAll(context,state,state.position());
        if(!session.manuallyPaused()) EncoWidgetService.start(context,EncoWidgetService.CONNECT);
    }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options) {
        WidgetSnapshot state=EncoSession.get(context).snapshot();
        renderAll(context,state,state.position());
    }
    private static boolean releaseIfUnused(Context context) {
        if (hasWidgets(context)) return false;
        if (updateTask != null) MAIN.removeCallbacks(updateTask);
        if (frameTask != null) MAIN.removeCallbacks(frameTask);
        updateTask = frameTask = null;
        displayedMode = -1;
        MOTION.snap(0);
        WidgetArtworkCache.clear();
        context.stopService(new Intent(context, EncoWidgetService.class));
        return true;
    }

    @Override public void onDeleted(Context context,int[] ids) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> { if (!releaseIfUnused(app)) scheduleUpdate(app); });
    }
    @Override public void onDisabled(Context context) { releaseIfUnused(context); }
    @Override public void onRestored(Context context,int[] oldIds,int[] newIds) { scheduleUpdate(context); }
    @Override public void onReceive(Context context,Intent intent) {
        String value=intent.getAction();
        if(EncoWidgetService.CONNECT.equals(value)||EncoWidgetService.REFRESH.equals(value)
                ||EncoWidgetService.ANC.equals(value)||EncoWidgetService.TRANSPARENCY.equals(value)) {
            if(!hasWidgets(context)) return;
            if(!EncoWidgetService.start(context,value)) {
                Toast.makeText(context,EncoWidgetService.allowed(context)
                        ?"暂时无法同步，请点击卡片空白处打开软件后重试"
                        :"请打开 Enco Free4，允许附近的设备权限后重试",Toast.LENGTH_SHORT).show();
                scheduleUpdate(context);
            }
            return;
        }
        super.onReceive(context,intent);
    }
}
