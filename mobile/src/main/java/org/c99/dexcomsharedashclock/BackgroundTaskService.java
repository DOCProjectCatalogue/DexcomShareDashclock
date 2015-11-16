package org.c99.dexcomsharedashclock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.GcmTaskService;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.TaskParams;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * Created by sam on 11/15/15.
 */
public class BackgroundTaskService extends GcmTaskService {
    private static final String TASK_GLUCOSE = "org.c99.TASK_GLUCOSE";

    public static void scheduleGlucoseSync(Context context) {
        GcmNetworkManager.getInstance(context).cancelAllTasks(BackgroundTaskService.class);
        GcmNetworkManager.getInstance(context).schedule(new PeriodicTask.Builder()
                        .setTag(TASK_GLUCOSE)
                        .setPeriod(300)
                        .setPersisted(true)
                        .setService(BackgroundTaskService.class)
                        .build()
        );
    }

    public static int runGlucoseSync(Context context) {
        Dexcom dexcom = new Dexcom();

        try {
            String token = PreferenceManager.getDefaultSharedPreferences(context).getString("token", "");
            if(token.length() > 0) {
                JSONArray a = dexcom.latestGlucoseValues(token, 1440, 1);
                if(a != null && a.length() > 0) {
                    JSONObject o = a.getJSONObject(0);
                    final int trend = o.getInt("Trend");
                    final int value = o.getInt("Value");
                    String WT = o.getString("WT");
                    final long time = Long.valueOf(WT.substring(6, WT.length() - 2));

                    SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
                    editor.putInt("Trend", trend);
                    editor.putInt("Value", value);
                    editor.putLong("Time", time);
                    editor.commit();

                    final GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                            .addApi(Wearable.API)
                            .build();

                    ConnectionResult connectionResult = googleApiClient.blockingConnect(30, TimeUnit.SECONDS);

                    if (!connectionResult.isSuccess()) {
                        Log.e("DexcomShareDashclock", "Failed to connect to GoogleApiClient.");
                        return GcmNetworkManager.RESULT_FAILURE;
                    }

                    DataMap map = new DataMap();
                    map.putInt("trend", trend);
                    map.putInt("value", value);
                    map.putLong("time", time);

                    NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes( googleApiClient ).await();
                    for(Node node : nodes.getNodes()) {
                        Wearable.MessageApi.sendMessage(googleApiClient, node.getId(), "/latest_glucose", map.toByteArray() ).await();
                    }

                    googleApiClient.disconnect();
                    return GcmNetworkManager.RESULT_SUCCESS;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return GcmNetworkManager.RESULT_RESCHEDULE;
        }
        return GcmNetworkManager.RESULT_FAILURE;
    }

    @Override
    public int onRunTask(TaskParams taskParams) {
        if(taskParams.getTag().equals(TASK_GLUCOSE)) {
            return runGlucoseSync(this);
        }
        return GcmNetworkManager.RESULT_FAILURE;
    }
}