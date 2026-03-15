package com.quimodotcom.blackboxcure.UI.Fragments;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.quimodotcom.blackboxcure.AppPreferences;
import com.quimodotcom.blackboxcure.R;
import rikka.preference.SimpleMenuPreference;

public class PreferenceFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.prefs);

        // ── Accuracy ─────────────────────────────────────────────
        EditTextPreference accuracySettingsPref = findPreference("accuracy_settings");
        if (accuracySettingsPref != null) {
            accuracySettingsPref.setSummary(AppPreferences.getAccuracy(requireContext()) + " m.");
            accuracySettingsPref.setDialogMessage(R.string.enter_accuracy_value);
            accuracySettingsPref.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
            });
            accuracySettingsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    Float.parseFloat((String) newValue);
                    preference.setSummary(newValue + " m.");
                } catch (NumberFormatException e) {
                    Toast.makeText(requireContext(), R.string.enter_valid_value, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        // ── GPS Update Delay (ms) ─────────────────────────────────
        EditTextPreference updatesDelayPref = findPreference("gps_updates_delay");
        if (updatesDelayPref != null) {
            updatesDelayPref.setSummary(
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .getString("gps_updates_delay", "1000") + " ms.");
            updatesDelayPref.setDialogMessage(R.string.enter_updates_time_value);
            updatesDelayPref.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
            });
            updatesDelayPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    int ms = Integer.parseInt((String) newValue);
                    if (ms < 100) {
                        Toast.makeText(requireContext(),
                                R.string.enter_valid_value, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    preference.setSummary(newValue + " ms.");
                } catch (NumberFormatException e) {
                    Toast.makeText(requireContext(), R.string.enter_valid_value, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        // ── GPS Update Drift (ms) ─────────────────────────────────
        EditTextPreference updatesDriftPref = findPreference("gps_updates_drift");
        if (updatesDriftPref != null) {
            updatesDriftPref.setSummary(
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .getString("gps_updates_drift", "150") + " ms.");
            updatesDriftPref.setDialogMessage(R.string.enter_updates_drift_value);
            updatesDriftPref.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
            });
            updatesDriftPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    Integer.parseInt((String) newValue);
                    preference.setSummary(newValue + " ms.");
                } catch (NumberFormatException e) {
                    Toast.makeText(requireContext(), R.string.enter_valid_value, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        // ── Units ─────────────────────────────────────────────────
        SimpleMenuPreference standartUnit = findPreference("standart_unit");
        if (standartUnit != null) {
            int stdUnit = AppPreferences.getStandartUnit(requireContext());
            Resources res = getResources();
            TypedArray array = res.obtainTypedArray(R.array.unitList);
            standartUnit.setSummary(array.getString(stdUnit));
            standartUnit.setOnPreferenceChangeListener((preference, newValue) -> {
                preference.setSummary(array.getString(Integer.parseInt(newValue.toString())));
                return true;
            });
        }

        // ── Map tiles ─────────────────────────────────────────────
        SimpleMenuPreference tileProvider = findPreference("default_tile_provider");
        if (tileProvider != null) {
            int defTileProvider = AppPreferences.getMapTileProvider(requireContext());
            Resources res = getResources();
            TypedArray array = res.obtainTypedArray(R.array.map_tiles);
            tileProvider.setSummary(array.getString(defTileProvider));
            tileProvider.setOnPreferenceChangeListener((preference, newValue) -> {
                preference.setSummary(array.getString(Integer.parseInt(newValue.toString())));
                return true;
            });
        }

        // ── Traffic side ──────────────────────────────────────────
        SimpleMenuPreference trafficSide = findPreference("traffic_side");
        if (trafficSide != null) {
            int defSide = AppPreferences.getTrafficSide(requireContext());
            Resources res = getResources();
            TypedArray array = res.obtainTypedArray(R.array.trafficSide);
            trafficSide.setSummary(array.getString(defSide));
            trafficSide.setOnPreferenceChangeListener((preference, newValue) -> {
                preference.setSummary(array.getString(Integer.parseInt(newValue.toString())));
                return true;
            });
        }
    }
}
