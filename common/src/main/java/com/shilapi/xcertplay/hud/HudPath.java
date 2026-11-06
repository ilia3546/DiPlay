package com.shilapi.xcertplay.hud;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HudPath {
    public interface Sink {
        void move(float x,float y);
        void line(float x,float y);
        void quad(float cx,float cy,float x,float y);
        void cubic(float x1,float y1,float x2,float y2,float x,float y);
        void arc(float cx,float cy,float radius,float start,float sweep);
        void close();
    }
    private static final Pattern TOKEN=Pattern.compile("[A-Za-z]|-?(?:[0-9]*\\.)?[0-9]+");
    public final String data;
    private final char[] commands;
    private final float[][] values;
    public HudPath(String data){
        this.data=data;
        Matcher matcher=TOKEN.matcher(data);ArrayList<String> tokens=new ArrayList<String>();
        while(matcher.find())tokens.add(matcher.group());
        ArrayList<Character> ops=new ArrayList<Character>();ArrayList<float[]> args=new ArrayList<float[]>();
        float x=0,y=0,qx=0,qy=0,mx=0,my=0;char previous=0;
        for(int i=0;i<tokens.size();){
            char command=tokens.get(i++).charAt(0);int count;
            switch(command){case 'M':case 'L':case 'T':count=2;break;case 'H':case 'V':count=1;break;case 'Q':count=4;break;case 'C':count=6;break;case 'A':count=7;break;case 'Z':count=0;break;default:throw new IllegalArgumentException("GLYPH_COMMAND");}
            float[] v=new float[count];for(int j=0;j<count;j++)v[j]=Float.parseFloat(tokens.get(i++));
            switch(command){
                case 'M':x=v[0];y=v[1];mx=x;my=y;break;
                case 'L':x=v[0];y=v[1];break;
                case 'H':x=v[0];command='L';v=new float[]{x,y};break;
                case 'V':y=v[0];command='L';v=new float[]{x,y};break;
                case 'T':
                    qx=previous=='Q' || previous=='T' ? 2*x-qx : x;qy=previous=='Q' || previous=='T' ? 2*y-qy : y;
                    x=v[0];y=v[1];command='Q';v=new float[]{qx,qy,x,y};break;
                case 'Q':qx=v[0];qy=v[1];x=v[2];y=v[3];break;
                case 'C':x=v[4];y=v[5];break;
                case 'A':
                    if(v[0]!=v[1] || v[2]!=0)throw new IllegalArgumentException("GLYPH_CIRCULAR_ARC");
                    v=arc(x,y,v[0],v[3]!=0,v[4]!=0,v[5],v[6]);
                    x=Float.parseFloat(tokens.get(i-2));y=Float.parseFloat(tokens.get(i-1));break;
                case 'Z':x=mx;y=my;break;
                default:throw new IllegalArgumentException("GLYPH_COMMAND");
            }
            previous=tokens.get(i-count-1).charAt(0);ops.add(command);args.add(v);
        }
        commands=new char[ops.size()];values=args.toArray(new float[args.size()][]);
        for(int i=0;i<commands.length;i++)commands[i]=ops.get(i);
    }
    private static float[] arc(float x1,float y1,float radius,boolean large,boolean clockwise,float x2,float y2){
        double dx=(x1-x2)/2.0,dy=(y1-y2)/2.0,half=dx*dx+dy*dy,r=Math.max(radius,Math.sqrt(half));
        if(half==0)throw new IllegalArgumentException("GLYPH_EMPTY_ARC");
        double factor=(large==clockwise ? -1 : 1)*Math.sqrt(Math.max(0,(r*r-half)/half));
        double cx=(x1+x2)/2.0+factor*dy,cy=(y1+y2)/2.0-factor*dx;
        double start=Math.atan2(y1-cy,x1-cx),end=Math.atan2(y2-cy,x2-cx),sweep=end-start;
        if(clockwise && sweep<0)sweep+=Math.PI*2;
        if(!clockwise && sweep>0)sweep-=Math.PI*2;
        return new float[]{(float)cx,(float)cy,(float)r,(float)Math.toDegrees(start),(float)Math.toDegrees(sweep)};
    }
    public void replay(Sink sink){
        for(int i=0;i<commands.length;i++){
            float[] v=values[i];
            switch(commands[i]){
                case 'M':sink.move(v[0],v[1]);break;case 'L':sink.line(v[0],v[1]);break;
                case 'Q':sink.quad(v[0],v[1],v[2],v[3]);break;
                case 'C':sink.cubic(v[0],v[1],v[2],v[3],v[4],v[5]);break;
                case 'A':sink.arc(v[0],v[1],v[2],v[3],v[4]);break;
                case 'Z':sink.close();break;default:throw new IllegalStateException("GLYPH_COMMAND");
            }
        }
    }
}
