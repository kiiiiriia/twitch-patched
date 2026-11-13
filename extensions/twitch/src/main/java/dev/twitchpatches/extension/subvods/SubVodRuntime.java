package dev.twitchpatches.extension.subvods;

import android.app.Application;
import android.content.SharedPreferences;
import com.amazonaws.ivs.net.HttpClient;
import dev.twitchpatches.extension.settings.PatchSettings;
import dev.twitchpatches.extension.settings.SettingSection;
import dev.twitchpatches.extension.settings.ToggleSetting;

public final class SubVodRuntime implements ToggleSetting {
    private static volatile SubVodRuntime instance;
    private final SharedPreferences preferences;
    private volatile boolean enabled;

    private SubVodRuntime(Application application) {
        preferences = application.getSharedPreferences("twitch_patches_sub_vods", 0);
        enabled = preferences.getBoolean("enabled", true);
    }

    public static synchronized void initialize(Application application) {
        if (instance != null) return;
        instance = new SubVodRuntime(application);
        PatchSettings.register(instance);
    }

    public static boolean enabled() { SubVodRuntime current = instance; return current != null && current.enabled; }

    public static HttpClient wrap(HttpClient original) { return new SubVodHttpClient(original); }

    @Override public String key() { return "sub_only_vods"; }
    @Override public String title() { return "Watch sub-only VODs"; }
    @Override public String summary() { return "Play subscriber-only past broadcasts and highlights from public video storage."; }
    @Override public SettingSection section() { return SettingSection.PLAYBACK; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) {
        enabled = value;
        preferences.edit().putBoolean("enabled", value).apply();
    }
}
