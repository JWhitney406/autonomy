package com.example.angustomlinson.tangoautonomy20;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.google.atap.tango.ux.TangoUx;
import com.google.atap.tango.ux.TangoUx.StartParams;
import com.google.atap.tango.ux.TangoUxLayout;
import com.google.atap.tango.ux.UxExceptionEvent;
import com.google.atap.tango.ux.UxExceptionEventListener;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.DoubleBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Enumeration;

public class AutonomyActivity extends Activity {

    // TANGO VARIABLES:
    static final String TAG = AutonomyActivity.class.getSimpleName();
    private static final String NETWORK_TAG = AutonomyActivity.class.getSimpleName() + " (Network)";
    private boolean mIsTangoServiceConnected;
    private Tango mTango;
    private TangoConfig mConfig;
    TangoPoseData mPose;
    private TangoXyzIjData mXyzIj;
    private TangoUx mTangoUx;

    // POSE UPDATE VARIABLES:
    private float mLastPoseTimeStamp = 0.0f;
    private float mCurrentPoseTimeStamp;
    private float mPoseDelta;
    private final float POSE_UPDATE_TIME = 0.1f; // in seconds

    // XYZIJ UPDATE VARIABLES:
    private float mLastXyzIjTimeStamp = 0.0f;
    private float mCurrentXyzIjTimeStamp;
    private float mXyzIjDelta;
    private final float XYZIJ_UPDATE_TIME = 0.4f; // in seconds

    // UI THREAD VARIABLES:
    private static final int UPDATE_INTERVAL_MS = 100;
    private final Object mUiPoseLock = new Object();
    private final Object mUiDepthLock = new Object();
    final Object arduinoLock = new Object();
    final Object bufferLock = new Object();
    DecimalFormat decimalFormat = new DecimalFormat("#.###");

    // COLOR CAMERA INTRINSICS AND POSE VARIABLES:
    public TangoCameraIntrinsics colorCameraIntrinsics;
    public TangoPoseData colorCameraTXyzIj;

    // DEPTH AND PLANE VARIABLES:
    public final int DEPTH_POINT_COUNT = 1024;

    public DoubleBuffer backDepthBuffer = DoubleBuffer.allocate(DEPTH_POINT_COUNT * 3);
    public DoubleBuffer frontDepthBuffer;

    public DoubleBuffer backPlaneBuffer = DoubleBuffer.allocate(DEPTH_POINT_COUNT * 4);
    public DoubleBuffer frontPlaneBuffer;

    AutonomyLogic autonomyLogic = new AutonomyLogic(this);

    // TextViews and Buttons:
    TextView digView;
    TextView adjustedAngleView;
    TextView arduinoFoundView;
    TextView receivedDataView;
    TextView poseTextView;
    TextView rotationTextView;
    TextView yawView;
    TextView angleView;
    TextView destinationTextView;
    TextView incomingCommandsView;
    TextView initialPositionView;
    TextView depthTextView;
    TextView networkStatusView;
    TextView textIPView;

    EditText angleOffsetField;

    Button connectButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        digView = (TextView) findViewById(R.id.dig_display);
        adjustedAngleView = (TextView) findViewById(R.id.adjustedAngleView);
        arduinoFoundView = ((TextView) findViewById(R.id.arduino_found));
        receivedDataView = (TextView) findViewById(R.id.recieved_data);
        poseTextView = (TextView) findViewById(R.id.poseView);
        rotationTextView = (TextView) findViewById(R.id.rotationView);
        yawView = (TextView) findViewById(R.id.yawView);
        angleView = (TextView) findViewById(R.id.angleView);
        destinationTextView = (TextView) findViewById(R.id.destinationView);
        incomingCommandsView = ((TextView) findViewById(R.id.incoming_commands));
        initialPositionView = (TextView) findViewById(R.id.initialPositionView);
        depthTextView = (TextView) findViewById(R.id.depthView);
        networkStatusView = (TextView) findViewById(R.id.networkStatusDisplay);
        textIPView = (TextView) findViewById(R.id.ipDisplay);

