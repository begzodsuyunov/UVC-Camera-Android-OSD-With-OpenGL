package com.serenegiant.encoder;
/*
 * libcommon
 * utility/helper classes for myself
 *
 * Copyright (c) 2014-2018 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.serenegiant.gles.FrameRect;
import com.serenegiant.gles.FrameRectSProgram;
import com.serenegiant.gles.TextureHelper;
import com.serenegiant.gles.WaterSignHelper;
import com.serenegiant.gles.WaterSignSProgram;
import com.serenegiant.gles.WaterSignature;
import com.serenegiant.glutils.EGLBase;
import com.serenegiant.glutils.GLDrawer2D;

import java.text.SimpleDateFormat;
import java.util.Date;

import static com.serenegiant.widget.UVCCameraTextureView.mSignTexId;

/**
 * Draw shared texture on specific whole Surface using OpenGL|ES
 * this will deprecate soon because I don't use this now
 */
@Deprecated
public final class MyRenderHandler extends Handler {
	private static final boolean DEBUG = true;	// FIXME set false on release
	private static final String TAG = "RenderHandler";
	private static final int TEXT_X = 20;

    private static final int MSG_RENDER_SET_GLCONTEXT = 1;
    private static final int MSG_RENDER_DRAW = 2;
    private static final int MSG_CHECK_VALID = 3;
    private static final int MSG_RENDER_QUIT = 9;

	private int mTexId = -1;
	private final RenderThread mThread;
	private static int mWidth;
	private static int mHeight;
	private static String cameraName;
	private static boolean useNew = true;



	public static MyRenderHandler createHandler() {
//		if (DEBUG) Log.v(TAG, "createHandler:");
		return createHandler("RenderThread");
	}

	public static final MyRenderHandler createHandler(final String name) {
//		if (DEBUG) Log.v(TAG, "createHandler:name=" + name);
		final RenderThread thread = new RenderThread(name);
		thread.start();
		return thread.getHandler();
	}

	public static final MyRenderHandler createHandler(final String name,int width,int height, String camName) {
//		if (DEBUG) Log.v(TAG, "createHandler:name=" + name);
		mWidth = width;
		mHeight = height;
		cameraName = camName;
		final RenderThread thread = new RenderThread(name);
		thread.start();
		return thread.getHandler();
	}

	public final void setEglContext(final EGLBase.IContext sharedContext,
		final int tex_id, final Object surface, final boolean isRecordable) {
		if (DEBUG) Log.d(TAG, "RenderHandler:setEglContext:");
		if (!(surface instanceof Surface)
			&& !(surface instanceof SurfaceTexture)
			&& !(surface instanceof SurfaceHolder))
			throw new RuntimeException("unsupported window type:" + surface);
		mTexId = tex_id;
		sendMessage(obtainMessage(MSG_RENDER_SET_GLCONTEXT,
			isRecordable ? 1 : 0, 0, new ContextParams(sharedContext, surface)));
	}

	public final void draw() {
		sendMessage(obtainMessage(MSG_RENDER_DRAW, mTexId, 0, null));
	}

	public final void draw(final int tex_id) {
		sendMessage(obtainMessage(MSG_RENDER_DRAW, tex_id, 0, null));
	}

	public final void draw(final float[] tex_matrix) {
		sendMessage(obtainMessage(MSG_RENDER_DRAW, mTexId, 0, tex_matrix));
	}

	public final void draw(final int tex_id, final float[] tex_matrix) {
		sendMessage(obtainMessage(MSG_RENDER_DRAW, tex_id, 0, tex_matrix));
	}

	public boolean isValid() {
		synchronized (mThread.mSync) {
			sendEmptyMessage(MSG_CHECK_VALID);
			try {
				mThread.mSync.wait();
			} catch (final InterruptedException e) {
			}
			return mThread.mSurface != null && mThread.mSurface.isValid();
		}
	}

