package com.streamvault.nativefeed;

import com.alibaba.fastjson.JSONObject;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeFeedLaunchOptionsTest {
    @Test
    public void allowsBackendOnlyLaunchWhenServerConfigPresent() {
        JSONObject options = new JSONObject();
        options.put("serveraddr", "http://host");
        options.put("serverport", "28081");
        options.put("servertoken", "tok");

        assertTrue(NativeFeedLaunchOptions.hasLaunchData(options));
    }

    @Test
    public void rejectsLaunchWithoutVideosOrServerConfig() {
        JSONObject options = new JSONObject();

        assertFalse(NativeFeedLaunchOptions.hasLaunchData(options));
    }
}
