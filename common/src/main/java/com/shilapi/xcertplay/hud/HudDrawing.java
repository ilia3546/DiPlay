package com.shilapi.xcertplay.hud;

public final class HudDrawing {
    public static final int WIDTH=800,HEIGHT=480;
    private static final int CYAN=0xff54d5ff,WHITE=0xfff4fbff,MUTED=0xffc8e2ec,INACTIVE=0xff7a97a3,CALL_GREEN=0xff30d158,CALL_MUTED=0xffff9f0a;
    static final int OTHER_LANE_COLOR=0xffaec6d0;
    static final double OTHER_LANE_OPACITY=1,OTHER_LANE_STROKE=2.2;
    // The firmware speed is fixed in window pixels, digits around x 400..430 and y 331..371, with no regard to the
    // height and scale settings, so the call is placed in window pixels too, right of a three-digit speed.
    static final double CALL_LEFT=510,CALL_RIGHT=780,CALL_Y=351;
    private static final HudPath LANE_UNDERLINE=new HudPath("M8 26.5 H16");
    private static final HudPath PHONE=new HudPath("M6.6 10.8 A15.1 15.1 0 0 0 13.2 17.4 L15.4 15.2 A1 1 0 0 1 16.4 14.95 C17.5 15.32 18.7 15.52 20 15.52 A1 1 0 0 1 21 16.52 V20 A1 1 0 0 1 20 21 A17 17 0 0 1 3 4 A1 1 0 0 1 4 3 H7.5 A1 1 0 0 1 8.5 4 C8.5 5.25 8.7 6.45 9.07 7.57 A1 1 0 0 1 8.82 8.57 Z");
    private HudDrawing() {}
    public static void draw(HudCanvas canvas,HudFrame frame,HudSettings settings) {
        draw(canvas,frame,null,settings);
    }
    public static void draw(HudCanvas canvas,HudFrame frame,HudCall call,HudSettings settings) {
        float icon,numberFont,unitFont,roadFont,stroke,lane,trip;
        switch (settings.variant) {
            case COMPACT:icon=46;numberFont=48;unitFont=21;roadFont=24;stroke=5;lane=24;trip=20;break;
            case BOLD:icon=66;numberFont=68;unitFont=29;roadFont=32;stroke=7.5f;lane=32;trip=25;break;
            default:icon=56;numberFont=58;unitFont=25;roadFont=28;stroke=6;lane=28;trip=22;break;
        }
        double k=icon/56.0;
        if(call!=null)call(canvas,call,CALL_LEFT,CALL_RIGHT,CALL_Y,28*k,(float)(21*k),(float)(16*k),k);
        if(frame==null)return;
        canvas=new ScaledCanvas(canvas,settings.scalePercent,settings.centerY);
        double left=64,right=736,y=settings.centerY,x0=left+icon+20*k;
        ManeuverIcon.draw(canvas,frame.kind,left+icon/2,y,icon,stroke,CYAN,frame.exitNumber);
        float baseline=(float)(y+.355*numberFont);double x=x0;
        canvas.text(frame.number,(float)x,baseline,numberFont,WHITE,true);x+=canvas.measure(frame.number,numberFont,true)+5*k;
        canvas.text(frame.unit,(float)x,baseline,unitFont,MUTED,false);x+=canvas.measure(frame.unit,unitFont,false)+20*k;
        canvas.text(fit(canvas,frame.instructionLabel,roadFont,false,(float)(right-x)),(float)x,baseline,roadFont,WHITE,false);
        double rowY=y+icon/2+12*k,tripX=x0;
        HudLane[] lanes=settings.showLanes ? frame.lanes.toArray(new HudLane[0]) : new HudLane[0];
        if(lanes.length>0){
            double pitch=lane*1.5,gap=pitch-lane,bx=x0+gap/2;
            for(int i=0;i<lanes.length;i++)lane(canvas,lanes[i],bx+i*pitch,rowY,lane);
            double top=rowY+lane*.05,bottom=rowY+lane*1.12;
            for(int i=0;i<lanes.length-1;i++)separator(canvas,bx+i*pitch+lane+gap/2,top,bottom,k,false);
            separator(canvas,bx-gap/2,top,bottom,k,true);
            separator(canvas,bx+(lanes.length-1)*pitch+lane+gap/2,top,bottom,k,true);
            tripX=bx+(lanes.length-1)*pitch+lane+gap/2+14*k;
        }
        if(settings.showTrip && frame.hasTrip)trip(canvas,frame,(float)tripX,(float)(rowY+lane/2+.355*trip),trip,(float)(right-tripX));
    }
    public static void lane(HudCanvas canvas,HudLane lane,double x,double y,double size){
        HudLaneDirection highlight=lane.preferred ? lane.highlighted : null;
        double width=lane.preferred ? 2.6 : OTHER_LANE_STROKE;
        canvas.beginOpacity(lane.preferred ? 1 : OTHER_LANE_OPACITY);
        for(HudLaneDirection direction:lane.directions)if(direction!=highlight)ManeuverIcon.laneAt(canvas,direction,x,y,size,width,lane.preferred ? MUTED : OTHER_LANE_COLOR);
        if(highlight!=null)ManeuverIcon.laneAt(canvas,highlight,x,y,size,width,CYAN);
        if(lane.preferred)canvas.path(LANE_UNDERLINE,x,y,size/24,2.2,MUTED,1);
        canvas.endOpacity();
    }
    private static void separator(HudCanvas canvas,double x,double top,double bottom,double k,boolean edge){
        canvas.rule(x,top,bottom,1.4*k,INACTIVE,edge ? .45 : .75,edge ? 0 : 3.5*k,3*k);
    }
    private static void trip(HudCanvas canvas,HudFrame frame,float x,float baseline,float size,float available){
        boolean time=!frame.tripMinutes.isEmpty(),distance=!frame.tripNumber.isEmpty();
        String[] spans={frame.tripMinutes,time ? "\u00a0"+frame.minutesUnit : "",time && distance ? "\u00a0\u00a0·\u00a0\u00a0" : "",frame.tripNumber,distance ? "\u00a0"+frame.tripUnit : ""};
        int[] colors={WHITE,MUTED,MUTED,WHITE,MUTED};float width=0;
        for(String value:spans)width+=canvas.measure(value,size,false);
        if(width>available)return;
        for(int i=0;i<spans.length;i++){canvas.text(spans[i],x,baseline,size,colors[i],false);x+=canvas.measure(spans[i],size,false);}
    }
    private static void call(HudCanvas canvas,HudCall call,double left,double right,double y,double size,float textFont,float mutedFont,double k){
        double gap=10*k,mutedGap=10*k;
        double muted=call.mutedLabel.isEmpty() ? 0 : mutedGap+canvas.measure(call.mutedLabel,mutedFont,true);
        String text=fit(canvas,call.text,textFont,true,(float)(right-left-size-gap-muted));
        double x=left;
        canvas.dot(12,12,12,x,y-size/2,size/24,CALL_GREEN);
        double glyph=size*.56;
        canvas.fill(PHONE,x+(size-glyph)/2,y-glyph/2,glyph/24,WHITE);
        x+=size+gap;
        canvas.text(text,(float)x,(float)(y+.355*textFont),textFont,WHITE,true);
        if(muted==0)return;
        x+=canvas.measure(text,textFont,true)+mutedGap;
        canvas.text(call.mutedLabel,(float)x,(float)(y+.355*mutedFont),mutedFont,CALL_MUTED,true);
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
        public void rule(double x,double top,double bottom,double width,int color,double opacity,double dash,double gap){delegate.rule(400+vectorScale*(x-400),y+vectorScale*(top-y),y+vectorScale*(bottom-y),width*vectorScale,color,opacity,dash*vectorScale,gap*vectorScale);}
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
