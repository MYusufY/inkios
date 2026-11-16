package tech.tachion.inkios.data;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Apps {
    public static final class App {
        public final ComponentName component;
        public final String label;
        private Bitmap icon;
        private final ResolveInfo info;

        App(ResolveInfo ri, String label) {
            this.info = ri;
            this.label = label;
            this.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        }

        public String packageName() { return component.getPackageName(); }

        public Bitmap icon(Context c, int sizePx) {
            if (icon != null && icon.getWidth() == sizePx) return icon;
            Drawable d;
            try {
                d = info.activityInfo.loadIcon(c.getPackageManager());
            } catch (Throwable t) {
                d = c.getPackageManager().getDefaultActivityIcon();
            }
            Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(cv);
            icon = Covers.toEinkGrey(b);
            b.recycle();
            return icon;
        }

        public Intent launchIntent() {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(component);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return i;
        }
    }

    private static final Map<String, String> RENAME = new HashMap<>();
    static {
        RENAME.put("com.zhangyue.iReader.Eink", "iReader Store");
        RENAME.put("com.steadfastinnovation.android.projectpapyrus", "Squid");
        RENAME.put("org.ds.simple.ink.launcher", "Simple Ink");
    }

    private Apps() {}

    public static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            String label = RENAME.get(pkg);
            if (label == null) label = String.valueOf(ri.loadLabel(pm));
            out.add(new App(ri, label));
        }
        final Collator col = Collator.getInstance();
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        return out;
    }

    public static App find(List<App> apps, String pkg) {
        for (App a : apps) if (a.packageName().equals(pkg)) return a;
        return null;
    }
}
