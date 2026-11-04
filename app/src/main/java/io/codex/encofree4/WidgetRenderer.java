package io.codex.encofree4;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.TextPaint;

/** Native Canvas artwork sent through a supported RemoteViews ImageView, never a WebView. */
final class WidgetRenderer {
    private static Bitmap device;
    private static String deviceKey;
    private final Canvas canvas;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final WidgetGeometry g;
    private final WidgetSnapshot state;

    static Bitmap render(Context context, WidgetGeometry g, WidgetSnapshot state, float position, float scale) {
        return render(context,g,state,position,scale,false);
    }
    /** Drops the cached artwork so the next render picks up a colour change. */
    static void invalidateDevice() {
        device = null;
    }

    static Bitmap render(Context context, WidgetGeometry g, WidgetSnapshot state, float position, float scale, boolean localSlider) {
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(g.width*scale)),Math.max(1,Math.round(g.height*scale)),Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas c=new Canvas(bitmap); c.scale(scale,scale);
        String artName=context.getSharedPreferences("enco_widgets",Context.MODE_PRIVATE).getString("device_name",null);
        String artColor=context.getSharedPreferences("enco_widgets",Context.MODE_PRIVATE).getString("device_color","white");
        // Earbuds only charge inside the case, so the charging flags tell us which
        // artwork to draw and the key makes the cache follow that state.
        boolean artInCase=state.leftCharging&&state.rightCharging&&state.left>=0&&state.right>=0;
        String artKey=artName+"|"+artColor+"|"+artInCase;
        if(device==null||!artKey.equals(deviceKey)) {
            device=BitmapFactory.decodeResource(context.getResources(),EncoModels.deviceImage(artName,artColor,artInCase));
            deviceKey=artKey;
        }
        new WidgetRenderer(c,g,state,EncoModels.hasNoiseControl(artName)).draw(position,localSlider);
        return bitmap;
    }
    static Bitmap controlIcons(Context context,WidgetGeometry g,WidgetSnapshot state,float scale) {
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(g.track.w*scale)),Math.max(1,Math.round(g.track.h*scale)),Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas c=new Canvas(bitmap);c.scale(scale,scale);c.translate(-g.track.x,-g.track.y);
        new WidgetRenderer(c,g,state,true).icons();
        return bitmap;
    }
    static Bitmap controlKnob(Context context,WidgetGeometry g,WidgetSnapshot state,float position,float scale) {
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(g.track.w*scale)),Math.max(1,Math.round(g.track.h*scale)),Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas c=new Canvas(bitmap);c.scale(scale,scale);c.translate(-g.track.x,-g.track.y);
        if(state.hasSelection())new WidgetRenderer(c,g,state,true).knob(position);
        return bitmap;
    }
    private final boolean noiseControls;
    private WidgetRenderer(Canvas canvas,WidgetGeometry g,WidgetSnapshot state,boolean noiseControls) { this.canvas=canvas;this.g=g;this.state=state;this.noiseControls=noiseControls; }
    private RectF rect(WidgetGeometry.Box b) { return new RectF(b.x,b.y,b.x+b.w,b.y+b.h); }
    private void color(int value) { paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(value);paint.setAlpha(Color.alpha(value)); }
    private void stroke(float width,int value) { color(value);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width); }

    private void draw(float position,boolean localSlider) {
        float w=g.width,h=g.height,r=g.radius;
        RectF card=new RectF(.7f,.7f,w-.7f,h-.7f);
        paint.setShader(new LinearGradient(0,0,w,h,new int[]{0x707D8EA5,0x60485D78,0x685E5C78},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card,r,r,paint);
        paint.setShader(null);
        Path clip=new Path();clip.addRoundRect(card,r,r,Path.Direction.CW);
        canvas.save();canvas.clipPath(clip);
        paint.setShader(new RadialGradient(w*.05f,h*.02f,w*.85f,new int[]{0x28FFFFFF,0x00FFFFFF},null,Shader.TileMode.CLAMP));
        canvas.drawRect(card,paint);
        paint.setShader(null);canvas.restore();
        stroke(.8f,0x70FFFFFF);canvas.drawRoundRect(card,r,r,paint);
        paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xBFFFFFFF,0x10FFFFFF,0x65FFFFFF},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(1.3f,1.3f,w-1.3f,h-1.3f),r-1,r-1,paint);paint.setShader(null);
        if(!g.compact) {
            text("Enco",g.title,g.titleSize,0xFFFFFFFF,false);
            if(device!=null) {
                float ratio=(float)device.getWidth()/device.getHeight();
                float imageH=Math.min(g.photo.h,g.photo.w/ratio),imageW=imageH*ratio;
                color(0xFFFFFFFF);
                canvas.drawBitmap(device,null,new RectF(g.photo.cx()-imageW/2,g.photo.cy()-imageH/2,g.photo.cx()+imageW/2,g.photo.cy()+imageH/2),paint);
            }
        }
        status();
        ring(g.left,state.left,state.leftCharging);ring(g.right,state.right,state.rightCharging);
        // Clip has no noise control, so the slider row is left out entirely.
        if(noiseControls) control(position,localSlider);
    }
    private void status() {
        WidgetGeometry.Box b=g.status;
        int fg=state.connected?0xFFD9FFE8:state.connecting?0xFFFFD99A:0xFFFFBAC4;
        int bg=state.connected?0x283D9269:state.connecting?0x30B58840:0x309E4B60;
        color(bg);canvas.drawRoundRect(rect(b),b.h*.35f,b.h*.35f,paint);
        stroke(.65f,0x25FFFFFF);canvas.drawRoundRect(rect(b),b.h*.35f,b.h*.35f,paint);
        String label=state.connected?"已连接":state.connecting?"连接中":"已断开";
        float dot=Math.max(2.5f,g.statusSize*.22f);
        TextPaint tp=new TextPaint(Paint.ANTI_ALIAS_FLAG);tp.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));tp.setTextSize(g.statusSize);
        float textW=tp.measureText(label),group=textW+dot+Math.max(3,g.statusSize*.3f);
        color(fg);canvas.drawCircle(b.cx()-group/2+dot/2,b.cy(),dot/2,paint);
        text(label,new WidgetGeometry.Box(b.cx()-group/2+dot+Math.max(3,g.statusSize*.3f),b.y,textW,b.h),g.statusSize,fg,true);
    }
    private void text(String value,WidgetGeometry.Box b,float size,int color,boolean center) {
        TextPaint tp=new TextPaint(Paint.ANTI_ALIAS_FLAG);tp.setColor(color);tp.setTextSize(size);tp.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        String label=TextUtils.ellipsize(value,tp,b.w,TextUtils.TruncateAt.END).toString();
        float x=center?b.cx()-tp.measureText(label)/2:b.x;
        float y=b.cy()-(tp.ascent()+tp.descent())/2;
        tp.setShadowLayer(1.1f,0,.4f,0x90000000);
        canvas.drawText(label,x,y,tp);
    }
    private void ring(WidgetGeometry.Box b,int value,boolean charging) {
        float line=Math.max(2,b.w*.07f),inset=line/2+1;
        RectF arc=new RectF(b.x+inset,b.y+inset,b.x+b.w-inset,b.y+b.h-inset);
        stroke(line,state.connected?0x36FFFFFF:0x21FFFFFF);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawArc(arc,0,360,false,paint);
        int c=charging?0xFFA9F1C4:value>=0 && value<=20?0xFFFFA1AE:0xFFF8FCFF;
        if(value>=0) {
            stroke(line,c);paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(arc,-90,Math.max(0,Math.min(100,value))*3.6f,false,paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        text(value<0?"—":Integer.toString(value),b,g.numberSize,state.connected?0xFFFFFFFF:0x85FFFFFF,true);
        if(state.refreshing) { stroke(.8f,0xC0FFFFFF);canvas.drawCircle(b.cx(),b.cy(),b.w/2+.7f,paint); }
    }
    private void control(float position,boolean localSlider) {
        WidgetGeometry.Box t=g.track;
        float d=t.h;
        color(0x205C6F89);canvas.drawRoundRect(rect(t),d/2,d/2,paint);
        stroke(.8f,0x66FFFFFF);canvas.drawRoundRect(rect(t),d/2,d/2,paint);
        if(!localSlider && state.hasSelection()) knob(position);
        if(!localSlider) icons();
    }
    private void knob(float position) {
        WidgetGeometry.Box t=g.track;
        float d=t.h;
            float x=t.x+(t.w-d)*WidgetGeometry.clamp(position,0,1);
            RectF knob=new RectF(x+.6f,t.y+.6f,x+d-.6f,t.y+d-.6f);
            color(0x20FFFFFF);canvas.drawOval(knob,paint);
            paint.setShader(new LinearGradient(x,t.y,x+d,t.y+d,new int[]{0x60FFFFFF,0x10FFFFFF,0x38FFFFFF},null,Shader.TileMode.CLAMP));
            canvas.drawOval(knob,paint);paint.setShader(null);
            stroke(1,0xB3FFFFFF);canvas.drawOval(knob,paint);
            stroke(.65f,0x45FFFFFF);canvas.drawOval(new RectF(x+2.7f,t.y+2.7f,x+d-2.7f,t.y+d-2.7f),paint);
    }
    private void icons() {
        WidgetGeometry.Box t=g.track;
        float d=t.h;
        float size=Math.min(d*.43f,g.compact?36.7f:43.7f);
        modeIcon(t.x+d/2,t.cy(),size,false);
        modeIcon(t.x+t.w-d/2,t.cy(),size,true);
    }
    private void modeIcon(float cx,float cy,float size,boolean transparency) {
        canvas.save();canvas.translate(cx-size/2,cy-size/2);canvas.scale(size/24,size/24);
        stroke(1.65f,state.connected?0xFFF5F8FF:0x75FFFFFF);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(new RectF(9,6,15,18),3,3,paint);
        if(!transparency) {
            canvas.drawLine(6,9,6,15,paint);canvas.drawLine(3,7,3,17,paint);
            canvas.drawLine(18,9,18,15,paint);canvas.drawLine(21,7,21,17,paint);
        } else {
            canvas.drawArc(new RectF(3,5,13,19),125,110,false,paint);
            canvas.drawArc(new RectF(-1,1,17,23),130,100,false,paint);
            canvas.drawArc(new RectF(11,5,21,19),-55,110,false,paint);
            canvas.drawArc(new RectF(7,1,25,23),-50,100,false,paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);canvas.restore();
    }
}
