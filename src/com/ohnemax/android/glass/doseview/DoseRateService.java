/* Copyright (C) 2014, Moritz Kütt
 * 
 * This file is part of DoseView.
 * 
 * DoseView is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * DoseView is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with DoseView.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * 
 * This file incorporates work (processLocationData / initializeLocalizationService) 
 * covered by the following copyright and  permission notice:  
 * 
 *     The Do-No-Evil License Version 1.0 (DNE-1.0)
 * 
 *     Copyright (c) 2013 Harry Y
 * 
 *     Permission is hereby granted to any person obtaining a copy of this software to 
 *     deal in the software without restriction subject to the following 
 *     conditions: "The software shall be used for good, not evil."
 * 
 *     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.
 * 
 */

package com.ohnemax.android.glass.doseview;

import com.ohnemax.android.glass.doseview.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Random;

import android.text.format.DateFormat;
import android.util.Log;
import android.net.ConnectivityManager;
import android.os.Bundle;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.timeline.LiveCard.PublishMode;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.StrictMode;
import android.widget.RemoteViews;
import android.net.NetworkInfo;

enum CSDataSort { NEARNEW, NEWNEAR, FIRSTSET, LASTSET };

public class DoseRateService extends Service {
	
	
    private static final String TAG = DoseRateService.class.getSimpleName();
    
    private static final String LIVE_CARD_TAG = "DoseRateCard";

    private LiveCard mLiveCard;
    private RemoteViews mLiveCardView;

    private Random mRanGen;
    
    private JSONArray CSdatasets;
    private Boolean jsonloaded;

    private final Handler mHandler = new Handler();
    private final UpdateLiveCardRunnable mUpdateLiveCardRunnable =
        new UpdateLiveCardRunnable();
    private static final long DELAY_MILLIS = 5000;
    
    //Location stuff
    Location lastLoc = null;
    private LocationManager locationManager = null;
    private long lastProcessedTime = 0L;
    private long lastUpdatedTime = 0L;

    public static boolean isConnected = false;

    public boolean isDeviceConnectedToInternet() {
        return isConnected;
    }

