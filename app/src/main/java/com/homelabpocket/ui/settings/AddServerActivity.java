// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Add / Edit Server Connection Activity with TLS Pinning
package com.homelabpocket.ui.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.security.CertProbe;
import com.homelabpocket.security.CredentialStore;
import com.homelabpocket.security.PinnedTrustManager;

import java.util.UUID;

public class AddServerActivity extends Activity {

    public static final String EXTRA_SERVER_ID = "extra_server_id";

    private String existingServerId;
    private ServerConfig existingConfig;

    private TextView tvTitle;
    private Button btnCancel;
    private EditText etServerName;
    private RadioGroup rgServerType;
    private RadioButton rbTypePve;
    private RadioButton rbTypePdm;
    private EditText etServerHost;
    private EditText etServerPort;
    private CheckBox cbSkipTlsVerify;
    private boolean skipTlsConfirmed = false;

    private RadioGroup rgAuthType;
    private RadioButton rbAuthPass;
    private RadioButton rbAuthToken;
    private LinearLayout layoutAuthPass;
    private LinearLayout layoutAuthToken;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etToken;

    private Button btnSave;
    private Button btnDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_server);

        existingServerId = getIntent().getStringExtra(EXTRA_SERVER_ID);
        if (existingServerId != null) {
            if (HomelabApp.getInstance().getSessionManager().isLocked() ||
                    HomelabApp.getInstance().getCredentialStore().isSessionLocked()) {
                Toast.makeText(this, "Session is locked: server configuration edit is blocked.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            existingConfig = HomelabApp.getInstance().getServerConfigStore().findById(existingServerId);
        }

        initViews();
        setupListeners();

        if (existingConfig != null) {
            populateExistingData(existingConfig);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (existingServerId != null &&
                (HomelabApp.getInstance().getSessionManager().isLocked() ||
                 HomelabApp.getInstance().getCredentialStore().isSessionLocked())) {
            finish();
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_add_server_title);
        btnCancel = findViewById(R.id.btn_cancel);
        etServerName = findViewById(R.id.et_server_name);
        rgServerType = findViewById(R.id.rg_server_type);
        rbTypePve = findViewById(R.id.rb_type_pve);
        rbTypePdm = findViewById(R.id.rb_type_pdm);
        rbTypePdm.setEnabled(true);
        rbTypePdm.setText("Proxmox Datacenter Manager (PDM)");
        etServerHost = findViewById(R.id.et_server_host);
        etServerPort = findViewById(R.id.et_server_port);
        cbSkipTlsVerify = findViewById(R.id.cb_skip_tls_verify);

        rgAuthType = findViewById(R.id.rg_auth_type);
        rbAuthPass = findViewById(R.id.rb_auth_pass);
        rbAuthToken = findViewById(R.id.rb_auth_token);
        layoutAuthPass = findViewById(R.id.layout_auth_pass);
        layoutAuthToken = findViewById(R.id.layout_auth_token);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        etToken = findViewById(R.id.et_token);

        btnSave = findViewById(R.id.btn_save_server);
        btnDelete = findViewById(R.id.btn_delete_server);

        if (existingConfig != null) {
            tvTitle.setText("Edit Server Connection");
            btnDelete.setVisibility(View.VISIBLE);
        }
    }

    private void setupListeners() {
        btnCancel.setOnClickListener(v -> finish());

        rgServerType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_type_pdm) {
                if (etServerPort.getText().toString().equals("8006") || etServerPort.getText().toString().isEmpty()) {
                    etServerPort.setText("8443");
                }
            } else {
                if (etServerPort.getText().toString().equals("8443") || etServerPort.getText().toString().isEmpty()) {
                    etServerPort.setText("8006");
                }
            }
        });

        rgAuthType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_auth_token) {
                layoutAuthPass.setVisibility(View.GONE);
                layoutAuthToken.setVisibility(View.VISIBLE);
            } else {
                layoutAuthPass.setVisibility(View.VISIBLE);
                layoutAuthToken.setVisibility(View.GONE);
            }
        });

        if (cbSkipTlsVerify != null) {
            cbSkipTlsVerify.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && !skipTlsConfirmed) {
                    new AlertDialog.Builder(this)
                            .setTitle("Disable TLS Verification?")
                            .setMessage("WARNING: Disabling TLS verification allows man-in-the-middle attacks. Any server or proxy on the network can intercept your Proxmox credentials and data.\n\nAre you sure you want to disable certificate verification for this server?")
                            .setPositiveButton("Disable Verification", (dialog, which) -> {
                                skipTlsConfirmed = true;
                                cbSkipTlsVerify.setChecked(true);
                            })
                            .setNegativeButton("Keep Verification", (dialog, which) -> {
                                skipTlsConfirmed = false;
                                cbSkipTlsVerify.setChecked(false);
                            })
                            .setOnCancelListener(dialog -> {
                                skipTlsConfirmed = false;
                                cbSkipTlsVerify.setChecked(false);
                            })
                            .show();
                } else if (!isChecked) {
                    skipTlsConfirmed = false;
                }
            });
        }

        btnSave.setOnClickListener(v -> handleSave());

        btnDelete.setOnClickListener(v -> {
            if (existingConfig != null) {
                try {
                    HomelabApp.getInstance().getServerConfigStore().remove(existingConfig.id);
                    HomelabApp.getInstance().getCredentialStore().clearCredentials(existingConfig.id);
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to remove server: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                HomelabApp.getInstance().getSessionManager().invalidateSession(existingConfig.id);
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private void populateExistingData(ServerConfig cfg) {
        etServerName.setText(cfg.name);
        etServerHost.setText(cfg.host);
        etServerPort.setText(String.valueOf(cfg.port));
        if (cbSkipTlsVerify != null) {
            skipTlsConfirmed = cfg.skipTlsVerify;
            cbSkipTlsVerify.setChecked(cfg.skipTlsVerify);
        }

        if (cfg.type == ServerConfig.ServerType.PDM) {
            rbTypePdm.setChecked(true);
        } else {
            rbTypePve.setChecked(true);
        }

        CredentialStore credStore = HomelabApp.getInstance().getCredentialStore();
        try {
            if (credStore.hasApiToken(cfg.id)) {
                rbAuthToken.setChecked(true);
                // Do not decrypt secrets into editable plain text UI; leave blank with placeholder hint
                etToken.setText("");
                etToken.setHint("•••••••• (saved token retained if unchanged)");
            } else if (credStore.hasPassword(cfg.id)) {
                rbAuthPass.setChecked(true);
                try {
                    String user = credStore.getUsername(cfg.id);
                    if (user != null) etUsername.setText(user);
                } catch (Exception ignored) {}
                // Do not decrypt password into editable plain text UI; leave blank with placeholder hint
                etPassword.setText("");
                etPassword.setHint("•••••••• (saved password retained if unchanged)");
            }
        } catch (Exception e) {
            // Handled gracefully
        }
    }

    private void handleSave() {
        String name = etServerName.getText().toString().trim();
        String host = etServerHost.getText().toString().trim();
        String portStr = etServerPort.getText().toString().trim();

        if (name.isEmpty()) {
            etServerName.setError("Name required");
            return;
        }
        if (host.isEmpty()) {
            etServerHost.setError("Host or IP required");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            etServerPort.setError("Valid port required (1-65535)");
            return;
        }

        ServerConfig.ServerType type = rbTypePdm.isChecked()
                ? ServerConfig.ServerType.PDM
                : ServerConfig.ServerType.PVE;

        boolean isToken = rbAuthToken.isChecked();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();
        String token = etToken.getText().toString().trim();

        CredentialStore credStore = HomelabApp.getInstance().getCredentialStore();

        if (isToken) {
            if (token.isEmpty()) {
                if (existingConfig != null && credStore.hasApiToken(existingConfig.id)) {
                    // Retaining existing token
                } else {
                    etToken.setError("Token required");
                    return;
                }
            }
        } else {
            if (username.isEmpty()) {
                etUsername.setError("Username required");
                return;
            }
            if (password.isEmpty()) {
                if (existingConfig != null && credStore.hasPassword(existingConfig.id)) {
                    // Retaining existing password
                } else {
                    etPassword.setError("Password required");
                    return;
                }
            }
        }

        String serverId = (existingConfig != null) ? existingConfig.id : UUID.randomUUID().toString();
        String pinnedFp = (existingConfig != null) ? existingConfig.pinnedCertFingerprint : null;
        boolean skipTls = (cbSkipTlsVerify != null && cbSkipTlsVerify.isChecked() && skipTlsConfirmed);

        if (skipTls) {
            // Opted-in server bypasses pin/hostname validation and onboarding cert approval, NEVER globally
            saveServerAndCredentials(serverId, name, host, port, type, isToken, username, password, token, pinnedFp, true);
            return;
        }

        // Strict default: Probe certificate fingerprint asynchronously using unauthenticated CertProbe
        new ProbeFingerprintTask(serverId, name, host, port, type, isToken, username, password, token, pinnedFp).execute();
    }

    private class ProbeFingerprintTask extends AsyncTask<Void, Void, String> {
        private final String serverId;
        private final String name;
        private final String host;
        private final int port;
        private final ServerConfig.ServerType type;
        private final boolean isToken;
        private final String username;
        private final String password;
        private final String token;
        private final String pinnedFp;

        private ProgressDialog progressDialog;
        private Exception probeError;

        ProbeFingerprintTask(String serverId, String name, String host, int port,
                             ServerConfig.ServerType type, boolean isToken,
                             String username, String password, String token, String pinnedFp) {
            this.serverId = serverId;
            this.name = name;
            this.host = host;
            this.port = port;
            this.type = type;
            this.isToken = isToken;
            this.username = username;
            this.password = password;
            this.token = token;
            this.pinnedFp = pinnedFp;
        }

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(AddServerActivity.this);
            progressDialog.setMessage("Inspecting TLS certificate (credential-free probe)…");
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                // Separate credential-free discovery probe
                return CertProbe.probeServerFingerprint(host, port);
            } catch (Exception e) {
                probeError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(String presentedFingerprint) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            if (presentedFingerprint == null) {
                // Host unreachable: offer manual entry or saving as locked draft
                AlertDialog.Builder builder = new AlertDialog.Builder(AddServerActivity.this);
                builder.setTitle("TLS Discovery Unreachable");
                builder.setMessage("Cannot connect to " + host + ":" + port + ".\n\nError: " +
                        (probeError != null ? probeError.getMessage() : "Host unreachable") +
                        "\n\nSecurity Notice: Connections require an approved TLS certificate fingerprint. " +
                        "If saved as an offline draft, all authenticated network requests will remain locked until verified.");

                final EditText etManualFp = new EditText(AddServerActivity.this);
                etManualFp.setHint("Or paste known SHA-256 fingerprint");
                etManualFp.setTextSize(12);
                builder.setView(etManualFp);

                builder.setPositiveButton("Enter Pin", (dialog, which) -> {
                    String manual = etManualFp.getText().toString().trim();
                    if (PinnedTrustManager.isValidFingerprint(manual)) {
                        saveServerAndCredentials(serverId, name, host, port, type, isToken, username, password, token, manual, false);
                    } else {
                        Toast.makeText(AddServerActivity.this, "Invalid SHA-256 fingerprint format", Toast.LENGTH_LONG).show();
                    }
                });

                builder.setNeutralButton("Save Draft (Locked)", (dialog, which) -> {
                    // Stored with null pin: fail-closed transport refuses to send any credentials
                    saveServerAndCredentials(serverId, name, host, port, type, isToken, username, password, token, null, false);
                });

                builder.setNegativeButton("Back", null);
                builder.show();
                return;
            }

            if (pinnedFp == null || !pinnedFp.equalsIgnoreCase(presentedFingerprint)) {
                // Present explicit fingerprint approval dialog
                showFingerprintVerificationDialog(serverId, name, host, port, type, isToken, username, password, token, presentedFingerprint, pinnedFp);
            } else {
                // Matches already pinned fingerprint
                saveServerAndCredentials(serverId, name, host, port, type, isToken, username, password, token, pinnedFp, false);
            }
        }
    }

    private void showFingerprintVerificationDialog(
            String serverId, String name, String host, int port,
            ServerConfig.ServerType type, boolean isToken,
            String username, String password, String token,
            String presentedFingerprint, String oldPinnedFp) {

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_cert_verify, null);
        TextView tvCertTitle = dialogView.findViewById(R.id.tv_cert_title);
        TextView tvCertMsg = dialogView.findViewById(R.id.tv_cert_message);
        TextView tvFp = dialogView.findViewById(R.id.tv_cert_fingerprint);
        Button btnReject = dialogView.findViewById(R.id.btn_cert_reject);
        Button btnTrust = dialogView.findViewById(R.id.btn_cert_trust);

        if (oldPinnedFp != null) {
            tvCertTitle.setText(R.string.cert_changed_title);
            tvCertTitle.setTextColor(getResources().getColor(R.color.matrix_danger));
            tvCertMsg.setText(getString(R.string.cert_changed_msg, oldPinnedFp, presentedFingerprint));
        } else {
            tvCertTitle.setText(R.string.cert_fingerprint_title);
            tvCertTitle.setTextColor(getResources().getColor(R.color.matrix_phosphor));
            tvCertMsg.setText(getString(R.string.cert_first_connect_msg, presentedFingerprint));
        }
        tvFp.setText("SHA-256:\n" + presentedFingerprint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        btnReject.setOnClickListener(v -> {
            dialog.dismiss();
            Toast.makeText(this, "Connection rejected: certificate not trusted.", Toast.LENGTH_SHORT).show();
        });

        btnTrust.setOnClickListener(v -> {
            dialog.dismiss();
            saveServerAndCredentials(serverId, name, host, port, type, isToken, username, password, token, presentedFingerprint, false);
        });

        dialog.show();
    }

    public interface StorageFailureInjector {
        StorageFailureInjector NONE = new StorageFailureInjector() {
            @Override public void onBeforeEncrypt() throws Exception {}
            @Override public void onBeforeCredentialPersist() throws Exception {}
            @Override public void onBeforeConfigPersist() throws Exception {}
        };

        void onBeforeEncrypt() throws Exception;
        void onBeforeCredentialPersist() throws Exception;
        void onBeforeConfigPersist() throws Exception;
    }

    public static void saveServerAndCredentialsTransactional(
            com.homelabpocket.api.ServerConfigStore configStore,
            CredentialStore credStore,
            com.homelabpocket.api.SessionManager sessionManager,
            ServerConfig existingConfig,
            String serverId, String name, String host, int port,
            ServerConfig.ServerType type, boolean isToken,
            String username, String password, String token,
            String fingerprintToPin, boolean skipTlsVerify) throws Exception {
        saveServerAndCredentialsTransactional(
                configStore, credStore, sessionManager, existingConfig,
                serverId, name, host, port, type, isToken,
                username, password, token, fingerprintToPin, skipTlsVerify,
                StorageFailureInjector.NONE
        );
    }

    public static void saveServerAndCredentialsTransactional(
            com.homelabpocket.api.ServerConfigStore configStore,
            CredentialStore credStore,
            com.homelabpocket.api.SessionManager sessionManager,
            ServerConfig existingConfig,
            String serverId, String name, String host, int port,
            ServerConfig.ServerType type, boolean isToken,
            String username, String password, String token,
            String fingerprintToPin, boolean skipTlsVerify,
            StorageFailureInjector failureInjector) throws Exception {

        if (failureInjector == null) {
            failureInjector = StorageFailureInjector.NONE;
        }

        boolean mutatedCredentials = false;
        boolean mutatedConfig = false;

        ServerConfig originalConfig = null;
        String origPasswordCipher = null;
        String origTokenCipher = null;
        boolean hadOldToken = false;
        boolean hadOldPassword = false;

        try {
            // Snapshot existing state inside try block so any snapshot-read / decryption failure
            // safely invalidates active sessions before rethrowing.
            originalConfig = (existingConfig != null)
                    ? existingConfig
                    : (configStore != null ? configStore.findById(serverId) : null);

            if (credStore != null) {
                origPasswordCipher = credStore.getRawCiphertext(serverId, "password");
                origTokenCipher = credStore.getRawCiphertext(serverId, "api_token");
                hadOldToken = (origTokenCipher != null);
                hadOldPassword = (origPasswordCipher != null);
            }

            // 1. Prepare target credential values in memory before altering any state
            String targetPasswordCipher = null;
            String targetTokenCipher = null;

            if (isToken) {
                if (token == null || token.trim().isEmpty()) {
                    if (hadOldToken && origTokenCipher != null) {
                        targetTokenCipher = origTokenCipher;
                    } else {
                        throw new IllegalArgumentException("Token required");
                    }
                } else {
                    failureInjector.onBeforeEncrypt();
                    if (credStore != null) {
                        targetTokenCipher = credStore.prepareEncryptedApiToken(token.trim());
                    }
                }
                targetPasswordCipher = null;
            } else {
                if (username == null || username.trim().isEmpty()) {
                    throw new IllegalArgumentException("Username required");
                }
                String targetUsername = username.trim();

                if (password == null || password.isEmpty()) {
                    if (hadOldPassword && origPasswordCipher != null) {
                        String oldUser = credStore.getUsername(serverId);
                        if (targetUsername.equals(oldUser)) {
                            // Username and password both unchanged: reuse original ciphertext directly
                            targetPasswordCipher = origPasswordCipher;
                        } else {
                            // Username changed: decrypt old password and prepare new ciphertext with new username
                            String oldPassword = credStore.getPassword(serverId);
                            if (oldPassword == null) {
                                throw new IllegalArgumentException("Password required");
                            }
                            failureInjector.onBeforeEncrypt();
                            targetPasswordCipher = credStore.prepareEncryptedPassword(targetUsername, oldPassword);
                        }
                    } else {
                        throw new IllegalArgumentException("Password required");
                    }
                } else {
                    failureInjector.onBeforeEncrypt();
                    if (credStore != null) {
                        targetPasswordCipher = credStore.prepareEncryptedPassword(targetUsername, password);
                    }
                }
                targetTokenCipher = null;
            }

            // 2. Prepare new ServerConfig in memory
            ServerConfig newConfig = new ServerConfig(
                    serverId, name, host, port, type, fingerprintToPin, !skipTlsVerify, skipTlsVerify);

            // 3. Credential persistence phase: atomic raw credential commit
            failureInjector.onBeforeCredentialPersist();
            if (credStore != null) {
                mutatedCredentials = true;
                credStore.commitRawCredentials(serverId, targetPasswordCipher, targetTokenCipher);
            }

            // 4. ServerConfig persistence phase: transactional update
            failureInjector.onBeforeConfigPersist();
            if (configStore != null) {
                mutatedConfig = true;
                configStore.addOrUpdate(newConfig);
            }

            // 5. Invalidate cached session safely on success
            if (sessionManager != null) {
                sessionManager.invalidateSession(serverId);
            }

        } catch (Exception e) {
            // TRANSACTION FAILURE:
            // Safely invalidate cached sessions on failure (including snapshot-read / decryption failure)
            if (sessionManager != null) {
                try {
                    sessionManager.invalidateSession(serverId);
                } catch (Throwable sessionEx) {
                    e.addSuppressed(sessionEx);
                }
            }

            // Roll back credentials ONLY IF they were mutated during step 3
            // Uses exact raw original ciphertext strings — avoids destructive re-encryption
            if (mutatedCredentials && credStore != null) {
                try {
                    credStore.commitRawCredentials(serverId, origPasswordCipher, origTokenCipher);
                } catch (Throwable rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
            }

            // Roll back config ONLY IF it was mutated during step 4
            if (mutatedConfig && configStore != null) {
                try {
                    if (originalConfig != null) {
                        configStore.addOrUpdate(originalConfig);
                    } else {
                        configStore.remove(serverId);
                    }
                } catch (Throwable rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
            }

            throw e;
        }
    }

    private void saveServerAndCredentials(
            String serverId, String name, String host, int port,
            ServerConfig.ServerType type, boolean isToken,
            String username, String password, String token,
            String fingerprintToPin, boolean skipTlsVerify) {

        try {
            saveServerAndCredentialsTransactional(
                    HomelabApp.getInstance().getServerConfigStore(),
                    HomelabApp.getInstance().getCredentialStore(),
                    HomelabApp.getInstance().getSessionManager(),
                    existingConfig,
                    serverId, name, host, port, type, isToken,
                    username, password, token,
                    fingerprintToPin, skipTlsVerify,
                    StorageFailureInjector.NONE
            );

            if (skipTlsVerify) {
                Toast.makeText(this, "Server saved with TLS verification skipped (insecure).", Toast.LENGTH_SHORT).show();
            } else if (fingerprintToPin == null) {
                Toast.makeText(this, "Server saved as unverified draft. Reconnect and verify TLS fingerprint before use.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Server connection verified and saved.", Toast.LENGTH_SHORT).show();
            }
            setResult(RESULT_OK);
            finish();

        } catch (Exception e) {
            Toast.makeText(this, "Failed to save credentials: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
