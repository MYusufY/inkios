package tech.tachion.inkios;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import tech.tachion.inkios.data.Apps;
import tech.tachion.inkios.data.Book;
import tech.tachion.inkios.data.Covers;
import tech.tachion.inkios.data.KOReader;
import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.HomeView;
import tech.tachion.inkios.ui.LibraryView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity implements HomeView.Host, LibraryView.Host {
    private static final String[][] DOCK = {
            {KOReader.PACKAGE},
            {"tech.tachion.inkios.notes", "com.steadfastinnovation.android.projectpapyrus"},
            {"tech.tachion.inkios.browser", "com.android.browser"},
            {"com.android.settings"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private HomeView home;
    private LibraryView library;
    private List<Apps.App> apps = new ArrayList<>();
    private boolean libraryShown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        home = new HomeView(this, this);
        library = new LibraryView(this, this, home.status);
        FrameLayout root = new FrameLayout(this);
        root.addView(home);
        root.addView(library);
        library.setVisibility(View.GONE);
        setContentView(root);
        hideSystemBar();
        root.setOnSystemUiVisibilityChangeListener(v -> hideSystemBar());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.RSSI_CHANGED_ACTION);
        registerReceiver(system, f);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);

        readWifi();
        loadApps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        io.execute(() -> tech.tachion.inkios.sleep.SleepScreen.render(this));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(system);
        unregisterReceiver(packages);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        home.updateClock();
        loadReading(true);
        hideSystemBar();
    }

    @SuppressWarnings("InlinedApi")
    private void hideSystemBar() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void loadApps() {
        io.execute(() -> {
            final List<Apps.App> list = Apps.load(this);
            final List<Apps.App> dock = new ArrayList<>();
            for (String[] slot : DOCK) {
                for (String p : slot) {
                    Apps.App a = Apps.find(list, p);
                    if (a != null) { dock.add(a); break; }
                }
            }
            main.post(() -> {
                apps = list;
                home.setDock(dock);
                library.setApps(list);
            });
        });
    }

    private void loadReading(final boolean thenFullRefresh) {
        io.execute(() -> {
            final List<Book> books = KOReader.history(5);
            Bitmap cover = null;
            final List<Bitmap> covers = new ArrayList<>();
            if (!books.isEmpty()) {
                int[] hs = home.heroCoverSize();
                cover = Covers.load(this, books.get(0), hs[0], hs[1]);
                int[] ss = home.shelfCoverSize();
                for (int i = 1; i < books.size(); i++) covers.add(Covers.load(this, books.get(i), ss[0], ss[1]));
            }
            final KOReader.Stats stats = KOReader.stats(this);
            final Bitmap heroCover = cover;
            main.post(() -> {
                home.quiet = thenFullRefresh;
                home.setBooks(books, heroCover, covers);
                home.setStats(stats);
                home.quiet = false;
                if (thenFullRefresh && !libraryShown) Eink.fullRefreshSoon(home, 60);
            });
        });
    }

    private final BroadcastReceiver system = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int pct = level < 0 ? -1 : Math.round(100f * level / Math.max(1, scale));
                boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL;
                if (pct != home.status.batteryPercent || charging != home.status.charging) {
                    home.status.batteryPercent = pct;
                    home.status.charging = charging;
                    refreshStatus();
                }
            } else if (a != null && a.startsWith("android.net.wifi")) {
                if (readWifi()) refreshStatus();
            } else {
                home.updateClock();
                if (libraryShown) library.invalidate();
            }
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    private void refreshStatus() {
        if (libraryShown) library.invalidate(); else home.updateStatus();
    }

    private boolean readWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        boolean enabled = wm.isWifiEnabled();
        WifiInfo info = wm.getConnectionInfo();
        boolean connected = enabled && info != null && info.getNetworkId() != -1
                && info.getSupplicantState() != null
                && info.getSupplicantState().toString().equals("COMPLETED");
        int level = connected ? WifiManager.calculateSignalLevel(info.getRssi(), 4) : 0;
        level = Math.max(connected ? 1 : 0, level);
        boolean changed = enabled != home.status.wifiEnabled || connected != home.status.wifiConnected
                || level != home.status.wifiLevel;
        home.status.wifiEnabled = enabled;
        home.status.wifiConnected = connected;
        home.status.wifiLevel = level;
        return changed;
    }

    @Override public void openBook(Book b) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(b.file), b.mimeType());
        i.setPackage(KOReader.PACKAGE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            openReader();
        }
    }

    @Override public void openReader() {
        Intent i = getPackageManager().getLaunchIntentForPackage(KOReader.PACKAGE);
        if (i == null) {
            Toast.makeText(this, "KOReader is not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(i);
    }

    @Override public void launch(Apps.App app) {
        try {
            startActivity(app.launchIntent());
        } catch (Throwable t) {
            Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void appInfo(Apps.App app) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Throwable ignored) {}
    }

    @Override public void showLibrary() {
        if (libraryShown) return;
        libraryShown = true;
        library.resetPage();
        library.setVisibility(View.VISIBLE);
        home.setVisibility(View.GONE);
        Eink.refresh(library, Eink.Mode.CLEAN);
    }

    @Override public void showHome() {
        libraryShown = false;
        home.setVisibility(View.VISIBLE);
        library.setVisibility(View.GONE);
        Eink.refresh(home, Eink.Mode.CLEAN);
    }

    @Override public void requestFullRefresh() {
        Eink.fullRefresh(home);
    }
}
