package dev.stillhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

/** The actual exported activity selected from Android's app catalog. */
final class AppEntry {
    final String packageName;
    final String label;
    final String activityName;
    final Drawable icon;
    final Intent launchIntent;

    AppEntry(String packageName, String label, String activityName,
             Drawable icon, Intent launchIntent) {
        this.packageName = packageName;
        this.label = label;
        this.activityName = activityName;
        this.icon = icon;
        this.launchIntent = launchIntent;
    }

    String id() {
        return packageName + "/" + activityName;
    }

    Drawable iconFor(Resources resources) {
        Drawable.ConstantState state = icon.getConstantState();
        return state == null ? icon : state.newDrawable(resources).mutate();
    }

    void launch(Activity activity) {
        try {
            Intent intent = new Intent(launchIntent);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(activity, "Could not open " + label, Toast.LENGTH_SHORT).show();
        }
    }
}
