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
    TextView title=new TextView(this);title.setText("STL VIEWER");title.setTextSize(23);title.setTextColor(0xff82e7d2);title.setPadding(20,22,10,8);root.addView(title);
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
        runOnUiThread(()->details.setText(uri.getLastPathSegment()+" • "+m.count+" triangles"));
      }catch(Exception ex){runOnUiThread(()->details.setText("Error: "+ex.getMessage()));}
    }).start();
  }
  @Override public void onResume(){super.onResume();if(view!=null)view.onResume();}
  @Override public void onPause(){if(view!=null)view.onPause();super.onPause();}

  static class Mesh {
    FloatBuffer triangles;
    int count;
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
      for(int i=0;i<coords.length;i++)coords[i]=(coords[i]-(min[i%3]+max[i%3])*0.5f)*2f/extent;
      Mesh m=new Mesh();m.count=coords.length/9;
      ByteBuffer direct=ByteBuffer.allocateDirect(coords.length*4).order(ByteOrder.nativeOrder());m.triangles=direct.asFloatBuffer();m.triangles.put(coords);m.triangles.position(0);return m;
    }
  }

  static class Viewer implements GLSurfaceView.Renderer {
    Mesh mesh;int program,position,color,mvpLoc;
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
      int vs=shader(GLES20.GL_VERTEX_SHADER,"uniform mat4 uMVP; attribute vec3 aPosition; void main(){gl_Position=uMVP*vec4(aPosition,1.0);}");
      int fs=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}");
      program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
      position=GLES20.glGetAttribLocation(program,"aPosition");color=GLES20.glGetUniformLocation(program,"uColor");mvpLoc=GLES20.glGetUniformLocation(program,"uMVP");
    }
    @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int w,int h){GLES20.glViewport(0,0,w,h);aspect=(float)w/Math.max(1,h);}
    @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);if(mesh==null||program==0)return;
      Matrix.perspectiveM(proj,0,45,aspect,.1f,100);Matrix.setIdentityM(mv,0);
      Matrix.translateM(mv,0,0,0,-zoom);Matrix.rotateM(mv,0,ax,1,0,0);Matrix.rotateM(mv,0,ay,0,1,0);Matrix.multiplyMM(mvp,0,proj,0,mv,0);
      GLES20.glUseProgram(program);GLES20.glUniformMatrix4fv(mvpLoc,1,false,mvp,0);GLES20.glUniform4f(color,.31f,.84f,.78f,1f);
      GLES20.glEnableVertexAttribArray(position);mesh.triangles.position(0);GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,mesh.triangles);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,mesh.count*3);GLES20.glDisableVertexAttribArray(position);
    }
  }
}