package dev.stillhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Finds exported TV apps, then ordinary launchable apps as a fallback. */
final class AppCatalog {
    private static final String[] KNOWN_TV_APPS = {
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.mitv.tvhome.mitvplus"
    };
    private static List<AppEntry> cache = Collections.emptyList();

    private AppCatalog() { }

    static synchronized List<AppEntry> load(Context context) {
        PackageManager manager = context.getPackageManager();
        LinkedHashMap<String, ResolveInfo> selected = new LinkedHashMap<>();
        addLaunchers(manager, context.getPackageName(), Intent.CATEGORY_LEANBACK_LAUNCHER, selected);
        addLaunchers(manager, context.getPackageName(), Intent.CATEGORY_LAUNCHER, selected);

        Map<String, AppEntry> previous = new HashMap<>();
        for (AppEntry entry : cache) {
            previous.put(entry.id(), entry);
        }
        List<AppEntry> found = new ArrayList<>();
        for (ResolveInfo info : selected.values()) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.applicationInfo == null) {
                continue;
            }
            String id = activity.packageName + "/" + activity.name;
            AppEntry old = previous.get(id);
            if (old != null) {
                found.add(old);
                continue;
            }

            String label = String.valueOf(info.loadLabel(manager));
            Drawable icon = info.loadIcon(manager);
            if (icon == null) {
                icon = activity.applicationInfo.loadIcon(manager);
            }
            Intent launch = launchIntent(manager, activity.packageName, activity.name);
            found.add(new AppEntry(activity.packageName, label, activity.name, icon, launch));
        }

        for (String packageName : KNOWN_TV_APPS) {
            if (selected.containsKey(packageName) || packageName.equals(context.getPackageName())) {
                continue;
            }
            Intent launch = manager.getLeanbackLaunchIntentForPackage(packageName);
            if (launch == null) {
                launch = manager.getLaunchIntentForPackage(packageName);
            }
            if (launch == null || launch.getComponent() == null) {
                continue;
            }
            try {
                ApplicationInfo app = manager.getApplicationInfo(packageName, 0);
                if (!app.enabled) {
                    continue;
                }
                String name = launch.getComponent().getClassName();
                String id = packageName + "/" + name;
                AppEntry old = previous.get(id);
                if (old != null) {
                    found.add(old);
                } else {
                    found.add(new AppEntry(packageName,
                            String.valueOf(manager.getApplicationLabel(app)),
                            name, app.loadIcon(manager), launch));
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // The app is not installed on this TV.
            }
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(found, (a, b) -> collator.compare(a.label, b.label));
        if (sameEntries(cache, found)) {
            return cache;
        }
        cache = Collections.unmodifiableList(found);
        return cache;
    }

    static synchronized void clearMemoryCache() {
        cache = Collections.emptyList();
    }

    private static void addLaunchers(PackageManager manager, String self,
                                     String category, Map<String, ResolveInfo> selected) {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(category);
        for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.packageName.equals(self)
                    || !activity.exported || !activity.enabled
                    || activity.applicationInfo == null || !activity.applicationInfo.enabled) {
                continue;
            }
            if (!selected.containsKey(activity.packageName)) {
                selected.put(activity.packageName, info);
            }
        }
    }

    private static Intent launchIntent(PackageManager manager, String packageName,
                                       String activityName) {
        Intent launch = manager.getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) {
            launch = manager.getLaunchIntentForPackage(packageName);
        }
        if (launch != null && launch.getComponent() != null) {
            return launch;
        }
        Intent explicit = new Intent(Intent.ACTION_MAIN);
        explicit.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        explicit.setComponent(new ComponentName(packageName, activityName));
        return explicit;
    }

    private static boolean sameEntries(List<AppEntry> left, List<AppEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).id().equals(right.get(index).id())) {
                return false;
            }
        }
        return true;
    }
}
