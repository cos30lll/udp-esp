package local.udp.esp.vpn;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed-build port of root r4 protocol.py/identity.py/camera.py.
 * Called only with direction/tuple supplied by this app's VpnService forwarder.
 * No packet rewriting, TLS inspection, port guessing or process access.
 */
final class GameDecoder {
    static final int MAX_MESSAGE=8*1024*1024;
    private final LinkedHashMap<String,Boolean> seen=new LinkedHashMap<>();
    private final LinkedHashMap<String,Fragment> fragments=new LinkedHashMap<>();
    long cameraMessages,sceneMessages,identityMessages,hpActions,decodeErrors,duplicates;
    String lastError="";
    static final class Event {
        String type,stream; double timestamp; int sequence,kind; JSONObject data;
        Event(String type,String stream,double ts,int seq,int kind,JSONObject data) {
            this.type=type;this.stream=stream;timestamp=ts;sequence=seq;this.kind=kind;this.data=data;
        }
    }
    private static final class Fragment {
        int flag,next,pieces;double timestamp;byte[] data;
    }
    static final class Field {int n,w;long value;byte[] bytes;}
    static final class Reader {
        final byte[] data;int p,count;
        Reader(byte[] data){this.data=data;}
        boolean more(){return p<data.length;}
        long varint(){
            long value=0;
            for(int shift=0;shift<70;shift+=7){
                if(p>=data.length)throw new IllegalArgumentException("truncated varint");
                int b=data[p++]&255;
                if(shift==63&&b>1)throw new IllegalArgumentException("uint64 overflow");
                value|=(long)(b&127)<<shift;if(b<128)return value;
            }
            throw new IllegalArgumentException("overlong varint");
        }
        byte[] take(long size){
            if(size<0||size>data.length-p)throw new IllegalArgumentException("field length");
            byte[] out=Arrays.copyOfRange(data,p,p+(int)size);p+=(int)size;return out;
        }
        Field next(boolean strict){
            if(++count>100000)throw new IllegalArgumentException("field limit");
            long tag=varint();Field f=new Field();f.n=(int)(tag>>>3);f.w=(int)(tag&7);
            if(f.n<=0)throw new IllegalArgumentException("zero field");
            if(f.w==0)f.value=varint();
            else if(f.w==2)f.bytes=take(varint());
            else if(!strict&&(f.w==1||f.w==5))f.bytes=take(f.w==1?8:4);
            else throw new IllegalArgumentException("unverified wire");return f;
        }
    }
    private static boolean checksum(byte[] b){return b.length>=4&&(b[0]&255)==((b.length+(b[1]&255)+(b[2]&255)+(b[3]&255))&255);}
    private static int le16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static int be16(byte[] b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}
    private boolean duplicate(String flow,int seq,byte[] b)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder key=new StringBuilder(flow).append('/').append(seq).append('/');
        for(byte v:hash)key.append((char)(v&255));String signature=key.toString();
        if(seen.containsKey(signature)){duplicates++;return true;}seen.put(signature,true);
        if(seen.size()>8192)seen.remove(seen.keySet().iterator().next());return false;
    }
    private static boolean transportHeader(byte[] b){
        if(b.length<8)return false;int f=b[1]&255;
        if(((f&15)!=4&&(f&15)!=5)||(f&16)!=0)return false;
        int off=(f&32)!=0?8:10;if(b.length<off)return false;
        if((f&32)!=0)return true;
        if((f&128)!=0)return b.length>=off+4&&be16(b,off)==0x28b5&&be16(b,off+2)==0x2ffd;
        return b.length>=off+4&&be16(b,off)==3;
    }
    private static void splitWalk(byte[] b,int offset,int expected,List<byte[]> parts,List<List<byte[]>> paths,int[] budget){
        if(++budget[0]>1024||parts.size()>=64||paths.size()>1)return;
        byte[] rest=Arrays.copyOfRange(b,offset,b.length);if(!transportHeader(rest))return;
        int seq=le16(rest,2);if(expected>=0&&seq!=expected)return;
        int minimum=(rest[1]&32)!=0?8:14;
        int length=((rest[0]&255)-(rest[1]&255)-(rest[2]&255)-(rest[3]&255))&255;
        while(length<minimum)length+=256;
        for(;length<=rest.length;length+=256){
            byte[] part=Arrays.copyOf(rest,length);if(!transportHeader(part)||!checksum(part))continue;
            List<byte[]> copy=new ArrayList<>(parts);copy.add(part);
            if(length==rest.length)paths.add(copy);
            else splitWalk(b,offset+length,(seq+1)&65535,copy,paths,budget);
        }
    }
    private static List<byte[]> segments(byte[] b){
        List<byte[]> single=new ArrayList<>();single.add(b);
        if(checksum(b)||!transportHeader(b))return single;
        List<List<byte[]>> paths=new ArrayList<>();int[] budget={0};
        splitWalk(b,0,-1,new ArrayList<>(),paths,budget);
        return paths.size()==1&&paths.get(0).size()>1&&budget[0]<=1024?paths.get(0):single;
    }
    List<Event> feed(boolean outgoing,String peer,int localPort,int remotePort,byte[] body,double timestamp){
        List<Event> out=new ArrayList<>();String stream=localPort+"@"+peer+":"+remotePort;
        try{
            if(outgoing){
                if(body.length<18)return out;int flag=body[1]&255;
                if(((flag&15)!=4&&(flag&15)!=5)||((flag&15)==4&&(flag&16)!=0)||(flag&96)!=0||!checksum(body))return out;
                int seq=le16(body,6);if(duplicate("out/"+stream+"/"+(flag&15),seq,body))return out;
                byte[] app=Arrays.copyOfRange(body,14,body.length);if((flag&128)!=0)app=VpnNative.decompress(app);
                if(app.length<4||be16(app,0)!=3)return out;int cmd=be16(app,2);byte[] payload=Arrays.copyOfRange(app,4,app.length);
                if(cmd==851){JSONObject view=view(payload);cameraMessages++;out.add(new Event("camera",stream,timestamp,seq,flag&15,view));}
                else if(cmd==2055){int v=0;Reader r=new Reader(payload);while(r.more()){Field f=r.next(true);if(f.n!=1||f.w!=0)throw new IllegalArgumentException("request field");v=(int)f.value;}
                    if(v!=0&&v!=1)throw new IllegalArgumentException("perspective");out.add(new Event("request",stream,timestamp,seq,flag&15,new JSONObject().put("value",v)));}
                return out;
            }
            Iterator<Map.Entry<String,Fragment>> it=fragments.entrySet().iterator();
            while(it.hasNext())if(timestamp-it.next().getValue().timestamp>15)it.remove();
            for(byte[] chunk:segments(body)){
                try{
                    if(chunk.length<2)continue;int flag=chunk[1]&255;
                    if(((flag&15)!=4&&(flag&15)!=5)||((flag&15)==4&&(flag&16)!=0))continue;
                    int minimum=(flag&32)!=0?8:10;
                    if(chunk.length<minimum||!checksum(chunk))throw new IllegalArgumentException("transport checksum");
                    int seq=le16(chunk,2);String flow=stream+"/"+(flag&15);
                    if(duplicate("in/"+flow,seq,chunk))continue;
                    byte[] app;int appFlag=flag;Fragment frag=fragments.get(flow);
                    if((flag&32)!=0){
                        if(frag==null)continue;
                        if(seq!=frag.next){fragments.remove(flow);continue;}
                        if(frag.data.length+chunk.length-8>MAX_MESSAGE||++frag.pieces>512){fragments.remove(flow);continue;}
                        byte[] next=Arrays.copyOf(frag.data,frag.data.length+chunk.length-8);
                        System.arraycopy(chunk,8,next,frag.data.length,chunk.length-8);frag.data=next;
                        frag.next=(seq+1)&65535;frag.timestamp=timestamp;
                        if((flag&64)!=0)continue;fragments.remove(flow);app=frag.data;appFlag=frag.flag;
                    }else if((flag&64)!=0){
                        frag=new Fragment();frag.data=Arrays.copyOfRange(chunk,10,chunk.length);frag.flag=flag;frag.next=(seq+1)&65535;frag.timestamp=timestamp;frag.pieces=1;
                        fragments.put(flow,frag);while(fragments.size()>16)fragments.remove(fragments.keySet().iterator().next());continue;
                    }else{fragments.remove(flow);app=Arrays.copyOfRange(chunk,10,chunk.length);}
                    if((appFlag&128)!=0)app=VpnNative.decompress(app);
                    if(app.length<4)throw new IllegalArgumentException("short app");if(be16(app,0)!=3)continue;
                    int cmd=be16(app,2);byte[] payload=Arrays.copyOfRange(app,4,app.length);JSONObject value;
                    if(cmd==852){value=scene(payload);sceneMessages++;out.add(new Event("scene",stream,timestamp,seq,flag&15,value));}
                    else if(cmd==1||cmd==3||cmd==6||cmd==2005){value=identity(cmd,payload);identityMessages++;out.add(new Event("identity",stream,timestamp,seq,flag&15,value));}
                    else if(cmd==2055){value=perspective(payload);out.add(new Event("perspective",stream,timestamp,seq,flag&15,value));}
                }catch(Exception error){decodeErrors++;lastError=error.toString();}
            }
        }catch(Exception error){decodeErrors++;lastError=error.toString();}
        return out;
    }
    private static JSONObject vector(byte[] data)throws Exception{
        Reader r=new Reader(data);JSONObject out=new JSONObject().put("x",0).put("y",0).put("z",0);
        while(r.more()){
            long tag=r.varint();int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(field<1||field>3||wire!=5)throw new IllegalArgumentException("vector field");
            long v=r.varint();if(v<0||v>0xffffffffL)throw new IllegalArgumentException("vector uint32");
            out.put("xyz".substring(field-1,field),((v>>>1)^-(v&1))/100.0);
        }return out;
    }
    private static JSONObject view(byte[] payload)throws Exception{
        JSONObject vectors=new JSONObject();Reader r=new Reader(payload);
        while(r.more()){Field f=r.next(true);if((f.n==1||f.n==3||f.n==4||f.n==5)&&f.w==2){
                // Repeated submessages merge, including explicitly present zero components.
                JSONObject old=vectors.optJSONObject(Integer.toString(f.n));JSONObject v=vector(f.bytes);
                if(old!=null){Reader parts=new Reader(f.bytes);while(parts.more()){
                    long tag=parts.varint();int n=(int)(tag>>>3);long raw=parts.varint();old.put("xyz".substring(n-1,n),((raw>>>1)^-(raw&1))/100.0);}v=old;}
                vectors.put(Integer.toString(f.n),v);
            }else if(f.n!=2||f.w!=0)throw new IllegalArgumentException("camera field");}
        JSONObject out=new JSONObject().put("source","outbound_3_851_field3").put("reported_vectors",vectors).put("status","view_field_absent");
        JSONObject v=vectors.optJSONObject("3");if(v==null)return out;
        double x=v.getDouble("x"),y=v.getDouble("y"),z=v.getDouble("z"),h=Math.hypot(x,z);
        if(Math.hypot(h,y)==0)return out.put("status","zero_view_vector");
        return out.put("status","decoded").put("yaw_deg",h>0?(Math.toDegrees(Math.atan2(x,z))+360)%360:JSONObject.NULL)
            .put("pitch_deg",Math.toDegrees(Math.atan2(-y,h)));
    }
    private static String utf8(byte[] b)throws CharacterCodingException{
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
    }
    private static JSONObject player(byte[] data)throws Exception{
        Reader r=new Reader(data);JSONObject p=new JSONObject();long id=0;
        while(r.more()){Field f=r.next(false);
            if(f.n==1&&f.w==0){id=f.value;if(id<0||id>0xffffffffL)throw new IllegalArgumentException("player id");p.put("entity_id",Long.toString(id));}
            else if(f.n==2&&f.w==0)p.put("uid",Long.toUnsignedString(f.value));
            else if(f.n==3&&f.w==2)p.put("name",utf8(f.bytes));}
        return id==0?null:p;
    }
    private static void roster(byte[] data,JSONArray out)throws Exception{
        Reader r=new Reader(data);while(r.more()){Field f=r.next(false);if(f.n==1&&f.w==2){JSONObject p=player(f.bytes);if(p!=null)out.put(p);}}
    }
    private static JSONObject perspective(byte[] data)throws Exception{
        Reader r=new Reader(data);JSONObject p=new JSONObject().put("value",0);while(r.more()){Field f=r.next(false);
            if(f.n==1&&f.w==0)p.put("value",f.value);else if(f.n==2&&f.w==0)p.put("uid",Long.toUnsignedString(f.value));}return p;
    }
    private static JSONObject identity(int cmd,byte[] data)throws Exception{
        JSONObject out=new JSONObject().put("command",cmd).put("reset",cmd==1);
        JSONArray players=new JSONArray(),removed=new JSONArray(),views=new JSONArray();out.put("players",players).put("removed",removed).put("perspectives",views);
        if(cmd==3||cmd==6)roster(data,players);
        Reader r=new Reader(data);while(r.more()){Field f=r.next(false);
            if(cmd==1){
                if(f.n==2&&f.w==0)out.put("local_uid",Long.toUnsignedString(f.value));
                else if(f.n==3&&f.w==0)out.put("local_entity_id",Long.toString(f.value));
                else if(f.n==5&&f.w==2)roster(f.bytes,players);
                else if((f.n==16||f.n==19)&&f.w==0)out.put(f.n==16?"gameplay_type":"gameplay_id",f.value);
                else if(f.n==51&&f.w==2){JSONObject v=new JSONObject().put("value",0);Reader q=new Reader(f.bytes);while(q.more()){Field x=q.next(false);
                    if(x.n==1&&x.w==0)v.put("uid",Long.toUnsignedString(x.value));else if(x.n==2&&x.w==0)v.put("value",x.value);}views.put(v);}
            }else if(cmd==6&&f.n==2){
                if(f.w==0)removed.put(Long.toString(f.value));else if(f.w==2){Reader q=new Reader(f.bytes);while(q.more())removed.put(Long.toString(q.varint()));}
            }else if(cmd==2005&&f.n==1&&f.w==0)out.put("local_uid",Long.toUnsignedString(f.value));
        }return out;
    }
    private static JSONObject action(byte[] data)throws Exception{
        JSONObject a=new JSONObject().put("kind",0).put("scale",0);JSONArray values=new JSONArray();Reader r=new Reader(data);
        while(r.more()){Field f=r.next(true);
            if((f.n==1||f.n==2)&&f.w==0)a.put(f.n==1?"kind":"scale",f.value);
            else if(f.n==3&&f.w==0)values.put(f.value);
            else if(f.n==3&&f.w==2){Reader p=new Reader(f.bytes);while(p.more()){if(values.length()>32768)throw new IllegalArgumentException("values limit");values.put(p.varint());}}
            else if(f.n!=4||f.w!=2)throw new IllegalArgumentException("action field");
        }return a.put("values",values);
    }
    private JSONObject scene(byte[] data)throws Exception{
        JSONObject out=new JSONObject();JSONArray entities=new JSONArray(),removed=new JSONArray();Reader r=new Reader(data);
        while(r.more()){Field f=r.next(true);
            if((f.n==4||f.n==5)&&f.w==0)continue;
            if(f.n==7&&(f.w==0||f.w==2)){if(f.w==0)removed.put(Long.toString(f.value));else{Reader q=new Reader(f.bytes);while(q.more())removed.put(Long.toString(q.varint()));}continue;}
            if(f.n!=6||f.w!=2)throw new IllegalArgumentException("scene field");
            JSONObject e=new JSONObject().put("id1","0").put("id2","0").put("category",0);JSONArray actions=new JSONArray();Reader q=new Reader(f.bytes);
            while(q.more()){Field x=q.next(true);
                if((x.n==1||x.n==2||x.n==3)&&x.w==0)e.put(x.n==1?"id1":x.n==2?"id2":"category",x.n==3?(Object)x.value:Long.toString(x.value));
                else if(x.n==4&&x.w==2){JSONObject a=action(x.bytes);if(a.optInt("kind")==31)hpActions++;actions.put(a);}
                else throw new IllegalArgumentException("entity field");}
            e.put("actions",actions);entities.put(e);
        }return out.put("entities",entities).put("removed",removed);
    }
}
