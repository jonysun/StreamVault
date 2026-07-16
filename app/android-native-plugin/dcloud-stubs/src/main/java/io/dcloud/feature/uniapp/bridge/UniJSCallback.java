package io.dcloud.feature.uniapp.bridge;

public interface UniJSCallback {
    void invoke(Object data);

    default void invokeAndKeepAlive(Object data) {
        invoke(data);
    }
}
