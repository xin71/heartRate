package cs.umass.edu.myactivitiestoolkit.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.LinkedList;
import java.util.Queue;
import java.util.*;

import cs.umass.edu.myactivitiestoolkit.R;
import cs.umass.edu.myactivitiestoolkit.ppg.HRSensorReading;
import cs.umass.edu.myactivitiestoolkit.ppg.PPGSensorReading;
import cs.umass.edu.myactivitiestoolkit.constants.Constants;
import cs.umass.edu.myactivitiestoolkit.ppg.HeartRateCameraView;
import cs.umass.edu.myactivitiestoolkit.ppg.PPGEvent;
import cs.umass.edu.myactivitiestoolkit.ppg.PPGListener;
import cs.umass.edu.myactivitiestoolkit.processing.Filter;
import edu.umass.cs.MHLClient.client.MobileIOClient;

/**
 * Photoplethysmography service. This service uses a {@link HeartRateCameraView}
 * to collect PPG data using a standard camera with continuous flash. This is where
 * you will do most of your work for this assignment.
 * <br><br>
 * <b>ASSIGNMENT (PHOTOPLETHYSMOGRAPHY)</b> :
 * In {@link #onSensorChanged(PPGEvent)}, you should smooth the PPG reading using
 * a {@link Filter}. You should send the filtered PPG reading both to the server
 * and to the {@link cs.umass.edu.myactivitiestoolkit.view.fragments.HeartRateFragment}
 * for visualization. Then call your heart rate detection algorithm, buffering the
 * readings if necessary, and send the bpm measurement back to the UI.
 * <br><br>
 * EXTRA CREDIT:
 *      Follow the steps outlined <a href="http://www.marcoaltini.com/blog/heart-rate-variability-using-the-phones-camera">here</a>
 *      to acquire a cleaner PPG signal. For additional extra credit, you may also try computing
 *      the heart rate variability from the heart rate, as they do.
 *
 * @author CS390MB
 *
 * @see HeartRateCameraView
 * @see PPGEvent
 * @see PPGListener
 * @see Filter
 * @see MobileIOClient
 * @see PPGSensorReading
 * @see Service
 */
public class PPGService extends SensorService implements PPGListener
{
    @SuppressWarnings("unused")
    /** used for debugging purposes */
    private static final String TAG = PPGService.class.getName();

    /* Surface view responsible for collecting PPG data and displaying the camera preview. */
    private HeartRateCameraView mPPGSensor;

    //Filter bufferingFilter = new Filter(5.0);
    Filter smoothFilter = new Filter(2.0);

    int dataCounter = 0;
    int peak = 0;
    int finalPeak = 0;
    private final String  NATURAL = "natural";
    private final String INCREASING = "increasing";
    private final String DECREASING = "decreasing";
    String status = INCREASING;
    long startTime = 0;
    long finishTime = 0;
    float constant = 0;

    Queue<Integer> windowsQueue = new LinkedList<Integer>();
    Queue<Constants.TIMESTAMPS> peakQueue = new LinkedList<Constants.TIMESTAMPS>();
   // ArrayList<Integer> signalArray = new ArrayList<Integer>();
    double[] signalArray = new double[121];
    @Override
    protected void start() {
        Log.d(TAG, "START");
        mPPGSensor = new HeartRateCameraView(getApplicationContext(), null);

        WindowManager winMan = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);

        //surface view dimensions and position specified where service intent is called
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;

        //display the surface view as a stand-alone window
        winMan.addView(mPPGSensor, params);
        mPPGSensor.setZOrderOnTop(true);

        // only once the surface has been created can we start the PPG sensor
        mPPGSensor.setSurfaceCreatedCallback(new HeartRateCameraView.SurfaceCreatedCallback() {
            @Override
            public void onSurfaceCreated() {
                mPPGSensor.start(); //start recording PPG
            }
        });

