package com.shilapi.xcertplay.hud;

public final class ManeuverIcon {
    private static final class Part {
        final HudPath path;final double opacity,x,y,radius;
        Part(String data,double opacity){path=new HudPath(data);this.opacity=opacity;x=0;y=0;radius=0;}
        Part(double x,double y,double radius){path=null;opacity=1;this.x=x;this.y=y;this.radius=radius;}
    }
    private static final Part[] UNKNOWN={new Part("M12 3 L21 12 L12 21 L3 12 Z",1),new Part(12.0,12.0,1.9)};
    private static final Part[] NO_TURN={new Part("M12 21 V17.5",1),new Part("M12 13.75 V10.25",1),new Part("M12 6.5 V3",1)};
    private static final Part[] STRAIGHT={new Part("M12 21 V4",1),new Part("M6.5 9.5 L12 4 L17.5 9.5",1)};
    private static final Part[] LEFT={new Part("M17 21 V13 Q17 9 13 9 H6.5",1),new Part("M11 4.5 L6.5 9 L11 13.5",1)};
    private static final Part[] SLIGHT_LEFT={new Part("M16 21 V16.5 Q16 14 14.2 12.2 L8 6",1),new Part("M8 12.5 V6 H14.5",1)};
    private static final Part[] SHARP_LEFT={new Part("M17 21 V8.5 Q17 5 14.5 7.5 L7 15",1),new Part("M7 9 V15 H13",1)};
    private static final Part[] UTURN={new Part("M7 20 V11 A5 5 0 0 1 17 11 V20",1),new Part("M3.5 16.5 L7 20 L10.5 16.5",1),new Part("M13.5 16.5 L17 20 L20.5 16.5",1)};
    private static final Part[] KEEP_LEFT={new Part("M12 16 C12 12.5 17 12 17 8 V5",0.4),new Part("M12 21 V16 C12 12.5 7 12 7 8 V4",1),new Part("M3 8 L7 4 L11 8",1)};
    private static final Part[] EXIT_LEFT={new Part("M16 21 V4",0.4),new Part("M16 21 V15 C16 11.5 12 10.5 8 6.5",1),new Part("M8 11.5 V6.5 H13",1)};
    private static final Part[] ROUNDABOUT_ENTER={new Part("M16.5 7 A4.5 4.5 0 1 1 7.5 7 A4.5 4.5 0 1 1 16.5 7",1),new Part("M12 22 V15.5",1),new Part("M8.5 19 L12 15.5 L15.5 19",1)};
    private static final Part[] ROUNDABOUT_EXIT={new Part("M16.5 15 A4.5 4.5 0 1 1 7.5 15 A4.5 4.5 0 1 1 16.5 15",1),new Part("M12 19.5 V22.5",1),new Part("M12 10.5 V2.5",1),new Part("M8.5 6 L12 2.5 L15.5 6",1)};
    private static final Part[] FERRY_ENTER={new Part("M3.5 12.5 H20.5 L18 17 H6 Z",1),new Part("M7.5 12.5 V9 H16.5 V12.5",1),new Part("M3 21 Q5.25 19.5 7.5 21 T12 21 T16.5 21 T21 21",1),new Part("M12 1.5 V6",1),new Part("M9.5 3.5 L12 6 L14.5 3.5",1)};
    private static final Part[] FERRY_EXIT={new Part("M3.5 12.5 H20.5 L18 17 H6 Z",1),new Part("M7.5 12.5 V9 H16.5 V12.5",1),new Part("M3 21 Q5.25 19.5 7.5 21 T12 21 T16.5 21 T21 21",1),new Part("M12 6 V1.5",1),new Part("M9.5 4 L12 1.5 L14.5 4",1)};
    private static final Part[] ARRIVE={new Part("M12 21 V11.5",1),new Part(12.0,7.0,4.3)};
    private static final Part[] RIGHT={new Part("M 7 21 V 13 Q 7 9 11 9 H 17.5",1),new Part("M 13 4.5 L 17.5 9 L 13 13.5",1)};
    private static final Part[] SLIGHT_RIGHT={new Part("M 8 21 V 16.5 Q 8 14 9.8 12.2 L 16 6",1),new Part("M 16 12.5 V 6 H 9.5",1)};
    private static final Part[] SHARP_RIGHT={new Part("M 7 21 V 8.5 Q 7 5 9.5 7.5 L 17 15",1),new Part("M 17 9 V 15 H 11",1)};
    private static final Part[] KEEP_RIGHT={new Part("M 12 16 C 12 12.5 7 12 7 8 V 5",0.4),new Part("M 12 21 V 16 C 12 12.5 17 12 17 8 V 4",1),new Part("M 21 8 L 17 4 L 13 8",1)};
    private static final Part[] EXIT_RIGHT={new Part("M 8 21 V 4",0.4),new Part("M 8 21 V 15 C 8 11.5 12 10.5 16 6.5",1),new Part("M 16 11.5 V 6.5 H 11",1)};
    private static final HudPath[] LANE_STRAIGHT={new HudPath("M12 23 V3.5"),new HudPath("M9.17 6.33 L12 3.5 L14.83 6.33")};
    private static final HudPath[] LANE_SLIGHT_RIGHT={new HudPath("M12 23 V16.5 Q12 14 13.77 12.23 L19.42 6.58"),new HudPath("M15.42 6.58 L19.42 6.58 L19.42 10.58")};
    private static final HudPath[] LANE_RIGHT={new HudPath("M12 23 V16.5 Q12 14 14.5 14 L22.5 14"),new HudPath("M19.67 11.17 L22.5 14 L19.67 16.83")};
    private static final HudPath[] LANE_SHARP_RIGHT={new HudPath("M12 23 V16.5 Q12 14 13.77 15.77 L19.42 21.42"),new HudPath("M19.42 17.42 L19.42 21.42 L15.42 21.42")};
    private static final HudPath[] LANE_SLIGHT_LEFT={new HudPath("M12 23 V16.5 Q12 14 10.23 12.23 L4.58 6.58"),new HudPath("M4.58 10.58 L4.58 6.58 L8.58 6.58")};
    private static final HudPath[] LANE_LEFT={new HudPath("M12 23 V16.5 Q12 14 9.5 14 L1.5 14"),new HudPath("M4.33 16.83 L1.5 14 L4.33 11.17")};
    private static final HudPath[] LANE_SHARP_LEFT={new HudPath("M12 23 V16.5 Q12 14 10.23 15.77 L4.58 21.42"),new HudPath("M8.58 21.42 L4.58 21.42 L4.58 17.42")};
    private static final HudPath[] LANE_UTURN_LEFT={new HudPath("M12 23 V8 A4 4 0 0 0 4 8 V17"),new HudPath("M6.83 14.17 L4 17 L1.17 14.17")};
    private static final HudPath[] LANE_UTURN_RIGHT={new HudPath("M12 23 V8 A4 4 0 0 1 20 8 V17"),new HudPath("M22.83 14.17 L20 17 L17.17 14.17")};
    private static final HudPath EXIT_RING=new HudPath("M21 10.5 A9 9 0 1 1 3 10.5 A9 9 0 1 1 21 10.5");
    private static final HudPath EXIT_STEM=new HudPath("M12 22.5 V19.5");
    private ManeuverIcon() {}
    public static void draw(HudCanvas canvas,HudManeuver kind,double x,double y,double size,double stroke,int color,int exit) {
        draw(canvas,kind.name(),x-size/2,y-size/2,size,stroke,color,exit);
    }
    private static void draw(HudCanvas canvas,String kind,double x,double y,double size,double stroke,int color,int exit){
        double scale=size/24;
        if(kind.equals("ROUNDABOUT_EXIT") && exit>0){
            canvas.path(EXIT_RING,x,y,scale,stroke/scale,color,1);canvas.path(EXIT_STEM,x,y,scale,stroke/scale,color,1);
            String number=Integer.toString(exit);boolean two=exit>=10;float font=(float)((two ? 10.5 : 12.5)*scale),tracking=two ? (float)(-.4*scale) : 0;
            float numberX=(float)(x+(12+(two ? .2 : 0))*scale-canvas.measure(number,font,700,tracking)/2);
            canvas.text(number,numberX,(float)(y+10.5*scale+.355*font),font,color,700,tracking);return;
        }
        for(Part part:parts(kind)){
            if(part.path!=null)canvas.path(part.path,x,y,scale,stroke/scale,color,part.opacity);
            else canvas.dot(part.x,part.y,part.radius,x,y,scale,color);
        }
    }
    private static Part[] parts(String kind){
        switch(kind){
            case "UNKNOWN":return UNKNOWN;
            case "NO_TURN":return NO_TURN;
            case "STRAIGHT":return STRAIGHT;
            case "LEFT":return LEFT;
            case "SLIGHT_LEFT":return SLIGHT_LEFT;
            case "SHARP_LEFT":return SHARP_LEFT;
            case "UTURN":return UTURN;
            case "KEEP_LEFT":return KEEP_LEFT;
            case "EXIT_LEFT":return EXIT_LEFT;
            case "ROUNDABOUT_ENTER":return ROUNDABOUT_ENTER;
            case "ROUNDABOUT_EXIT":return ROUNDABOUT_EXIT;
            case "FERRY_ENTER":return FERRY_ENTER;
            case "FERRY_EXIT":return FERRY_EXIT;
            case "ARRIVE":return ARRIVE;
            case "RIGHT":return RIGHT;
            case "SLIGHT_RIGHT":return SLIGHT_RIGHT;
            case "SHARP_RIGHT":return SHARP_RIGHT;
            case "KEEP_RIGHT":return KEEP_RIGHT;
            case "EXIT_RIGHT":return EXIT_RIGHT;
            default:return UNKNOWN;
        }
    }
    public static void lane(HudCanvas canvas,HudLaneDirection direction,double x,double y,double size,double stroke,int color){
        laneAt(canvas,direction,x-size/2,y-size/2,size,stroke,color);
    }
    public static void laneAt(HudCanvas canvas,HudLaneDirection direction,double x,double y,double size,double stroke,int color){
        HudPath[] paths;
        switch(direction){
            case STRAIGHT:paths=LANE_STRAIGHT;break;
            case SLIGHT_RIGHT:paths=LANE_SLIGHT_RIGHT;break;
            case RIGHT:paths=LANE_RIGHT;break;
            case SHARP_RIGHT:paths=LANE_SHARP_RIGHT;break;
            case SLIGHT_LEFT:paths=LANE_SLIGHT_LEFT;break;
            case LEFT:paths=LANE_LEFT;break;
            case SHARP_LEFT:paths=LANE_SHARP_LEFT;break;
            case UTURN_LEFT:paths=LANE_UTURN_LEFT;break;
            case UTURN_RIGHT:paths=LANE_UTURN_RIGHT;break;
            default:throw new IllegalArgumentException("LANE_DIRECTION");
        }
        for(HudPath path:paths)canvas.path(path,x,y,size/24,stroke,color,1);
    }
}
