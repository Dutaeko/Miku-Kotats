package moe.miku.app.parser;

import android.content.Context;
import okhttp3.OkHttpClient;

public final class NetworkDohManager {
    private NetworkDohManager() {}

    public static void init(Context context) {}

    public static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        return builder == null ? new OkHttpClient.Builder() : builder;
    }

    public static void refresh() {}
}
