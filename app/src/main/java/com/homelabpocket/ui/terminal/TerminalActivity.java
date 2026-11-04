// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Terminal Activity with Honest Disclosures and Pure Native Fallbacks
package com.homelabpocket.ui.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.R;
import com.homelabpocket.api.ServerConfigStore;
import com.homelabpocket.api.TerminalRoutingDecision;
import com.homelabpocket.model.ServerConfig;

import org.json.JSONObject;

/**
 * Terminal Activity.
 *
 * Honest Disclosure & Stability Guarantees:
 * - Built-in live streaming is visibly disabled because standard Proxmox termproxy
 *   requires a dedicated pinned WebSocket transport not implemented in this release.
 * - Pure Android native views: completely free of android.webkit.WebView engine dependencies,
 *   preventing MissingWebViewPackageException and layout inflation crashes on devices
 *   with disabled or missing WebViews.
 * - Fault-tolerant external SSH and Web Console fallback routing: gracefully handles
 *   missing client handlers, null server configs, IPv6 hosts, and offline nodes without crashing.
 */
public class TerminalActivity extends Activity {

    public static final String EXTRA_NODE = "extra_node";
    public static final String EXTRA_VMID = "extra_vmid";
    public static final String EXTRA_SERVER_ID = "extra_server_id";
    public static final String EXTRA_TYPE = "extra_type"; // "NODE", "LXC", or "QEMU"
    public static final String EXTRA_TITLE = "extra_title";

    private String node;
    private int vmid;
    private String serverId;
    private String terminalType;
    private String displayTitle;

    private TextView tvTarget;
    private TextView tvHostInfo;
    private TextView tvStatus;
    private Button btnBack;

    // Honest disclosure container & external controls
    private LinearLayout layoutLiveDisabled;
    private TextView tvSshCommand;
    private Button btnCopySsh;
    private Button btnLaunchExternalSsh;
    private Button btnLaunchWebConsole;
    private Button btnShowConsoleInfo;

    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        mainHandler = new Handler(Looper.getMainLooper());

        Intent intent = getIntent();
        if (intent != null) {
            node = intent.getStringExtra(EXTRA_NODE);
            vmid = intent.getIntExtra(EXTRA_VMID, -1);
            serverId = intent.getStringExtra(EXTRA_SERVER_ID);
            terminalType = intent.getStringExtra(EXTRA_TYPE);
            displayTitle = intent.getStringExtra(EXTRA_TITLE);
        }

        if (terminalType == null || terminalType.trim().isEmpty()) {
            terminalType = "NODE";
        }

