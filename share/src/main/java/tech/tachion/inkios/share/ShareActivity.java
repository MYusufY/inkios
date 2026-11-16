package tech.tachion.inkios.share;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.StatusBar;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class ShareActivity extends Activity implements Server.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ShareView view;
    private Server server;
    private WifiManager.WifiLock wifiLock;

    String url;
    String status = "";
    final List<String> received = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new ShareView(this);
        setContentView(view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Eink.fullRefreshSoon(view, 80);
    }

    @Override
    protected void onStop() {
        stopServer();
        super.onStop();
    }

    private void startServer() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = wm != null && wm.getConnectionInfo() != null ? wm.getConnectionInfo().getIpAddress() : 0;
        if (ip == 0) {
            url = null;
            status = "Connect to Wi-Fi to share books.";
            view.rebuild();
            return;
        }
        String host = String.format(Locale.US, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        File dir = new File(Environment.getExternalStorageDirectory(), "Books");
        dir.mkdirs();
        server = new Server(getAssets(), dir, newKey(), this);
        try {
            int port = server.start(8080);
            url = "http://" + host + ":" + port + "/?k=" + keyOf(server);
            status = "Waiting for books…";
            if (BuildConfig.DEBUG) android.util.Log.d("InkiosShare", "serving " + url);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "inkios-share");
            wifiLock.acquire();
        } catch (Exception e) {
            url = null;
            status = "Couldn't start sharing: " + e.getMessage();
        }
        view.rebuild();
    }

    private String key;

    private String newKey() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        key = sb.toString();
        return key;
    }

    private String keyOf(Server s) { return key; }

    private void stopServer() {
        if (server != null) server.stop();
        server = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override
    public void onTransfer(final String name, final long done, final long total) {
        main.post(() -> {
            status = "Receiving “" + name + "”  " + (total > 0 ? (done * 100 / total) + "%" : "");
            view.statusChanged();
        });
    }

    @Override
    public void onReceived(final String name) {
        main.post(() -> {
            received.add(0, name);
            while (received.size() > 5) received.remove(received.size() - 1);
            status = "Received “" + name + "”";
            view.listChanged();
        });
    }

    @Override
    public void onDeleted(final String name) {
        main.post(() -> {
            status = "Deleted “" + name + "”";
            view.statusChanged();
        });
    }

    final class ShareView extends TapView {
        private final StatusBar statusBar;
        private final StatusBar.State st = new StatusBar.State();
        private final Paint title, lead, addr, step, stepNum, statusP, section, item;
        private final Paint edge, fill;
        private Bitmap qr;
        private final RectF qrR = new RectF(), statusR = new RectF(), listR = new RectF();

        ShareView(Context c) {
            super(c);
            statusBar = new StatusBar(t);
            title = t.textPaint(t.displayBold, 36, Theme.INK);
            lead = t.textPaint(t.textMedium, 16, Theme.SECONDARY);
            addr = t.textPaint(t.displaySemibold, 20, Theme.INK);
            addr.setTextAlign(Paint.Align.CENTER);
            step = t.textPaint(t.textMedium, 16, Theme.INK);
            stepNum = t.textPaint(t.textSemibold, 14, Theme.PAPER);
            stepNum.setTextAlign(Paint.Align.CENTER);
            statusP = t.textPaint(t.textSemibold, 16, Theme.INK);
            section = t.textPaint(t.displaySemibold, 17, Theme.SECONDARY);
            item = t.textPaint(t.textMedium, 15, Theme.INK);
            edge = t.stroke(Theme.SEPARATOR, 1.5f);
            fill = t.fill(Theme.FILL);
        }

        void rebuild() {
            if (getWidth() == 0) return;
            layoutAndQr();
            invalidate();
            Eink.refresh(this, Eink.Mode.CLEAN);
        }

        void statusChanged() { refreshRect(statusR, Eink.Mode.TEXT); }

        void listChanged() {
            refreshRect(statusR, Eink.Mode.TEXT);
            refreshRect(listR, Eink.Mode.TEXT);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutAndQr();
        }

        private void layoutAndQr() {
            int w = getWidth(), h = getHeight();
            float size = t.px(264);
            qrR.set((w - size) / 2f, t.px(150), (w + size) / 2f, t.px(150) + size);
            statusR.set(0, qrR.bottom + t.px(48), w, qrR.bottom + t.px(88));
            float stepsEnd = statusR.bottom + t.px(30) + 3 * t.px(40);
            listR.set(t.px(22), stepsEnd, w - t.px(22), h - t.px(12));
            if (qr != null) { qr.recycle(); qr = null; }
            if (url != null) qr = encode(url, Math.round(size));
        }

        private Bitmap encode(String text, int px) {
            try {
                Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
                hints.put(EncodeHintType.MARGIN, 2);
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
                int n = m.getWidth();
                int cell = Math.max(1, px / n), side = cell * n;
                int[] pixels = new int[side * side];
                for (int y = 0; y < side; y++) {
                    for (int x = 0; x < side; x++) {
                        pixels[y * side + x] = m.get(x / cell, y / cell) ? 0xFF000000 : 0xFFFFFFFF;
                    }
                }
                return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void drawContent(Canvas c) {
            int w = getWidth();
            float m = t.px(22);
            statusBar.draw(c, st, w);
            c.drawText("Share", m, t.px(96), title);
            c.drawText("Send books from your phone or computer", m, t.px(126), lead);

            if (qr != null) {
                float l = qrR.centerX() - qr.getWidth() / 2f, tp = qrR.centerY() - qr.getHeight() / 2f;
                c.drawBitmap(qr, l, tp, null);
                String shown = url.replace("http://", "");
                int k = shown.indexOf("/?k=");
                c.drawText(k > 0 ? shown.substring(0, k) : shown, w / 2f, qrR.bottom + t.px(34), addr);
            } else {
                Draw.roundRect(c, qrR.left, qrR.top, qrR.right, qrR.bottom, t.px(22), fill);
            }

            Paint sp = statusP;
            float sw = sp.measureText(status);
            Draw.text(c, status, Math.max(m, (w - sw) / 2f), statusR.centerY() + t.px(6), w - 2 * m, sp);

            float y = statusR.bottom + t.px(30);
            String[] steps = {"Connect your phone to the same Wi-Fi",
                    "Scan the code with your phone’s camera",
                    "Choose books. They land in the Books folder"};
            for (int i = 0; i < steps.length; i++) {
                float cx = m + t.px(13), cy = y + i * t.px(40);
                c.drawCircle(cx, cy - t.px(5), t.px(12), t.fill(Theme.INK));
                c.drawText(String.valueOf(i + 1), cx, cy, stepNum);
                Draw.text(c, steps[i], m + t.px(36), cy, w - 2 * m - t.px(36), step);
            }

            if (!received.isEmpty()) {
                c.drawText("Received", listR.left, listR.top + t.px(18), section);
                int fit = Math.max(0, (int) ((listR.height() - t.px(40)) / t.px(32)));
                for (int i = 0; i < received.size() && i < fit; i++) {
                    float ry = listR.top + t.px(50) + i * t.px(32);
                    Draw.text(c, received.get(i), listR.left, ry, listR.width(), item);
                    c.drawRect(listR.left, ry + t.px(11), listR.right, ry + t.px(12), t.fill(Theme.SEPARATOR));
                }
            }
            Draw.roundRect(c, qrR.left - t.px(10), qrR.top - t.px(10), qrR.right + t.px(10), qrR.bottom + t.px(10),
                    t.px(18), edge);
        }
    }
}
