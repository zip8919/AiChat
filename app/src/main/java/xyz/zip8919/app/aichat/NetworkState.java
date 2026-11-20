package xyz.zip8919.app.aichat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/**
 * 网络可用性：判断「现在能不能上网」，并在状态变化时回调。
 *
 * <p>为什么不用 {@code registerDefaultNetworkCallback}：那是 API 24 才有的，
 * 本项目 minSdk 18、目标机型是安卓 4.4 的词典笔，只能用广播方式。
 *
 * <p>为什么不只信 {@code CONNECTIVITY_ACTION}：部分定制 ROM（尤其是词典笔这类
 * 精简系统）在飞行模式、WiFi 掉线时不发这条广播，横幅会一直停在「有网」状态。
 * 所以界面侧另有一个低频轮询兜底，见 {@code MainActivity} 的 offlineTick。
 */
final class NetworkState {

    private static final String TAG = "NetworkState";

    /** 这些 action 任一到达都意味着网络可能变了，统一重算一次。 */
    private static final String[] ACTIONS = {
            ConnectivityManager.CONNECTIVITY_ACTION,
            "android.net.wifi.WIFI_STATE_CHANGED",
            "android.net.wifi.STATE_CHANGE",
    };

    private NetworkState() {
    }

    /**
     * 当前是否有可用网络。
     *
     * <p>{@code getActiveNetworkInfo()} 在 API 19 上可用，返回 null 表示完全没有连接
     * （飞行模式、无 SIM 且无 WiFi）。连着但需要网页认证的 WiFi 这里算「有网」——
     * 那种情况请求会正常失败并显示错误卡片，比误报「无网络」更准确。
     */
    static boolean isOnline(Context ctx) {
        if (ctx == null) return true; // 拿不到上下文时不要误报断网
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            // 部分 ROM 未授予 ACCESS_NETWORK_STATE 时抛 SecurityException：
            // 读不到就当有网，不要让横幅一直挂着
            LogUtil.w(TAG, "isOnline failed: %s", t);
            return true;
        }
    }

    /**
     * 注册网络变化监听，返回注销用的 receiver。
     *
     * <p>会先记一次当前状态：注册当下就是断网时，回调必须能被触发，
     * 否则用户打开应用那一刻的断网状态永远画不出来。
     */
    static BroadcastReceiver listen(Context ctx, final Runnable onChange) {
        final BroadcastReceiver recv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (onChange != null) onChange.run();
            }
        };
        try {
            IntentFilter f = new IntentFilter();
            for (String a : ACTIONS) f.addAction(a);
            ctx.registerReceiver(recv, f);
        } catch (Throwable t) {
            LogUtil.w(TAG, "listen failed: %s", t);
        }
        return recv;
    }

    /** 注销；没注册成功或被重复注销都不抛。 */
    static void unlisten(Context ctx, BroadcastReceiver recv) {
        if (ctx == null || recv == null) return;
        try {
            ctx.unregisterReceiver(recv);
        } catch (Throwable t) {
            LogUtil.d(TAG, "unlisten: %s", t);
        }
    }


}