        angleOffsetField = (EditText) findViewById(R.id.angleOffset);

        connectButton = (Button) findViewById(R.id.networkConnectButton);

        mTango = new Tango(this);
        mConfig = setupTangoConfig(mTango);

        mTangoUx = setupTangoUxAndLayout();
        mIsTangoServiceConnected = false;

        colorCameraIntrinsics = mTango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);

        //Initialize Connection to Raspberry Pi


        autonomyLogic.initializeDrivePath();

        startUIThread();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTangoUx.stop();
        try {
            mTango.disconnect();
            mIsTangoServiceConnected = false;
        } catch (TangoErrorException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StartParams params = new StartParams();
        mTangoUx.start(params);
        if (!mIsTangoServiceConnected) {
            startActivityForResult(
                    Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),
                    Tango.TANGO_INTENT_ACTIVITYCODE);
        }
        Log.i(TAG, "onResumed");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) throws TangoErrorException {
        // Check which request we're responding to
        if (requestCode == Tango.TANGO_INTENT_ACTIVITYCODE) {
            Log.i(TAG, "Triggered");
            // Make sure the request was successful
            if (resultCode == RESULT_CANCELED) {
                finish();
                return;
            }
            try {
                setTangoListeners();
            } catch (SecurityException ignored) {
            }
            try {
                mTango.connect(mConfig);
                mIsTangoServiceConnected = true;
            } catch (TangoOutOfDateException outDateEx) {
                if (mTangoUx != null) {
                    mTangoUx.showTangoOutOfDate();
                }
            } catch (TangoErrorException ignored) {

            }
            setupExtrinsics();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }

    private void setTangoListeners() {
        // Configure the Tango coordinate frame pair
        final ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        // Listen for new Tango data
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {

            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // Passing in the pose data to UX library produce exceptions.
                if (mTangoUx != null) {
                    mTangoUx.updatePoseStatus(pose.statusCode);
                }

                // Make sure to have atomic access to Tango Pose Data so that
                // render loop doesn't interfere while Pose call back is updating
                // the data.
                synchronized (mUiPoseLock) {
                    mPose = pose;

                    mCurrentPoseTimeStamp = (float) pose.timestamp;
                    mPoseDelta = mCurrentPoseTimeStamp - mLastPoseTimeStamp;
                }
            }

            @Override
            public void onXyzIjAvailable(final TangoXyzIjData xyzIj) {
                if (mTangoUx != null) {
                    mTangoUx.updateXyzCount(xyzIj.xyzCount);
                }

                // Make sure to have atomic access to TangoXyzIjData so that
                // UI loop doesn't interfere while onXYZijAvailable callback is updating
                // the mPoint cloud data.
                synchronized (mUiDepthLock) {
                    mXyzIj = xyzIj;

                    mCurrentXyzIjTimeStamp = (float) xyzIj.timestamp;
                    mXyzIjDelta = mCurrentXyzIjTimeStamp - mLastXyzIjTimeStamp;

                    TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
                    framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
                    framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
                    colorCameraTXyzIj = mTango.getPoseAtTime(mCurrentXyzIjTimeStamp, framePair);
                }
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                if (mTangoUx != null) {
                    mTangoUx.updateTangoEvent(event);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                    }
                });
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }

    /**
     * Create a separate thread to update Log information on UI at the specified interval of
     * UPDATE_INTERVAL_MS. This function also makes sure to have access to the mPose atomically.
     */
    private void startUIThread() {

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(UPDATE_INTERVAL_MS);
                        // Update the UI with TangoPose information
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mUiPoseLock) {
                                    if (mPose == null) {
                                        return;
                                    }
                                    initialPositionView.setText("Initial Position: " + autonomyLogic.initializationPosition);

                                    final CheckBox checkBox = (CheckBox) findViewById(R.id.autonomy_checkbox);
                                    checkBox.setOnClickListener(new OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            synchronized (bufferLock) {
                                                //arduinoConnection.resetBuffer();
                                                //if (arduinoConnection.arduinoConnected())
                                                //    arduinoConnection.sendCommands();
                                            }
                                        }
                                    });

                                    autonomyLogic.autonomyActive = checkBox.isChecked();

                                    updateUI();
                                    extractYaw();
                                    autonomyLogic.findAdjustedAngle();

                                    if (autonomyLogic.autonomyActive) {
                                        initialPositionView.setText("Initial Position: " + autonomyLogic.initializationPosition);//Is this necessary?
                                        autonomyLogic.performNextAutonomyAction();
                                    }
                                }
                                synchronized (mUiDepthLock) {
                                    if (mXyzIj == null) {
                                        return;
                                    }

                                    if (frontDepthBuffer != null) {
                                        String depthString;
                                        depthString = "Depth at center of screen: {" + decimalFormat.format(frontDepthBuffer.get(1538)) + "}";
                                        depthTextView.setText(depthString);
                                    }
                                }
                            }
                        });
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    private void updateUI() {
        // displays the Tango's translation
        String poseString = "Tango Pose: {" + decimalFormat.format(mPose.translation[0]) + ", " +
                decimalFormat.format(mPose.translation[1]) + ", " +
                decimalFormat.format(mPose.translation[2]) + "}";
        poseTextView.setText(poseString);

        // displays the Tango's rotation
        String rotationString = "Tango Rotation: {" + decimalFormat.format(mPose.rotation[0]) + ", " +
                decimalFormat.format(mPose.rotation[1]) + ", " +
                decimalFormat.format(mPose.rotation[2]) + ", " +
                decimalFormat.format(mPose.rotation[3]) + "}";
        rotationTextView.setText(rotationString);

        // displays the Tango's yaw
        String yawString = "Tango yaw: " + decimalFormat.format(autonomyLogic.yaw) + " degrees";
        yawView.setText(yawString);

        // displays angle between the Tango and destination
        String angleString = "Angle between Tango and destination: " + decimalFormat.format(autonomyLogic.angle) +
                " degrees";

        // display the angle
        angleView.setText(angleString);

        // displays the destination position
        String destinationString = "Destination: {" + decimalFormat.format(autonomyLogic.destinationTranslation[0]) + ", " +
                decimalFormat.format(autonomyLogic.destinationTranslation[1]) + ", " +
                decimalFormat.format(autonomyLogic.destinationTranslation[2]) + "}";
        destinationTextView.setText(destinationString);
    }

    private void setupExtrinsics() {
        TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
        colorCameraTXyzIj = mTango.getPoseAtTime(0.0, framePair);
    }

    /*
   * This is an advanced way of using UX exceptions. In most cases developers can just use the in
   * built exception notifications using the Ux Exception layout. In case a developer doesn't want
   * to use the default Ux Exception notifications, he can set the UxException listener as shown
   * below.
   * In this example we are just logging all the ux exceptions to logcat, but in a real app,
   * developers should use these exceptions to contextually notify the user and help direct the
   * user in using the device in a way Tango service expects it.
   */
    private UxExceptionEventListener mUxExceptionListener = new UxExceptionEventListener() {

        @Override
        public void onUxExceptionEvent(UxExceptionEvent uxExceptionEvent) {
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_LYING_ON_SURFACE) {
                Log.i(TAG, "Device lying on surface ");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_FEW_DEPTH_POINTS) {
                Log.i(TAG, "Very few depth points in mPoint cloud ");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_FEW_FEATURES) {
                Log.i(TAG, "Invalid poses in MotionTracking ");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_INCOMPATIBLE_VM) {
                Log.i(TAG, "Device not running on ART");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_MOTION_TRACK_INVALID) {
                Log.i(TAG, "Invalid poses in MotionTracking ");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_MOVING_TOO_FAST) {
                Log.i(TAG, "Invalid poses in MotionTracking ");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_OVER_EXPOSED) {
                Log.i(TAG, "Camera Over Exposed");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_TANGO_SERVICE_NOT_RESPONDING) {
                Log.i(TAG, "TangoService is not responding ");
            }
            if (uxExceptionEvent.getType() == UxExceptionEvent.TYPE_UNDER_EXPOSED) {
                Log.i(TAG, "Camera Under Exposed ");
            }

        }
    };

    /**
     * Sets up the tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_CURRENT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        return config;
    }

    /**
     * Sets up TangoUX layout and sets its listener.
     */
    private TangoUx setupTangoUxAndLayout() {
        TangoUxLayout uxLayout = (TangoUxLayout) findViewById(R.id.layout_tango);
        TangoUx tangoUx = new TangoUx(this);
        tangoUx.setLayout(uxLayout);
        tangoUx.setUxExceptionEventListener(mUxExceptionListener);
        return tangoUx;
    }

    private void extractYaw() {
        double yaw = autonomyLogic.yaw;
        yaw = Math.toDegrees(Math.atan2((2 * ((mPose.rotation[0] * mPose.rotation[1]) + (mPose.rotation[2] * mPose.rotation[3]))),
                (Math.pow(mPose.rotation[3], 2) + Math.pow(mPose.rotation[0], 2) -
                        Math.pow(mPose.rotation[1], 2) - Math.pow(mPose.rotation[2], 2))));

        if (yaw < 0) {
            yaw += 360;
        }

        double yawOffset = 90.0;

        yaw += yawOffset;

        autonomyLogic.yaw = yaw % 360;
    }


    private static class NetworkHandler extends Handler {
        WeakReference<AutonomyActivity> activityReference;

        // Here lies:
        // Looper looper
        // Super looper
        // RIP
        private NetworkHandler(WeakReference<AutonomyActivity> activityReference) {
            super(activityReference.get().getMainLooper());
            this.activityReference = activityReference;
        }

        @Override
        public void handleMessage(final Message networkMessage) {
            final AutonomyActivity activity = activityReference.get();

            activity.networkStatusView.post(new Runnable() {
                @Override
                public void run() {
                    activity.networkStatusView.setText("Connecting");
                }
            });

            Log.v(NETWORK_TAG, "HandleMessage");
            // Append String From Incoming Message Object
            Bundle displayMessage = (Bundle) networkMessage.obj;

            Log.v(NETWORK_TAG, "About to read message");
            byte[] remoteMotorBuffer = displayMessage.getByteArray("BUFFER");
            activity.autonomyLogic.autonomyActive = displayMessage.getBoolean("AUTONOMY_ACTIVE");
            Log.v(NETWORK_TAG, "read message");

            if (remoteMotorBuffer == null) Log.v(NETWORK_TAG, "null");
            else
                Log.v(NETWORK_TAG, "remoteBuffer: " + remoteMotorBuffer[0] + " " + remoteMotorBuffer[1] + " " + remoteMotorBuffer[3]);

            //TODO: Put these commands somewhere else.
            if (!activity.autonomyLogic.autonomyActive) {
                assert remoteMotorBuffer != null;
                synchronized (activity.bufferLock) {
                    /*activity.arduinoConnection.setLeftForward(remoteMotorBuffer[ArduinoConnection.Motors.LEFT.ordinal()]);
                    activity.arduinoConnection.setRightForward(remoteMotorBuffer[ArduinoConnection.Motors.RIGHT.ordinal()]);
                    activity.arduinoConnection.setDump(remoteMotorBuffer[ArduinoConnection.Motors.DUMP.ordinal()]);
                    activity.arduinoConnection.setActuate(remoteMotorBuffer[ArduinoConnection.Motors.DIG_LIFT.ordinal()]);
                    activity.arduinoConnection.setDig(remoteMotorBuffer[ArduinoConnection.Motors.DIG.ordinal()]);*/
                }
            }
        }
    }
}