        super.start();
    }

    @Override
    protected void onServiceStarted() {
        broadcastMessage(Constants.MESSAGE.PPG_SERVICE_STARTED);
    }

    @Override
    protected void onServiceStopped() {
        if (mPPGSensor != null)
            mPPGSensor.stop();
        if (mPPGSensor != null) {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(mPPGSensor);
        }
        broadcastMessage(Constants.MESSAGE.PPG_SERVICE_STOPPED);
    }

    @Override
    protected void registerSensors() {
        // TODO: Register a PPG listener with the PPG sensor (mPPGSensor)
        mPPGSensor.registerListener(this);
    }

    @Override
    protected void unregisterSensors() {
        // TODO: Unregister the PPG listener
        mPPGSensor.unregisterListener(this);
    }

    @Override
    protected int getNotificationID() {
        return Constants.NOTIFICATION_ID.PPG_SERVICE;
    }

    @Override
    protected String getNotificationContentText() {
        return getString(R.string.ppg_service_notification);
    }

    @Override
    protected int getNotificationIconResourceID() {
        return R.drawable.ic_whatshot_white_48dp;
    }

    /**
     * This method is called each time a PPG sensor reading is received.
     * <br><br>
     * You should smooth the data using {@link Filter} and then send the filtered data both
     * to the server and the main UI for real-time visualization. Run your algorithm to
     * detect heart beats, calculate your current bpm and send the bmp measurement to the
     * main UI. Additionally, it may be useful for you to send the peaks you detect to
     * the main UI, using {@link #broadcastPeak(long, double)}. The plot is already set up
     * to draw these peak points upon receiving them.
     * <br><br>
     * Also make sure to send your bmp measurement to the server for visualization. You
     * can do this using {@link HRSensorReading}.
     *
     * @param event The PPG sensor reading, wrapping a timestamp and mean red value.
     *
     * @see PPGEvent
     * @see PPGSensorReading
     * @see HeartRateCameraView#onPreviewFrame(byte[], Camera)
     * @see MobileIOClient
     * @see HRSensorReading
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onSensorChanged(PPGEvent event) {
        // TODO: Smooth the signal using a Butterworth / exponential smoothing filter
        //double[] filterBuffValues = bufferingFilter.getFilteredValues((float) event.value);
        double[] filterSmoothValue = smoothFilter.getFilteredValues((float) event.value);
       // double[] filterBuffValues = bufferingFilter.getFilteredValues((float) filterSmoothValue[0]);
        // TODO: send the data to the UI fragment for visualization, using broadcastPPGReading(...)
        // long timestamp_in_milliseconds = (long) ((double) event.timestamp / Constants.TIMESTAMPS.NANOSECONDS_PER_MILLISECOND);
        broadcastPPGReading(event.timestamp,filterSmoothValue[0]);
        // TODO: Send the filtered mean red value to the server
        PPGSensorReading reading = new PPGSensorReading(mUserID,"Mobile","",event.timestamp,filterSmoothValue[0]);
        mClient.sendSensorReading(reading);
        // TODO: Buffer data if necessary for your algorithm
        // TODO: Call your heart beat and bpm detection algorithm
        if(dataCounter == 0){
            startTime = event.timestamp;
        }

        if(dataCounter == 120){
            finishTime = event.timestamp;
            constant = 1/ ((float)(finishTime-startTime)/(float)(60000));
        }

        calBMP(filterSmoothValue[0]);
        broadcastPeak(event.timestamp,(double)(peak));
        broadcastBPM((int)(finalPeak*constant));
        // TODO: Send your heart rate estimate to the server
    }

    private float[] convertToFloatArray(double[] doubleArray) {
        float[] floatArray = new float[doubleArray.length];
        for (int i = 0 ; i < doubleArray.length; i++)
        {
            floatArray[i] = (float) doubleArray[i];
        }
        return  floatArray;
    }

    /**
     * Broadcasts the PPG reading to other application components, e.g. the main UI.
     * @param ppgReading the mean red value.
     */
    public void broadcastPPGReading(final long timestamp, final double ppgReading) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.PPG_DATA, ppgReading);
        intent.putExtra(Constants.KEY.TIMESTAMP, timestamp);
        intent.setAction(Constants.ACTION.BROADCAST_PPG);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    /**
     * Broadcasts the current heart rate in BPM to other application components, e.g. the main UI.
     * @param bpm the current beats per minute measurement.
     */
    public void broadcastBPM(final int bpm) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.HEART_RATE, bpm);
        intent.setAction(Constants.ACTION.BROADCAST_HEART_RATE);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    /**
     * Broadcasts the current heart rate in BPM to other application components, e.g. the main UI.
     * @param timestamp the current beats per minute measurement.
     */
    public void broadcastPeak(final long timestamp, final double value) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.PPG_PEAK_TIMESTAMP, timestamp);
        intent.putExtra(Constants.KEY.PPG_PEAK_VALUE, value);
        intent.setAction(Constants.ACTION.BROADCAST_PPG_PEAK);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }



    /*
    public void calBMP (PPGEvent event){
        if(dataCounter<60){
            windowsQueue.add(new Integer((int)event.value));
            dataCounter++;
            if(event.value < windowsQueue.peek() && status == INCREASING){
                peak++;
                peakQueue.add(Constants.TIMESTAMPS);
                status = DECREASING;
            }else{
                status = INCREASING;
            }
        }else {
            if(event.value < windowsQueue.peek() && status == INCREASING){
                peak++;
                peakQueue.add(Constants.TIMESTAMPS);
                status = DECREASING;
            }else{
                status = INCREASING;
            }
            windowsQueue.poll();
            windowsQueue.add(new Integer((int) event.value));
        }
    }
*/

    public void calBMP(double value){
        if(dataCounter == 120){
            signalArray[119] = value;
            Log.d(TAG, "Array: " + Arrays.toString(signalArray));
            dataCounter++;
            for (int i = 0; i < 120; i++) {
                if((i+1)<120) {
                    if ((signalArray[i + 1] - signalArray[i]) < 0 && status == INCREASING) {
                        peak++;
                        Log.d(TAG, "calBMP: " + peak);
                        status = DECREASING;
                    } else if ((signalArray[i + 1] - signalArray[i]) > 0 && status == DECREASING) {
                        status = INCREASING;
                    } else if ((signalArray[i + 1] - signalArray[i]) < 0 && status == DECREASING) {
                        status = DECREASING;
                    }else if ((signalArray[i + 1] - signalArray[i])  == 0 && status == DECREASING){
                        status = DECREASING;
                    }else if ((signalArray[i + 1] - signalArray[i])  == 0 && status == INCREASING){
                        status = INCREASING;
                    }
                    else {
                        status = INCREASING;
                    }
                }
            }
            finalPeak = peak;

        }else if (dataCounter <= 120){
            signalArray[dataCounter] = value;
            dataCounter++;
        }else{
            dataCounter = 0;
            peak = 0;
            signalArray = new double[121];
            status = INCREASING;
        }
    }




}



