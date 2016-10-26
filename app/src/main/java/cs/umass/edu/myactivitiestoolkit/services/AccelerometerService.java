package cs.umass.edu.myactivitiestoolkit.services;

import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import org.apache.commons.io.IOUtils;

import cs.umass.edu.myactivitiestoolkit.R;
import cs.umass.edu.myactivitiestoolkit.constants.Constants;
import cs.umass.edu.myactivitiestoolkit.processing.Filter;
import cs.umass.edu.myactivitiestoolkit.steps.OnStepListener;
import cs.umass.edu.myactivitiestoolkit.steps.StepDetector;
import cs.umass.edu.myactivitiestoolkit.storage.FileUtil;
import edu.umass.cs.MHLClient.client.MessageReceiver;
import edu.umass.cs.MHLClient.client.MobileIOClient;
import edu.umass.cs.MHLClient.sensors.AccelerometerReading;
import edu.umass.cs.MHLClient.sensors.SensorReading;

/**
 * This service is responsible for collecting the accelerometer data on
 * the phone. It is an ongoing foreground service that will run even when your
 * application is not running. Note, however, that a process of your application
 * will still be running! The sensor service will receive sensor events in the
 * {@link #onSensorChanged(SensorEvent)} method defined in the {@link SensorEventListener}
 * interface.
 * <br><br>
 * <b>ASSIGNMENT 0 (Data Collection & Visualization)</b> :
 *      In this assignment, you will display and visualize the accelerometer readings
 *      and send the data to the server. In {@link #onSensorChanged(SensorEvent)},
 *      you should send the data to the main UI using the method
 *      {@link #broadcastAccelerometerReading(long, float[])}. You should also
 *      use the {@link #mClient} object to send data to the server. You can
 *      confirm it works by checking that both the local and server-side plots
 *      are updating (make sure your html script is running on your machine!).
 * <br><br>
 *
 * <b>ASSIGNMENT 1 (Step Detection)</b> :
 *      In this assignment, you will detect steps using the accelerometer sensor. You
 *      will design both a local step detection algorithm and a server-side (Python)
 *      step detection algorithm. Your algorithm should look for peaks and account for
 *      the fact that humans generally take steps every 0.5 - 2.0 seconds. Your local
 *      and server-side algorithms may be functionally identical, or you may choose
 *      to take advantage of other Python tools/libraries to improve performance.
 *      Call your local step detection algorithm from {@link #onSensorChanged(SensorEvent)}.
 *      <br><br>
 *      To listen for messages from the server,
 *      register a {@link MessageReceiver} with the {@link #mClient} and override
 *      the {@link MessageReceiver#onMessageReceived(JSONObject)} method to handle
 *      the message appropriately. The data will be received as a {@link JSONObject},
 *      which you can parse to acquire the step count reading.
 *      <br><br>
 *      We have provided you with the reading computed by the Android built-in step
 *      detection algorithm as an example and a ground-truth reading that you may
 *      use for comparison. Note that although the built-in algorithm has empirically
 *      been shown to work well, it is not perfect and may be sensitive to the phone
 *      orientation. Also note that it does not update the step count immediately,
 *      so don't be surprised if the step count increases suddenly by a lot!
 *  <br><br>
 *
 * <b>ASSIGNMENT 2 (Activity Detection)</b> :
 *      In this assignment, you will classify the user's activity based on the
 *      accelerometer data. The only modification you should make to the mobile
 *      app is to register a listener which will parse the activity from the acquired
 *      {@link org.json.JSONObject} and update the UI. The real work, that is
 *      your activity detection algorithm, will be running in the Python script
 *      and acquiring data from the server.
 *
 * @author CS390MB
 *
 * @see android.app.Service
 * @see <a href="http://developer.android.com/guide/components/services.html#Foreground">
 * Foreground Service</a>
 * @see SensorEventListener#onSensorChanged(SensorEvent)
 * @see SensorEvent
 * @see MobileIOClient
 */
public class AccelerometerService extends SensorService implements SensorEventListener,OnStepListener {

    /** Used during debugging to identify logs by class */
    private static final String TAG = AccelerometerService.class.getName();

    /** Sensor Manager object for registering and unregistering system sensors */
    private SensorManager mSensorManager;

    /** Manages the physical accelerometer sensor on the phone. */
    private Sensor mAccelerometerSensor;

    /** Android built-in step detection sensor **/
    private Sensor mStepSensor;

    /** Defines your step detection algorithm. **/
    private final StepDetector mStepDetector;

