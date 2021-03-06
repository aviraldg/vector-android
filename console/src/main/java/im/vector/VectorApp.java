/*
 * Copyright 2014 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector;
import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.analytics.ExceptionParser;
import com.google.android.gms.analytics.GoogleAnalytics;

import im.vector.activity.CommonActivityUtils;
import im.vector.contacts.ContactsManager;
import im.vector.contacts.PIDsRetriever;
import im.vector.ga.Analytics;
import im.vector.services.EventStreamService;
import im.vector.util.LogUtilities;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The main application injection point
 */
public class VectorApp extends Application {
    private static final String LOG_TAG = "ConsoleApplication";

    private Timer mActivityTransitionTimer;
    private TimerTask mActivityTransitionTimerTask;
    public boolean isInBackground = true;
    private final long MAX_ACTIVITY_TRANSITION_TIME_MS = 2000;

    // google analytics
    private static GoogleAnalytics sGoogleAnalytics;
    private int VERSION_BUILD = -1;
    private String VERSION_STRING = "";

    @Override
    public void onCreate() {
        super.onCreate();
        mActivityTransitionTimer = null;
        mActivityTransitionTimerTask = null;

        try {
            PackageInfo pinfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION_BUILD = pinfo.versionCode;
            VERSION_STRING = pinfo.versionName;
        }
        catch (PackageManager.NameNotFoundException e) {}

        LogUtilities.setLogDirectory(new File(getCacheDir().getAbsolutePath()+"/logs"));
        LogUtilities.storeLogcat();

        initGoogleAnalytics();

        // reset the application badge at application launch
        CommonActivityUtils.updateUnreadMessagesBadge(this, 0);

        // get the contact update at application launch
        ContactsManager.refreshLocalContactsSnapshot(this);

        isInBackground = false;
    }

    public void startActivityTransitionTimer() {

        // reset the application badge when displaying a new activity
        // when the user taps on a notification, it is the first called method.
        CommonActivityUtils.updateUnreadMessagesBadge(this, 0);

        this.mActivityTransitionTimer = new Timer();
        this.mActivityTransitionTimerTask = new TimerTask() {
            public void run() {
                VectorApp.this.isInBackground = true;

                // suspend the events thread if the client uses GCM
                if (Matrix.getInstance(VectorApp.this).getSharedGcmRegistrationManager().useGCM()) {
                    CommonActivityUtils.pauseEventStream(VectorApp.this);
                }
                PIDsRetriever.getIntance().onAppBackgrounded();

                MyPresenceManager.advertiseAllUnavailable();
            }
        };

        this.mActivityTransitionTimer.schedule(mActivityTransitionTimerTask, MAX_ACTIVITY_TRANSITION_TIME_MS);
    }

    public void stopActivityTransitionTimer() {
        if (this.mActivityTransitionTimerTask != null) {
            this.mActivityTransitionTimerTask.cancel();
        }

        if (this.mActivityTransitionTimer != null) {
            this.mActivityTransitionTimer.cancel();
        }

        if (isInBackground) {
            // resume the events thread if the client uses GCM
            if (Matrix.getInstance(VectorApp.this).getSharedGcmRegistrationManager().useGCM()) {

                // the event stream service has been killed
                if (null == EventStreamService.getInstance()) {
                    CommonActivityUtils.startEventStreamService(VectorApp.this);
                } else {
                    CommonActivityUtils.resumeEventStream(VectorApp.this);
                }
            }

            // get the contact update at application launch
            ContactsManager.refreshLocalContactsSnapshot(this);
        }

        MyPresenceManager.advertiseAllOnline();

        this.isInBackground = false;
    }

    static private Activity mCurrentActivity = null;
    public static void setCurrentActivity(Activity activity) {
        mCurrentActivity = activity;
    }
    public static Activity getCurrentActivity() { return mCurrentActivity; }

    /**
     * Return true if the application is in background.
     */
    public static boolean isAppInBackground() {
        if (mCurrentActivity != null) {
            return ((VectorApp)(mCurrentActivity.getApplication())).isInBackground;
        }

        return true;
    }

    private void initGoogleAnalytics() {
        // pull tracker resource ID from res/values/analytics.xml
        int trackerResId = getResources().getIdentifier("ga_trackingId", "string", getPackageName());
        if (trackerResId == 0) {
            Log.e(LOG_TAG, "Unable to find tracker id for Google Analytics");
            return;
        }

        String trackerId = getString(trackerResId);
        Log.d(LOG_TAG, "Tracker ID: "+trackerId);
        // init google analytics with this tracker ID
        if (!TextUtils.isEmpty(trackerId)) {
            Analytics.initialiseGoogleAnalytics(this, trackerId, new ExceptionParser() {
                @Override
                public String getDescription(String threadName, Throwable throwable) {
                    StringBuilder b = new StringBuilder();
                    b.append("Build : " + VERSION_BUILD + "\n");
                    b.append("Version : " + VERSION_STRING + "\n");
                    b.append("Phone : " + Build.MODEL.trim() + " (" + Build.VERSION.INCREMENTAL + " " + Build.VERSION.RELEASE + " " + Build.VERSION.CODENAME + ")\n");
                    b.append("Thread: ");
                    b.append(threadName);

                    Activity a = VectorApp.getCurrentActivity();
                    if (a != null) {
                        b.append(", Activity:");
                        b.append(a.getLocalClassName());
                    }

                    b.append(", Exception: ");
                    b.append(Analytics.getStackTrace(throwable));
                    Log.e("FATAL EXCEPTION", b.toString());
                    return b.toString();
                }
            });
        }
    }
}

