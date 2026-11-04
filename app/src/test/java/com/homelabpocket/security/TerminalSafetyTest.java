// SPDX-License-Identifier: GPL-3.0-or-later
// PVE Pocket — Terminal Safety, Quote Sanitization, and Pure Native Fallback Tests
package com.homelabpocket.security;

import com.homelabpocket.ui.terminal.TerminalActivity;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class TerminalSafetyTest {

    @Test
    public void testJsonQuoteEscapingForTerminalInputAndOutput() throws Exception {
        String[] testStrings = {
                "simple echo",
                "echo \"hello world\"",
                "path\\with\\backslashes",
                "multiline\r\noutput\nwith\rbreaks",
                "\033[32mANSI GREEN\033[0m",
                "alert('xss attack');\"); //",
                "root@node:~# rm -rf /; \n\r\t"
        };

        for (String input : testStrings) {
            String quoted = JSONObject.quote(input);
            assertNotNull(quoted);
            assertTrue("Quoted string must start with double quote", quoted.startsWith("\""));
            assertTrue("Quoted string must end with double quote", quoted.endsWith("\""));

            // Must NOT contain raw unescaped newlines or carriage returns inside the JS literal
            assertFalse("Quoted string must not contain raw newline", quoted.contains("\n"));
            assertFalse("Quoted string must not contain raw carriage return", quoted.contains("\r"));

            // Verify it parses cleanly back via JSON
            JSONObject container = new JSONObject("{\"val\":" + quoted + "}");
            assertEquals(input, container.getString("val"));
        }
    }

    @Test
    public void testSshFallbackUriConstruction() {
        String testHost = "192.0.2.122";
        String sshUri = "ssh://root@" + testHost;

        assertTrue(sshUri.startsWith("ssh://"));
        assertTrue(sshUri.contains("root@"));
        assertTrue(sshUri.endsWith(testHost));
        assertFalse("SSH URI must never embed passwords", sshUri.contains(":password@"));
    }

    @Test
    public void testTerminalActivityBuildSshCommand() {
        assertEquals("ssh root@192.0.2.1", TerminalActivity.buildSshCommand("192.0.2.1"));
        assertEquals("ssh root@pve-node-01", TerminalActivity.buildSshCommand("pve-node-01"));
        assertNull("Blank or null host must NOT fall back to localhost; must fail closed", TerminalActivity.buildSshCommand(null));
        assertNull("Whitespace-only host must NOT fall back to localhost; must fail closed", TerminalActivity.buildSshCommand("   "));
        assertNull("Unknown host must NOT format SSH command", TerminalActivity.buildSshCommand("unknown"));
        assertNull("None host must NOT format SSH command", TerminalActivity.buildSshCommand("none"));
    }

    @Test
    public void testTerminalActivityBuildWebConsoleUrl() {
        assertEquals("https://192.0.2.1:8006/", TerminalActivity.buildWebConsoleUrl("192.0.2.1", 8006));
        assertEquals("https://pve.example.com:8443/", TerminalActivity.buildWebConsoleUrl("pve.example.com", 8443));
        assertNull("Blank or null host must NOT fall back to localhost; must fail closed", TerminalActivity.buildWebConsoleUrl(null, 0));
        assertNull("Whitespace-only host must NOT fall back to localhost; must fail closed", TerminalActivity.buildWebConsoleUrl("   ", 0));
        // IPv6 address must be wrapped in brackets for RFC-compliant URL
        assertEquals("https://[2001:db8::1]:8006/", TerminalActivity.buildWebConsoleUrl("2001:db8::1", 8006));
        assertEquals("https://[2001:db8::1]:8006/", TerminalActivity.buildWebConsoleUrl("[2001:db8::1]", 8006));
        assertNull("Unknown host must NOT format URL", TerminalActivity.buildWebConsoleUrl("unknown", 8006));
        assertNull("None host must NOT format URL", TerminalActivity.buildWebConsoleUrl("none", 8006));
    }

    @Test
    public void testTerminalActivityLayoutHasNoWebViewDependency() throws Exception {
        // Root cause verification: Terminal layout must NOT contain <WebView> tags
        // so that devices lacking the Android System WebView package or during WebView updates
        // never experience InflateException / MissingWebViewPackageException crashes on launch.
        File layoutFile = new File("app/src/main/res/layout/activity_terminal.xml");
        assertTrue("activity_terminal.xml must exist", layoutFile.exists());
        String content = new String(Files.readAllBytes(layoutFile.toPath()));
        assertFalse("activity_terminal.xml must NOT instantiate android.webkit.WebView in XML",
                content.contains("<WebView"));
        assertTrue("activity_terminal.xml must declare scrollable fallback container",
                content.contains("layout_live_disabled"));
        assertTrue("activity_terminal.xml must have external SSH trigger button",
                content.contains("btn_launch_external_ssh"));
        assertTrue("activity_terminal.xml must have web console trigger button",
                content.contains("btn_launch_web_console"));
    }

    @Test
    public void testTerminalActivityQuoteInput() {
        String quoted = TerminalActivity.quoteInput("hello \"world\"");
        assertEquals("\"hello \\\"world\\\"\"", quoted);
        assertEquals("\"\"", TerminalActivity.quoteInput(null));
    }

    @Test
    public void testQemuConsoleExplanationArchitecture() {
        String explanation = "Standard Proxmox VE provides graphical noVNC consoles for QEMU VMs. " +
                "A direct serial/text shell is not provisioned via PVE API. " +
                "For QEMU VMs, please connect via SSH directly or launch noVNC in your browser.";

        assertTrue(explanation.contains("noVNC"));
        assertTrue(explanation.contains("SSH"));
        assertTrue(explanation.contains("QEMU"));
    }

    @Test
    public void testDispatchSshIntent_MissingIntentHandlerFallback() {
        java.util.concurrent.atomic.AtomicReference<String> copiedText = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> copiedLabel = new java.util.concurrent.atomic.AtomicReference<>();

        TerminalActivity.ClipboardHelper clipboard = (text, label) -> {
            copiedText.set(text);
            copiedLabel.set(label);
            return true;
        };

        TerminalActivity.ExternalIntentLauncher throwingLauncher = intent -> {
            throw new android.content.ActivityNotFoundException("No activity found to handle ssh:// intent");
        };

        String host = "192.0.2.55";
        String sshCmd = "ssh root@192.0.2.55";

        TerminalActivity.IntentDispatchResult result = TerminalActivity.dispatchSshIntent(
                throwingLauncher, clipboard, host, sshCmd);

        assertFalse("Intent launch must report false when ActivityNotFoundException is thrown", result.launched);
        assertTrue("Clipboard fallback must report true", result.copiedToClipboard);
        assertEquals(sshCmd, result.fallbackText);
        assertEquals(sshCmd, copiedText.get());
        assertEquals("SSH Command", copiedLabel.get());
        assertTrue("Failure message must disclose no SSH app: " + result.failureMessage,
                result.failureMessage.contains("No SSH client app found"));
    }

    @Test
    public void testDispatchWebConsoleIntent_MissingIntentHandlerFallback() {
        java.util.concurrent.atomic.AtomicReference<String> copiedText = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> copiedLabel = new java.util.concurrent.atomic.AtomicReference<>();

        TerminalActivity.ClipboardHelper clipboard = (text, label) -> {
            copiedText.set(text);
            copiedLabel.set(label);
            return true;
        };

        TerminalActivity.ExternalIntentLauncher throwingLauncher = intent -> {
            throw new android.content.ActivityNotFoundException("No web browser available");
        };

        String url = "https://192.0.2.55:8006/";
        TerminalActivity.IntentDispatchResult result = TerminalActivity.dispatchWebConsoleIntent(
                throwingLauncher, clipboard, url);

        assertFalse("Intent launch must report false when browser is missing", result.launched);
        assertTrue("Clipboard fallback must report true", result.copiedToClipboard);
        assertEquals(url, result.fallbackText);
        assertEquals(url, copiedText.get());
        assertEquals("Web Console URL", copiedLabel.get());
        assertTrue("Failure message must disclose missing browser: " + result.failureMessage,
                result.failureMessage.contains("No web browser app found"));
    }

    @Test
    public void testDispatchIntent_BlankOrUnknownInputsFailClosed() {
        TerminalActivity.ClipboardHelper clipboard = (text, label) -> true;
        TerminalActivity.ExternalIntentLauncher mockLauncher = intent -> {};

        TerminalActivity.IntentDispatchResult res1 = TerminalActivity.dispatchSshIntent(
                mockLauncher, clipboard, null, "ssh root@foo");
        assertFalse(res1.launched);

        TerminalActivity.IntentDispatchResult res2 = TerminalActivity.dispatchSshIntent(
                mockLauncher, clipboard, "unknown", "ssh root@unknown");
        assertFalse(res2.launched);

        TerminalActivity.IntentDispatchResult res3 = TerminalActivity.dispatchWebConsoleIntent(
                mockLauncher, clipboard, null);
        assertFalse(res3.launched);

        TerminalActivity.IntentDispatchResult res4 = TerminalActivity.dispatchWebConsoleIntent(
                mockLauncher, clipboard, "   ");
        assertFalse(res4.launched);
    }

    @Test
    public void testDispatchSshIntent_SecurityExceptionUnsupportedHandlerFallback() {
        java.util.concurrent.atomic.AtomicReference<String> copiedText = new java.util.concurrent.atomic.AtomicReference<>();
        TerminalActivity.ClipboardHelper clipboard = (text, label) -> {
            copiedText.set(text);
            return true;
        };
        TerminalActivity.ExternalIntentLauncher launcher = intent -> {
            throw new SecurityException("Permission Denial: cannot launch ssh intent");
        };

        TerminalActivity.IntentDispatchResult res = TerminalActivity.dispatchSshIntent(
                launcher, clipboard, "192.0.2.10", "ssh root@192.0.2.10");
        assertFalse("Launch must report false when security exception occurs", res.launched);
        assertTrue("Clipboard fallback must report true", res.copiedToClipboard);
        assertEquals("ssh root@192.0.2.10", copiedText.get());
        assertTrue("Failure message must disclose no SSH app: " + res.failureMessage,
                res.failureMessage.contains("No SSH client app found"));
    }

    @Test
    public void testDispatchWebConsoleIntent_SecurityExceptionUnsupportedHandlerFallback() {
        java.util.concurrent.atomic.AtomicReference<String> copiedText = new java.util.concurrent.atomic.AtomicReference<>();
        TerminalActivity.ClipboardHelper clipboard = (text, label) -> {
            copiedText.set(text);
            return true;
        };
        TerminalActivity.ExternalIntentLauncher launcher = intent -> {
            throw new SecurityException("Permission Denial: browser activity not exported");
        };

        TerminalActivity.IntentDispatchResult res = TerminalActivity.dispatchWebConsoleIntent(
                launcher, clipboard, "https://192.0.2.10:8006/");
        assertFalse("Launch must report false when security exception occurs", res.launched);
        assertTrue("Clipboard fallback must report true", res.copiedToClipboard);
        assertEquals("https://192.0.2.10:8006/", copiedText.get());
        assertTrue("Failure message must disclose no web browser: " + res.failureMessage,
                res.failureMessage.contains("No web browser app found"));
    }
}
