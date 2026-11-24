package com.example.stlviewer;

import android.util.Base64;
import org.json.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Mesh-only importers; materials/animation are intentionally not imported. */
public final class ModelImporter {
  private static final int LIMIT=100_000_000, MAX_FLOATS=6_000_000;
  private ModelImporter(){}
  static MainActivity.Mesh open(InputStream in,String name) throws Exception {
    byte[] data=read(in);
    String lower=name.toLowerCase(Locale.ROOT);
    if(lower.endsWith(".stl"))return MainActivity.Mesh.read(new ByteArrayInputStream(data));
    float[] triangles;
    if(lower.endsWith(".obj")) triangles=obj(data);
    else if(lower.endsWith(".off")) triangles=off(data);
    else if(lower.endsWith(".ply")) triangles=ply(data);
    else if(lower.endsWith(".3mf")) triangles=threeMf(data);
    else if(lower.endsWith(".glb") || lower.endsWith(".gltf")) triangles=gltf(data,lower.endsWith(".glb"));
    else if(lower.endsWith(".3ds")) triangles=threeDs(data);
    else throw new IOException("Unsupported format: "+name+". Use STL, OBJ, PLY, OFF, 3MF, GLB, GLTF (embedded data) or 3DS.");
    if(triangles.length<9)throw new IOException("No triangle faces found");
    if(triangles.length>MAX_FLOATS)throw new IOException("Model too complex");
    // Reuse STL mesh normalization, normals, bounds and volume computation.
    int count=triangles.length/9;
    ByteBuffer bb=ByteBuffer.allocate(84+count*50).order(ByteOrder.LITTLE_ENDIAN);
    bb.position(80);bb.putInt(count);
    for(int i=0;i<triangles.length;i+=9){
      bb.putFloat(0).putFloat(0).putFloat(0);
      for(int j=0;j<9;j++)bb.putFloat(triangles[i+j]);
      bb.putShort((short)0);
    }
    return MainActivity.Mesh.read(new ByteArrayInputStream(bb.array()));
  }
  private static byte[] read(InputStream in)throws IOException {
    ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] temp=new byte[32768];int n;
    while((n=in.read(temp))!=-1){if(b.size()+n>LIMIT)throw new IOException("Model exceeds 100 MB");b.write(temp,0,n);}
    return b.toByteArray();
  }
  private static final class Builder {
    final ArrayList<Float> values=new ArrayList<>();
    void tri(float[] a,float[] b,float[] c)throws IOException {
      if(values.size()+9>MAX_FLOATS)throw new IOException("Too many triangles");
      for(float f:a)add(f);for(float f:b)add(f);for(float f:c)add(f);
    }
    void add(float f)throws IOException{if(!Float.isFinite(f))throw new IOException("Non-finite coordinate");values.add(f);}
    float[] done(){float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
    void face(List<float[]> vertices,int[] indices)throws IOException {
      for(int n=1;n+1<indices.length;n++)tri(vertices.get(indices[0]),vertices.get(indices[n]),vertices.get(indices[n+1]));
    }
  }
  private static String[] words(String line){return line.trim().split("\\s+");}
  private static int idx(String token,int size)throws IOException{
    int n=Integer.parseInt(token);int v=n>0?n-1:size+n;
    if(v<0||v>=size)throw new IOException("Face index out of range");
    return v;
  }
  private static float[] vec(String[] s,int start)throws IOException{
    try{return new float[]{Float.parseFloat(s[start]),Float.parseFloat(s[start+1]),Float.parseFloat(s[start+2])};}
    catch(Exception e){throw new IOException("Bad vertex coordinates",e);}
  }
  private static float[] obj(byte[] data)throws IOException {
    ArrayList<float[]> vertices=new ArrayList<>();Builder b=new Builder();
    try(BufferedReader reader=new BufferedReader(new StringReader(new String(data,StandardCharsets.UTF_8)))){
      String line;
      while((line=reader.readLine())!=null){
        String s=line.trim();if(s.isEmpty()||s.startsWith("#"))continue;
        String[] w=words(s);
        if(w[0].equals("v")&&w.length>=4)vertices.add(vec(w,1));
        else if(w[0].equals("f")&&w.length>=4){
          int[] indices=new int[w.length-1];
          for(int i=1;i<w.length;i++)indices[i-1]=idx(w[i].split("/",-1)[0],vertices.size());
          b.face(vertices,indices);
        }
      }
    }
    return b.done();
  }
  private static float[] off(byte[] data)throws IOException{
    String text=new String(data,StandardCharsets.UTF_8).replace("\r","");
    ArrayList<String> tokens=new ArrayList<>();
    for(String line:text.split("\n")){String s=line.split("#",2)[0].trim();if(!s.isEmpty())tokens.addAll(Arrays.asList(words(s)));}
    int p=0;
    if(tokens.isEmpty()||!tokens.get(p++).equalsIgnoreCase("OFF"))throw new IOException("Invalid OFF header");
    int nv=Integer.parseInt(tokens.get(p++)),nf=Integer.parseInt(tokens.get(p++));p++; // edges
    if(nv<0||nv>2_000_000||nf<0||nf>1_000_000)throw new IOException("OFF size out of range");
    ArrayList<float[]> v=new ArrayList<>();
    for(int i=0;i<nv;i++){v.add(new float[]{Float.parseFloat(tokens.get(p++)),Float.parseFloat(tokens.get(p++)),Float.parseFloat(tokens.get(p++))});}
    Builder b=new Builder();
    for(int i=0;i<nf;i++){int n=Integer.parseInt(tokens.get(p++));if(n<3||n>100000)throw new IOException("Bad OFF polygon");int[] ids=new int[n];
      for(int k=0;k<n;k++){ids[k]=Integer.parseInt(tokens.get(p++));if(ids[k]<0||ids[k]>=nv)throw new IOException("OFF index out of range");}
      b.face(v,ids);
    }
    return b.done();
  }
  private static float[] ply(byte[] data)throws IOException{
    int end=-1;for(int i=0;i<data.length-10;i++){
      if(data[i]=='e'&&data[i+1]=='n'&&data[i+2]=='d'&&data[i+3]=='_'&&data[i+4]=='h'&&data[i+5]=='e'&&data[i+6]=='a'&&data[i+7]=='d'&&data[i+8]=='e'&&data[i+9]=='r'){
        int p=i+10;while(p<data.length&&(data[p]=='\r'||data[p]=='\n'))p++;end=p;break;
      }
    }
    if(end<0)throw new IOException("PLY missing end_header");
    String header=new String(data,0,end,StandardCharsets.US_ASCII);
    if(!header.startsWith("ply"))throw new IOException("Not a PLY file");
    boolean ascii=header.contains("format ascii"),little=header.contains("format binary_little_endian");
    if(!ascii&&!little)throw new IOException("PLY format not supported (big endian)");
    int nv=0,nf=0;ArrayList<String> props=new ArrayList<>();boolean vertex=false;
    for(String l:header.split("\r?\n")){String[] w=words(l);if(w.length==0)continue;
      if(w.length>=3&&w[0].equals("element")){vertex=w[1].equals("vertex");if(vertex)nv=Integer.parseInt(w[2]);else if(w[1].equals("face"))nf=Integer.parseInt(w[2]);}
      else if(w.length>=3&&w[0].equals("property")&&vertex){if(w[1].equals("list"))throw new IOException("PLY vertex list properties unsupported");props.add(w[1]+":"+w[2]);}
    }
    if(nv<0||nv>2_000_000||nf<0||nf>1_000_000)throw new IOException("PLY size out of range");
    int px=propertyIndex(props,"x"),py=propertyIndex(props,"y"),pz=propertyIndex(props,"z");
    if(px<0||py<0||pz<0)throw new IOException("PLY xyz vertex coordinates missing");
    ArrayList<float[]> verts=new ArrayList<>(nv);Builder b=new Builder();
    if(ascii){
      BufferedReader lines=new BufferedReader(new StringReader(new String(data,end,data.length-end,StandardCharsets.UTF_8)));
      for(int i=0;i<nv;i++){String line=lines.readLine();if(line==null)throw new IOException("Short PLY vertices");
        String[] w=words(line);verts.add(new float[]{Float.parseFloat(w[px]),Float.parseFloat(w[py]),Float.parseFloat(w[pz])});}
      for(int i=0;i<nf;i++){String line=lines.readLine();if(line==null)throw new IOException("Short PLY faces");
        String[] w=words(line);int n=Integer.parseInt(w[0]);if(n<3||n>100000||w.length<=n)throw new IOException("Invalid PLY face");
        int[] ids=new int[n];for(int k=0;k<n;k++){ids[k]=Integer.parseInt(w[k+1]);if(ids[k]<0||ids[k]>=nv)throw new IOException("PLY face index out of range");}b.face(verts,ids);}
    }else{
      ByteBuffer bb=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);bb.position(end);
      for(int i=0;i<nv;i++){float[] xyz=new float[3];
        for(int k=0;k<props.size();k++){
          String type=props.get(k).split(":")[0];double value=number(bb,type);
          if(k==px)xyz[0]=(float)value;if(k==py)xyz[1]=(float)value;if(k==pz)xyz[2]=(float)value;
        }verts.add(xyz);
      }
      // Typical binary PLY face format: property list uchar int vertex_indices.
      String listHeader=header.toLowerCase(Locale.ROOT);
      boolean u8=listHeader.contains("property list uchar int")||listHeader.contains("property list uint8 int32");
      if(!u8&&nf>0)throw new IOException("Binary PLY faces require uchar/int list indices");
      for(int i=0;i<nf;i++){int n=Byte.toUnsignedInt(bb.get());if(n<3||n>100)throw new IOException("Unsupported PLY polygon");
        int[] ids=new int[n];for(int k=0;k<n;k++){ids[k]=bb.getInt();if(ids[k]<0||ids[k]>=nv)throw new IOException("Bad PLY face index");}
        b.face(verts,ids);
      }
    }
    return b.done();
  }
  private static int propertyIndex(ArrayList<String> props,String name){for(int i=0;i<props.size();i++)if(props.get(i).endsWith(":"+name))return i;return -1;}
  private static double number(ByteBuffer b,String t)throws IOException{
    switch(t){case "float":case "float32":return b.getFloat();
      case "double":case "float64":return b.getDouble();
      case "char":case "int8":return b.get();
      case "uchar":case "uint8":return Byte.toUnsignedInt(b.get());
      case "short":case "int16":return b.getShort();
      case "ushort":case "uint16":return Short.toUnsignedInt(b.getShort());
      case "int":case "int32":return b.getInt();
      case "uint":case "uint32":return Integer.toUnsignedLong(b.getInt());
      default:throw new IOException("Unsupported PLY property: "+t);}
  }
  private static Document xml(byte[] bytes)throws Exception{
    DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    f.setFeature("http://xml.org/sax/features/external-general-entities",false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
  }
  private static float[] threeMf(byte[] data)throws Exception{
    ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(data));
    ZipEntry entry;byte[] model=null;
    while((entry=z.getNextEntry())!=null){String n=entry.getName().toLowerCase(Locale.ROOT);
      if(n.endsWith(".model")&&n.startsWith("3d/")){model=read(z);break;}z.closeEntry();
    }
    z.close();if(model==null)throw new IOException("3MF model content not found");
    Document doc=xml(model);Element root=doc.getDocumentElement();
    String unit=root.getAttribute("unit");float factor=1;
    if(unit.equals("micron"))factor=.001f;else if(unit.equals("centimeter"))factor=10;
    else if(unit.equals("meter"))factor=1000;else if(unit.equals("inch"))factor=25.4f;else if(unit.equals("foot"))factor=304.8f;
    HashMap<String,ArrayList<float[]>> objects=new HashMap<>();
    HashMap<String,ArrayList<int[]>> faces=new HashMap<>();
    NodeList nodes=doc.getElementsByTagNameNS("*","object");
    for(int i=0;i<nodes.getLength();i++){
      Element o=(Element)nodes.item(i);String id=o.getAttribute("id");
      NodeList vs=o.getElementsByTagNameNS("*","vertex");
      ArrayList<float[]> v=new ArrayList<>();
      for(int j=0;j<vs.getLength();j++){Element e=(Element)vs.item(j);
        v.add(new float[]{Float.parseFloat(e.getAttribute("x"))*factor,Float.parseFloat(e.getAttribute("y"))*factor,Float.parseFloat(e.getAttribute("z"))*factor});}
      ArrayList<int[]> ff=new ArrayList<>();
      NodeList ts=o.getElementsByTagNameNS("*","triangle");
      for(int j=0;j<ts.getLength();j++){Element e=(Element)ts.item(j);
        ff.add(new int[]{Integer.parseInt(e.getAttribute("v1")),Integer.parseInt(e.getAttribute("v2")),Integer.parseInt(e.getAttribute("v3"))});}
      objects.put(id,v);faces.put(id,ff);
    }
    Builder b=new Builder();NodeList items=doc.getElementsByTagNameNS("*","item");
    if(items.getLength()>0){
      for(int i=0;i<items.getLength();i++){Element e=(Element)items.item(i);
        append3mf(b,objects,faces,e.getAttribute("objectid"),e.getAttribute("transform")); }
    }else for(String id:objects.keySet())append3mf(b,objects,faces,id,"");
    return b.done();
  }
  private static void append3mf(Builder b,HashMap<String,ArrayList<float[]>> obj,HashMap<String,ArrayList<int[]>> faces,String id,String transform)throws IOException{
    ArrayList<float[]> v=obj.get(id);ArrayList<int[]> ff=faces.get(id);
    if(v==null||ff==null)return;
    float[] t=null;
    if(!transform.isEmpty()){String[] s=words(transform);if(s.length!=12)throw new IOException("Invalid 3MF transform");t=new float[12];for(int i=0;i<12;i++)t[i]=Float.parseFloat(s[i]);}
    ArrayList<float[]> verts=new ArrayList<>(v.size());
    for(float[] a:v)verts.add(t==null?a:new float[]{a[0]*t[0]+a[1]*t[3]+a[2]*t[6]+t[9],a[0]*t[1]+a[1]*t[4]+a[2]*t[7]+t[10],a[0]*t[2]+a[1]*t[5]+a[2]*t[8]+t[11]});
    for(int[] triangle:ff){for(int idx:triangle)if(idx<0||idx>=verts.size())throw new IOException("Invalid 3MF face");b.face(verts,triangle);}
  }
  private static float[] gltf(byte[] data,boolean binary)throws Exception{
    byte[] json,bin=null;
    if(binary){
      ByteBuffer bb=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
      if(data.length<20||bb.getInt()!=0x46546c67||bb.getInt()!=2)throw new IOException("Invalid GLB");
      long total=Integer.toUnsignedLong(bb.getInt());if(total>data.length)throw new IOException("Truncated GLB");
      int jsonSize=bb.getInt(),jsonType=bb.getInt();
      if(jsonSize<0||jsonSize>bb.remaining()||jsonType!=0x4e4f534a)throw new IOException("Invalid GLB JSON");
      json=new byte[jsonSize];bb.get(json);
      if(bb.remaining()>=8){int n=bb.getInt(),type=bb.getInt();if(type==0x004e4942&&n>=0&&n<=bb.remaining()){bin=new byte[n];bb.get(bin);}}
    }else json=data;
    JSONObject doc=new JSONObject(new String(json,StandardCharsets.UTF_8));
    JSONArray buffers=doc.getJSONArray("buffers");
    ArrayList<byte[]> raw=new ArrayList<>();
    for(int i=0;i<buffers.length();i++){
      JSONObject buffer=buffers.getJSONObject(i);String uri=buffer.optString("uri","");
      if(uri.startsWith("data:")){int comma=uri.indexOf(',');if(comma<0)throw new IOException("Bad data URI");raw.add(Base64.decode(uri.substring(comma+1),Base64.DEFAULT));}
      else if(i==0&&bin!=null)raw.add(bin);
      else throw new IOException("External glTF buffers are not supported; export embedded glTF or GLB");
    }
    JSONArray views=doc.getJSONArray("bufferViews"),accessors=doc.getJSONArray("accessors"),meshes=doc.getJSONArray("meshes");
    Builder out=new Builder();
    for(int i=0;i<meshes.length();i++){
      JSONArray ps=meshes.getJSONObject(i).getJSONArray("primitives");
      for(int k=0;k<ps.length();k++){
        JSONObject p=ps.getJSONObject(k);if(p.optInt("mode",4)!=4)continue;
        int posIndex=p.getJSONObject("attributes").optInt("POSITION",-1);
        if(posIndex<0)continue;
        float[] verts=positions(accessors.getJSONObject(posIndex),views,raw);
        int[] ids=p.has("indices")?indices(accessors.getJSONObject(p.getInt("indices")),views,raw):null;
        int n=ids==null?verts.length/3:ids.length;
        for(int a=0;a+2<n;a+=3){
          int a0=ids==null?a:ids[a],a1=ids==null?a+1:ids[a+1],a2=ids==null?a+2:ids[a+2];
          if(a0*3+2>=verts.length||a1*3+2>=verts.length||a2*3+2>=verts.length)throw new IOException("glTF index out of bounds");
          out.tri(Arrays.copyOfRange(verts,a0*3,a0*3+3),Arrays.copyOfRange(verts,a1*3,a1*3+3),Arrays.copyOfRange(verts,a2*3,a2*3+3));
        }
      }
    }
    return out.done();
  }
  private static ByteBuffer accessor(JSONObject a,JSONArray views,ArrayList<byte[]> buffers,int bytes)throws Exception {
    if(a.has("sparse"))throw new IOException("Sparse glTF accessors not supported");
    JSONObject v=views.getJSONObject(a.getInt("bufferView"));
    byte[] src=buffers.get(v.getInt("buffer"));
    int off=v.optInt("byteOffset",0)+a.optInt("byteOffset",0);
    int count=a.getInt("count");
    int stride=v.optInt("byteStride",bytes);
    if(off<0||count<0||stride<bytes||off+(long)(Math.max(0,count-1))*stride+bytes>src.length)throw new IOException("Invalid glTF buffer range");
    ByteBuffer b=ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN);b.position(off);return b;
  }
  private static float[] positions(JSONObject a,JSONArray views,ArrayList<byte[]> raw)throws Exception {
    if(a.getInt("componentType")!=5126||!a.getString("type").equals("VEC3"))throw new IOException("glTF POSITION requires FLOAT VEC3");
    int n=a.getInt("count");if(n<0||n>2_000_000)throw new IOException("Too many glTF vertices");
    int stride=views.getJSONObject(a.getInt("bufferView")).optInt("byteStride",12);
    ByteBuffer b=accessor(a,views,raw,12);int off=b.position();
    float[] verts=new float[n*3];for(int i=0;i<n;i++){int at=off+i*stride;for(int j=0;j<3;j++)verts[i*3+j]=b.getFloat(at+j*4)*1000f;}return verts;
  }
  private static int[] indices(JSONObject a,JSONArray views,ArrayList<byte[]> raw)throws Exception{
    int ct=a.getInt("componentType"),bytes=ct==5121?1:ct==5123?2:ct==5125?4:0;
    if(bytes==0)throw new IOException("Unsupported glTF index type");
    int n=a.getInt("count");if(n<0||n>MAX_FLOATS)throw new IOException("Too many indices");
    JSONObject v=views.getJSONObject(a.getInt("bufferView"));int stride=v.optInt("byteStride",bytes);
    ByteBuffer b=accessor(a,views,raw,bytes);int off=b.position();
    int[] ids=new int[n];for(int i=0;i<n;i++){int at=off+i*stride;ids[i]=bytes==1?Byte.toUnsignedInt(b.get(at)):bytes==2?Short.toUnsignedInt(b.getShort(at)):b.getInt(at);}
    return ids;
  }
  private static float[] threeDs(byte[] data)throws IOException{
    ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if(data.length<6||Short.toUnsignedInt(b.getShort(0))!=0x4d4d)throw new IOException("Not a 3DS file");
    Builder out=new Builder();read3ds(b,0,data.length,out,null);
    return out.done();
  }
  private static ArrayList<float[]> read3ds(ByteBuffer b,int start,int limit,Builder out,ArrayList<float[]> current)throws IOException{
    int p=start;
    while(p+6<=limit){
      int tag=Short.toUnsignedInt(b.getShort(p));long length=Integer.toUnsignedLong(b.getInt(p+2));
      if(length<6||p+length>limit)throw new IOException("Corrupt 3DS chunk");
      int end=(int)(p+length),body=p+6;
      if(tag==0x4d4d||tag==0x3d3d||tag==0x4100||tag==0x4000){
        if(tag==0x4000){while(body<end&&b.get(body)!=0)body++;body++;}
        if(body<end)current=read3ds(b,body,end,out,current);
      }else if(tag==0x4110){
        int n=Short.toUnsignedInt(b.getShort(body));ArrayList<float[]> v=new ArrayList<>(n);int at=body+2;
        for(int i=0;i<n;i++){if(at+12>end)throw new IOException("Short 3DS vertices");
          v.add(new float[]{b.getFloat(at),b.getFloat(at+4),b.getFloat(at+8)});at+=12;}
        current=v;
      }else if(tag==0x4120&&current!=null){
        int n=Short.toUnsignedInt(b.getShort(body)),at=body+2;
        for(int i=0;i<n;i++){if(at+8>end)throw new IOException("Short 3DS faces");
          int a=Short.toUnsignedInt(b.getShort(at)),c=Short.toUnsignedInt(b.getShort(at+2)),d=Short.toUnsignedInt(b.getShort(at+4));at+=8;
          if(a>=current.size()||c>=current.size()||d>=current.size())throw new IOException("Bad 3DS index");
          out.tri(current.get(a),current.get(c),current.get(d));
        }
      }
      p=end;
    }
    return current;
  }
}