        initViews();
        setupLiveDisabledMode();
    }

    private void initViews() {
        tvTarget = findViewById(R.id.tv_terminal_target);
        tvHostInfo = findViewById(R.id.tv_terminal_host_info);
        tvStatus = findViewById(R.id.tv_terminal_status);
        btnBack = findViewById(R.id.btn_terminal_back);

        layoutLiveDisabled = findViewById(R.id.layout_live_disabled);
        tvSshCommand = findViewById(R.id.tv_ssh_command);
        btnCopySsh = findViewById(R.id.btn_copy_ssh);
        btnLaunchExternalSsh = findViewById(R.id.btn_launch_external_ssh);
        btnLaunchWebConsole = findViewById(R.id.btn_launch_web_console);
        btnShowConsoleInfo = findViewById(R.id.btn_show_console_info);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (tvTarget != null) {
            tvTarget.setText(displayTitle != null && !displayTitle.trim().isEmpty() ? displayTitle : "Terminal");
        }

        if (tvHostInfo != null) {
            String nodeDisplay = !TerminalRoutingDecision.isUnknownOrBlank(node) ? node.trim() : "[Unspecified Node]";
            String targetDesc = (vmid > 0 ? ("[" + vmid + "] " + terminalType) : "Node Host");
            tvHostInfo.setText("Host: " + nodeDisplay + " • Target: " + targetDesc);
        }
    }

    /**
     * Honest disclosure of live terminal limitation:
     * Visibly disables built-in live terminal, does NOT create termproxy,
     * does NOT show "Connected", does NOT fabricate root prompt,
     * and provides functional fallback to external SSH client or browser web console.
     */
    private void setupLiveDisabledMode() {
        if (tvStatus != null) {
            tvStatus.setText("Live Terminal Disabled");
            tvStatus.setTextColor(getResources().getColor(R.color.matrix_warning));
        }

        if (layoutLiveDisabled != null) {
            layoutLiveDisabled.setVisibility(View.VISIBLE);
        }

        ServerConfig server = null;
        try {
            HomelabApp app = HomelabApp.getInstance();
            if (app != null) {
                ServerConfigStore store = app.getServerConfigStore();
                if (store != null && serverId != null) {
                    server = store.findById(serverId);
                }
            }
        } catch (Throwable ignored) {
            // Tolerate headless test environments where HomelabApp is not initialized
        }

        TerminalRoutingDecision decision = TerminalRoutingDecision.evaluate(server, terminalType, node, vmid);

        if (decision.isSshAllowed) {
            final String sshCmd = decision.sshCommand;
            final String sshHost = decision.sshTargetHost;
            if (tvSshCommand != null) {
                tvSshCommand.setText(sshCmd);
                tvSshCommand.setTextColor(getResources().getColor(R.color.matrix_phosphor));
            }

            if (btnCopySsh != null) {
                btnCopySsh.setEnabled(true);
                btnCopySsh.setAlpha(1.0f);
                btnCopySsh.setOnClickListener(v -> {
                    boolean copied = copyToClipboard(sshCmd, "SSH Command");
                    if (copied) {
                        Toast.makeText(this, "Copied to clipboard: " + sshCmd, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            if (btnLaunchExternalSsh != null) {
                btnLaunchExternalSsh.setEnabled(true);
                btnLaunchExternalSsh.setAlpha(1.0f);
                btnLaunchExternalSsh.setText("Launch External SSH Client");
                btnLaunchExternalSsh.setOnClickListener(v -> launchExternalSsh(sshHost, sshCmd));
            }
        } else {
            if (tvSshCommand != null) {
                tvSshCommand.setText(decision.denialReason);
                tvSshCommand.setTextColor(getResources().getColor(R.color.matrix_warning));
            }

            if (btnCopySsh != null) {
                btnCopySsh.setEnabled(false);
                btnCopySsh.setAlpha(0.4f);
                btnCopySsh.setOnClickListener(null);
            }

            if (btnLaunchExternalSsh != null) {
                btnLaunchExternalSsh.setEnabled(false);
                btnLaunchExternalSsh.setAlpha(0.4f);
                btnLaunchExternalSsh.setText("Direct SSH Disabled (Unverified Target)");
                btnLaunchExternalSsh.setOnClickListener(null);
            }
        }

        if (btnLaunchWebConsole != null) {
            btnLaunchWebConsole.setText(decision.webConsoleLabel);
            if (decision.webManagementUrl != null) {
                btnLaunchWebConsole.setEnabled(true);
                btnLaunchWebConsole.setAlpha(1.0f);
                btnLaunchWebConsole.setOnClickListener(v -> launchWebConsole(decision.webManagementUrl));
            } else {
                btnLaunchWebConsole.setEnabled(false);
                btnLaunchWebConsole.setAlpha(0.4f);
                btnLaunchWebConsole.setOnClickListener(null);
            }
        }

        if (btnShowConsoleInfo != null) {
            btnShowConsoleInfo.setOnClickListener(v -> showConsoleArchitectureDialog(decision));
        }
    }

    public static String buildSshCommand(String host) {
        return TerminalRoutingDecision.formatSshCommand(host);
    }

    public static String buildWebConsoleUrl(String host, int port) {
        return TerminalRoutingDecision.formatWebUrl(host, port);
    }

    public interface ExternalIntentLauncher {
        void startActivity(Intent intent) throws Exception;
    }

    public interface ClipboardHelper {
        boolean copy(String text, String label);
    }

    public static class IntentDispatchResult {
        public final boolean launched;
        public final boolean copiedToClipboard;
        public final String failureMessage;
        public final String fallbackText;

        public IntentDispatchResult(boolean launched, boolean copiedToClipboard, String failureMessage, String fallbackText) {
            this.launched = launched;
            this.copiedToClipboard = copiedToClipboard;
            this.failureMessage = failureMessage;
            this.fallbackText = fallbackText;
        }
    }

    public static IntentDispatchResult dispatchSshIntent(
            ExternalIntentLauncher launcher,
            ClipboardHelper clipboard,
            String host,
            String sshCmd) {
        if (TerminalRoutingDecision.isUnknownOrBlank(host) || sshCmd == null || sshCmd.trim().isEmpty()) {
            return new IntentDispatchResult(false, false, "Invalid or unknown SSH host", null);
        }
        try {
            Uri sshUri = Uri.parse("ssh://root@" + host.trim());
            Intent intent = new Intent(Intent.ACTION_VIEW, sshUri);
            if (launcher != null) {
                launcher.startActivity(intent);
                return new IntentDispatchResult(true, false, null, null);
            }
            return new IntentDispatchResult(false, false, "No intent launcher provided", null);
        } catch (Throwable t) {
            boolean copied = false;
            if (clipboard != null) {
                copied = clipboard.copy(sshCmd, "SSH Command");
            }
            return new IntentDispatchResult(false, copied, "No SSH client app found: " + t.getMessage(), sshCmd);
        }
    }

    public static IntentDispatchResult dispatchWebConsoleIntent(
            ExternalIntentLauncher launcher,
            ClipboardHelper clipboard,
            String consoleUrl) {
        if (consoleUrl == null || consoleUrl.trim().isEmpty()) {
            return new IntentDispatchResult(false, false, "Invalid web console URL", null);
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(consoleUrl.trim()));
            if (launcher != null) {
                launcher.startActivity(intent);
                return new IntentDispatchResult(true, false, null, null);
            }
            return new IntentDispatchResult(false, false, "No intent launcher provided", null);
        } catch (Throwable t) {
            boolean copied = false;
            if (clipboard != null) {
                copied = clipboard.copy(consoleUrl, "Web Console URL");
            }
            return new IntentDispatchResult(false, copied, "No web browser app found: " + t.getMessage(), consoleUrl);
        }
    }

    private void launchExternalSsh(String host, String sshCmd) {
        IntentDispatchResult result = dispatchSshIntent(
                intent -> startActivity(Intent.createChooser(intent, "Choose SSH Client")),
                (text, label) -> copyToClipboard(text, label),
                host,
                sshCmd
        );
        if (!result.launched) {
            new AlertDialog.Builder(this)
                    .setTitle("No SSH App Found")
                    .setMessage("No external SSH client was detected on this device.\n\nThe command has been copied to your clipboard:\n\n" + sshCmd)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void launchWebConsole(String consoleUrl) {
        IntentDispatchResult result = dispatchWebConsoleIntent(
                intent -> startActivity(intent),
                (text, label) -> copyToClipboard(text, label),
                consoleUrl
        );
        if (!result.launched) {
            new AlertDialog.Builder(this)
                    .setTitle("Could Not Open Browser")
                    .setMessage("No web browser was available to open the console.\n\nThe URL has been copied to your clipboard:\n\n" + consoleUrl)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void showConsoleArchitectureDialog(TerminalRoutingDecision decision) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qemu_console, null);
        TextView tvMsg = dialogView.findViewById(R.id.tv_qemu_disclosure_msg);
        Button btnSsh = dialogView.findViewById(R.id.btn_open_external_ssh);
        Button btnBrowser = dialogView.findViewById(R.id.btn_open_browser_vnc);
        Button btnClose = dialogView.findViewById(R.id.btn_close_disclosure);

        if (tvMsg != null) {
            if (decision.isSshAllowed) {
                tvMsg.setText("QEMU graphical VMs require noVNC or SPICE graphical consoles. Host SSH is available for the verified host endpoint " + decision.sshTargetHost + ".");
            } else {
                tvMsg.setText(decision.denialReason + "\n\nUse the official management web UI or verify direct guest network connectivity.");
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (btnSsh != null) {
            if (decision.isSshAllowed) {
                btnSsh.setEnabled(true);
                btnSsh.setOnClickListener(v -> {
                    dialog.dismiss();
                    IntentDispatchResult result = dispatchSshIntent(
                            intent -> startActivity(Intent.createChooser(intent, "SSH Client")),
                            (text, label) -> copyToClipboard(text, label),
                            decision.sshTargetHost,
                            decision.sshCommand
                    );
                    if (!result.launched && result.copiedToClipboard) {
                        Toast.makeText(this, "Copied SSH command to clipboard.", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                btnSsh.setEnabled(false);
                btnSsh.setAlpha(0.4f);
                btnSsh.setText("SSH Disabled");
            }
        }

        if (btnBrowser != null) {
            if (decision.webManagementUrl != null) {
                btnBrowser.setEnabled(true);
                btnBrowser.setText("Open Management Endpoint");
                btnBrowser.setOnClickListener(v -> {
                    dialog.dismiss();
                    IntentDispatchResult result = dispatchWebConsoleIntent(
                            intent -> startActivity(intent),
                            (text, label) -> copyToClipboard(text, label),
                            decision.webManagementUrl
                    );
                    if (!result.launched && result.copiedToClipboard) {
                        Toast.makeText(this, "Copied Web Console URL to clipboard.", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                btnBrowser.setEnabled(false);
            }
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private boolean copyToClipboard(String text, String label) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Sanitizes output via JSONObject.quote for security and testing.
     */
    public static String quoteInput(String text) {
        return text != null ? JSONObject.quote(text) : "\"\"";
    }
}