    private class CheckConnectivityTask extends AsyncTask<Void, Boolean, Boolean> {
        protected Boolean doInBackground(Void... voids) {
            while(true) {
                // Update isConnected variable.
                publishProgress(isConnected());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Determines if the Glassware can access the internet.
         * isNetworkAvailable() is used first because there is no point in executing an HTTP GET
         * request if ConnectivityManager and NetworkInfo tell us that no network is available.
         */
        private boolean isConnected(){
            if (isNetworkAvailable()) {
                HttpGet httpGet = new HttpGet("http://www.google.com");
                HttpParams httpParameters = new BasicHttpParams();
                HttpConnectionParams.setConnectionTimeout(httpParameters, 3000);
                HttpConnectionParams.setSoTimeout(httpParameters, 5000);

                DefaultHttpClient httpClient = new DefaultHttpClient(httpParameters);
                try{
                    Log.d(TAG, "Checking network connection...");
                    httpClient.execute(httpGet);
                    Log.d(TAG, "Connection OK");
                    return true;
                }
                catch(ClientProtocolException e){
                    e.printStackTrace();
                }
                catch(IOException e){
                    e.printStackTrace();
                }
                Log.d(TAG, "Connection unavailable");
            } else {
                // No connection; for Glass this probably means Bluetooth is disconnected.
                Log.d(TAG, "No network available!");
            }
            return false;
        }

        private boolean isNetworkAvailable() {
            ConnectivityManager connectivityManager
                    = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            Log.d(TAG, String.format("In isConnected(), activeNetworkInfo.toString(): %s",
                    activeNetworkInfo == null ? "null" : activeNetworkInfo.toString()));
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }

        protected void onProgressUpdate(Boolean... isConnected) {
            DoseRateService.isConnected = isConnected[0];
            Log.d(TAG, "Checking connection: connected = " + isConnected[0]);
        }
    }
    @Override
    public void onCreate() {
    	 // Just for testing, allow network access in the main thread
    	 // NEVER use this is productive code
    	StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
    	StrictMode.setThreadPolicy(policy);
    	
        super.onCreate();
        mRanGen = new Random();
        
        new CheckConnectivityTask().execute();
        initializeLocationManager();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLiveCard == null) {

            // Get an instance of a live card
            mLiveCard = new LiveCard(this, LIVE_CARD_TAG);

            // Inflate a layout into a remote view
            mLiveCardView = new RemoteViews(getPackageName(),R.layout.service_doserate);

         
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

    public int getCSDataIndex(CSDataSort sort, double longitude, double latitude) {
		Location locA = new Location("I");
		Location locB = new Location("Data");
		Log.d(TAG, "Long: " + String.valueOf(longitude));
		Log.d(TAG, "Lat: " + String.valueOf(latitude));

    	if(sort == CSDataSort.FIRSTSET) {
    		return 0;
    	} else if (sort == CSDataSort.LASTSET) {
    		return CSdatasets.length() - 1;
    	} else if (sort == CSDataSort.NEARNEW){

        	locA.setLongitude(longitude);
        	locA.setLatitude(latitude);
        	
        	double mindistance = 1000000000;
        	String lastdate = "0000-00-00T00:00:00Z";
        	int select = 0;
    		for (int i = 0; i < CSdatasets.length(); i++) {
                JSONObject jsonObject;
				try {
					jsonObject = CSdatasets.getJSONObject(i);
					locB.setLongitude(jsonObject.getDouble("longitude"));
		            locB.setLatitude(jsonObject.getDouble("latitude"));
		            if(locB.distanceTo(locA) < mindistance) {
		                mindistance = locB.distanceTo(locA);
		                lastdate = jsonObject.getString("captured_at");
		                select = i;
		            } else if (locB.distanceTo(locA) == mindistance) {
		            	if(lastdate.compareTo(jsonObject.getString("captured_at")) <= 0) {
		            		select = i;
		            		mindistance = locB.distanceTo(locA);
		            		lastdate = jsonObject.getString("captured_at");
		            	}
		            }
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            
    		}
    		return select;
    	} else {
        	locA.setLongitude(longitude);
        	locA.setLatitude(latitude);
        	
        	double mindistance = 1000000000;
        	String lastdate = "0000-00-00T00:00:00Z";
        	int select = 0;
    		for (int i = 0; i < CSdatasets.length(); i++) {   
                JSONObject jsonObject;

				try {
					jsonObject = CSdatasets.getJSONObject(i);
	    			if(lastdate.compareTo(jsonObject.getString("captured_at")) < 0) {
	            		select = i;
	            		mindistance = locB.distanceTo(locA);
	            		lastdate = jsonObject.getString("captured_at");
	            	} else if(lastdate.compareTo(jsonObject.getString("captured_at")) == 0) {
	            		if(locB.distanceTo(locA) < mindistance) {
			                mindistance = locB.distanceTo(locA);
			                lastdate = jsonObject.getString("captured_at");
			                select = i;
			            }
	            	}
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
    		}
    		return select;
    	}
    }
    
    public double getCPM(CSDataSort sort, double longitude, double latitude) {
    	if(jsonloaded) {
        	int datano = getCSDataIndex(sort, longitude, latitude);
        	try {
				JSONObject jsonobject = CSdatasets.getJSONObject(datano);
				return jsonobject.getDouble("value");
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        	
    	}
    	return 0;
    }
    
    public double getDoseRate(CSDataSort sort, double longitude, double latitude) {
    	return getCPM(sort, longitude, latitude) / 334.0;
    }
    public String getDate(CSDataSort sort, double longitude, double latitude) {
    	SimpleDateFormat  format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");  
    	SimpleDateFormat expformat = new SimpleDateFormat();
    	if(jsonloaded) {
        	int datano = getCSDataIndex(sort, longitude, latitude);
        	try {
				JSONObject jsonobject = CSdatasets.getJSONObject(datano);
				String dateold = jsonobject.getString("captured_at");
				Date newdate;
				try {
					newdate = format.parse(dateold);
					return expformat.format(newdate);
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        	
    	}    	
    	return "";
    }
    
    public double getDistance(CSDataSort sort, double longitude, double latitude) {
		Location locA = new Location("I");
		Location locB = new Location("Data");
		locA.setLatitude(latitude);
		locA.setLongitude(longitude);
    	if(jsonloaded) {
        	int datano = getCSDataIndex(sort, longitude, latitude);
        	try {
				JSONObject jsonobject = CSdatasets.getJSONObject(datano);
				locB.setLongitude(jsonobject.getDouble("longitude"));
				locB.setLatitude(jsonobject.getDouble("latitude"));
				return locB.distanceTo(locA);
				
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        	
    	}    	
    	return 0;
    }
    
    public String getUser(CSDataSort sort, double longitude, double latitude) {
    	
    
    	if(jsonloaded) {
    		int uid = 0;

        	int datano = getCSDataIndex(sort, longitude, latitude);
        	try {
				JSONObject jsonobject = CSdatasets.getJSONObject(datano);
				uid = jsonobject.getInt("user_id");
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        	if(uid != 0) {
        		StringBuilder builder = new StringBuilder();
        		HttpClient httpclient = new DefaultHttpClient();
        		String url = "https://api.safecast.org/users/" + String.valueOf(uid) + ".json";
        		HttpGet httpget = new HttpGet(url); 
        		HttpResponse response;
        		try {
        			response = httpclient.execute(httpget);
        			StatusLine statusLine = response.getStatusLine();
        			int statusCode = statusLine.getStatusCode();
        			if (statusCode == 200) {
        				HttpEntity entity = response.getEntity();
        				InputStream content = entity.getContent();
        				BufferedReader reader = new BufferedReader(new InputStreamReader(content));
        				String line;
        				while ((line = reader.readLine()) != null) {
        					builder.append(line);
        				}
        				String datastring = builder.toString();
        				try {
        						JSONObject obj = new JSONObject(datastring);
        						return obj.getString("name");			
        				} catch (Exception e) {
        					e.printStackTrace();
        				}
        			} else {
        				Log.e(TAG, "Failed to download file");
        			}
        		} catch (ClientProtocolException er) {
        			er.printStackTrace();
        			Log.e(TAG, "protocol");
        		} catch (IOException er) {
        			er.printStackTrace();
        			Log.e(TAG, "io");
        		} catch (Exception er) {
        			Log.e(TAG, "other");
        		}  
        	}
    	}
    	return "";
    	//https://api.safecast.org/users/506.json
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
            	
            	double latitude;
            	double longitude;
            	
            	if(lastLoc != null) {
            		Log.d(TAG, "last updated: " + String.valueOf(lastUpdatedTime));
            		latitude = lastLoc.getLatitude();
            		longitude = lastLoc.getLongitude();
            		mLiveCardView.setTextViewText(R.id.device_lat_info,
                            "Dev. Latitude:");
                	mLiveCardView.setTextViewText(R.id.device_lat,
                            String.valueOf((double)Math.round(latitude * 1000000) / 1000000));
                	mLiveCardView.setTextViewText(R.id.device_long_info,
                            "Dev. Longitude:");
                	mLiveCardView.setTextViewText(R.id.device_long,
                            String.valueOf((double)Math.round(longitude * 1000000) / 1000000));
                	Log.d(TAG, "location: " + lastLoc.toString());
                	
                	                	
                	if(isDeviceConnectedToInternet()) {
                		readSCdata(longitude, latitude, 100);
                	}
                	else {
                		jsonloaded = false;
                	}
                    if(!jsonloaded) {
                    	mLiveCardView.setTextViewText(R.id.cpm,
                                "---");
                    	mLiveCardView.setTextViewText(R.id.dose_rate,
                                "---");
                    	mLiveCardView.setTextViewText(R.id.status_text_info,
                                "Error:");
                    	mLiveCardView.setTextViewText(R.id.data_date,
                                "No DB Conn.");
                    	mLiveCardView.setTextViewText(R.id.distance_text_info,
                                "");
                    	mLiveCardView.setTextViewText(R.id.data_distance,
                                "");
                    	mLiveCardView.setTextViewText(R.id.data_user,
                               "");
                    }
                    else {
                    	if(CSdatasets.length() > 0) {
                    		mLiveCardView.setTextViewText(R.id.cpm,
                    				String.valueOf(getCPM(CSDataSort.NEARNEW, longitude, latitude)) + " CPM");
                    		
                    		double doserate = getDoseRate(CSDataSort.NEARNEW, longitude, latitude);
                    		// TODO: add different units...
                        	doserate = (double)Math.round(doserate * 1000) / 1000;
                    		mLiveCardView.setTextViewText(R.id.dose_rate,
                    				String.valueOf(doserate) + " µSv/h");
                    		
                    		mLiveCardView.setTextViewText(R.id.status_text_info,
                    				"Measured on:");

                    		mLiveCardView.setTextViewText(R.id.data_date,
                    				getDate(CSDataSort.NEARNEW, longitude, latitude));
                        	mLiveCardView.setTextViewText(R.id.distance_text_info,
                                    "Measurement Distance: ");
                        	double distance = getDistance(CSDataSort.NEARNEW, longitude, latitude);
                        	distance = (double)Math.round(distance * 100) / 100;
                        	mLiveCardView.setTextViewText(R.id.data_distance,
                                    String.valueOf(distance) + " m");
                        	
                        	Log.d(TAG, getUser(CSDataSort.NEARNEW, longitude, latitude));
                        	mLiveCardView.setTextViewText(R.id.data_user,
                                    getUser(CSDataSort.NEARNEW, longitude, latitude));
                        	
                    	}
                    	else {
                    		mLiveCardView.setTextViewText(R.id.cpm,
                    				"---");
                    		
                    		mLiveCardView.setTextViewText(R.id.dose_rate,
                    				"---");

                    		mLiveCardView.setTextViewText(R.id.status_text_info,
                    				"Connection, but:");

                    		mLiveCardView.setTextViewText(R.id.data_date,
                    				"No data in range");
                    		mLiveCardView.setTextViewText(R.id.data_user,
                                   "");
                    	}
                    }

            	}
            	else {
            		
            		mLiveCardView.setTextViewText(R.id.device_lat_info,
                            "Dev. Latitude:");
                	mLiveCardView.setTextViewText(R.id.device_lat,
                            "No Location Available");
                	mLiveCardView.setTextViewText(R.id.device_long_info,
                            "Dev. Longitude:");
                	mLiveCardView.setTextViewText(R.id.device_long,
                            "No Location Available");
                	
                	
                	mLiveCardView.setTextViewText(R.id.cpm,
                            "---");
                	mLiveCardView.setTextViewText(R.id.dose_rate,
                            "---");
                	mLiveCardView.setTextViewText(R.id.status_text_info,
                            "Sorry:");
                	mLiveCardView.setTextViewText(R.id.data_date,
                            "No Location Data.");
                	mLiveCardView.setTextViewText(R.id.distance_text_info,
                            "");
                	mLiveCardView.setTextViewText(R.id.data_distance,
                            "");
                	mLiveCardView.setTextViewText(R.id.data_user,
                           "");
            	}
             

                // Always call setViews() to update the live card's RemoteViews.
                mLiveCard.setViews(mLiveCardView);

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
        return null;
    }
    
    public void readSCdata(double longitude, double latitude, int distance) {
    	jsonloaded = false;
    	
        StringBuilder builder = new StringBuilder();
     	HttpClient httpclient = new DefaultHttpClient();
     	String url = "https://api.safecast.org/measurements.json?distance=" + String.valueOf(distance) + "&latitude=" + String.valueOf(latitude) + "&longitude=" + String.valueOf(longitude);
    	Log.d(TAG, url);
     	//String url = "https://api.safecast.org/en-US/measurements?distance=7&latitude=-37.5982&longitude=145.4131";   
    	//String url = "http://ip.jsontest.com/";
    	HttpGet httpget = new HttpGet(url); 
    	HttpResponse response;
    	try {
    		response = httpclient.execute(httpget);
    	    StatusLine statusLine = response.getStatusLine();
    		int statusCode = statusLine.getStatusCode();
    	    if (statusCode == 200) {
    	        HttpEntity entity = response.getEntity();
    	        InputStream content = entity.getContent();
    	        BufferedReader reader = new BufferedReader(new InputStreamReader(content));
    	        String line;
    	        while ((line = reader.readLine()) != null) {
    	        	builder.append(line);
    	        }
    	        String datastring = builder.toString();
    	        try {
                    JSONArray jsonArray = new JSONArray(datastring);
                    Log.i(TAG,
                        "Number of entries " + jsonArray.length());
                    CSdatasets = jsonArray;
                    jsonloaded = true;
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
    	    } else {
    	        Log.e(TAG, "Failed to download file");
    	    }
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
    }

    private void processLocationData(Location location) {
        long now = System.currentTimeMillis();
        if(location == null) {
            Log.d(TAG, "processLocationData() called with null location");
            // TBD:
            // periodic checking???
            // Update the location/time regardless of location ????
            // ...
        } else if(lastLoc == null) {
            lastLoc = location;
            lastUpdatedTime = now;
        } else {
            float lastAcc = lastLoc.getAccuracy();
            float acc = location.getAccuracy();

            if(acc < lastAcc) {
                // The current data is better than the last one
                lastLoc = location;
                lastUpdatedTime = now;
            } else {
                // We use an interesting logic here.
                // If the new location data is "different" from the last data (within the accuracy of the new data),
                // then update the location. Otherwise, consider the new data as "noise".

                double dLat2 = (location.getLatitude() - lastLoc.getLatitude()) * (location.getLatitude() - lastLoc.getLatitude());
                double dLng2 = (location.getLongitude() - lastLoc.getLongitude()) * (location.getLongitude() - lastLoc.getLongitude());
                double dAlt2 = (location.getAltitude() - lastLoc.getAltitude()) * (location.getAltitude() - lastLoc.getAltitude());
                double distance = Math.sqrt(dLat2 + dLng2 + dAlt2);

                // Note the arbitrary factor.
                // We need to "tune" this value...
                if(distance >= acc * 0.001 ) {
                    lastLoc = location;
                    lastUpdatedTime = now;
                } else {
                    Log.d(TAG, "New data is within the error bar from the last location: distance = " + distance + "; new location accuracy = " + acc);
                }
            }
        }
    }
    
    private void initializeLocationManager()
    {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        List<String> providers = locationManager.getAllProviders();
        for (String provider : providers) {
            if (locationManager.isProviderEnabled(provider)) {
                Log.d(TAG, "Location provider added: provider = " + provider);
            } else {
                Log.i(TAG, "Location provider not enabled: " + provider);
            }
            // TBD: Do this only for the currently enabled providers????
            try {
                locationManager.requestLocationUpdates(provider, 5000L, 5.0f, new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        Log.d(TAG, "locationChanged: location = " + location);
                        processLocationData(location);
                    }
                    @Override
                    public void onProviderDisabled(String provider) {
                        Log.i(TAG, "providerDisabled: provider = " + provider);
                        // TBD
                    }
                    @Override
                    public void onProviderEnabled(String provider) {
                        Log.i(TAG, "providerEnabled: provider = " + provider);
                        // TBD
                    }
                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {
                        Log.i(TAG, "statusChanged: provider = " + provider + "; status = " + status + "; extras = " + extras);
                        // TBD
                    }
                });
            } catch (Exception e) {
                // ignore
                Log.w(TAG, "requestLocationUpdates() failed for provider = " + provider);
            }
        }
    }

    

}
