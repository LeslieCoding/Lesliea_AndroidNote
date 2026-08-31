package com.leslie.colorosnote.document.office;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Default {@link LicenseProvider}: reads license keys from
 * SharedPreferences "office_licenses" (key {@code license.<vendorId>}, optionally
 * written by the settings/license UI after secure enrollment) or from a bundled
 * asset {@code licenses/<vendorId>.lic}. No key material is ever hardcoded here;
 * absent keys yield {@link LicenseProvider.LicenseState.Status#DEV_MODE}.
 *
 * <p>Cryptographic validation of the key itself is core-security's job; this
 * class only locates and returns the raw key material.
 */
public final class DefaultLicenseProvider implements LicenseProvider {

    private static final String PREFS_NAME = "office_licenses";
    private static final String PREF_KEY_PREFIX = "license.";
    private static final String TRIAL_PREFIX = "trial:";
    private static final String ASSET_DIR = "licenses/";

    private final Context appContext;

    public DefaultLicenseProvider(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public LicenseState licenseState(String vendorId) {
        String key = readFromPrefs(vendorId);
        if (key == null) {
            key = readFromAssets(vendorId);
        }
        if (key == null || key.trim().isEmpty()) {
            return LicenseState.devMode(vendorId);
        }
        key = key.trim();
        if (key.startsWith(TRIAL_PREFIX)) {
            return LicenseState.trial(vendorId, key.substring(TRIAL_PREFIX.length()), 0L);
        }
        return LicenseState.licensed(vendorId, key, 0L);
    }

    private String readFromPrefs(String vendorId) {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(PREF_KEY_PREFIX + vendorId, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String readFromAssets(String vendorId) {
        try (InputStream in = appContext.getAssets().open(ASSET_DIR + vendorId + ".lic")) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null; // asset simply not bundled
        }
    }
}
