package com.streamvault.nativefeed;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class StreamVaultNativeVideoModule extends UniModule {
    private static final String TAG = "StreamVault-NativeVideo";

    @UniJSMethod(uiThread = true)
    public JSONObject ping(JSONObject options, UniJSCallback callback) {
        JSONObject result = new JSONObject();
        Context context = getContext();
        if (context == null) {
            result.put("ok", false);
            result.put("reason", "context_unavailable");
            invoke(callback, result);
            return result;
        }
        result.put("ok", true);
        Toast.makeText(context, "native ping", Toast.LENGTH_SHORT).show();
        invoke(callback, result);
        return result;
    }

    @UniJSMethod(uiThread = true)
    public JSONObject openFeed(JSONObject options, UniJSCallback callback) {
        JSONObject result = new JSONObject();
        try {
            Context context = getContext();
            Activity activity = findActivity(context);
            if (activity == null) {
                result.put("ok", false);
                result.put("reason", "activity_unavailable");
                invoke(callback, result);
                return result;
            }
            if (!NativeFeedLaunchOptions.hasLaunchData(options)) {
                result.put("ok", false);
                result.put("reason", "empty_videos");
                invoke(callback, result);
                return result;
            }
            Intent intent = new Intent(activity, NativeVideoFeedActivity.class);
            intent.putExtra("options", options == null ? "{}" : options.toJSONString());
            Toast.makeText(activity, "打开原生播放", Toast.LENGTH_SHORT).show();
            activity.startActivity(intent);
            result.put("ok", true);
            invoke(callback, result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "openFeed failed", e);
            result.put("ok", false);
            result.put("reason", "open_failed");
            invoke(callback, result);
            return result;
        }
    }

    private Context getContext() {
        return mUniSDKInstance == null ? null : mUniSDKInstance.getContext();
    }

    private void invoke(UniJSCallback callback, JSONObject result) {
        if (callback != null) {
            callback.invoke(result);
        }
    }

    private Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
