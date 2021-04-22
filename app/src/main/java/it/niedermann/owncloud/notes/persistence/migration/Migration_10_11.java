package it.niedermann.owncloud.notes.persistence.migration;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.Map;

import it.niedermann.owncloud.notes.preferences.DarkModeSetting;

public class Migration_10_11 extends Migration {
    @NonNull
    private final Context context;

    public Migration_10_11(@NonNull Context context) {
        super(10, 11);
        this.context = context;
    }

    /**
     * Changes the boolean for light / dark mode to {@link DarkModeSetting} to also be able to represent system default value
     */
    @Override
    public void migrate(@NonNull SupportSQLiteDatabase database) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        Map<String, ?> prefs = sharedPreferences.getAll();
        for (Map.Entry<String, ?> pref : prefs.entrySet()) {
            String key = pref.getKey();
            final String DARK_THEME_KEY = "NLW_darkTheme";
            if ("darkTheme".equals(key) || key.startsWith(DARK_THEME_KEY) || key.startsWith("SNW_darkTheme")) {
                Boolean darkTheme = (Boolean) pref.getValue();
                editor.putString(pref.getKey(), darkTheme ? DarkModeSetting.DARK.name() : DarkModeSetting.LIGHT.name());
            }
        }
        editor.apply();
    }
}
