package com.shilapi.xcertplay.hud;

import java.util.EnumSet;

public final class HudDrawing {
    public static final int WIDTH=800,HEIGHT=480;
    private static final int CYAN=0xff54d5ff,WHITE=0xfff4fbff,MUTED=0xffc8e2ec,CALL_GREEN=0xff30d158,CALL_RING=0x8c30d158,CALL_MUTED=0xffff9f0a;
    static final int OTHER_LANE_COLOR=0xffaec6d0;
    /** The light parts of a lane sign, a lane not to take or a recommended lane's other ways, are this opaque as a whole. */
    static final double LIGHT_LANE_OPACITY=.8,OTHER_LANE_STROKE=2.8,PREFERRED_LANE_STROKE=3.2,BRANCHED_LANE_STROKE_SCALE=.8,HIGHLIGHT_OUTLINE=1.2;
    static final double LANE_INSET=8,LANE_PITCH=48,TRIP_GAP=24;
    /** The cap height of the font, in ems; a row's centre is the middle of its capitals. */
    private static final double CAP=.71;
    // The firmware speed is fixed in window pixels, digits around x 400..430 and y 331..371, with no regard to the
    // height and scale settings, so the call is placed in window pixels too, right of a three-digit speed.
    static final double CALL_LEFT=510,CALL_RIGHT=780,CALL_Y=351,CALL_DISC=32,CALL_RING_GAP=4,CALL_GAP=12,CALL_LINE_GAP=6,CALL_MIC=16,CALL_MIC_GAP=12;
    static final float CALL_NAME=22,CALL_STATUS=17;
    private static final HudPath MIC=new HudPath("M9 5.5 A3 3 0 0 1 15 5.5 V10.5 A3 3 0 0 1 9 10.5 Z M5.5 11 A6.5 6.5 0 0 0 18.5 11 M12 17.5 V21.5"),MIC_SLASH=new HudPath("M4 3 L20 21");
    private static final HudPath PHONE=new HudPath("M6.6 10.8 A15.1 15.1 0 0 0 13.2 17.4 L15.4 15.2 A1 1 0 0 1 16.4 14.95 C17.5 15.32 18.7 15.52 20 15.52 A1 1 0 0 1 21 16.52 V20 A1 1 0 0 1 20 21 A17 17 0 0 1 3 4 A1 1 0 0 1 4 3 H7.5 A1 1 0 0 1 8.5 4 C8.5 5.25 8.7 6.45 9.07 7.57 A1 1 0 0 1 8.82 8.57 Z");
    private HudDrawing() {}
    public static void draw(HudCanvas canvas,HudFrame frame,HudSettings settings) {
        draw(canvas,frame,null,settings);
    }
    public static void draw(HudCanvas canvas,HudFrame frame,HudCall call,HudSettings settings) {
        float icon=56,numberFont=58,unitFont=25,roadFont=28,lane=42,trip=22;
        double k=icon/56.0,stroke=ManeuverIcon.STROKE*icon/24;
        if(call!=null)call(canvas,call);
        if(frame==null)return;
        canvas=new ScaledCanvas(canvas,settings.scalePercent,settings.centerY);
        double left=64,right=736,y=settings.centerY,x0=left+icon+20*k;
        ManeuverIcon.draw(canvas,frame.kind,left+icon/2,y,icon,stroke,CYAN,frame.exitNumber,frame.leftHandTraffic,0xff000000);
        float baseline=(float)(y+CAP/2*numberFont);double x=x0;
        canvas.text(frame.number,(float)x,baseline,numberFont,WHITE,true);x+=canvas.measure(frame.number,numberFont,true)+5*k;
        canvas.text(frame.unit,(float)x,baseline,unitFont,MUTED,false);x+=canvas.measure(frame.unit,unitFont,false)+20*k;
        canvas.text(fit(canvas,frame.instructionLabel,roadFont,false,(float)(right-x)),(float)x,baseline,roadFont,WHITE,false);
        double rowY=y+icon/2+12*k,tripX=x0;
        HudLane[] lanes=settings.showLanes ? frame.lanes.toArray(new HudLane[0]) : new HudLane[0];
        if(lanes.length>0){
            double bx=x0+LANE_INSET*k,pitch=LANE_PITCH*k;
            for(int i=0;i<lanes.length;i++)lane(canvas,lanes[i],bx+i*pitch,rowY,lane);
            tripX=bx+(lanes.length-1)*pitch+lane+TRIP_GAP*k;
        }
        if(settings.showTrip && frame.hasTrip)trip(canvas,frame,(float)tripX,(float)(rowY+lane/2+CAP/2*trip),trip,(float)(right-tripX));
    }
    public static void lane(HudCanvas canvas,HudLane lane,double x,double y,double size){
        HudLaneDirection highlight=lane.preferred ? lane.highlighted : null;
        EnumSet<HudLaneDirection> directions=EnumSet.noneOf(HudLaneDirection.class);
        directions.addAll(lane.directions);
        if(highlight!=null)directions.add(highlight);
        boolean branched=directions.size()>1;
        double width=(lane.preferred ? PREFERRED_LANE_STROKE : OTHER_LANE_STROKE)*(branched ? BRANCHED_LANE_STROKE_SCALE : 1);
        boolean light=!lane.preferred || highlight!=null && branched;
        int color=!lane.preferred ? OTHER_LANE_COLOR : highlight==null ? CYAN : MUTED;
        if(light)canvas.beginOpacity(LIGHT_LANE_OPACITY);
        for(HudLaneDirection direction:directions)if(direction!=highlight)ManeuverIcon.laneAt(canvas,directions,direction,x,y,size,width,color);
        if(light)canvas.endOpacity();
        if(highlight!=null && branched)ManeuverIcon.laneOutlineAt(canvas,directions,highlight,x,y,size,width,HIGHLIGHT_OUTLINE,0xff000000);
        if(highlight!=null)ManeuverIcon.laneAt(canvas,directions,highlight,x,y,size,width,CYAN);
    }
    private static void trip(HudCanvas canvas,HudFrame frame,float x,float baseline,float size,float available){
        boolean time=!frame.tripMinutes.isEmpty(),distance=!frame.tripNumber.isEmpty();
        String[] spans={frame.tripMinutes,time ? "\u00a0"+frame.minutesUnit : "",time && distance ? "\u00a0\u00a0·\u00a0\u00a0" : "",frame.tripNumber,distance ? "\u00a0"+frame.tripUnit : ""};
        int[] colors={WHITE,MUTED,MUTED,WHITE,MUTED};float width=0;
        for(String value:spans)width+=canvas.measure(value,size,false);
        if(width>available)return;
        for(int i=0;i<spans.length;i++){canvas.text(spans[i],x,baseline,size,colors[i],false);x+=canvas.measure(spans[i],size,false);}
    }
    /** Who calls over how the call stands; the name alone gives way to the width, never the time or the microphone. */
    private static void call(HudCanvas canvas,HudCall call){
        double radius=CALL_DISC/2,glyph=CALL_DISC*.56,x=CALL_LEFT+CALL_DISC+CALL_GAP;
        boolean connected=call.phase==CarPlayCallCard.Phase.ACTIVE;
        if(!connected)canvas.circle((float)(CALL_LEFT+radius),(float)CALL_Y,(float)(radius+CALL_RING_GAP),2,CALL_RING);
        canvas.dot(12,12,12,CALL_LEFT,CALL_Y-radius,CALL_DISC/24,CALL_GREEN);
        canvas.fill(PHONE,CALL_LEFT+radius-glyph/2,CALL_Y-glyph/2,glyph/24,WHITE);
        float width=(float)(CALL_RIGHT-x);
        if(call.name.isEmpty()){
            String status=connected ? call.status : fit(canvas,call.status,CALL_NAME,true,width);
            canvas.text(status,(float)x,(float)(CALL_Y+CAP/2*CALL_NAME),CALL_NAME,WHITE,true);
            if(call.muted)mic(canvas,x+canvas.measure(status,CALL_NAME,true)+CALL_MIC_GAP,CALL_Y);
            return;
        }
        double nameCap=CAP*CALL_NAME,statusCap=CAP*CALL_STATUS,nameBaseline=CALL_Y-(nameCap+CALL_LINE_GAP+statusCap)/2+nameCap;
        double statusBaseline=nameBaseline+CALL_LINE_GAP+statusCap;
        canvas.text(fit(canvas,call.name,CALL_NAME,true,width),(float)x,(float)nameBaseline,CALL_NAME,WHITE,true);
        String status=connected ? call.status : fit(canvas,call.status,CALL_STATUS,false,width);
        int color=connected ? WHITE : call.phase==CarPlayCallCard.Phase.RINGING ? CALL_GREEN : MUTED;
        canvas.text(status,(float)x,(float)statusBaseline,CALL_STATUS,color,connected);
        if(call.muted)mic(canvas,x+canvas.measure(status,CALL_STATUS,true)+CALL_MIC_GAP,statusBaseline-statusCap/2);
    }
    /** The microphone crossed out, [CALL_MIC] square, its left edge at [x] and centred on [y]. */
    private static void mic(HudCanvas canvas,double x,double y){
        double top=y-CALL_MIC/2,scale=CALL_MIC/24;
        canvas.path(MIC,x,top,scale,2.4,CALL_MUTED,1);
        canvas.path(MIC_SLASH,x,top,scale,5.6,0xff000000,1);
        canvas.path(MIC_SLASH,x,top,scale,2.4,CALL_MUTED,1);
    }
    private static final class ScaledCanvas implements HudCanvas {
        private final HudCanvas delegate;private final float scale,y;private final double vectorScale;
        ScaledCanvas(HudCanvas delegate,int percent,int y){this.delegate=delegate;this.scale=percent/100f;this.vectorScale=percent/100.0;this.y=y;}
        private float x(float value){return 400+scale*(value-400);}
        private float y(float value){return y+scale*(value-y);}
        public float measure(String value,float size,boolean bold){return delegate.measure(value,size*scale,bold)/scale;}
        public void text(String value,float x,float baseline,float size,int color,boolean bold){delegate.text(value,x(x),y(baseline),size*scale,color,bold);}
        public float measure(String value,float size,int weight,float tracking){return delegate.measure(value,size*scale,weight,tracking*scale)/scale;}
        public void text(String value,float x,float baseline,float size,int color,int weight,float tracking){delegate.text(value,x(x),y(baseline),size*scale,color,weight,tracking*scale);}
        public void line(float[] points,float width,int color){float[] scaled=new float[points.length];for(int i=0;i<points.length;i+=2){scaled[i]=x(points[i]);scaled[i+1]=y(points[i+1]);}delegate.line(scaled,width*scale,color);}
        public void circle(float x,float y,float radius,float width,int color){delegate.circle(x(x),y(y),radius*scale,width*scale,color);}
        public void path(HudPath path,double x,double y,double pathScale,double width,int color,double opacity){delegate.path(path,400+vectorScale*(x-400),this.y+vectorScale*(y-this.y),pathScale*vectorScale,width,color,opacity);}
        public void fill(HudPath path,double x,double y,double pathScale,int color){delegate.fill(path,400+vectorScale*(x-400),this.y+vectorScale*(y-this.y),pathScale*vectorScale,color);}
        public void dot(double cx,double cy,double radius,double x,double y,double pathScale,int color){delegate.dot(cx,cy,radius,400+vectorScale*(x-400),this.y+vectorScale*(y-this.y),pathScale*vectorScale,color);}
        public void beginOpacity(double opacity){delegate.beginOpacity(opacity);}
        public void endOpacity(){delegate.endOpacity();}
    }
    public static String fit(HudCanvas canvas,String text,float size,boolean bold,float width) {
        if (width<size || text==null || text.isEmpty()) return "";
        if (canvas.measure(text,size,bold)<=width) return text;
        int count=text.codePointCount(0,text.length()),low=0,high=count;
        while(low<high) {
            int middle=(low+high+1)/2;
            String candidate=trimEnd(text.substring(0,text.offsetByCodePoints(0,middle)))+"…";
            if(canvas.measure(candidate,size,bold)<=width) low=middle;else high=middle-1;
        }
        return low==0 ? "" : trimEnd(text.substring(0,text.offsetByCodePoints(0,low)))+"…";
    }
    private static String trimEnd(String value){int end=value.length();while(end>0 && (Character.isWhitespace(value.charAt(end-1)) || Character.isSpaceChar(value.charAt(end-1))))end--;return value.substring(0,end);}
}
