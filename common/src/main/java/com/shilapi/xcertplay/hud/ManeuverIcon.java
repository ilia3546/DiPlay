package com.shilapi.xcertplay.hud;

public final class ManeuverIcon {
    private static final class Part {
        final HudPath path;final boolean fill;final double opacity,x,y,radius;
        Part(String data,double opacity){this(new HudPath(data),false,opacity);}
        private Part(HudPath path,boolean fill,double opacity){this.path=path;this.fill=fill;this.opacity=opacity;x=0;y=0;radius=0;}
        Part(double x,double y,double radius){path=null;fill=false;opacity=1;this.x=x;this.y=y;this.radius=radius;}
        static Part fill(String data){return new Part(new HudPath(data),true,1);}
    }
    private static final Part[] UNKNOWN={new Part("M12 3 L21 12 L12 21 L3 12 Z",1),new Part(12.0,12.0,1.9)};
    private static final Part[] NO_TURN={new Part("M12 21 V17.5",1),new Part("M12 13.75 V10.25",1),new Part("M12 6.5 V3",1)};
    private static final Part[] STRAIGHT={new Part("M12 21.5 V6.6",1),Part.fill("M11.47 2.73 Q12 2 12.53 2.73 L15.07 6.27 Q15.6 7 14.7 7 L9.3 7 Q8.4 7 8.93 6.27 Z")};
    private static final Part[] LEFT={new Part("M16 21.5 V13.5 Q16 9.5 12 9.5 H7.1",1),Part.fill("M3.23 10.03 Q2.5 9.5 3.23 8.97 L6.77 6.43 Q7.5 5.9 7.5 6.8 L7.5 12.2 Q7.5 13.1 6.77 12.57 Z")};
    private static final Part[] SLIGHT_LEFT={new Part("M15.5 21.5 V16 Q15.5 13.5 13.73 11.73 L8.75 6.75",1),Part.fill("M5.64 4.39 Q5.5 3.5 6.39 3.64 L10.69 4.35 Q11.58 4.49 10.94 5.13 L7.13 8.94 Q6.49 9.58 6.35 8.69 Z")};
    private static final Part[] SHARP_LEFT={new Part("M16 21.5 V9.5 Q16 7 14.23 8.77 L9.75 13.25",1),Part.fill("M7.39 16.36 Q6.5 16.5 6.64 15.61 L7.35 11.31 Q7.49 10.42 8.13 11.06 L11.94 14.87 Q12.58 15.51 11.69 15.65 Z")};
    private static final Part[] UTURN={new Part("M16.5 21.5 V10 A4.5 4.5 0 0 0 7.5 10 V16.4",1),Part.fill("M8.03 20.27 Q7.5 21 6.97 20.27 L4.43 16.73 Q3.9 16 4.8 16 L10.2 16 Q11.1 16 10.57 16.73 Z")};
    private static final Part[] KEEP_LEFT={new Part("M12 16 C12 12.5 17 12 17 8 V4.5",0.4),new Part("M12 21.5 V16 C12 12.5 7 12 7 8 V6.6",1),Part.fill("M6.47 2.73 Q7 2 7.53 2.73 L10.07 6.27 Q10.6 7 9.7 7 L4.3 7 Q3.4 7 3.93 6.27 Z")};
    private static final Part[] EXIT_LEFT={new Part("M16 21.5 V3.5",0.4),new Part("M16 21.5 V16 Q16 13.5 14.23 11.73 L9.75 7.25",1),Part.fill("M6.64 4.89 Q6.5 4 7.39 4.14 L11.69 4.85 Q12.58 4.99 11.94 5.63 L8.13 9.44 Q7.49 10.08 7.35 9.19 Z")};
    private static final Part[] ROUNDABOUT_ENTER={new Part("M16.5 6.5 A4.5 4.5 0 1 1 7.5 6.5 A4.5 4.5 0 1 1 16.5 6.5",1),new Part("M12 22.5 V17.6",1),Part.fill("M11.46 14.22 Q12 13.5 12.54 14.22 L14.86 17.28 Q15.4 18 14.5 18 L9.5 18 Q8.6 18 9.14 17.28 Z")};
    private static final Part[] ROUNDABOUT_EXIT={new Part("M16.5 15.5 A4.5 4.5 0 1 1 7.5 15.5 A4.5 4.5 0 1 1 16.5 15.5",1),new Part("M12 20 V22.5",1),new Part("M12 11 V5.6",1),Part.fill("M11.46 2.22 Q12 1.5 12.54 2.22 L14.86 5.28 Q15.4 6 14.5 6 L9.5 6 Q8.6 6 9.14 5.28 Z")};
    private static final Part[] FERRY_ENTER={new Part("M3.5 13 H20.5 L18 17.5 H6 Z",1),new Part("M7.5 13 V9.5 H16.5 V13",1),new Part("M3 21.5 Q5.25 20 7.5 21.5 T12 21.5 T16.5 21.5 T21 21.5",1),new Part("M12 .5 V3.8",1),Part.fill("M12.56 6.3 Q12 7 11.44 6.3 L9.66 4.1 Q9.1 3.4 10 3.4 L14 3.4 Q14.9 3.4 14.34 4.1 Z")};
    private static final Part[] FERRY_EXIT={new Part("M3.5 13 H20.5 L18 17.5 H6 Z",1),new Part("M7.5 13 V9.5 H16.5 V13",1),new Part("M3 21.5 Q5.25 20 7.5 21.5 T12 21.5 T16.5 21.5 T21 21.5",1),new Part("M12 7 V3.7",1),Part.fill("M11.44 1.2 Q12 0.5 12.56 1.2 L14.34 3.4 Q14.9 4.1 14 4.1 L10 4.1 Q9.1 4.1 9.66 3.4 Z")};
    private static final Part[] ARRIVE={new Part("M12 21.5 V11.5",1),new Part(12.0,7.0,4.6)};
    private static final Part[] RIGHT={new Part("M8 21.5 V13.5 Q8 9.5 12 9.5 H16.9",1),Part.fill("M20.77 10.03 Q21.5 9.5 20.77 8.97 L17.23 6.43 Q16.5 5.9 16.5 6.8 L16.5 12.2 Q16.5 13.1 17.23 12.57 Z")};
    private static final Part[] SLIGHT_RIGHT={new Part("M8.5 21.5 V16 Q8.5 13.5 10.27 11.73 L15.25 6.75",1),Part.fill("M18.36 4.39 Q18.5 3.5 17.61 3.64 L13.31 4.35 Q12.42 4.49 13.06 5.13 L16.87 8.94 Q17.51 9.58 17.65 8.69 Z")};
    private static final Part[] SHARP_RIGHT={new Part("M8 21.5 V9.5 Q8 7 9.77 8.77 L14.25 13.25",1),Part.fill("M16.61 16.36 Q17.5 16.5 17.36 15.61 L16.65 11.31 Q16.51 10.42 15.87 11.06 L12.06 14.87 Q11.42 15.51 12.31 15.65 Z")};
    private static final Part[] KEEP_RIGHT={new Part("M12 16 C12 12.5 7 12 7 8 V4.5",0.4),new Part("M12 21.5 V16 C12 12.5 17 12 17 8 V6.6",1),Part.fill("M17.53 2.73 Q17 2 16.47 2.73 L13.93 6.27 Q13.4 7 14.3 7 L19.7 7 Q20.6 7 20.07 6.27 Z")};
    private static final Part[] EXIT_RIGHT={new Part("M8 21.5 V3.5",0.4),new Part("M8 21.5 V16 Q8 13.5 9.77 11.73 L14.25 7.25",1),Part.fill("M17.36 4.89 Q17.5 4 16.61 4.14 L12.31 4.85 Q11.42 4.99 12.06 5.63 L15.87 9.44 Q16.51 10.08 16.65 9.19 Z")};
    private static final Part[] LANE_UTURN_LEFT={new Part("M12 22.5 V9 A4 4 0 0 0 4 9 V15.4",1),Part.fill("M3.47 19.27 Q4 20 4.53 19.27 L7.07 15.73 Q7.6 15 6.7 15 L1.3 15 Q0.4 15 0.93 15.73 Z")};
    private static final Part[] LANE_SHARP_LEFT={new Part("M12 22.5 V10 Q12 7.5 10.23 9.27 L5.75 13.75",1),Part.fill("M2.64 16.11 Q2.5 17 3.39 16.86 L7.69 16.15 Q8.58 16.01 7.94 15.37 L4.13 11.56 Q3.49 10.92 3.35 11.81 Z")};
    private static final Part[] LANE_LEFT={new Part("M12 22.5 V16 Q12 12 8 12 H5.6",1),Part.fill("M1.73 11.47 Q1 12 1.73 12.53 L5.27 15.07 Q6 15.6 6 14.7 L6 9.3 Q6 8.4 5.27 8.93 Z")};
    private static final Part[] LANE_SLIGHT_LEFT={new Part("M12 22.5 V15.5 Q12 13 10.23 11.23 L6.75 7.75",1),Part.fill("M4.39 4.64 Q3.5 4.5 3.64 5.39 L4.35 9.69 Q4.49 10.58 5.13 9.94 L8.94 6.13 Q9.58 5.49 8.69 5.35 Z")};
    private static final Part[] LANE_STRAIGHT={new Part("M12 22.5 V6.1",1),Part.fill("M11.47 2.23 Q12 1.5 12.53 2.23 L15.07 5.77 Q15.6 6.5 14.7 6.5 L9.3 6.5 Q8.4 6.5 8.93 5.77 Z")};
    private static final Part[] LANE_SLIGHT_RIGHT={new Part("M12 22.5 V15.5 Q12 13 13.77 11.23 L17.25 7.75",1),Part.fill("M19.61 4.64 Q20.5 4.5 20.36 5.39 L19.65 9.69 Q19.51 10.58 18.87 9.94 L15.06 6.13 Q14.42 5.49 15.31 5.35 Z")};
    private static final Part[] LANE_RIGHT={new Part("M12 22.5 V16 Q12 12 16 12 H18.4",1),Part.fill("M22.27 11.47 Q23 12 22.27 12.53 L18.73 15.07 Q18 15.6 18 14.7 L18 9.3 Q18 8.4 18.73 8.93 Z")};
    private static final Part[] LANE_SHARP_RIGHT={new Part("M12 22.5 V10 Q12 7.5 13.77 9.27 L18.25 13.75",1),Part.fill("M21.36 16.11 Q21.5 17 20.61 16.86 L16.31 16.15 Q15.42 16.01 16.06 15.37 L19.87 11.56 Q20.51 10.92 20.65 11.81 Z")};
    private static final Part[] LANE_UTURN_RIGHT={new Part("M12 22.5 V9 A4 4 0 0 1 20 9 V15.4",1),Part.fill("M20.53 19.27 Q20 20 19.47 19.27 L16.93 15.73 Q16.4 15 17.3 15 L22.7 15 Q23.6 15 23.07 15.73 Z")};
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
            if(part.fill)canvas.fill(part.path,x,y,scale,color);
            else if(part.path!=null)canvas.path(part.path,x,y,scale,stroke/scale,color,part.opacity);
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
        Part[] parts;
        switch(direction){
            case STRAIGHT:parts=LANE_STRAIGHT;break;
            case SLIGHT_RIGHT:parts=LANE_SLIGHT_RIGHT;break;
            case RIGHT:parts=LANE_RIGHT;break;
            case SHARP_RIGHT:parts=LANE_SHARP_RIGHT;break;
            case SLIGHT_LEFT:parts=LANE_SLIGHT_LEFT;break;
            case LEFT:parts=LANE_LEFT;break;
            case SHARP_LEFT:parts=LANE_SHARP_LEFT;break;
            case UTURN_LEFT:parts=LANE_UTURN_LEFT;break;
            case UTURN_RIGHT:parts=LANE_UTURN_RIGHT;break;
            default:throw new IllegalArgumentException("LANE_DIRECTION");
        }
        double scale=size/24;
        for(Part part:parts){
            if(part.fill)canvas.fill(part.path,x,y,scale,color);
            else canvas.path(part.path,x,y,scale,stroke,color,1);
        }
    }
}
