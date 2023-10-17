/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest8;

import android.animation.Animator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.common.BaseActivity;

import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.Size;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.utils.ViewAnimationHelper;
import com.serenegiant.widget.CameraViewInterface;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends BaseActivity implements CameraDialog.CameraDialogParent {
    private static final boolean DEBUG = true;    // TODO set false on release
    private static final String TAG = "MainActivity";

    /**
     * set true if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     * by almost same way as USBCameratest2)
     * set false if you want to record movie using MediaVideoEncoder
     */
    private static final boolean USE_SURFACE_ENCODER = false;
    private ToggleButton mCameraButton;

    /**
     * preview resolution(width)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_WIDTH = 640;
    /**
     * preview resolution(height)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private UVCCamera mUVCCamera;

    private static final int PREVIEW_HEIGHT = 480;
    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 1;

    protected static final int SETTINGS_HIDE_DELAY_MS = 2500;

    /**
     * for accessing USB
     */
    private USBMonitor mUSBMonitor;
    /**
     * Handler to execute camera related methods sequentially on private thread
     */
    private UVCCameraHandler mCameraHandler;
    /**
     * for camera preview display
     */
    private CameraViewInterface mUVCCameraView;
    /**
     * for open&start / stop&close camera preview
     */
    private Button mCameraControls;
    /**
     * button for start/stop recording
     */
    private ImageButton mCaptureButton;

    private View mResetButton;
    private Handler mHandler = new Handler();

    private View mToolsLayout;
    private SeekBar mSettingSeekbar;

    private Handler handler = new Handler();
    private TextView mCurrentTimeTextView;
    private long videoStartTime;
    private static final long VIDEO_DURATION = 60000; // 1 minute in milliseconds
    private Runnable mStopRecordRunnable;
    private Runnable mStartRecordRunnable;
    private int mSettingMode = -1;

    private Handler recordingHandler = new Handler();
    private static final long TIME_UPDATE_INTERVAL_MS = 1000; // 1 second

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) Log.v(TAG, "onCreate:");
        setContentView(R.layout.activity_main);

        mCameraButton = (ToggleButton) findViewById(R.id.camera_button);
        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
        mCaptureButton = (ImageButton) findViewById(R.id.capture_button);
        mCaptureButton.setOnClickListener(mOnClickListener);
        mCaptureButton.setVisibility(View.INVISIBLE);
        final View view = findViewById(R.id.camera_view);
        view.setOnLongClickListener(mOnLongClickListener);
        mUVCCameraView = (CameraViewInterface) view;
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float) PREVIEW_HEIGHT);
        mCurrentTimeTextView = findViewById(R.id.realtime_clock);

        mCameraControls = findViewById(R.id.combined_settings_button);
        mCameraControls.setOnClickListener(mOnClickListener);
        // Find the resolution button and set its click listener
        //Button resolutionButton = findViewById(R.id.resolution_button);
        //resolutionButton.setOnClickListener(mOnClickListener);


        mToolsLayout = findViewById(R.id.tools_layout);
        mToolsLayout.setVisibility(View.INVISIBLE);

        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
        mCameraHandler = UVCCameraHandler.createHandler(this, mUVCCameraView,
                USE_SURFACE_ENCODER ? 0 : 1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE);

        updateCurrentTimeText();
        InitializingRunnable();

    }

    private void updateCurrentTimeText() {
        // Get the current time
        long currentTimeMillis = System.currentTimeMillis();
        Date currentDate = new Date(currentTimeMillis);

        // Format the current time as "YYYY MM dd HH mm ss"
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String formattedTime = sdf.format(currentDate);

        // Update the TextView
        mCurrentTimeTextView.setText("Current Time: " + formattedTime);

        if (mCameraHandler.isPreviewing()) {
            updateItems();
            mCaptureButton.setVisibility(View.VISIBLE); // Ensure capture button is always visible


        }

    }

    @Override
    protected void onStart() {
        super.onStart();
        if (DEBUG) Log.v(TAG, "onStart:");
        mUSBMonitor.register();
        if (mUVCCameraView != null)
            mUVCCameraView.onResume();

        mHandler.postDelayed(mUpdateTimeRunnable, TIME_UPDATE_INTERVAL_MS);

    }

    @Override
    protected void onStop() {
        super.onStop();

        //Old version
//
//        if (DEBUG) Log.v(TAG, "onStop:");
//        mCameraHandler.close();
//        setCameraButton(false);
//        if (mUVCCameraView != null)
//            mUVCCameraView.onPause();
//        mHandler.removeCallbacks(mUpdateTimeRunnable);


        if (DEBUG) Log.v(TAG, "onStop:");
        mCameraHandler.close();
        setCameraButton(false);
        if (mUVCCameraView != null)
            mUVCCameraView.onPause();
        if (mCameraHandler != null) {
            mCameraHandler.release();
            mCameraHandler = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mUVCCameraView = null;
        mCameraButton = null;
        mCaptureButton = null;
        mHandler.removeCallbacks(mUpdateTimeRunnable);
        recordingHandler.removeCallbacks(mStartRecordRunnable);
        recordingHandler.removeCallbacks(mStopRecordRunnable);

    }

    private Runnable mUpdateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateCurrentTimeText();
            mHandler.postDelayed(this, TIME_UPDATE_INTERVAL_MS);
        }
    };

    @Override
    public void onDestroy() {
        if (DEBUG) Log.v(TAG, "onDestroy:");
        if (mCameraHandler != null) {
            mCameraHandler.release();
            mCameraHandler = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        recordingHandler.removeCallbacks(mStartRecordRunnable);
        recordingHandler.removeCallbacks(mStopRecordRunnable);
        mUVCCameraView = null;
        mCameraButton = null;
        mCaptureButton = null;
        super.onDestroy();
    }

    private void InitializingRunnable() {
        mStopRecordRunnable = () -> {
            mCaptureButton.setColorFilter(0);    // return to default color
            mCameraHandler.stopRecording();
            recordingHandler.postDelayed(mStartRecordRunnable, 700L);
        };

        mStartRecordRunnable = () -> {
            mCaptureButton.setColorFilter(0xffff0000);    // turn red
            mCameraHandler.startRecording();
            recordingHandler.postDelayed(mStopRecordRunnable, 60_000 - 300L);

        };
    }

    private void showSettingsDialog() {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.combined_settings_button, null);
        dialogBuilder.setView(dialogView);

        // Find SeekBars and Buttons from the dialog view
        SeekBar brightnessSeekBar = dialogView.findViewById(R.id.brightness_seekbar);
        SeekBar contrastSeekBar = dialogView.findViewById(R.id.contrast_seekbar);
        SeekBar hueSeekBar = dialogView.findViewById(R.id.hue_seekbar);
        SeekBar saturationSeekBar = dialogView.findViewById(R.id.saturation_seekbar);
        SeekBar gammaSeekBar = dialogView.findViewById(R.id.gamma_seekbar);
        SeekBar sharpnessSeekBar = dialogView.findViewById(R.id.sharpness_seekbar);
        SeekBar gainSeekBar = dialogView.findViewById(R.id.gain_seekbar);
        SeekBar wbSeekBar = dialogView.findViewById(R.id.tilt_seekbar);
        SeekBar zoomSeekBar = dialogView.findViewById(R.id.zoom_seekbar);


        Button resetButton = dialogView.findViewById(R.id.reset_button);

        SharedPreferences sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
        int savedBrightnessProgress = sharedPreferences.getInt("brightness_progress", 0); // Default to 0 if not found
        int savedContrastProgress = sharedPreferences.getInt("contrast_progress", 0); // Default to 0 if not found
        int savedHueProgress = sharedPreferences.getInt("hue_progress", 0); // Default to 0 if not found
        int savedSaturationProgress = sharedPreferences.getInt("saturation_progress", 0); // Default to 0 if not found
        int savedGammaProgress = sharedPreferences.getInt("gamma_progress", 0); // Default to 0 if not found
        int savedSharpnessProgress = sharedPreferences.getInt("sharpness_progress", 0); // Default to 0 if not found
        int savedGainProgress = sharedPreferences.getInt("gain_progress", 0); // Default to 0 if not found
        int savedWBProgress = sharedPreferences.getInt("white_balance_progress", 0); // Default to 0 if not found
        int savedZoomProgress = sharedPreferences.getInt("zoom_progress", 0); // Default to 0 if not found


        brightnessSeekBar.setProgress(savedBrightnessProgress);
        contrastSeekBar.setProgress(savedContrastProgress);
        hueSeekBar.setProgress(savedHueProgress);
        saturationSeekBar.setProgress(savedSaturationProgress);
        gammaSeekBar.setProgress(savedGammaProgress);
        sharpnessSeekBar.setProgress(savedSharpnessProgress);
        gainSeekBar.setProgress(savedGainProgress);
        wbSeekBar.setProgress(savedWBProgress);
        zoomSeekBar.setProgress(savedZoomProgress);


        // Set SeekBar listeners and button actions
        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle brightness value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_BRIGHTNESS)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("brightness_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_BRIGHTNESS, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

        });

        // Set SeekBar listeners and button actions for contrast
        contrastSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_CONTRAST)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("contrast_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_CONTRAST, progress);
                }


            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

        });
        hueSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_HUE)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("hue_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_HUE, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        saturationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_SATURATION)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("saturation_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_SATURATION, progress);
                }


            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        gammaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_GAMMA)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("gamma_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_GAMMA, progress);
                }


            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sharpnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_SHARPNESS)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("sharpness_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_SHARPNESS, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        gainSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_GAIN)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("gain_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_GAIN, progress);
                }


            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

            // Other methods as needed
        });
        wbSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.PU_WB_TEMP)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("white_balance_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.PU_WB_TEMP, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Handle contrast value change
                if (isActive() && checkSupportFlag(UVCCamera.CTRL_ZOOM_ABS)) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("zoom_progress", progress);
                    editor.apply();

                    // Set the brightness value for the camera
                    setValue(UVCCamera.CTRL_ZOOM_ABS, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

        });
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Handle reset button click
                if (isActive()) {

                    // Update SharedPreferences to reflect the reset values
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putInt("brightness_progress", 0); // Reset brightness value to 0
                    editor.putInt("contrast_progress", 0);   // Reset contrast value to 0
                    editor.putInt("hue_progress", 0);   // Reset contrast value to 0
                    editor.putInt("saturation_progress", 0);   // Reset contrast value to 0
                    editor.putInt("gamma_progress", 0);   // Reset contrast value to 0
                    editor.putInt("sharpness_progress", 0);   // Reset contrast value to 0
                    editor.putInt("gain_progress", 0);   // Reset contrast value to 0
                    editor.putInt("white_balance_progress", 0);   // Reset contrast value to 0
                    editor.putInt("zoom_progress", 0);   // Reset contrast value to 0
                    // Add similar lines for other settings if needed
                    editor.apply();


                    // Set SeekBar progress to 0 for all SeekBars
                    brightnessSeekBar.setProgress(0);
                    contrastSeekBar.setProgress(0);
                    hueSeekBar.setProgress(0);
                    saturationSeekBar.setProgress(0);
                    gammaSeekBar.setProgress(0);
                    sharpnessSeekBar.setProgress(0);
                    gainSeekBar.setProgress(0);
                    wbSeekBar.setProgress(0);
                    zoomSeekBar.setProgress(0);

                    resetValue(UVCCamera.PU_BRIGHTNESS);
                    resetValue(UVCCamera.PU_CONTRAST);
                    resetValue(UVCCamera.PU_HUE);
                    resetValue(UVCCamera.PU_SATURATION);
                    resetValue(UVCCamera.PU_GAMMA);
                    resetValue(UVCCamera.PU_SHARPNESS);
                    resetValue(UVCCamera.PU_GAIN);
                    resetValue(UVCCamera.PU_WB_TEMP);
                    resetValue(UVCCamera.CTRL_ZOOM_ABS);

                }
            }
        });

        AlertDialog settingsDialog = dialogBuilder.create();
        settingsDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        settingsDialog.show();

    }

    private void starting1MinRecording() {
        //Calculate the start time of the current video segment aligned to the nearest minute
        Calendar calendarSec = Calendar.getInstance();

        // Calculate the remaining time to the next minute
        long calendarSecond = calendarSec.get(Calendar.SECOND);
        long calendarMili = calendarSec.get(Calendar.MILLISECOND);
        long delay = (60 - calendarSecond) * 1000;
        System.out.println(calendarMili + "starting milli");

        mCaptureButton.setColorFilter(0xffff0000);    // turn red
        mCameraHandler.startRecording();

        // Start the timer to automatically stop recording after the remaining time
        recordingHandler.postDelayed(mStopRecordRunnable, delay);
    }

    /**
     * event handler when click camera / capture button
     */
    private final OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(final View view) {
            int id = view.getId();
            if (id == R.id.capture_button) {
                if (mCameraHandler.isOpened()) {
                    if (checkPermissionWriteExternalStorage() && checkPermissionAudio()) {
                        if (!mCameraHandler.isRecording()) {
                            starting1MinRecording();
							/*
							handler.postDelayed(new Runnable() {
								@Override
								public void run() {
									mCaptureButton.performClick();
								}
							},30*60*1000);*/
                        } else {
                            mCaptureButton.setColorFilter(0);    // return to default color
                            mCameraHandler.stopRecording();
                            recordingHandler.removeCallbacks(mStopRecordRunnable);
                            recordingHandler.removeCallbacks(mStartRecordRunnable);
                        }
                    }
                }
            } else if (id == R.id.combined_settings_button) {
                if (!mCameraHandler.isRecording()) {
                    showSettingsDialog();
                } else {
                    Toast.makeText(MainActivity.this, "Stop recording to change controls", Toast.LENGTH_SHORT).show();
                }
            }
        }

    };

    private final CompoundButton.OnCheckedChangeListener mOnCheckedChangeListener
            = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(final CompoundButton compoundButton, final boolean isChecked) {
            switch (compoundButton.getId()) {
                case R.id.camera_button:
                    if (isChecked && !mCameraHandler.isOpened()) {
                        CameraDialog.showDialog(MainActivity.this);
                    } else {
                        mCameraHandler.close();
                        setCameraButton(false);
                        invalidateOptionsMenu();

                    }
                    break;
            }
        }
    };

    /**
     * capture still image when you long click on preview image(not on buttons)
     */
    private final OnLongClickListener mOnLongClickListener = new OnLongClickListener() {
        @Override
        public boolean onLongClick(final View view) {
            switch (view.getId()) {
                case R.id.camera_view:
                    if (mCameraHandler.isOpened()) {
                        if (checkPermissionWriteExternalStorage()) {
                            mCameraHandler.captureStill();
                        }
                        return true;
                    }
            }
            return false;
        }
    };

    private void setCameraButton(final boolean isOn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mCameraButton != null) {
                    try {
                        mCameraButton.setOnCheckedChangeListener(null);
                        //mCameraButton.setChecked(isOn);
                    } finally {
                        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
                    }
                }