	public final void release() {
		if (DEBUG) Log.d(TAG, "release:");
		removeMessages(MSG_RENDER_SET_GLCONTEXT);
		removeMessages(MSG_RENDER_DRAW);
		sendEmptyMessage(MSG_RENDER_QUIT);
	}

	@Override
	public final void handleMessage(final Message msg) {
		switch (msg.what) {
		case MSG_RENDER_SET_GLCONTEXT:
			final ContextParams params = (ContextParams)msg.obj;
			mThread.handleSetEglContext(params.sharedContext, params.surface, msg.arg1 != 0);
			break;
		case MSG_RENDER_DRAW:
			mThread.handleDraw(msg.arg1, (float[])msg.obj);
			break;
		case MSG_CHECK_VALID:
			synchronized (mThread.mSync) {
				mThread.mSync.notify();
			}
			break;
		case MSG_RENDER_QUIT:
			Looper.myLooper().quit();
			break;
		default:
			super.handleMessage(msg);
		}
	}

//********************************************************************************
//********************************************************************************
	private MyRenderHandler(final RenderThread thread) {
//		if (DEBUG) Log.i(TAG, "RenderHandler:");
		mThread = thread;
	}

	private static final class ContextParams {
    	final EGLBase.IContext sharedContext;
    	final Object surface;
    	public ContextParams(final EGLBase.IContext sharedContext, final Object surface) {
    		this.sharedContext = sharedContext;
    		this.surface = surface;
    	}
    }

	/**
	 * Thread to execute render methods
	 * You can also use HandlerThread insted of this and create Handler from its Looper.
	 */
    private static final class RenderThread extends Thread {
		private static final String TAG_THREAD = "RenderThread";
    	private final Object mSync = new Object();
    	private MyRenderHandler mHandler;
    	private EGLBase mEgl;
    	private EGLBase.IEglSurface mTargetSurface;
    	private Surface mSurface;
    	private GLDrawer2D mDrawer;

		private FrameRect mFrameRect;
		private WaterSignature mWaterSign;
		private WaterSignHelper waterSignHelper;
		private int waterTexture = -1;

    	public RenderThread(final String name) {
    		super(name);
    	}

    	public final MyRenderHandler getHandler() {
            synchronized (mSync) {
                // create rendering thread
            	try {
            		mSync.wait();
            	} catch (final InterruptedException e) {
                }
            }
            return mHandler;
    	}

    	/**
    	 * Set shared context and Surface
    	 * @param shardContext
    	 * @param surface
    	 */
    	public final void handleSetEglContext(final EGLBase.IContext shardContext,
    		final Object surface, final boolean isRecordable) {
    		if (DEBUG) Log.d(TAG_THREAD, "setEglContext:");
    		release();
    		synchronized (mSync) {
    			mSurface = surface instanceof Surface ? (Surface)surface
    				: (surface instanceof SurfaceTexture
    					? new Surface((SurfaceTexture)surface) : null);
    		}
    		mEgl = EGLBase.createFrom(3, shardContext, false, 0, isRecordable);
    		try {
	   			mTargetSurface = mEgl.createFromSurface(surface);
	    		mDrawer = new GLDrawer2D(isRecordable);
				if(useNew) {
					mFrameRect = new FrameRect();
					mWaterSign = new WaterSignature();
					mFrameRect.setShaderProgram(new FrameRectSProgram());
					mWaterSign.setShaderProgram(new WaterSignSProgram());
					waterSignHelper = new WaterSignHelper();
					waterTexture = waterSignHelper.getTexture();
				}
    		} catch (final Exception e) {
				Log.w(TAG, e);
    			if (mTargetSurface != null) {
    				mTargetSurface.release();
    				mTargetSurface = null;
    			}
    			if (mDrawer != null) {
    				mDrawer.release();
    				mDrawer = null;
    			}
    		}
    	}

