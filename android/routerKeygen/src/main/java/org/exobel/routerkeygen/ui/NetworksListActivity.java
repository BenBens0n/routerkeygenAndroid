/*
 * Copyright 2012 Rui Araújo, Luís Fonseca
 *
 * This file is part of Router Keygen.
 *
 * Router Keygen is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Router Keygen is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Router Keygen.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exobel.routerkeygen.ui;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import org.exobel.routerkeygen.BuildConfig;
import org.exobel.routerkeygen.R;
import org.exobel.routerkeygen.UpdateCheckerService;
import org.exobel.routerkeygen.WifiScanReceiver;
import org.exobel.routerkeygen.WifiScanReceiver.OnScanListener;
import org.exobel.routerkeygen.WifiStateReceiver;
import org.exobel.routerkeygen.algorithms.Keygen;
import org.exobel.routerkeygen.algorithms.WiFiNetwork;

public class NetworksListActivity extends Activity implements
        NetworksListFragment.OnItemSelectionListener, OnScanListener {

    private static final String TAG = "NetworksListActivity";
    private final static String LAST_DIALOG_TIME = "last_time";
    private static final int MY_PERMISSIONS_ACCESS_COARSE_LOCATION = 2;
    private static final int MY_PERMISSIONS_ACCESS_FINE_LOCATION = 1;
    private static final int REQUEST_CHECK_SETTINGS = 1;
    private boolean mTwoPane;
    private NetworksListFragment networkListFragment;
    private WifiManager wifi;
    private BroadcastReceiver scanFinished;
    private BroadcastReceiver stateChanged;
    private boolean welcomeScreenShown;

    private final Handler mHandler = new Handler();
    private boolean wifiState;
    private boolean wifiOn;
    private boolean scanPermission = true;
    private boolean autoScan;
    private boolean analyticsOptIn;
    private long autoScanInterval;
    private final Runnable mAutoScanTask = new Runnable() {
        @Override
        public void run() {
            scan();
            mHandler.postDelayed(mAutoScanTask, autoScanInterval * 1000L);
        }
    };
    private SwipeRefreshLayout mSwipeRefreshLayout;

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_networks_list);

        networkListFragment = ((NetworksListFragment) getFragmentManager()
                .findFragmentById(R.id.frag_networks_list));
        if (findViewById(R.id.keygen_fragment) != null) {
            mTwoPane = true;
            networkListFragment.setActivateOnItemClick(true);
        }
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        wifiState = wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLED
                || wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLING;
        scanFinished = new WifiScanReceiver(wifi, networkListFragment, this);
        stateChanged = new WifiStateReceiver(wifi, networkListFragment);

        final SharedPreferences mPrefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        welcomeScreenShown = mPrefs.getBoolean(Preferences.VERSION, false);

        final long timePassed = System.currentTimeMillis()
                - mPrefs.getLong(LAST_DIALOG_TIME, 0);
        if (!welcomeScreenShown || (timePassed > DateUtils.WEEK_IN_MILLIS)) {
            final SharedPreferences.Editor editor = mPrefs.edit();
            editor.putBoolean(Preferences.VERSION, true);
            editor.putLong(LAST_DIALOG_TIME, System.currentTimeMillis());
            editor.apply();

            // Checking for updates every week
            if (BuildConfig.APPLICATION_ID.equals("org.exobel.routerkeygen")) {
                 startService(new Intent(getApplicationContext(), UpdateCheckerService.class));
            }
        }

        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swiperefresh);
        mSwipeRefreshLayout.setOnRefreshListener(
                new SwipeRefreshLayout.OnRefreshListener() {
                    @Override
                    public void onRefresh() {
                        scan();
                    }
                }
        );
        mSwipeRefreshLayout.setColorSchemeResources(R.color.accent);
    }

    @Override
    public void onItemSelected(WiFiNetwork keygen) {
        if (mTwoPane) {
            final Bundle arguments = new Bundle();
            arguments.putParcelable(NetworkFragment.NETWORK_ID, keygen);
            final NetworkFragment fragment = new NetworkFragment();
            fragment.setArguments(arguments);
            getFragmentManager().beginTransaction()
                    .replace(R.id.keygen_fragment, fragment).commit();
        } else {
            if (keygen.getSupportState() == Keygen.UNSUPPORTED) {
                Toast.makeText(this, R.string.msg_unspported,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent detailIntent = new Intent(this, NetworkActivity.class);
            detailIntent.putExtra(NetworkFragment.NETWORK_ID, keygen);
            startActivity(detailIntent);
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.networks_list, menu);
        getMenuInflater().inflate(R.menu.preferences, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.manual_input:
                if (mTwoPane) {
                    getFragmentManager()
                            .beginTransaction()
                            .replace(R.id.keygen_fragment,
                                    ManualInputFragment.newInstance()).commit();
                } else {
                    startActivity(new Intent(this, ManualInputActivity.class));
                }
            case R.id.wifi_scan:
                if (!scanPermission) {
                    Toast.makeText(this, R.string.msg_nolocationpermission, Toast.LENGTH_SHORT)
                            .show();
                    return true;
                }
                scan();
                return true;
            case R.id.pref:
                startActivity(new Intent(this, Preferences.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        getPrefs();
        if (wifiOn) {
            try {
                if (!wifi.setWifiEnabled(true))
                    networkListFragment.setMessage(R.string.msg_wifibroken);
                else
                    wifiState = true;
            } catch (SecurityException e) {
                // Workaround for
                // http://code.google.com/p/android/issues/detail?id=22036
                networkListFragment.setMessage(R.string.msg_wifibroken);
            }
        }

        scan();
    }

    @Override
    public void onResume() {
        super.onResume();
        getPrefs();

        // Here, thisActivity is the current activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
        {
            scanPermission = false;

            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    MY_PERMISSIONS_ACCESS_COARSE_LOCATION);

            // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
            // app-defined int constant. The callback method gets the
            // result of the request.

        } else {
            scanPermission = true;
        }

        if (!scanPermission) {
            networkListFragment.setMessage(R.string.msg_nolocationpermission);
            return;
        }

        scan();

        if (autoScan && scanPermission) {
            mHandler.removeCallbacks(mAutoScanTask);
            mHandler.postDelayed(mAutoScanTask, autoScanInterval * 1000L);
        } else {
            mHandler.removeCallbacks(mAutoScanTask);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            mHandler.removeCallbacks(mAutoScanTask);
        } catch (Exception e) {
            Log.e(TAG, "Exception", e);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(scanFinished);
        } catch (Exception e) {
            Log.e(TAG, "Unregister error", e);
        }

        try {
            unregisterReceiver(stateChanged);
        } catch (Exception e) {
            Log.e(TAG, "Unregister error", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_ACCESS_FINE_LOCATION:
            case MY_PERMISSIONS_ACCESS_COARSE_LOCATION:
            {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    scanPermission = true;
                    networkListFragment.updatePermission(this);
                    scan();
                }
            }
        }
    }

    public static void settingsRequest(final Activity activity, OnSuccessListener cb) {
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(100000);
        mLocationRequest.setFastestInterval(50000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_LOW_POWER);

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(mLocationRequest);
        builder.setAlwaysShow(true);

        SettingsClient client = LocationServices.getSettingsClient(activity);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(activity, cb);

        task.addOnFailureListener(activity, e -> {
            int statusCode = ((ApiException) e).getStatusCode();
            switch (statusCode) {
                case CommonStatusCodes.RESOLUTION_REQUIRED:
                    // Location settings are not satisfied, but this can be fixed
                    // by showing the user a dialog.
                    try {
                        // Show the dialog by calling startResolutionForResult(),
                        // and check the result in onActivityResult().
                        ResolvableApiException resolvable = (ResolvableApiException) e;
                        resolvable.startResolutionForResult(activity, REQUEST_CHECK_SETTINGS);
                    } catch (IntentSender.SendIntentException sendEx) {
                        // Ignore the error.
                    }
                    break;
                case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                    // Location settings are not satisfied. However, we have no way
                    // to fix the settings so we won't show the dialog.
                    break;
            }
        });
    }


    private void scan() {
        if (!wifiState && !wifiOn) {
            networkListFragment.setMessage(R.string.msg_nowifi);
            return;
        }
        if (!scanPermission) {
            return;
        }
        registerReceiver(scanFinished, new IntentFilter(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        if (wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLING) {
            registerReceiver(stateChanged, new IntentFilter(
                    WifiManager.WIFI_STATE_CHANGED_ACTION));
            Toast.makeText(this, R.string.msg_wifienabling, Toast.LENGTH_SHORT)
                    .show();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settingsRequest(this, o -> startScan());
            } else {
                startScan();
            }
        }
    }

    private void startScan() {
        if (wifi.startScan()) {
            //setRefreshActionItemState(true);
            mSwipeRefreshLayout.setRefreshing(true);
        } else
            networkListFragment.setMessage(R.string.msg_scanfailed);
    }

    private void getPrefs() {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        wifiOn = prefs.getBoolean(Preferences.wifiOnPref, getResources()
                .getBoolean(R.bool.wifiOnDefault));
        autoScan = prefs.getBoolean(Preferences.autoScanPref, getResources()
                .getBoolean(R.bool.autoScanDefault));
        autoScanInterval = prefs.getInt(Preferences.autoScanIntervalPref,
                getResources().getInteger(R.integer.autoScanIntervalDefault));
        analyticsOptIn = prefs.getBoolean(Preferences.analyticsPref,
                getResources().getBoolean(R.bool.analyticsDefault));
    }

    @Override
    public void onScanFinished(WiFiNetwork[] networks) {
        mSwipeRefreshLayout.setRefreshing(false);
        if (!welcomeScreenShown) {
            Toast.makeText(this, R.string.msg_welcome_tip, Toast.LENGTH_LONG)
                    .show();
            welcomeScreenShown = true;
        }
    }

    @Override
    public void onItemSelected(String mac) {
        if (mTwoPane) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(R.id.keygen_fragment,
                            ManualInputFragment.newInstance(mac)).commit();
        } else {
            startActivity(new Intent(this, ManualInputActivity.class).putExtra(
                    ManualInputFragment.MAC_ADDRESS_ARG, mac));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case REQUEST_CHECK_SETTINGS:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        scan();
                        break;
                    case Activity.RESULT_CANCELED:
                        break;
                    default:
                        break;
                }
                break;
        }
    }
}
