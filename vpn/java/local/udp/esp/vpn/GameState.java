package local.udp.esp.vpn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owned by one decoder worker. Snapshots are immutable after publication. */
final class GameState {
    private final LinkedHashMap<String,JSONObject> entities=new LinkedHashMap<>(),health=new LinkedHashMap<>();
    private final LinkedHashMap<String,Session> sessions=new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> sequences=new LinkedHashMap<>();
    private final LinkedHashMap<String,JSONObject> actions=new LinkedHashMap<>();
    private final String epoch=Long.toString(System.nanoTime());
    long positions,hpUpdates,reordered;
    static final class Session {
        String uid,localId;JSONObject camera,context;double scene;
        final LinkedHashMap<String,JSONObject> players=new LinkedHashMap<>(),views=new LinkedHashMap<>();
    }
    private static JSONObject copy(JSONObject source)throws Exception{return new JSONObject(source.toString());}
    private Session session(String stream)throws Exception{
        Session s=sessions.get(stream);
        if(s==null){s=new Session();s.context=new JSONObject().put("stream",stream);sessions.put(stream,s);}
        while(sessions.size()>32){String old=sessions.keySet().iterator().next();reset(old);sessions.remove(old);}
        return s;
    }
    private void reset(String stream){
        entities.entrySet().removeIf(e->stream.equals(e.getValue().optString("stream")));
        health.entrySet().removeIf(e->e.getKey().startsWith(stream+"/"));
        actions.entrySet().removeIf(e->stream.equals(e.getValue().optString("stream")));
    }
    private void remove(String stream,String id){
        entities.entrySet().removeIf(e->stream.equals(e.getValue().optString("stream"))&&id.equals(e.getValue().optString("id1")));
        health.entrySet().removeIf(e->e.getKey().startsWith(stream+"/")&&e.getKey().split("/")[2].equals(id));
        actions.entrySet().removeIf(e->stream.equals(e.getValue().optString("stream"))&&id.equals(e.getValue().optString("id1")));
    }
    private void attach(JSONObject e,Session s)throws Exception{
        e.remove("name");e.remove("is_local");JSONObject p=s.players.get(e.optString("id1"));
        if(p!=null&&p.has("name"))e.put("name",p.getString("name"));
        String uid=p==null?null:p.optString("uid",null);
        if(uid!=null&&!"0".equals(uid)&&s.uid!=null&&!"0".equals(s.uid))e.put("is_local",uid.equals(s.uid));
        else if(s.localId!=null&&!"0".equals(s.localId))e.put("is_local",s.localId.equals(e.optString("id1")));
    }
    void apply(GameDecoder.Event event)throws Exception{
        String order=event.stream+"/"+event.kind+"/"+event.type;Integer last=sequences.get(order);
        if(last!=null&&((event.sequence-last)&65535)>32767){reordered++;return;}
        sequences.put(order,event.sequence);while(sequences.size()>2048)sequences.remove(sequences.keySet().iterator().next());
        Session s=session(event.stream);JSONObject data=event.data;double ts=event.timestamp;
        if("camera".equals(event.type)){
            if(s.camera!=null&&ts<s.camera.optDouble("timestamp"))return;
            if("zero_view_vector".equals(data.optString("status"))&&s.camera!=null&&"decoded".equals(s.camera.optString("status")))return;
            s.camera=copy(data).put("stream",event.stream).put("timestamp",ts);return;
        }
        if("request".equals(event.type)){
            s.context.put("requested_perspective",copy(data).put("timestamp",ts).put("confirmed",false).put("source","outbound_3_2055_request_not_confirmed"));return;
        }
        if("perspective".equals(event.type)){
            String uid=data.optString("uid","");if(!uid.isEmpty()&&!"0".equals(uid))s.views.put(uid,copy(data).put("timestamp",ts).put("source","inbound_3_2055"));return;
        }
        if("identity".equals(event.type)){
            if(data.optBoolean("reset")){
                reset(event.stream);s.uid=null;s.localId=null;s.players.clear();s.views.clear();s.scene=0;s.camera=null;
                s.context=new JSONObject().put("stream",event.stream).put("login_timestamp",ts);
                if(data.has("gameplay_type"))s.context.put("gameplay_type",data.get("gameplay_type"));
                if(data.has("gameplay_id"))s.context.put("gameplay_id",data.get("gameplay_id"));
                JSONArray views=data.optJSONArray("perspectives");if(views!=null)for(int i=0;i<views.length();i++){
                    JSONObject v=copy(views.getJSONObject(i));String uid=v.optString("uid","");
                    if(!uid.isEmpty()&&!"0".equals(uid))s.views.put(uid,v.put("timestamp",ts).put("source","inbound_3_1_field51"));}
            }
            if(data.has("local_uid")){
                String uid=data.getString("local_uid");if(!uid.equals(s.uid))s.localId=null;s.uid=uid;
            }
            if(data.has("local_entity_id"))s.localId=data.getString("local_entity_id");
            JSONArray players=data.getJSONArray("players");for(int i=0;i<players.length();i++){
                JSONObject p=players.getJSONObject(i);String id=p.getString("entity_id");JSONObject old=s.players.get(id);
                JSONObject next=old==null||p.has("uid")&&!p.optString("uid").equals(old.optString("uid"))?new JSONObject():copy(old);
                Iterator<String> keys=p.keys();while(keys.hasNext()){String k=keys.next();next.put(k,p.get(k));}s.players.put(id,next);
            }
            JSONArray removed=data.getJSONArray("removed");for(int i=0;i<removed.length();i++){String id=removed.getString(i);s.players.remove(id);remove(event.stream,id);}
            while(s.players.size()>2048)s.players.remove(s.players.keySet().iterator().next());
            for(JSONObject e:entities.values())if(event.stream.equals(e.optString("stream")))attach(e,s);
            return;
        }
        if(!"scene".equals(event.type))return;s.scene=ts;
        JSONArray list=data.getJSONArray("entities");for(int i=0;i<list.length();i++){
            JSONObject item=list.getJSONObject(i);String id=item.getString("id1"),id2=item.getString("id2");int category=item.getInt("category");
            String key=event.stream+"/"+category+"/"+id+"/"+id2;JSONArray aa=item.getJSONArray("actions");
            for(int j=0;j<aa.length();j++){
                JSONObject a=aa.getJSONObject(j);JSONArray v=a.getJSONArray("values");int kind=a.optInt("kind");
                if(kind==31&&v.length()>0){JSONObject h=new JSONObject().put("hp",(int)v.getLong(0)).put("hp_timestamp",ts);health.put(key,h);hpUpdates++;
                    JSONObject e=entities.get(key);if(e!=null)e.put("hp",h.getInt("hp")).put("hp_timestamp",ts);}
                else if(kind==1&&v.length()==3){
                    double scale=a.optDouble("scale");JSONObject e=new JSONObject().put("key",key).put("stream",event.stream).put("id1",id).put("id2",id2).put("category",category).put("timestamp",ts);
                    for(int k=0;k<3;k++)e.put("xyz".substring(k,k+1),scale>0?v.getLong(k)/scale:(double)v.getLong(k));
                    JSONObject h=health.get(key);if(h!=null)e.put("hp",h.getInt("hp")).put("hp_timestamp",h.getDouble("hp_timestamp"));
                    attach(e,s);entities.put(key,e);positions++;
                }else if(category==4&&(kind==2||kind==32||kind==253)){
                    JSONObject reported=reported(kind,a.optLong("scale"),v);if(reported==null)continue;
                    JSONObject record=new JSONObject().put("stream",event.stream).put("id1",id).put("kind",kind).put("timestamp",ts).put("reported_input",reported);
                    String actionKey=key+"/"+kind;actions.remove(actionKey);actions.put(actionKey,record);
                }
            }
        }
        JSONArray removed=data.getJSONArray("removed");for(int i=0;i<removed.length();i++)remove(event.stream,removed.getString(i));
        double now=ts;entities.entrySet().removeIf(e->now-e.getValue().optDouble("timestamp")>300);
        health.entrySet().removeIf(e->now-e.getValue().optDouble("hp_timestamp")>300);
        for(JSONObject e:entities.values()){String key=e.optString("key");if(!health.containsKey(key)){e.remove("hp");e.remove("hp_timestamp");}}
        while(entities.size()>512)entities.remove(entities.keySet().iterator().next());
        while(health.size()>512)health.remove(health.keySet().iterator().next());
        while(actions.size()>512)actions.remove(actions.keySet().iterator().next());
    }
    private static JSONObject reported(int kind,long scale,JSONArray values)throws Exception{
        JSONObject out=new JSONObject().put("status","reported_input_not_render_state");
        if(kind==32){if(values.length()<1)return null;return out.put("reported_squat_u32",values.getLong(0)&0xffffffffL);}
        int count=kind==2?4:3;if(values.length()<count)return null;float denom=(float)scale;float[] v=new float[count];
        for(int i=0;i<count;i++){float value=(float)values.getLong(i);v[i]=denom>0?value/denom:value;}
        if(kind==2){float norm=((v[0]*v[0]+v[1]*v[1])+v[2]*v[2])+v[3]*v[3];if(norm<=0.000001f)v=new float[]{0,0,0,1};}
        JSONObject vector=new JSONObject();for(int i=0;i<count;i++)vector.put("xyzw".substring(i,i+1),(double)v[i]);
        return out.put(kind==2?"reported_actor_rotation":"reported_actor_up",vector);
    }
    JSONObject snapshot(double now)throws Exception{
        JSONArray all=new JSONArray(),camera=new JSONArray(),context=new JSONArray(),scene=new JSONArray(),localActions=new JSONArray();
        for(JSONObject e:entities.values())if(e.optInt("category")==4)all.put(copy(e));
        for(Map.Entry<String,Session> entry:sessions.entrySet()){
            String stream=entry.getKey();Session s=entry.getValue();if(s.camera!=null)camera.put(copy(s.camera));
            JSONObject c=copy(s.context);JSONObject view=s.uid==null?null:s.views.get(s.uid);if(view!=null)c.put("reported_perspective",copy(view));context.put(c);
            if(s.scene>0)scene.put(new JSONObject().put("stream",stream).put("timestamp",s.scene));
        }
        List<JSONObject> selected=new ArrayList<>();for(JSONObject a:actions.values()){
            for(JSONObject e:entities.values())if(e.optBoolean("is_local")&&e.optString("stream").equals(a.optString("stream"))&&e.optString("id1").equals(a.optString("id1"))){selected.add(a);break;}}
        selected.sort((a,b)->Double.compare(b.optDouble("timestamp"),a.optDouble("timestamp")));
        for(int i=0;i<Math.min(selected.size(),64);i++)localActions.put(copy(selected.get(i)));
        return new JSONObject().put("schema","esp_latest_v2").put("session",epoch).put("now",now)
            .put("entities",all).put("camera_streams",camera).put("camera_context",context).put("scene_streams",scene)
            .put("actor_action_state",new JSONObject().put("local_records",localActions));
    }
}