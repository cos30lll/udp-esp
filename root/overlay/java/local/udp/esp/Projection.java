package local.udp.esp;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** UDP live positions/view + fixed-version steady-state approximation.
 * Never writes an estimated FOV back as a verified live camera value.
 * Evidence: factory 06000823; SetCameraInfo 060059E6; TPS 06005A43;
 * distance 06005A7D + ScriptCommonUtils_200 SafeValueManager::.ctor 0600004B.
 * CameraLook rest chain: camera-auto-r1-20261124/camera-look-rest-chains.json.
 */
final class Projection {
    static final double MAX_AGE = 1.5, MAX_SKEW = 0.35;
    private static final double TPS_FOV = 55, FPS_FOV = 66;
    private static final double DISTANCE_SETTING = 3.5, OFFSET_SETTING = 2.5;
    private static final double[] REST_ANCHOR = {0.0017079335724046558,2.1794792299989707,0.21601167688367584};
    // Drawing profile, NOT recovered animated bounds/collider dimensions.
    // A fixed standing envelope is used until per-target model/pose bounds exist.
    private static final double BOX_HEIGHT = 2.20, BOX_HALF_WIDTH = 0.45;
    static final class Point {
        final float x,y,left,top,right,bottom;
        final String label,hpLabel;
        final boolean hpKnown,hpPositive;
        Point(double x,double y,double[] bounds,String text,String hp,boolean known,boolean positive) {
            this.x=(float)x; this.y=(float)y;
            left=(float)bounds[0]; top=(float)bounds[1];
            right=(float)bounds[2]; bottom=(float)bounds[3];
            label=text; hpLabel=hp; hpKnown=known; hpPositive=positive;
        }
    }
    static final class Result {
        final List<Point> points = new ArrayList<>();
        String reason = "等待 UDP 后端"; boolean drawable;
        int left,top,width,height;
        double expiresAt;
        double cameraAgeMs=Double.NaN;
        String timing="";
    }
    private Projection() {}
    private static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
    private static double number(JSONObject o,String key) {
        Object value=o==null?null:o.opt(key); return value instanceof Number?((Number)value).doubleValue():Double.NaN;
    }
    private static double age(JSONObject o,double now) {
        double ts=number(o,"timestamp"); return finite(ts)&&ts<=now+MAX_SKEW?Math.max(0,now-ts):Double.POSITIVE_INFINITY;
    }
    private static double[] xyz(JSONObject o) {
        double[] v={number(o,"x"),number(o,"y"),number(o,"z")};
        return finite(v[0])&&finite(v[1])&&finite(v[2])?v:null;
    }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double[] normalized(double[] v) {
        if(v==null) return null; double n=Math.sqrt(dot(v,v));
        return finite(n)&&n>0.000001?new double[]{v[0]/n,v[1]/n,v[2]/n}:null;
    }
    private static double[] rotate(double[] q,double[] v) {
        double x=q[0],y=q[1],z=q[2],w=q[3];
        double tx=2*(y*v[2]-z*v[1]),ty=2*(z*v[0]-x*v[2]),tz=2*(x*v[1]-y*v[0]);
        return new double[]{v[0]+w*tx+y*tz-z*ty,v[1]+w*ty+z*tx-x*tz,v[2]+w*tz+x*ty-y*tx};
    }
    private static double[] upRotation(double[] up) {
        if(up[1]<-0.999999) return new double[]{1,0,0,0};
        double[] q={up[2],0,-up[0],1+up[1]};
        double n=Math.sqrt(q[0]*q[0]+q[2]*q[2]+q[3]*q[3]);
        for(int i=0;i<4;i++) q[i]/=n; return q;
    }
    private static JSONObject context(JSONObject state,String stream) {
        JSONArray list=state.optJSONArray("camera_context");
        if(list!=null) for(int i=0;i<Math.min(list.length(),32);i++) {
            JSONObject c=list.optJSONObject(i); if(c!=null&&stream.equals(c.optString("stream"))) return c;
        } return null;
    }
    private static JSONObject local(JSONArray entities,String stream,double now) {
        JSONObject found=null; int count=0;
        for(int i=0;i<entities.length();i++) {
            JSONObject e=entities.optJSONObject(i);
            if(e!=null&&stream.equals(e.optString("stream"))&&e.optInt("category")==4
                    &&Boolean.TRUE.equals(e.opt("is_local"))&&xyz(e)!=null&&age(e,now)!=Double.POSITIVE_INFINITY) { found=e; count++; }
        } return count==1?found:null;
    }
    private static double sceneTime(JSONObject state,String stream) {
        JSONArray scenes=state.optJSONArray("scene_streams");
        if(scenes!=null) for(int i=0;i<Math.min(scenes.length(),32);i++) {
            JSONObject s=scenes.optJSONObject(i);
            if(s!=null&&stream.equals(s.optString("stream"))) return number(s,"timestamp");
        }
        return Double.NaN;
    }
    private static void add(Map<String,JSONObject> all,JSONObject camera) {
        if(camera==null) return; String stream=camera.optString("stream","");
        if(stream.isEmpty()||!finite(number(camera,"timestamp"))) return;
        JSONObject old=all.get(stream);
        if(old==null||number(camera,"timestamp")>number(old,"timestamp")) all.put(stream,camera);
    }
    private static JSONObject action(JSONObject state,JSONObject own,int kind,double now,double login) {
        JSONObject block=state.optJSONObject("actor_action_state");
        JSONArray list=block==null?null:block.optJSONArray("local_records"); JSONObject best=null;
        if(list!=null) for(int i=0;i<Math.min(list.length(),64);i++) {
            JSONObject r=list.optJSONObject(i); if(r==null||r.optInt("kind",-1)!=kind) continue;
            if(!own.optString("stream").equals(r.optString("stream"))||!own.optString("id1").equals(r.optString("id1"))) continue;
            double ts=number(r,"timestamp");
            if(!finite(ts)||ts>now+MAX_SKEW||(finite(login)&&ts<login)) continue;
            JSONObject value=r.optJSONObject("reported_input");
            if(value==null||!"reported_input_not_render_state".equals(value.optString("status"))) continue;
            if(best==null||ts>number(best,"timestamp")) best=r;
        } return best==null?null:best.optJSONObject("reported_input");
    }
    private static double[] bodyBox(double[] pos,double[] origin,double[] forward,
            double[] right,double[] up,double focal,int width,int height) {
        double left=Double.POSITIVE_INFINITY,top=left;
        double rightEdge=Double.NEGATIVE_INFINITY,bottom=rightEdge;
        for(int row=0;row<2;row++) for(int side=-1;side<=1;side+=2) {
            double dx=pos[0]+right[0]*BOX_HALF_WIDTH*side-origin[0];
            double dy=pos[1]+row*BOX_HEIGHT+right[1]*BOX_HALF_WIDTH*side-origin[1];
            double dz=pos[2]+right[2]*BOX_HALF_WIDTH*side-origin[2];
            double depth=dx*forward[0]+dy*forward[1]+dz*forward[2];
            // Reject a near-plane crossing instead of drawing unbounded rectangles.
            if(!finite(depth)||depth<=0.05)return null;
            double x=width/2.0+(dx*right[0]+dy*right[1]+dz*right[2])*focal/depth;
            double y=height/2.0-(dx*up[0]+dy*up[1]+dz*up[2])*focal/depth;
            if(!finite(x)||!finite(y))return null;
            left=Math.min(left,x);rightEdge=Math.max(rightEdge,x);
            top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        if(rightEdge<0||left>width||bottom<0||top>height)return null;
        return new double[]{Math.max(0,left),Math.max(0,top),
                Math.min(width,rightEdge),Math.min(height,bottom)};
    }
    static Result project(JSONObject state,JSONObject ignored,double now,int width,int height) {
        Result out=new Result(); out.width=width; out.height=height;
        if(state==null||!finite(now)||width<64||height<64) return out;
        JSONArray entities=state.optJSONArray("entities");
        if(entities==null||entities.length()>512) { out.reason="实体数据缺失或超限"; return out; }
        Map<String,JSONObject> cameras=new LinkedHashMap<>(); JSONArray cs=state.optJSONArray("camera_streams");
        if(cs!=null) for(int i=0;i<Math.min(cs.length(),32);i++) add(cameras,cs.optJSONObject(i));
        add(cameras,state.optJSONObject("camera"));
        JSONObject camera=null,own=null,ctx=null; int valid=0;
        for(JSONObject c:cameras.values()) {
            if(!"decoded".equals(c.optString("status"))||!"outbound_3_851_field3".equals(c.optString("source"))||age(c,now)>MAX_AGE) continue;
            JSONObject x=context(state,c.optString("stream"));
            if(x==null||x.optInt("gameplay_type",-1)!=12||x.optInt("gameplay_id",-1)!=1201) continue;
            JSONObject e=local(entities,c.optString("stream"),now);
            double scene=sceneTime(state,c.optString("stream")),loginTime=number(x,"login_timestamp");
            if(e==null||!finite(scene)||scene>now+MAX_SKEW||now-scene>MAX_AGE) continue;
            if(finite(loginTime)&&(number(e,"timestamp")<loginTime||number(c,"timestamp")<loginTime)) continue;
            camera=c;own=e;ctx=x;valid++;
        }
        if(valid!=1) { out.reason=valid>1?"多个有效游戏分流 · 为避免串流暂停绘制":"等待 1201 同分流的 UDP 视角和自身坐标"; return out; }
        out.cameraAgeMs=age(camera,now)*1000;
        double yaw=number(camera,"yaw_deg"),pitch=number(camera,"pitch_deg");
        if(!finite(yaw)||!finite(pitch)) { out.reason="UDP 视角无效"; return out; }
        int view=1; String modeSource="缺省TPS假设";
        JSONObject confirmed=ctx.optJSONObject("reported_perspective"),request=ctx.optJSONObject("requested_perspective");
        double seen=Double.NEGATIVE_INFINITY,login=number(ctx,"login_timestamp");
        if(confirmed!=null&&(confirmed.optInt("value",-1)==0||confirmed.optInt("value",-1)==1)&&age(confirmed,now)!=Double.POSITIVE_INFINITY
                &&(!finite(login)||number(confirmed,"timestamp")>=login)) {
            view=confirmed.optInt("value"); seen=number(confirmed,"timestamp");modeSource="UDP报告视角";
        }
        if(request!=null&&(request.optInt("value",-1)==0||request.optInt("value",-1)==1)&&age(request,now)!=Double.POSITIVE_INFINITY
                &&(!finite(login)||number(request,"timestamp")>=login)&&number(request,"timestamp")>seen) {
            view=request.optInt("value");modeSource="UDP请求未确认";
        }
        double[] actorUp={0,1,0}; double squat=0;
        JSONObject upRecord=action(state,own,253,now,login),squatRecord=action(state,own,32,now,login);
        if(upRecord!=null) {
            double[] v=normalized(xyz(upRecord.optJSONObject("reported_actor_up")));
            if(v==null) { out.reason="UDP 朝上方向无效";return out; } actorUp=v;
        }
        if(squatRecord!=null) squat=number(squatRecord,"reported_squat_u32");
        if(!finite(squat)) {out.reason="蹲伏状态无效";return out;}
        double[] base=upRotation(actorUp);
        double yr=Math.toRadians(yaw),pr=Math.toRadians(pitch),sy=Math.sin(yr),cy=Math.cos(yr),sp=Math.sin(pr),cp=Math.cos(pr);
        double[] forward=rotate(base,new double[]{sy*cp,-sp,cy*cp});
        double[] right=rotate(base,new double[]{cy,0,-sy});
        double[] up=rotate(base,new double[]{sy*sp,cp,cy*sp});
        double[] root=xyz(own),origin=new double[3]; double fov=view==0?FPS_FOV:TPS_FOV;
        if(view==1) {
            // Native steady ordinary branch: vertical Offset*(1-.3*Squat),
            // shoulder Offset/7, boom Distance-1; no collision/local modifiers.
            double eye=OFFSET_SETTING*(1-0.3*squat),shoulder=OFFSET_SETTING/7.0,boom=DISTANCE_SETTING-1.0;
            for(int k=0;k<3;k++) origin[k]=root[k]+actorUp[k]*eye+right[k]*shoulder-forward[k]*boom;
        } else {
            // Static rig is only a rest-pose estimate, not animated head position.
            JSONObject rotation=action(state,own,2,now,login);double[] offset=null;
            if(rotation!=null) {
                JSONObject q=rotation.optJSONObject("reported_actor_rotation");
                double[] v={number(q,"x"),number(q,"y"),number(q,"z"),number(q,"w")};
                double n=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]+v[3]*v[3]);
                if(finite(n)&&n>0.000001) {for(int k=0;k<4;k++) v[k]/=n;offset=rotate(v,REST_ANCHOR);}
            }
            if(offset==null) {
                JSONObject vectors=camera.optJSONObject("reported_vectors");
                double[] direction=vectors==null?null:xyz(vectors.optJSONObject("4"));
                double heading=direction!=null&&Math.hypot(direction[0],direction[2])>0.000001?Math.atan2(direction[0],direction[2]):yr;
                offset=rotate(base,new double[]{REST_ANCHOR[0]*Math.cos(heading)+REST_ANCHOR[2]*Math.sin(heading),REST_ANCHOR[1],-REST_ANCHOR[0]*Math.sin(heading)+REST_ANCHOR[2]*Math.cos(heading)});
            }
            for(int k=0;k<3;k++) origin[k]=root[k]+offset[k];
        }
        double focal=height/(2*Math.tan(Math.toRadians(fov)/2));String stream=camera.optString("stream");
        for(int i=0;i<entities.length();i++) {
            JSONObject e=entities.optJSONObject(i);
            if(e==null||!stream.equals(e.optString("stream"))||e.optInt("category")!=4||Boolean.TRUE.equals(e.opt("is_local")))continue;
            if(!own.optString("key","").isEmpty()&&own.optString("key").equals(e.optString("key")))continue;
            double[] pos=xyz(e);
            // Positions are sparse delta-state. Do not drop a stationary actor merely
            // because its last position packet is older than the camera packet.
            if(pos==null||age(e,now)==Double.POSITIVE_INFINITY||(finite(login)&&number(e,"timestamp")<login))continue;
            double[] d={pos[0]-origin[0],pos[1]-origin[1],pos[2]-origin[2]};double depth=dot(d,forward);
            if(!finite(depth)||depth<=0.0001)continue;
            double x=width/2.0+dot(d,right)*focal/depth,y=height/2.0-dot(d,up)*focal/depth;
            if(!finite(x)||!finite(y))continue;
            double[] bounds=bodyBox(pos,origin,forward,right,up,focal,width,height);
            if(bounds==null)continue;
            String name=e.optString("name","");if(name.isEmpty())name="实体 "+e.optString("id1","?");
            name=name.replaceAll("[\\p{Cc}\\p{Cf}]"," ");if(name.length()>48)name=name.substring(0,48);
            double distance=Math.sqrt(Math.pow(pos[0]-root[0],2)+Math.pow(pos[1]-root[1],2)+Math.pow(pos[2]-root[2],2));
            double hp=number(e,"hp"),hpTime=number(e,"hp_timestamp");
            // Action 31 is current Int32 HP. No verified maximum: never invent a % bar.
            boolean known=finite(hp)&&hp>=Integer.MIN_VALUE&&hp<=Integer.MAX_VALUE&&hp==Math.rint(hp)
                    &&finite(hpTime)&&hpTime<=now+MAX_SKEW&&(!finite(login)||hpTime>=login);
            String hpText=known?"HP "+Long.toString((long)hp):"HP 未知";
            out.points.add(new Point(x,y,bounds,name+String.format(java.util.Locale.ROOT,
                    " · %.1f 世界单位",distance),hpText,known,hp>0));
        }
        out.drawable=true;
        out.expiresAt=Math.min(number(camera,"timestamp"),sceneTime(state,stream))+MAX_AGE;
        out.reason="方框HP r4 · 免填参近似 · "+(view==0?"FPS":"TPS")+" · "+modeSource+" · 基础FOV "+(int)fov+"° · "+out.points.size()+" 标记";
        return out;
    }
}