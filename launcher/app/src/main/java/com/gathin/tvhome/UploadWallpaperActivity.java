package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Shows the address and PIN for sending a wallpaper from a phone, and runs WallpaperUploadServer while it is
 * open. The server stops when this screen closes (Back, Home) or after TIMEOUT_MS, whichever comes first.
 */
public final class UploadWallpaperActivity extends Activity {
    private static final long TIMEOUT_MS = 10 * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WallpaperUploadServer server;
    private TextView status;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_upload_wallpaper);
        UiFont.apply(this, findViewById(android.R.id.content));   // the font chosen in Launcher settings
        status = findViewById(R.id.uploadStatus);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TextView address = findViewById(R.id.uploadAddress);
        TextView pinView = findViewById(R.id.uploadPin);
        String ip = WallpaperUploadServer.localAddress();
        if (ip == null) {
            address.setText("—");
            status.setText(R.string.upload_no_network);
            return;
        }
        String pin = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
        server = new WallpaperUploadServer(this, pin, () -> main.post(() ->
                status.setText(R.string.upload_received)));
        try {
            int port = server.start();
            address.setText("http://" + ip + ":" + port);
            pinView.setText(pin);
            status.setText(R.string.upload_waiting);
            main.postDelayed(this::finish, TIMEOUT_MS);
        } catch (IOException e) {
            server = null;
            status.setText(getString(R.string.upload_failed, String.valueOf(e.getMessage())));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        server = null;
        if (!isFinishing()) finish();
    }
}
