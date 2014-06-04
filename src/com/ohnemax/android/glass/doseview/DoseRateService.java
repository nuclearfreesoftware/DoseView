package com.ohnemax.android.glass.doseview;

import com.ohnemax.android.glass.doseview.R;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import android.util.Log;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.timeline.LiveCard.PublishMode;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.StrictMode;
import android.widget.RemoteViews;

public class DoseRateService extends Service {
	
    private static final String TAG = DoseRateService.class.getSimpleName();

    private static final String LIVE_CARD_TAG = "DoseRateCard";

    private LiveCard mLiveCard;
    private RemoteViews mLiveCardView;

    private Random mRanGen;

    private final Handler mHandler = new Handler();
    private final UpdateLiveCardRunnable mUpdateLiveCardRunnable =
        new UpdateLiveCardRunnable();
    private static final long DELAY_MILLIS = 5000;

    @Override
    public void onCreate() {
    	 // Just for testing, allow network access in the main thread
    	 // NEVER use this is productive code
    	StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
    	StrictMode.setThreadPolicy(policy);
    	
        super.onCreate();
        mRanGen = new Random();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLiveCard == null) {

            // Get an instance of a live card
            mLiveCard = new LiveCard(this, LIVE_CARD_TAG);

            // Inflate a layout into a remote view
            mLiveCardView = new RemoteViews(getPackageName(),R.layout.service_doserate);

            // Set up initial RemoteViews values
            mLiveCardView.setTextViewText(R.id.text_left_column, 
            		"MK");           
           // mLiveCardView.setTextViewText(R.id.home_team_name_text_view, "Princeton Tigers");


            // Set up the live card's action with a pending intent
            // to show a menu when tapped
            Intent menuIntent = new Intent(this, MenuActivity.class);
            menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mLiveCard.setAction(PendingIntent.getActivity(
                this, 0, menuIntent, 0));

            // Publish the live card
            mLiveCard.publish(PublishMode.REVEAL);

            // Queue the update text runnable
            mHandler.post(mUpdateLiveCardRunnable);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mLiveCard != null && mLiveCard.isPublished()) {
          //Stop the handler from queuing more Runnable jobs
            mUpdateLiveCardRunnable.setStop(true);

            mLiveCard.unpublish();
            mLiveCard = null;
        }
        super.onDestroy();
    }

    /**
     * Runnable that updates live card contents
     */
    private class UpdateLiveCardRunnable implements Runnable{

        private boolean mIsStopped = false;

        /*
         * If you are executing a long running task to get data to update a
         * live card(e.g, making a web call), do this in another thread or
         * AsyncTask.
         */
        public void run(){
            if(!isStopped()){
            	HttpClient httpclient = new DefaultHttpClient();
            	String url = "https://api.safecast.org/en-US/measurements?distance=7&latitude=-37.5982&longitude=145.4131";   
            	//String url = "http://ip.jsontest.com/";
            	// Prepare a request object
            	HttpGet httpget = new HttpGet(url); 

            	// Execute the request
            	HttpResponse response;
            	try {
            		response = httpclient.execute(httpget);
            		// Examine the response status
            		Log.i(TAG, response.getStatusLine().toString());

            		// Get hold of the response entity
            		HttpEntity entity = response.getEntity();
            		// If the response does not enclose an entity, there is no need
            		// to worry about connection release

            		if (entity != null) {

            			// A Simple JSON Response Read
            			InputStream instream = entity.getContent();
            			//String result= convertStreamToString(instream);
            			// now you have the string representation of the HTML request
            			instream.close();
            		}
            		mLiveCardView.setTextViewText(R.id.data_distance,
                            "Connection works");
            		Log.e(TAG, "Connection Works");

            	} catch (ClientProtocolException er) {
            		mLiveCardView.setTextViewText(R.id.data_distance,
                            "No DB Con 1");
            		er.printStackTrace();
            		Log.e(TAG, "protocol");
                } catch (IOException er) {
                	er.printStackTrace();
                	Log.e(TAG, "io");
                	mLiveCardView.setTextViewText(R.id.data_distance,
                            "No DB Con 2");
                } catch (Exception er) {
                	mLiveCardView.setTextViewText(R.id.data_distance,
                            "No DB Con 3");
                	Log.e(TAG, "other");
                	
                }
                
                double doserate = mRanGen.nextDouble();
                doserate = (double)Math.round(doserate * 100) / 100;
                
                mLiveCardView.setTextViewText(R.id.dose_rate,
                        String.valueOf(doserate) + "µSv/h");
               
                
                mLiveCardView.setTextViewText(R.id.status_text_info,
                        "Measured on:");
                
                mLiveCardView.setTextViewText(R.id.data_date,
                        "05/13/2014");
              

                // Always call setViews() to update the live card's RemoteViews.
                mLiveCard.setViews(mLiveCardView);

                // Queue another score update in 30 seconds.
                mHandler.postDelayed(mUpdateLiveCardRunnable, DELAY_MILLIS);
            }
        }

        public boolean isStopped() {
            return mIsStopped;
        }

        public void setStop(boolean isStopped) {
            this.mIsStopped = isStopped;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
      /*
       *  If you need to set up interprocess communication
       * (activity to a service, for instance), return a binder object
       * so that the client can receive and modify data in this service.
       *
       * A typical use is to give a menu activity access to a binder object
       * if it is trying to change a setting that is managed by the live card
       * service. The menu activity in this sample does not require any
       * of these capabilities, so this just returns null.
       */
        return null;
    }
}