//                if (!isOn && (mCaptureButton != null)) {
//                    mCaptureButton.setVisibility(View.INVISIBLE);
//                }
            }
        }, 0);
        updateItems();
    }

    public void startPreview() {
        final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
        mCameraHandler.startPreview(new Surface(st));
        System.out.println(mCameraHandler.getWidth());
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCaptureButton.setVisibility(View.VISIBLE);
            }
        });
        updateItems();
    }

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            if (DEBUG) Log.v(TAG, "onConnect:");
            mCameraHandler.open(ctrlBlock);
            //startPreview();
            updateItems();
            mCameraHandler.isPreviewing();

        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:");
            if (mCameraHandler != null) {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mCameraHandler.close();
                        invalidateOptionsMenu();


                    }
                }, 0);
                mCaptureButton.setVisibility(View.INVISIBLE); // Ensure capture button is always visible
                mCaptureButton.setColorFilter(0);    // return to default color
                mCameraHandler.stopRecording();
                setCameraButton(false);
                updateItems();
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            setCameraButton(false);
        }
    };

    /**
     * to access from CameraDialog
     *
     * @return
     */
    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {
        if (DEBUG) Log.v(TAG, "onDialogResult:canceled=" + canceled);
        if (canceled) {
            setCameraButton(false);
        }
    }

    //================================================================================
    private boolean isActive() {
        return mCameraHandler != null && mCameraHandler.isOpened();
    }

    private boolean checkSupportFlag(final int flag) {
        return mCameraHandler != null && mCameraHandler.checkSupportFlag(flag);
    }

    private int getValue(final int flag) {
        return mCameraHandler != null ? mCameraHandler.getValue(flag) : 0;
    }

    private int setValue(final int flag, final int value) {
        return mCameraHandler != null ? mCameraHandler.setValue(flag, value) : 0;
    }

    private int resetValue(final int flag) {
        return mCameraHandler != null ? mCameraHandler.resetValue(flag) : 0;
    }

    private void updateItems() {
        runOnUiThread(mUpdateItemsOnUITask, 100);
    }

    private final Runnable mUpdateItemsOnUITask = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            final int visible_active = isActive() ? View.VISIBLE : View.INVISIBLE;
            mToolsLayout.setVisibility(visible_active);
            mCameraButton.setVisibility(View.VISIBLE); // Ensure camera button is always visible
            //mCaptureButton.setVisibility(View.VISIBLE); // Ensure capture button is always visible
        }
    };
}
