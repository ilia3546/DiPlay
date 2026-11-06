package com.shilapi.xcertplay.hud;

public interface HudCanvas {
    float measure(String value,float size,boolean bold);
    void text(String value,float x,float baseline,float size,int color,boolean bold);
    float measure(String value,float size,int weight,float tracking);
    void text(String value,float x,float baseline,float size,int color,int weight,float tracking);
    void line(float[] points,float width,int color);
    void circle(float x,float y,float radius,float width,int color);
    void path(HudPath path,double x,double y,double scale,double width,int color,double opacity);
    void rule(double x,double top,double bottom,double width,int color,double opacity,double dash,double gap);
    void dot(double cx,double cy,double radius,double x,double y,double scale,int color);
    void beginOpacity(double opacity);
    void endOpacity();
}
