# USB摄像头录像增加动态时间水印记录
修改基于开源项目[saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera)中的usbCameraTest8

使用方法  
运行后，点击左下角按钮，授权后点击中间那个透明小相机按钮开始录像，再点一次透明小相机停止录像，录像文件保存在/sdcard/Movies/USBCameraTest/目录下
## 隐藏预览
1.修改```usbCameraTest8/src/main/res/layout/activity_main.xml```
```
<com.serenegiant.widget.UVCCameraTextureView
  android:id="@+id/camera_view"
  android:layout_width="1dp"
  android:layout_height="1dp"
  android:layout_centerInParent="true" />
```
2.为了确保隐藏在设置个透明,修改```usbCameraCommon/src/main/java/com/serenegiant/widget/UVCCameraTextureView.java```
```
public UVCCameraTextureView(final Context context, final AttributeSet attrs) {
  this(context, attrs, 0);
  if(!isPreview)
    this.setAlpha(0);
}

public UVCCameraTextureView(final Context context, final AttributeSet attrs, final int defStyle) {
  super(context, attrs, defStyle);
  setSurfaceTextureListener(this);
  if(!isPreview)
    this.setAlpha(0);
}
```
修改onDrawFrame不进行预览
```
public final void onDrawFrame() {
				//if(DEBUG)Log.d(TAG,"onDrawFrame");
				mEglSurface.makeCurrent();
				if(useNew) {
					GLES30.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES30.GL_COLOR_BUFFER_BIT);
					GLES30.glEnable(GLES20.GL_BLEND); //打开混合功能
					GLES30.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); //指定混合模式
					GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
				}
				// update texture(came from camera)
				mPreviewSurface.updateTexImage();
				// get texture matrix
				mPreviewSurface.getTransformMatrix(mStMatrix);
				if(useNew)
					GLES30.glViewport(0, 0, mViewWidth, mViewHeight); //设置视口为整个surface大小
				// notify video encoder if it exist
				if (mEncoder != null) {
					// notify to capturing thread that the camera frame is available.
					if (mEncoder instanceof MediaVideoEncoder)
						((MediaVideoEncoder)mEncoder).frameAvailableSoon(mStMatrix);
					else
						mEncoder.frameAvailableSoon();
				}
				if(isPreview) {
					if (useNew) {
						mFrameRect.drawFrame(mTexId, mStMatrix); // 画图
						//mFrameRect.drawFrame(mTexId, mStMatrix,mSignTexId); // 画图
						//GLES30.glViewport(0, 0, 240, 363); // x, y, width, height. 设置绘制的视口位置/大小
						//Bitmap bitmap = BitmapFactory.decodeFile("/sdcard/123.jpg");
						//int waterId = TextureHelper.loadBitmapTexture(bitmap);
						//mWaterSign.drawFrame(waterId);
						//bitmap.recycle();
					} else {
						// draw to preview screen
						mDrawer.draw(mTexId, mStMatrix, 0);
						//mDrawer.draw(waterId, mStMatrix, 0);
						//mDrawer.draw(mSignTexId, mStMatrix, 0);
					}
				}
				mEglSurface.swap();
```

## 录像增加动态时间水印
新增加的文件```usbCameraCommon/src/main/java/com/serenegiant/encoder/MyRenderHandler.java```，这个文件和RenderHandler是一样的，只是增加了动态时间水印部分
主要修改handleDraw函数
```
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
```
刚开始使用的```int waterId = TextureHelper.loadBitmapTexture(bitmap);```这个会使内存占用量不断升高，录不了几分钟就得崩溃，后面新增加了waterSignHelper，发现显示能是正常的.

录像视频旋转修改```usbCameraCommon/src/main/java/com/serenegiant/gles/FrameRect.java```中FULL_RECTANGLE_COORDS变量，已经定义好了四个旋转方向
```
    private static final float FULL_RECTANGLE_COORDS_0[] = {
            -1.0f, -1.0f,   // 0 bottom left
            1.0f, -1.0f,   // 1 bottom right
            -1.0f,  1.0f,   // 2 top left
            1.0f,  1.0f,   // 3 top right
    };

    private static final float FULL_RECTANGLE_COORDS_1[] = {
            -1.0f, 1.0f,   // 0 bottom left
            -1.0f, -1.0f,   // 1 bottom right
            1.0f,  1.0f,   // 2 top left
            1.0f,  -1.0f,   // 3 top right
    };

    private static final float FULL_RECTANGLE_COORDS_2[] = {
            1.0f, 1.0f,   // 0 bottom left
            -1.0f, 1.0f,   // 1 bottom right
            1.0f,  -1.0f,   // 2 top left
            -1.0f,  -1.0f,   // 3 top right
    };

    private static final float FULL_RECTANGLE_COORDS_3[] = {
            1.0f, -1.0f,   // 0 bottom left
            1.0f, 1.0f,   // 1 bottom right
            -1.0f,  -1.0f,   // 2 top left
            -1.0f,  1.0f,   // 3 top right
    };

private static final float[] FULL_RECTANGLE_COORDS = FULL_RECTANGLE_COORDS_0;
```
参考了最下面那个链接，基本是没看懂，抱着试一试的态度最后改好了，完全没理解.哈哈.
![](https://github.com/hcly/UVCCamera/blob/master/video.png)

## 参考
[OpenGL.ES在Android上的简单实践](https://blog.csdn.net/a360940265a/category_7388966.html)
