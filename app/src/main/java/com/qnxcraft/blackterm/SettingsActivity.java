package com.qnxcraft.blackterm;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.widget.Toast;

import com.qnxcraft.blackterm.service.FtpServerService;
import com.qnxcraft.blackterm.service.SshServerService;

public class SettingsActivity extends PreferenceActivity {

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        // SSH server enable toggle listener
        CheckBoxPreference sshEnabled = (CheckBoxPreference) findPreference("ssh_enabled");
        if (sshEnabled != null) {
            sshEnabled.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean enabled = (Boolean) newValue;
                    if (enabled) {
                        SshServerService.start(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this, "SSH Server starting...", Toast.LENGTH_SHORT).show();
                    } else {
                        SshServerService.stop(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this, "SSH Server stopped", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
        }

        // FTP server enable toggle listener
        CheckBoxPreference ftpEnabled = (CheckBoxPreference) findPreference("ftp_enabled");
        if (ftpEnabled != null) {
            ftpEnabled.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean enabled = (Boolean) newValue;
                    if (enabled) {
                        FtpServerService.start(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this, "FTP Server starting...", Toast.LENGTH_SHORT).show();
                    } else {
                        FtpServerService.stop(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this, "FTP Server stopped", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
        }

        // Bind summaries
        bindSummaryToValue(findPreference("font_size"));
        bindSummaryToValue(findPreference("font_family"));
        bindSummaryToValue(findPreference("bg_color"));
        bindSummaryToValue(findPreference("fg_color"));
        bindSummaryToValue(findPreference("ssh_port"));
        bindSummaryToValue(findPreference("ssh_username"));
        bindSummaryToValue(findPreference("ftp_port"));
        bindSummaryToValue(findPreference("ftp_username"));
        bindSummaryToValue(findPreference("terminal_columns"));
        bindSummaryToValue(findPreference("terminal_rows"));
        bindSummaryToValue(findPreference("scrollback_lines"));
        bindSummaryToValue(findPreference("shell_command"));
    }

    private void bindSummaryToValue(Preference pref) {
        if (pref == null) return;
        pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (preference instanceof ListPreference) {
                    ListPreference lp = (ListPreference) preference;
                    int idx = lp.findIndexOfValue(newValue.toString());
                    if (idx >= 0) {
                        preference.setSummary(lp.getEntries()[idx]);
                    }
                } else if (preference instanceof EditTextPreference) {
                    preference.setSummary(newValue.toString());
                }
                return true;
            }
        });

        // Set initial summary
        if (pref instanceof ListPreference) {
            ListPreference lp = (ListPreference) pref;
            if (lp.getEntry() != null) {
                pref.setSummary(lp.getEntry());
            }
        } else if (pref instanceof EditTextPreference) {
            EditTextPreference ep = (EditTextPreference) pref;
            if (ep.getText() != null) {
                pref.setSummary(ep.getText());
            }
        }
    }
}
