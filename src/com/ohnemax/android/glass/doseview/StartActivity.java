package com.ohnemax.android.glass.doseview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;



public class StartActivity extends Activity {
    private static final String TAG = StartActivity.class.getSimpleName();

    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        Log.e(TAG, "Started!");
        startService(new Intent(StartActivity.this, DoseRateService.class));
    }
}