    	/**
    	 * drawing
    	 * @param tex_id
    	 * @param tex_matrix
    	 */
    	public void handleDraw(final int tex_id, final float[] tex_matrix) {
//    		if (DEBUG) Log.i(TAG_THREAD, "draw");
    		if (tex_id >= 0 && mTargetSurface != null) {
	    		mTargetSurface.makeCurrent();
	    		if(useNew) {
					GLES30.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES30.GL_COLOR_BUFFER_BIT);
					GLES30.glEnable(GLES30.GL_BLEND); //打开混合功能
					GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA); //指定混合模式
					GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
					mFrameRect.drawFrame(tex_id, tex_matrix); // 画图

					Bitmap bitmap = getBitmap();
					int text_y = mHeight - (bitmap.getHeight() / 2) - TEXT_X;
					GLES30.glViewport(TEXT_X, text_y, bitmap.getWidth(), bitmap.getHeight()); // x, y, width, height. 设置绘制的视口位置/大小
					//GLES30.glViewport(0, 0, 180, 180); // x, y, width, height. 设置绘制的视口位置/大小
					//int waterId = TextureHelper.loadBitmapTexture(bitmap);
					waterSignHelper.loadBitmap(bitmap);
					mWaterSign.drawFrame(waterTexture);
					bitmap.recycle();
				} else {
	    			mDrawer.draw(tex_id, tex_matrix, 0);
				}
	    		mTargetSurface.swap();
    		}
    	}

		public Bitmap getBitmap() {
			String infoTime = new SimpleDateFormat("YYYY:MM:dd HH:mm:ss").format(new Date(System.currentTimeMillis()));
			String infoCamera = cameraName;
			String info	= infoTime + " " + infoCamera;
			float FontSize = 20;
			System.out.println();
			Paint mTextPaint = new Paint();
			mTextPaint.setTextSize(FontSize);
			mTextPaint.setColor(Color.GREEN);
			mTextPaint.setFakeBoldText(true);
			mTextPaint.setAntiAlias(true);
			mTextPaint.setSubpixelText(true);
			mTextPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
			/*
			mTextPaint.setShadowLayer(2, 2, 2, Color.BLACK);*/
			int bitmapWidth = (int) (mTextPaint.measureText(info) + 2.0f);
			int bitmapHeight = (int) FontSize + 2;
			Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap);
			//canvas.drawColor(Color.RED);
			//float baseX = canvas.getWidth() / 2 - mTextPaint.measureText(info) / 2;
			float baseY = (canvas.getHeight() / 2) - ((mTextPaint.descent() + mTextPaint.ascent()) / 2);
			//canvas.drawText(info,1, 1.0f + FontSize * 0.75f,mTextPaint);
			canvas.drawText(info,0, baseY,mTextPaint);
			//img.draw(canvas);
			//canvas.save();
			return bitmap;
		}

    	@Override
    	public final void run() {
//			if (DEBUG) Log.v(TAG_THREAD, "started");
            Looper.prepare();
            synchronized (mSync) {
                mHandler = new MyRenderHandler(this);
                mSync.notify();
            }
            Looper.loop();
//			if (DEBUG) Log.v(TAG_THREAD, "finishing");
            release();
            synchronized (mSync) {
                mHandler = null;
            }
//			if (DEBUG) Log.v(TAG_THREAD, "finished");
    	}

    	private final void release() {
//    		if (DEBUG) Log.v(TAG_THREAD, "release:");
    		if (mDrawer != null) {
    			mDrawer.release();
    			mDrawer = null;
    		}
    		synchronized (mSync) {
    			mSurface = null;
    		}
    		if (mTargetSurface != null) {
    			clear();
    			mTargetSurface.release();
    			mTargetSurface = null;
    		}
    		if (mEgl != null) {
    			mEgl.release();
    			mEgl = null;
    		}
    	}

    	/**
    	 * Fill black on specific Surface
    	 */
    	private final void clear() {
//    		if (DEBUG) Log.v(TAG_THREAD, "clear:");
    		mTargetSurface.makeCurrent();
			GLES20.glClearColor(0, 0, 0, 1);
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
			mTargetSurface.swap();
    	}
    }

}
