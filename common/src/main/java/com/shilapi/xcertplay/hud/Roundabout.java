package com.shilapi.xcertplay.hud;

/**
 * Numbered roundabout exits on the 24-unit grid. The first three enter from the bottom and follow the ring to the exit
 * to the side, ahead or to the other side; later ones are three arrows chasing round a circle, as the way round is no
 * longer worth drawing. Each sign is pairs of a stroke and its filled head.
 */
final class Roundabout {
    static final double RIM=1.2;
    /**
     * The ring sits low and small, so each exit's head clears it by more than the rim and the exit ahead shows a stem
     * between the ring and its head, which stays at the top of the grid.
     */
    private static final double CENTER=12,RING_Y=14,RADIUS=5,SIDE_REACH=11.6,AHEAD_TIP_Y=1,HEAD=4.4,HALF=3.2,OVERLAP=.4;
    static final HudPath RING=new HudPath("M"+LaneGlyph.f(CENTER+RADIUS)+" "+LaneGlyph.f(RING_Y)+" A5 5 0 1 1 "+LaneGlyph.f(CENTER-RADIUS)+" "+LaneGlyph.f(RING_Y)
        +" A5 5 0 1 1 "+LaneGlyph.f(CENTER+RADIUS)+" "+LaneGlyph.f(RING_Y));
    private static final HudPath[][] WAY=new HudPath[6][],LOOP=new HudPath[2][];
    static {
        for(int side=0;side<2;side++){
            for(int exit=1;exit<=3;exit++)WAY[side*3+exit-1]=way(exit,side==1);
            LOOP[side]=loop(side==1);
        }
    }
    private Roundabout() {}
    static HudPath[] of(int exit,boolean clockwise){
        int side=clockwise ? 1 : 0;
        return exit<=3 ? WAY[side*3+exit-1] : LOOP[side];
    }
    /** Up to the ring, round it a quarter turn per exit, then straight out to the tip near the edge. */
    private static HudPath[] way(int exit,boolean clockwise){
        int k=clockwise ? -1 : 1,sweep=clockwise ? 1 : 0;
        double[][] quarter={{CENTER+k*RADIUS,RING_Y},{CENTER,RING_Y-RADIUS},{CENTER-k*RADIUS,RING_Y}};
        double[][] out={{k,0},{0,-1},{-k,0}};
        StringBuilder data=new StringBuilder("M12 22.5 V").append(LaneGlyph.f(RING_Y+RADIUS));
        for(int i=0;i<exit;i++)data.append(" A").append(LaneGlyph.f(RADIUS)).append(' ').append(LaneGlyph.f(RADIUS)).append(" 0 0 ").append(sweep).append(' ').append(LaneGlyph.f(quarter[i][0])).append(' ').append(LaneGlyph.f(quarter[i][1]));
        double ux=out[exit-1][0],uy=out[exit-1][1],reach=exit==2 ? RING_Y-AHEAD_TIP_Y : SIDE_REACH,tipX=CENTER+ux*reach,tipY=RING_Y+uy*reach;
        data.append(" L").append(LaneGlyph.f(tipX-ux*(HEAD-OVERLAP))).append(' ').append(LaneGlyph.f(tipY-uy*(HEAD-OVERLAP)));
        return new HudPath[]{new HudPath(data.toString()),LaneGlyph.head(tipX,tipY,ux,uy,HEAD,HALF)};
    }
    /** Three arcs a third of the circle apart, each ending in a head whose tip lies back on the circle. */
    private static HudPath[] loop(boolean clockwise){
        double radius=7.5,arc=58,head=4.6,half=3.4,turn=Math.toDegrees(2*Math.asin(head/(2*radius)));
        int direction=clockwise ? 1 : -1,sweep=clockwise ? 1 : 0;
        HudPath[] sign=new HudPath[6];
        for(int i=0;i<3;i++){
            double start=-90+direction*(20+i*120),end=start+direction*arc,tip=end+direction*turn;
            double[] from=point(radius,start),to=point(radius,end),at=point(radius,tip);
            double length=Math.hypot(at[0]-to[0],at[1]-to[1]),ux=(at[0]-to[0])/length,uy=(at[1]-to[1])/length;
            sign[2*i]=new HudPath("M"+LaneGlyph.f(from[0])+" "+LaneGlyph.f(from[1])+" A"+LaneGlyph.f(radius)+" "+LaneGlyph.f(radius)+" 0 0 "+sweep+" "+LaneGlyph.f(to[0])+" "+LaneGlyph.f(to[1]));
            sign[2*i+1]=LaneGlyph.head(at[0],at[1],ux,uy,head,half);
        }
        return sign;
    }
    private static double[] point(double radius,double degrees){
        double angle=Math.toRadians(degrees);
        return new double[]{CENTER+radius*Math.cos(angle),CENTER+radius*Math.sin(angle)};
    }
}
