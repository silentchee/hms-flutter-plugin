/*
Copyright (c) Huawei Technologies Co., Ltd. 2012-2020. All rights reserved.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.huawei.hms.flutter.push.hms;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import com.huawei.hms.flutter.push.PushPlugin;
import com.huawei.hms.flutter.push.constants.PushIntent;
import com.huawei.hms.flutter.push.utils.Utils;
import com.huawei.hms.push.HmsMessageService;
import com.huawei.hms.push.RemoteMessage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.FlutterCallbackInformation;
import io.flutter.view.FlutterMain;
import io.flutter.view.FlutterNativeView;
import io.flutter.view.FlutterRunArguments;

/**
 * class FlutterHmsMessageService
 *
 * @since 4.0.4
 */
public class FlutterHmsMessageService extends HmsMessageService {
    private static List<String> backgroundMessageQueue =
            Collections.synchronizedList(new LinkedList<String>());
    private static AtomicBoolean isIsolateRunning = new AtomicBoolean(false);
    private static final String TAG = "MyPushService";
    private static FlutterNativeView backgroundFlutterView;
    private static PluginRegistry.PluginRegistrantCallback pluginRegistrantCallback;
    private static MethodChannel backgroundChannel;

    private static final String SHARED_PREFERENCES_KEY = "io.flutter.android_hms_plugin";
    private static final String BACKGROUND_MESSAGE_CALLBACK_HANDLE_KEY =
            "background_message_callback";
    private static final String BACKGROUND_MESSAGE_DISPATCHER_HANDLE_KEY =
            "background_message_dispatcher";


    private static Context backgroundContext;


    public static void setBackgroundChannel(MethodChannel channel) {
        backgroundChannel = channel;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        backgroundContext = getApplicationContext();
        FlutterMain.ensureInitializationComplete(backgroundContext, null);

//         If background isolate is not running start it.
        if (!isIsolateRunning.get()) {
            SharedPreferences p = backgroundContext.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
            long callbackHandle = p.getLong(BACKGROUND_MESSAGE_DISPATCHER_HANDLE_KEY, 0);
            startBackgroundIsolate(backgroundContext, callbackHandle);
        }
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "token received");
        Utils.sendIntent(backgroundContext, PushIntent.TOKEN_INTENT_ACTION, PushIntent.TOKEN, token);
    }
    public static void setBackgroundMessageHandle(Context context, Long handle) {

        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
        prefs.edit().putLong(BACKGROUND_MESSAGE_CALLBACK_HANDLE_KEY, handle).apply();
    }


    public static Long getBackgroundMessageHandle(Context context) {
        return context
                .getSharedPreferences(SHARED_PREFERENCES_KEY, 0)
                .getLong(BACKGROUND_MESSAGE_CALLBACK_HANDLE_KEY, 0);
    }

    public static void setBackgroundMessageDispatcher(Context context, Long handle) {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_KEY, 0);
        prefs.edit().putLong(BACKGROUND_MESSAGE_DISPATCHER_HANDLE_KEY, handle).apply();
    }


    public static Long getBackgroundMessageDispatcher(Context context) {
        return context
                .getSharedPreferences(SHARED_PREFERENCES_KEY, 0)
                .getLong(BACKGROUND_MESSAGE_DISPATCHER_HANDLE_KEY, 0);
    }

    public static void startBackgroundIsolate(Context context, long callbackHandle) {
        FlutterMain.ensureInitializationComplete(context, null);
        String appBundlePath = FlutterMain.findAppBundlePath();
        FlutterCallbackInformation flutterCallback =
                FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
        if (flutterCallback == null) {
            Log.e(TAG, "Fatal: failed to find callback");
            return;
        }

        // Note that we're passing `true` as the second argument to our
        // FlutterNativeView constructor. This specifies the FlutterNativeView
        // as a background view and does not create a drawing surface.
        backgroundFlutterView = new FlutterNativeView(context, true);
        if (appBundlePath != null && !isIsolateRunning.get()) {
            if (pluginRegistrantCallback == null) {
                throw new RuntimeException("PluginRegistrantCallback is not set.");
            }
            FlutterRunArguments args = new FlutterRunArguments();
            args.bundlePath = appBundlePath;
            args.entrypoint = flutterCallback.callbackName;
            args.libraryPath = flutterCallback.callbackLibraryPath;
            backgroundFlutterView.runFromBundle(args);
            pluginRegistrantCallback.registerWith(backgroundFlutterView.getPluginRegistry());
        }
        backgroundChannel =new  MethodChannel(backgroundFlutterView,
                "plugins.flutter.io/hms_background");
        backgroundChannel.setMethodCallHandler(new PushPlugin());
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        if (remoteMessage.getData().length() > 0) {
            Context context = getApplicationContext();
            if(isApplicationForeground(context)) {
                Utils.sendIntent(backgroundContext, PushIntent.DATA_MESSAGE_INTENT_ACTION, PushIntent.DATA_MESSAGE, remoteMessage.getData());
                Log.d(TAG, "Message data received");
            }else{
                if (!isIsolateRunning.get()) {
                    backgroundMessageQueue.add(remoteMessage.getData());
                } else {
                    runBackgroundHandle(context, remoteMessage.getData());
                }
            }
        }
    }

    @Override
    public void onMessageSent(String s) {
        super.onMessageSent(s);
    }

    @Override
    public void onSendError(String s, Exception e) {
        super.onSendError(s, e);
    }

    public static void setPluginRegistrant(PluginRegistry.PluginRegistrantCallback callback) {
        pluginRegistrantCallback = callback;
    }

    private static boolean isApplicationForeground(Context context) {
        KeyguardManager keyguardManager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

        if (keyguardManager.isKeyguardLocked()) {
            return false;
        }
        int myPid = Process.myPid();

        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        List<ActivityManager.RunningAppProcessInfo> list;

        if ((list = activityManager.getRunningAppProcesses()) != null) {
            for (ActivityManager.RunningAppProcessInfo aList : list) {
                ActivityManager.RunningAppProcessInfo info;
                if ((info = aList).pid == myPid) {
                    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        }
        return false;
    }

    public static void onInitialized() {
        isIsolateRunning.set(true);
        synchronized (backgroundMessageQueue) {
            // Handle all the messages received before the Dart isolate was
            // initialized, then clear the queue.
            Iterator<String> i = backgroundMessageQueue.iterator();
            while (i.hasNext()) {
                runBackgroundHandle(backgroundContext, i.next());
            }
            backgroundMessageQueue.clear();
        }
    }
    public static void runBackgroundHandle(Context context, String msg){
        if (backgroundFlutterView != null) {
            // Grab the callback handle for the callback dispatcher from
            // storage.
            long callbackHandle = getBackgroundMessageHandle(context);

            // Retrieve the actual callback information needed to invoke it.
            FlutterCallbackInformation callbackInfo = FlutterCallbackInformation.
                    lookupCallbackInformation(callbackHandle);

            if (callbackInfo == null) {
                Log.e(TAG, "Fatal: failed to find callback");
                return;
            }

            Map<String, Object> arg = new HashMap<>();
            arg.put("handle", callbackHandle);
            arg.put("message", msg);

            backgroundChannel.invokeMethod("handleBackgroundMessage", arg);
        }
    }

}
