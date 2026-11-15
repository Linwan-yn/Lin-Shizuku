package rikka.rish;

import android.util.Log;

import java.util.Arrays;

public class Rish {
    private static final String TAG = "RISH";

    public void requestPermission(Runnable onGrantedRunnable) {
        // 1.1.2：授权改由服务端在 rish 事务入口弹窗并同步返回结果，客户端不再自己请求。
        // 这里原本是空实现，onGrantedRunnable 永远不会被调用，startShell(args, false)
        // 就卡在等待授权，表现是 rish 既不弹窗也不执行。
        onGrantedRunnable.run();
    }

    private void startShell(String[] args, boolean permissionGranted) {
        if (!permissionGranted) {
            requestPermission(() -> startShell(args, true));
            return;
        }
        startShell(args);
    }

    private void startShell(String[] args) {
        try {
            RishTerminal terminal = new RishTerminal(args);
            terminal.start();
            int exitCode = terminal.waitFor();
            System.exit(exitCode);
        } catch (Throwable e) {
            System.err.println(e.getMessage());
            System.err.flush();
            System.exit(1);
            //abort(e.getMessage());
        }
    }

    public void start(String[] args) {
        Log.d(TAG, "args: " + Arrays.toString(args));
        startShell(args, false);
    }
}
