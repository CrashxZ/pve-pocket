// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.security;

/**
 * Production decision logic for TLS verification bypass confirmation.
 * Ensures the explicit user confirmation prompt is required before skipTlsVerify
 * can be activated, and maintains default-OFF safety.
 */
public class TlsConfirmationDecision {

    /**
     * Resolves the effective checkbox/configuration state based on user action
     * and explicit warning confirmation.
     *
     * @param userRequestedSkip true if the user attempted to toggle the skip TLS checkbox on
     * @param userConfirmedWarning true if the user explicitly clicked "Disable Verification" on the warning dialog
     * @return true only if both requested and explicitly confirmed
     */
    public static boolean resolveCheckboxState(boolean userRequestedSkip, boolean userConfirmedWarning) {
        if (!userRequestedSkip) {
            return false;
        }
        return userConfirmedWarning;
    }
}
