package com.example.stlviewer;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.*;
import android.view.Gravity;
import android.graphics.Color;
import java.io.*;
import java.nio.*;
import java.util.*;

public class MainActivity extends Activity {
  static final int REQUEST=42;
  GLSurfaceView view;
  Viewer renderer;
  TextView details;
  ScaleGestureDetector pinch;
  float lastX,lastY;
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff101926);
    TextView title=new TextView(this);title.setText("STL VIEWER 3");title.setTextSize(23);title.setTextColor(0xff82e7d2);title.setPadding(20,22,10,8);root.addView(title);
    details=new TextView(this);details.setText("Open an STL file to view a 3D model");details.setTextColor(Color.WHITE);details.setPadding(20,4,10,16);root.addView(details);
    renderer=new Viewer();
    view=new GLSurfaceView(this);view.setEGLContextClientVersion(2);view.setRenderer(renderer);view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);
    Button open=new Button(this);open.setText("Open STL");actions.addView(open,new LinearLayout.LayoutParams(0,-2,1));
    Button reset=new Button(this);reset.setText("Reset view");actions.addView(reset,new LinearLayout.LayoutParams(0,-2,1));
    root.addView(actions);setContentView(root);
    open.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQUEST);});
    reset.setOnClickListener(v->{view.queueEvent(()->renderer.reset());view.requestRender();});
    pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
      @Override public boolean onScale(ScaleGestureDetector d) {float s=d.getScaleFactor();view.queueEvent(()->renderer.zoom(s));view.requestRender();return true;}
    });
    view.setOnTouchListener((v,e)->{
      pinch.onTouchEvent(e);
      if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}
      if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float x=e.getX(),y=e.getY(),dx=x-lastX,dy=y-lastY;lastX=x;lastY=y;
        if(e.getPointerCount()==1&&!pinch.isInProgress()){view.queueEvent(()->renderer.rotate(dx,dy));view.requestRender();}}
      return true;
    });
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req!=REQUEST||result!=RESULT_OK||data==null)return;
    Uri uri=data.getData();if(uri==null)return;
    details.setText("Loading STL...");
    new Thread(()->{
      try(InputStream in=getContentResolver().openInputStream(uri)){
        if(in==null)throw new IOException("Cannot open file");
        Mesh m=Mesh.read(in);
        view.queueEvent(()->renderer.setMesh(m));view.requestRender();
        runOnUiThread(()->{
          double cm3=m.volumeMm3/1000.0;
          double meters=m.volumeMm3/(Math.PI*0.875*0.875)/1000.0;
          details.setText(String.format(java.util.Locale.US,
            "%s  •  %,d triangles\nSize: %.2f × %.2f × %.2f mm\nSolid volume: %.3f cm³  |  1.75 mm filament: %.2f m\nAssumes STL units are mm; closed watertight mesh; 100%% solid",
            uri.getLastPathSegment(),m.count,m.width,m.height,m.depth,cm3,meters));
        });
      }catch(Exception ex){runOnUiThread(()->details.setText("Error: "+ex.getMessage()));}
    }).start();
  }
  @Override public void onResume(){super.onResume();if(view!=null)view.onResume();}
  @Override public void onPause(){if(view!=null)view.onPause();super.onPause();}

  static class Mesh {
    FloatBuffer triangles;
    FloatBuffer normals;
    int count;
    float width,height,depth;
    double volumeMm3;
    static Mesh read(InputStream stream)throws Exception {
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[32768];int n;
      while((n=stream.read(buf))!=-1){out.write(buf,0,n);if(out.size()>100000000)throw new IOException("File too large (100 MB max)");}
      byte[] data=out.toByteArray(); if(data.length<15)throw new IOException("Invalid STL file");
      ByteBuffer bb=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
      boolean binary=false;long count=0;
      if(data.length>=84){count=Integer.toUnsignedLong(bb.getInt(80));binary=(count>0 && count<2000000 && 84L+50L*count==data.length);}
      float[] coords;
      if(binary){
        coords=new float[(int)count*9];
        for(int i=0;i<count;i++){int start=84+50*i;for(int k=0;k<9;k++)coords[i*9+k]=bb.getFloat(start+12+4*k);}
      }else {
        String s=new String(data,java.nio.charset.StandardCharsets.US_ASCII);
        java.util.regex.Matcher mat=java.util.regex.Pattern.compile("(?i)\\bvertex\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)").matcher(s);
        java.util.ArrayList<Float> vals=new java.util.ArrayList<>();
        while(mat.find()){for(int i=1;i<=3;i++)vals.add(Float.parseFloat(mat.group(i)));}
        if(vals.size()<9||vals.size()%9!=0)throw new IOException("Invalid ASCII STL triangles");
        coords=new float[vals.size()];for(int i=0;i<coords.length;i++)coords[i]=vals.get(i);
      }
      float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
      float[] max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
      for(int i=0;i<coords.length;i++){float f=coords[i];if(!Float.isFinite(f))throw new IOException("Invalid vertex");int k=i%3;min[k]=Math.min(min[k],f);max[k]=Math.max(max[k],f);}
      float extent=Math.max(max[0]-min[0],Math.max(max[1]-min[1],max[2]-min[2])); if(extent<=0)extent=1;
      // Signed tetrahedral sum: valid enclosed volume for consistently oriented watertight STL surfaces.
      // Coordinates must be evaluated before normalization.
      double signedVolume=0;
      for(int i=0;i<coords.length;i+=9){
        double ax=coords[i],ay=coords[i+1],az=coords[i+2];
        double bx=coords[i+3],by=coords[i+4],bz=coords[i+5];
        double cx=coords[i+6],cy=coords[i+7],cz=coords[i+8];
        signedVolume+=ax*(by*cz-bz*cy)-ay*(bx*cz-bz*cx)+az*(bx*cy-by*cx);
      }
      for(int i=0;i<coords.length;i++)coords[i]=(coords[i]-(min[i%3]+max[i%3])*0.5f)*2f/extent;
      // STL is a triangle soup. Calculate one normal per face, repeated at each vertex,
      // so flat CAD surfaces stay distinct rather than being smoothed together.
      float[] normals=new float[coords.length];
      for(int i=0;i<coords.length;i+=9){
        float ux=coords[i+3]-coords[i],uy=coords[i+4]-coords[i+1],uz=coords[i+5]-coords[i+2];
        float vx=coords[i+6]-coords[i],vy=coords[i+7]-coords[i+1],vz=coords[i+8]-coords[i+2];
        float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
        float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(len>1e-15f){nx/=len;ny/=len;nz/=len;}else{nx=0;ny=0;nz=1;}
        for(int k=0;k<9;k+=3){normals[i+k]=nx;normals[i+k+1]=ny;normals[i+k+2]=nz;}
      }
      Mesh m=new Mesh();m.count=coords.length/9;
      m.width=max[0]-min[0];m.height=max[1]-min[1];m.depth=max[2]-min[2];
      m.volumeMm3=Math.abs(signedVolume/6.0);
      ByteBuffer direct=ByteBuffer.allocateDirect(coords.length*4).order(ByteOrder.nativeOrder());
      m.triangles=direct.asFloatBuffer();m.triangles.put(coords);m.triangles.position(0);
      ByteBuffer ndirect=ByteBuffer.allocateDirect(normals.length*4).order(ByteOrder.nativeOrder());
      m.normals=ndirect.asFloatBuffer();m.normals.put(normals);m.normals.position(0);
      return m;
    }
  }

  static class Viewer implements GLSurfaceView.Renderer {
    Mesh mesh;int program,position,normal,mvpLoc,mvLoc;
    float ax=-25,ay=30,zoom=3.6f,aspect=1;
    float[] proj=new float[16],mv=new float[16],mvp=new float[16];
    void setMesh(Mesh m){mesh=m;reset();}
    void reset(){ax=-25;ay=30;zoom=3.6f;}
    void rotate(float dx,float dy){ay+=dx*0.45f;ax+=dy*0.45f;}
    void zoom(float z){zoom=Math.max(1.6f,Math.min(20,zoom/Math.max(.1f,z)));}
    static int shader(int type,String code){int sh=GLES20.glCreateShader(type);GLES20.glShaderSource(sh,code);GLES20.glCompileShader(sh);return sh;}
    @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,javax.microedition.khronos.egl.EGLConfig cfg){
      GLES20.glClearColor(.055f,.09f,.145f,1);
      GLES20.glEnable(GLES20.GL_DEPTH_TEST);
      String vertex="uniform mat4 uMVP; uniform mat4 uMV; attribute vec3 aPosition; attribute vec3 aNormal;"+
        "varying vec3 vNormal; varying vec3 vEye;"+
        "void main(){vec4 eye=uMV*vec4(aPosition,1.0);vEye=eye.xyz;vNormal=(uMV*vec4(aNormal,0.0)).xyz;gl_Position=uMVP*vec4(aPosition,1.0);}";
      String fragment="precision mediump float; varying vec3 vNormal; varying vec3 vEye;"+
        "void main(){"+
        "vec3 N=normalize(vNormal);if(!gl_FrontFacing)N=-N;"+
        "vec3 light=normalize(vec3(-0.45,0.75,0.85));"+
        "vec3 fill=normalize(vec3(0.7,-0.4,0.25));"+
        "float diffuse=max(dot(N,light),0.0);"+
        "float bounce=max(dot(N,fill),0.0);"+
        "float brightness=0.25+0.64*diffuse+0.16*bounce;"+
        "vec3 eye=normalize(-vEye);vec3 halfDir=normalize(light+eye);"+
        "float spec=pow(max(dot(N,halfDir),0.0),30.0)*0.22;"+
        "vec3 base=vec3(0.67,0.75,0.83);"+
        "vec3 result=base*brightness+vec3(spec);"+
        "gl_FragColor=vec4(result,1.0);}";
      int vs=shader(GLES20.GL_VERTEX_SHADER,vertex);
      int fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
      program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
      position=GLES20.glGetAttribLocation(program,"aPosition");
      normal=GLES20.glGetAttribLocation(program,"aNormal");
      mvpLoc=GLES20.glGetUniformLocation(program,"uMVP");
      mvLoc=GLES20.glGetUniformLocation(program,"uMV");
    }
    @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int w,int h){GLES20.glViewport(0,0,w,h);aspect=(float)w/Math.max(1,h);}
    @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);if(mesh==null||program==0)return;
      Matrix.perspectiveM(proj,0,45,aspect,.1f,100);Matrix.setIdentityM(mv,0);
      Matrix.translateM(mv,0,0,0,-zoom);Matrix.rotateM(mv,0,ax,1,0,0);Matrix.rotateM(mv,0,ay,0,1,0);Matrix.multiplyMM(mvp,0,proj,0,mv,0);
      GLES20.glUseProgram(program);
      GLES20.glUniformMatrix4fv(mvpLoc,1,false,mvp,0);
      GLES20.glUniformMatrix4fv(mvLoc,1,false,mv,0);
      GLES20.glEnableVertexAttribArray(position);
      mesh.triangles.position(0);
      GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,mesh.triangles);
      GLES20.glEnableVertexAttribArray(normal);
      mesh.normals.position(0);
      GLES20.glVertexAttribPointer(normal,3,GLES20.GL_FLOAT,false,0,mesh.normals);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,mesh.count*3);
      GLES20.glDisableVertexAttribArray(normal);
      GLES20.glDisableVertexAttribArray(position);
    }
  }
}