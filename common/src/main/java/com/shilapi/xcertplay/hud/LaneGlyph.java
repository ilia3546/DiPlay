package com.shilapi.xcertplay.hud;

import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The arrows of one lane composed into a single sign on the 24-unit grid: one stem, and a branch per direction placed
 * so that no two heads meet. A turn and a U-turn to the same side share one wide arc: the turn leaves its top and the
 * U-turn comes down the far side. With a sharp turn instead, the sharp turn leaves the top of the stem and the U-turn
 * is a small hook under it. A slight turn lowers the plain turn, a sharp one drops under it, and the stem moves aside
 * for a U-turn. A lane of several directions has smaller heads, so its branches keep apart.
 */
final class LaneGlyph {
    private static final double BOTTOM=22.5,ROUND=.9,OVERLAP=.4,BEND=3.5,ARC=4.5;
    private static final ConcurrentHashMap<Integer,HudPath[][]> CACHE=new ConcurrentHashMap<Integer,HudPath[][]>();
    /** The stroke and filled head of every direction of a lane, indexed by ordinal; null for directions it lacks. */
    static HudPath[][] of(Collection<HudLaneDirection> directions){
        int mask=0;
        for(HudLaneDirection direction:directions)mask|=1<<direction.ordinal();
        HudPath[][] glyph=CACHE.get(mask);
        if(glyph==null){glyph=new LaneGlyph(mask).glyph;CACHE.put(mask,glyph);}
        return glyph;
    }
    private final int mask;
    private final boolean straight;
    private final double head,half;
    private final HudPath[][] glyph=new HudPath[HudLaneDirection.values().length][];
    private LaneGlyph(int mask){
        this.mask=mask;
        straight=has(HudLaneDirection.STRAIGHT);
        boolean several=Integer.bitCount(mask)>1;
        head=several ? 4.4 : 5;half=several ? 3 : 3.6;
        boolean left=has(HudLaneDirection.SLIGHT_LEFT) || has(HudLaneDirection.LEFT) || has(HudLaneDirection.SHARP_LEFT) || has(HudLaneDirection.UTURN_LEFT);
        boolean right=has(HudLaneDirection.SLIGHT_RIGHT) || has(HudLaneDirection.RIGHT) || has(HudLaneDirection.SHARP_RIGHT) || has(HudLaneDirection.UTURN_RIGHT);
        boolean arcLeft=has(HudLaneDirection.LEFT) && has(HudLaneDirection.UTURN_LEFT) && !has(HudLaneDirection.UTURN_RIGHT);
        boolean arcRight=has(HudLaneDirection.RIGHT) && has(HudLaneDirection.UTURN_RIGHT) && !has(HudLaneDirection.UTURN_LEFT);
        boolean hookLeft=!arcLeft && has(HudLaneDirection.SHARP_LEFT) && has(HudLaneDirection.UTURN_LEFT) && !has(HudLaneDirection.UTURN_RIGHT);
        boolean hookRight=!arcRight && has(HudLaneDirection.SHARP_RIGHT) && has(HudLaneDirection.UTURN_RIGHT) && !has(HudLaneDirection.UTURN_LEFT);
        double stem=12;
        if(arcLeft)stem=right ? 15.5 : 16;
        else if(arcRight)stem=left ? 8.5 : 8;
        else if(hookLeft)stem=right ? 15 : 16;
        else if(hookRight)stem=left ? 9 : 8;
        else{
            if(has(HudLaneDirection.UTURN_LEFT))stem+=right ? 1 : 2;
            if(has(HudLaneDirection.UTURN_RIGHT))stem-=left ? 1 : 2;
        }
        if(straight)glyph[HudLaneDirection.STRAIGHT.ordinal()]=new HudPath[]{path("M",stem,BOTTOM,"V",1.5+head-OVERLAP),head(stem,1.5,0,-1)};
        side(stem,-1,arcLeft,hookLeft,right,HudLaneDirection.SLIGHT_LEFT,HudLaneDirection.LEFT,HudLaneDirection.SHARP_LEFT,HudLaneDirection.UTURN_LEFT);
        side(stem,1,arcRight,hookRight,left,HudLaneDirection.SLIGHT_RIGHT,HudLaneDirection.RIGHT,HudLaneDirection.SHARP_RIGHT,HudLaneDirection.UTURN_RIGHT);
    }
    private boolean has(HudLaneDirection direction){return (mask&1<<direction.ordinal())!=0;}
    /** The branches to one side; [k] is -1 for the left side and 1 for the right. */
    private void side(double stem,int k,boolean arc,boolean hook,boolean otherSide,HudLaneDirection slight,HudLaneDirection turn,HudLaneDirection sharp,HudLaneDirection uturn){
        boolean hasSlight=has(slight),hasTurn=has(turn),hasSharp=has(sharp),hasUturn=has(uturn);
        int sweep=k<0 ? 0 : 1;
        if(arc){
            double radius=otherSide ? 4.25 : ARC,top=straight ? 9.5 : 5.5,tipX=k<0 ? .6 : 23.4,leg=stem+k*2*radius;
            String over="M"+f(stem)+" "+f(BOTTOM)+" V"+f(top+radius)+" A"+f(radius)+" "+f(radius)+" 0 0 "+sweep+" "+f(stem+k*radius)+" "+f(top);
            glyph[turn.ordinal()]=new HudPath[]{new HudPath(over+" H"+f(tipX-k*(head-OVERLAP))),head(tipX,top,k,0)};
            glyph[uturn.ordinal()]=new HudPath[]{new HudPath(over+" A"+f(radius)+" "+f(radius)+" 0 0 "+sweep+" "+f(leg)+" "+f(top+radius)+" V"+f(22-head+OVERLAP)),head(leg,22,0,1)};
            hasTurn=false;hasUturn=false;
        }
        if(hook){
            double joint=straight ? 6.5 : 2.5,tipX=k<0 ? 2 : 22,top=straight ? 15 : 14,radius=straight ? 2.5 : 3;
            glyph[sharp.ordinal()]=branch(stem,joint,2,k*Math.sqrt(.5),Math.sqrt(.5),tipX,joint+Math.abs(tipX-stem));
            glyph[uturn.ordinal()]=new HudPath[]{new HudPath("M"+f(stem)+" "+f(BOTTOM)+" V"+f(top+radius)+" A"+f(radius)+" "+f(radius)+" 0 0 "+sweep+" "+f(stem+k*2*radius)+" "+f(top+radius)
                +" V"+f(22-head+OVERLAP)),head(stem+k*2*radius,22,0,1)};
            hasSharp=false;hasUturn=false;
        }
        boolean apart=hasSlight && hasSharp && !has(turn);
        double turnY=hasSlight ? 15 : has(uturn) ? (straight ? 10 : 8) : hasSharp ? (straight ? 9.5 : 6.5) : 12;
        if(hasSlight){
            double reach=Math.min(apart ? 7.5 : 10.5,k<0 ? stem-.6 : 23.4-stem),joint=apart ? 10 : 14.5;
            glyph[slight.ordinal()]=branch(stem,joint,2.5,k*Math.sqrt(.5),-Math.sqrt(.5),stem+k*reach,joint-reach);
        }
        if(hasTurn){
            double tipX=12+k*11,radius=Math.min(hasUturn ? BEND : turnY>=10 ? 4 : 3,Math.abs(tipX-stem)-head+OVERLAP);
            glyph[turn.ordinal()]=new HudPath[]{new HudPath("M"+f(stem)+" "+f(BOTTOM)+" V"+f(turnY+radius)+" Q"+f(stem)+" "+f(turnY)+" "+f(stem+k*radius)+" "+f(turnY)
                +" H"+f(tipX-k*(head-OVERLAP))),head(tipX,turnY,k,0)};
        }
        if(hasSharp){
            double joint=apart ? 13 : has(turn) ? Math.min(turnY+4.5,13.5) : straight ? 9.5 : 6,length=apart ? 9.5 : 12,ux=k*Math.sqrt(.5),uy=Math.sqrt(.5);
            glyph[sharp.ordinal()]=branch(stem,joint,2,ux,uy,stem+ux*length,joint+uy*length);
        }
        if(hasUturn){
            if(hasTurn){
                double drop=2.5,leg=stem+k*(BEND+drop),tip=22;
                glyph[uturn.ordinal()]=new HudPath[]{new HudPath("M"+f(stem)+" "+f(BOTTOM)+" V"+f(turnY+BEND)+" Q"+f(stem)+" "+f(turnY)+" "+f(stem+k*BEND)+" "+f(turnY)
                    +" A"+f(drop)+" "+f(drop)+" 0 0 "+sweep+" "+f(leg)+" "+f(turnY+drop)+" V"+f(tip-head+OVERLAP)),head(leg,tip,0,1)};
            }else{
                double top=5,radius=4,tip=20;
                if(straight){top=10.5;radius=3.5;tip=22;}
                if(hasSlight){top=13.5;radius=3;tip=22.5;}
                double joint=top+radius,leg=stem+k*2*radius;
                glyph[uturn.ordinal()]=new HudPath[]{new HudPath("M"+f(stem)+" "+f(BOTTOM)+" V"+f(joint)+" A"+f(radius)+" "+f(radius)+" 0 0 "+sweep+" "+f(leg)+" "+f(joint)
                    +" V"+f(tip-head+OVERLAP)),head(leg,tip,0,1)};
            }
        }
    }
    /** Up the stem to [joint], a rounded bend of [corner] units, then straight along (ux, uy) to the head at the tip. */
    private HudPath[] branch(double stem,double joint,double corner,double ux,double uy,double tipX,double tipY){
        double endX=tipX-ux*(head-OVERLAP),endY=tipY-uy*(head-OVERLAP);
        return new HudPath[]{new HudPath("M"+f(stem)+" "+f(BOTTOM)+" V"+f(joint+corner)+" Q"+f(stem)+" "+f(joint)+" "+f(stem+ux*corner)+" "+f(joint+uy*corner)
            +" L"+f(endX)+" "+f(endY)),head(tipX,tipY,ux,uy)};
    }
    private static HudPath path(String move,double x,double y,String line,double to){
        return new HudPath(move+f(x)+" "+f(y)+" "+line+f(to));
    }
    /** A triangle with rounded corners, its tip at (x, y) and pointing along (ux, uy). */
    private HudPath head(double x,double y,double ux,double uy){
        double baseX=x-ux*head,baseY=y-uy*head,px=-uy*half,py=ux*half;
        double[][] corners={{x,y},{baseX+px,baseY+py},{baseX-px,baseY-py}};
        StringBuilder data=new StringBuilder();
        for(int i=0;i<3;i++){
            double[] corner=corners[i],previous=corners[(i+2)%3],next=corners[(i+1)%3];
            double[] from=toward(corner,previous),to=toward(corner,next);
            data.append(i==0 ? "M" : " L").append(f(from[0])).append(' ').append(f(from[1]))
                .append(" Q").append(f(corner[0])).append(' ').append(f(corner[1])).append(' ').append(f(to[0])).append(' ').append(f(to[1]));
        }
        return new HudPath(data.append(" Z").toString());
    }
    private static double[] toward(double[] from,double[] to){
        double dx=to[0]-from[0],dy=to[1]-from[1],length=Math.hypot(dx,dy);
        return new double[]{from[0]+dx*ROUND/length,from[1]+dy*ROUND/length};
    }
    private static String f(double value){
        String text=String.format(Locale.US,"%.2f",value);
        if(text.indexOf('.')>=0)text=text.replaceAll("0+$","").replaceAll("\\.$","");
        return text.equals("-0") ? "0" : text;
    }
}
