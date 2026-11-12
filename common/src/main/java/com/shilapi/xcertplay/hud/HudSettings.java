package com.shilapi.xcertplay.hud;

public final class HudSettings {
    public static final int DEFAULT_Y=215,MIN_Y=72,MAX_Y=360,DEFAULT_SCALE=65,MIN_SCALE=50,MAX_SCALE=100;
    public final int centerY,scalePercent;
    public final boolean showLanes,showTrip;
    public HudSettings(int centerY,int scalePercent,boolean showLanes,boolean showTrip){
        if(centerY<MIN_Y || centerY>MAX_Y || scalePercent<MIN_SCALE || scalePercent>MAX_SCALE)throw new IllegalArgumentException("LAYOUT_SETTINGS");
        this.centerY=centerY;this.scalePercent=scalePercent;this.showLanes=showLanes;this.showTrip=showTrip;
    }
    public static HudSettings minimal(){return new HudSettings(DEFAULT_Y,DEFAULT_SCALE,false,false);}
}
