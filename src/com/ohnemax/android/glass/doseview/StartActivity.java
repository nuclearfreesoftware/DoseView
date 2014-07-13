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
 */

package com.ohnemax.android.glass.doseview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;



public class StartActivity extends Activity {
    private static final String TAG = StartActivity.class.getSimpleName();

    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        Log.d(TAG, "Started DoseView!");
        startService(new Intent(StartActivity.this, DoseRateService.class));
    }
}