    /** The step count as predicted by the Android built-in step detection algorithm. */
    private int mAndroidStepCount = 0;

    private  int mServerStepCount = 0;

    static int label = 0;

//    private final BufferedWriter writer = FileUtil.getFileWriter("ACCEL");

    public AccelerometerService(){
        mStepDetector = new StepDetector();
    }

    @Override
    protected void onServiceStarted() {
        broadcastMessage(Constants.MESSAGE.ACCELEROMETER_SERVICE_STARTED);
    }

    @Override
    protected void onServiceStopped() {
        broadcastMessage(Constants.MESSAGE.ACCELEROMETER_SERVICE_STOPPED);
    }

    @Override
    public void onConnected() {
        super.onConnected();
        mClient.registerMessageReceiver(new MessageReceiver(Constants.MHLClientFilter.STEP_DETECTED) {
            @Override
            protected void onMessageReceived(JSONObject json) {
                Log.w(TAG, "Received step update from server.");
                try {
                    JSONObject data = json.getJSONObject("data");
                    long timestamp = data.getLong("timestamp");
                    Log.w(TAG, "Step occurred at " + timestamp + ".");
                    mServerStepCount  += 1;
                    broadcastServerStepCount(mServerStepCount);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mClient.registerMessageReceiver(new MessageReceiver(Constants.MHLClientFilter.ACTIVITY_DETECTED) {
            @Override
            protected void onMessageReceived(JSONObject json) {
                String activity;
                try {
                    JSONObject data = json.getJSONObject("data");
                    activity = data.getString("activity");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                // TODO : broadcast activity to UI
                Log.d("=======>", "onMessageReceived:" + activity);
                broadcastActivity(activity);
            }
        });
    }

    /**
     * Register accelerometer sensor listener
     */
    @Override
    protected void registerSensors(){

        //TODO : (Assignment 0) Register the accelerometer sensor from the sensor manager.
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mStepSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        mSensorManager.registerListener(this,mAccelerometerSensor,SensorManager.SENSOR_DELAY_NORMAL);
        //TODO : (Assignment 1) Register your step detector. Register an OnStepListener to receive step events
        mSensorManager.registerListener(mStepDetector,mAccelerometerSensor,SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this,mStepSensor,SensorManager.SENSOR_DELAY_NORMAL);
        mStepDetector.registerOnStepListener(this);
    }

    /**
     * Unregister the sensor listener, this is essential for the battery life!
     */
    @Override
    protected void unregisterSensors() {
        //TODO : Unregister your sensors. Make sure mSensorManager is not null before calling its unregisterListener method.
        mSensorManager.unregisterListener(this,mAccelerometerSensor);
        mSensorManager.unregisterListener(this,mStepSensor);
        mSensorManager.unregisterListener(mStepDetector,mAccelerometerSensor);
        mStepDetector.unregisterOnStepListener(this);
    }

    @Override
    protected int getNotificationID() {
        return Constants.NOTIFICATION_ID.ACCELEROMETER_SERVICE;
    }

    @Override
    protected String getNotificationContentText() {
        return getString(R.string.activity_service_notification);
    }

    @Override
    protected int getNotificationIconResourceID() {
        return R.drawable.ic_running_white_24dp;
    }

    /**
     * This method is called when we receive a sensor reading. We will be interested in this method primarily.
     * <br><br>
     *
     * Assignment 0 : Your job is to send the accelerometer readings to the server as you receive
     * them. Use the {@link #mClient} from the base class {@link SensorService} to communicate with
     * the data collection server. Specifically look at {@link MobileIOClient#sendSensorReading(SensorReading)}.
     * <br><br>
     *
     * We will be sending {@link AccelerometerReading}s. When instantiating an {@link AccelerometerReading},
     * pass in your user ID, which is accessible from the base sensor service, your device type and
     * your device identifier, as well as the timestamp and values of the sensor event.
     * <br><br>
     *
     * Note you may leave the device identifier a blank string. For the device type, you can use "MOBILE".
     * <br><br>
     *
     * You also want to broadcast the accelerometer reading to the UI. You can do this by calling
     * {@link #broadcastAccelerometerReading(long, float[])}.
     *
     * @see AccelerometerReading
     * @see SensorReading
     * @see MobileIOClient
     * @see SensorEvent
     * @see #broadcastAccelerometerReading(long, float[])
     */

    public static void changeLabel(int newlabel) {
        label = newlabel;
    }
    @Override
    public void onSensorChanged(SensorEvent event) {
//        Log.d(TAG, "X : " + event.values[0] + ", Y : " +
//                event.values[1] + ", Z : " + event.values[2]);
        Filter bufferingFilter = new Filter(3.0);
        Filter smoothFilter = new Filter(1);

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            // convert the timestamp to milliseconds (note this is not in Unix time)
            long timestamp_in_milliseconds = (long) ((double) event.timestamp / Constants.TIMESTAMPS.NANOSECONDS_PER_MILLISECOND);

            //TODO: Send the accelerometer reading to the server
            double[] filterValues = bufferingFilter.getFilteredValues(event.values);
            float[] floatFilterValues = convertToFloatArray(filterValues);
            float[] finalData = convertToFloatArray(smoothFilter.getFilteredValues(floatFilterValues));
//            synchronized(writer) {
//                FileUtil.writeToFile(timestamp_in_milliseconds + "," + finalData[0] + "," + finalData[1] + "," + finalData[2] + "," + label, writer);
//            }
            AccelerometerReading reading  = new AccelerometerReading(mUserID,"Mobile","",timestamp_in_milliseconds,label,finalData);
            broadcastAccelerometerReading(timestamp_in_milliseconds,event.values);
            mClient.sendSensorReading(reading);
            //TODO: broadcast the accelerometer reading to the UI

        }else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {

            // we received a step event detected by the built-in Android step detector (assignment 1)
            broadcastAndroidStepCount(mAndroidStepCount++);

        } else {

            // cannot identify sensor type
            Log.w(TAG, Constants.ERROR_MESSAGES.WARNING_SENSOR_NOT_SUPPORTED);

        }
    }

    private float[] convertToFloatArray(double[] doubleArray) {
        float[] floatArray = new float[doubleArray.length];
        for (int i = 0 ; i < doubleArray.length; i++)
        {
            floatArray[i] = (float) doubleArray[i];
        }
        return  floatArray;
    }
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.i(TAG, "Accuracy changed: " + accuracy);
    }

    /**
     * Broadcasts the accelerometer reading to other application components, e.g. the main UI.
     * @param accelerometerReadings the x, y, and z accelerometer readings
     */
    public void broadcastAccelerometerReading(final long timestamp, final float[] accelerometerReadings) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.TIMESTAMP,timestamp);
        intent.putExtra(Constants.KEY.ACCELEROMETER_DATA, accelerometerReadings);
        intent.setAction(Constants.ACTION.BROADCAST_ACCELEROMETER_DATA);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    // ***************** Methods for broadcasting step counts (assignment 1) *****************

    /**
     * Broadcasts the step count computed by the Android built-in step detection algorithm
     * to other application components, e.g. the main UI.
     */
    public void broadcastAndroidStepCount(int stepCount) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.STEP_COUNT, stepCount);
        intent.setAction(Constants.ACTION.BROADCAST_ANDROID_STEP_COUNT);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    /**
     * Broadcasts the step count computed by your step detection algorithm
     * to other application components, e.g. the main UI.
     */
    public void broadcastLocalStepCount(int stepCount) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.STEP_COUNT, stepCount);
        intent.setAction(Constants.ACTION.BROADCAST_LOCAL_STEP_COUNT);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }


    // TODO: (Assignment 1) Broadcast the step count as computed by your server-side algorithm.
    public void broadcastServerStepCount(int stepCount) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.STEP_COUNT, stepCount);
        intent.setAction(Constants.ACTION.BROADCAST_SERVER_STEP_COUNT);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    /**
     * Broadcasts a step event to other application components, e.g. the main UI.
     * Use this if you would like to visualize the detected step on the accelerometer signal.
     */
    public void broadcastStepDetected(long timestamp, float[] values) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.ACCELEROMETER_PEAK_TIMESTAMP, timestamp);
        intent.putExtra(Constants.KEY.ACCELEROMETER_PEAK_VALUE, values);
        intent.setAction(Constants.ACTION.BROADCAST_ACCELEROMETER_PEAK);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    public void broadcastActivity(String activity) {
        Log.w(TAG, "broadcastActivity: here");
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.ACTIVITY,activity);
        intent.setAction(Constants.ACTION.BROADCAST_ACTIVITY);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }
    @Override
    public void onStepCountUpdated(int stepCount) {
        broadcastLocalStepCount(stepCount);
    }

    @Override
    public void onStepDetected(long timestamp, float[] values) {
        broadcastStepDetected(timestamp,values);
    }
}
