package com.shilapi.xcertplay.hud;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

public final class AndroidHudCanvas implements HudCanvas {
    private final Canvas canvas;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public AndroidHudCanvas(Canvas canvas) { this.canvas=canvas; }
    private void font(float size,int weight,float tracking) {
        paint.setTextSize(size);paint.setLetterSpacing(size==0 ? 0 : tracking/size);
        paint.setTypeface(weight==700 ? Typeface.create("sans-serif",Typeface.BOLD) : Typeface.create(weight==500 ? "sans-serif-medium" : "sans-serif",Typeface.NORMAL));
    }
    @Override public float measure(String value,float size,boolean bold) { return measure(value,size,bold ? 500 : 400,0); }
    @Override public void text(String value,float x,float baseline,float size,int color,boolean bold) { text(value,x,baseline,size,color,bold ? 500 : 400,0); }
    @Override public float measure(String value,float size,int weight,float tracking) { font(size,weight,tracking);return paint.measureText(value); }
    @Override public void text(String value,float x,float baseline,float size,int color,int weight,float tracking) {
        font(size,weight,tracking);paint.setColor(color);paint.setStyle(Paint.Style.FILL);canvas.drawText(value,x,baseline,paint);
    }
    private void stroke(float width,int color){
        paint.setPathEffect(null);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void line(float[] points,float width,int color) {
        stroke(width,color);Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);canvas.drawPath(path,paint);
    }
    @Override public void circle(float x,float y,float radius,float width,int color) {
        stroke(width,color);canvas.drawCircle(x,y,radius,paint);
    }
    @Override public void path(HudPath source,double x,double y,double scale,double width,int color,double opacity){
        final Path path=new Path();source.replay(new HudPath.Sink(){
            public void move(float x,float y){path.moveTo(x,y);}
            public void line(float x,float y){path.lineTo(x,y);}
            public void quad(float cx,float cy,float x,float y){path.quadTo(cx,cy,x,y);}
            public void cubic(float x1,float y1,float x2,float y2,float x,float y){path.cubicTo(x1,y1,x2,y2,x,y);}
            public void arc(float cx,float cy,float radius,float start,float sweep){path.arcTo(new RectF(cx-radius,cy-radius,cx+radius,cy+radius),start,sweep);}
            public void close(){path.close();}
        });
        canvas.save();canvas.translate((float)x,(float)y);canvas.scale((float)scale,(float)scale);stroke((float)width,color);paint.setAlpha((int)Math.round(((color>>>24)&255)*opacity));canvas.drawPath(path,paint);canvas.restore();
    }
    @Override public void rule(double x,double top,double bottom,double width,int color,double opacity,double dash,double gap){
        stroke((float)width,color);paint.setStrokeCap(Paint.Cap.BUTT);paint.setAlpha((int)Math.round(((color>>>24)&255)*opacity));
        paint.setPathEffect(dash>0 ? new DashPathEffect(new float[]{(float)dash,(float)gap},0) : null);canvas.drawLine((float)x,(float)top,(float)x,(float)bottom,paint);paint.setPathEffect(null);
    }
    @Override public void dot(double cx,double cy,double radius,double x,double y,double scale,int color){paint.setColor(color);paint.setStyle(Paint.Style.FILL);canvas.save();canvas.translate((float)x,(float)y);canvas.scale((float)scale,(float)scale);canvas.drawCircle((float)cx,(float)cy,(float)radius,paint);canvas.restore();}
    @Override public void beginOpacity(double opacity){Paint layer=new Paint();layer.setAlpha((int)Math.round(255*opacity));canvas.saveLayer(null,layer);}
    @Override public void endOpacity(){canvas.restore();}
}
