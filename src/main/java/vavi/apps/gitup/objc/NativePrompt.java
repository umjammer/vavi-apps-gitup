/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import org.rococoa.Foundation;
import org.rococoa.ID;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;
import org.rococoa.cocoa.foundation.NSPoint;
import org.rococoa.cocoa.foundation.NSRect;
import org.rococoa.cocoa.foundation.NSSize;


/**
 * credential prompts as native NSAlerts, run modally on the main thread.
 * <p>
 * GitUpKit calls the credential delegate with {@code dispatch_sync} on the main queue.
 * a Swing dialog cannot be used there: showing it needs the AppKit main thread, which is
 * the one waiting (the application would hang).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public final class NativePrompt {

    private NativePrompt() {}

    private static final long FIRST_BUTTON = 1000; // NSAlertFirstButtonReturn

    /** the answer, null fields when cancelled */
    public record Answer(String username, String secret, boolean remember) {}

    private static ID cls(String name) {
        return Foundation.getClass(name);
    }

    private static ID str(String s) {
        return Foundation.cfString(s != null ? s : "");
    }

    private static NSRect rect(double x, double y, double w, double h) {
        return new NSRect(new NSPoint(x, y), new NSSize(w, h));
    }

    /**
     * objc_msgSend with fixed prototypes. rococoa sends through a variadic call, on arm64 variadic
     * arguments go on the stack while AppKit reads an NSRect / NSSize from the float registers:
     * every frame became zero (the fields of the alert were not shown).
     */
    interface ObjC extends com.sun.jna.Library {
        ObjC INSTANCE = com.sun.jna.Native.load("objc", ObjC.class);

        com.sun.jna.Pointer sel_registerName(String name);
        com.sun.jna.Pointer objc_msgSend(com.sun.jna.Pointer self, com.sun.jna.Pointer sel, NSRect rect);
    }

    interface ObjCSize extends com.sun.jna.Library {
        ObjCSize INSTANCE = com.sun.jna.Native.load("objc", ObjCSize.class);

        void objc_msgSend(com.sun.jna.Pointer self, com.sun.jna.Pointer sel, NSSize size);
    }

    private static com.sun.jna.Pointer p(ID id) {
        return new com.sun.jna.Pointer(id.longValue());
    }

    /** [receiver selector:rect], returns the result as an ID */
    private static ID sendRect(ID receiver, String selector, NSRect rect) {
        com.sun.jna.Pointer r = ObjC.INSTANCE.objc_msgSend(p(receiver), ObjC.INSTANCE.sel_registerName(selector), rect);
        return ID.fromLong(com.sun.jna.Pointer.nativeValue(r));
    }

    private static void sendSize(ID receiver, String selector, NSSize size) {
        ObjCSize.INSTANCE.objc_msgSend(p(receiver), ObjC.INSTANCE.sel_registerName(selector), size);
    }

    private static ID newView(String className, NSRect frame) {
        ID o = Foundation.sendReturnsID(cls(className), "alloc");
        return sendRect(o, "initWithFrame:", frame);
    }

    /** the alert with its fields, before running (separated for testing) */
    record Form(ID alert, ID user, ID secret, ID remember) {
        String userText() { return Foundation.toString(Foundation.sendReturnsID(user, "stringValue")); }
        String secretText() { return Foundation.toString(Foundation.sendReturnsID(secret, "stringValue")); }
        boolean rememberChecked() { return remember != null && Foundation.send(remember, "state", Long.class) != 0; }
    }

    /**
     * @param user     null for a passphrase form (no username field)
     * @param remember label of a checkbox, null for none
     */
    static Form form(String title, String message, String user, String secretPlaceholder, String remember) {
        Foundation.sendReturnsID(cls("NSApplication"), "sharedApplication");
        ID alert = Foundation.sendReturnsID(Foundation.sendReturnsID(cls("NSAlert"), "alloc"), "init");
        Foundation.sendReturnsVoid(alert, "setMessageText:", str(title));
        Foundation.sendReturnsVoid(alert, "setInformativeText:", str(message));
        Foundation.sendReturnsID(alert, "addButtonWithTitle:", str("OK"));
        Foundation.sendReturnsID(alert, "addButtonWithTitle:", str("Cancel"));

        double w = 260;
        double y = 0;
        ID view = newView("NSView", rect(0, 0, w, 84));
        ID rememberBox = null;
        if (remember != null) {
            rememberBox = Foundation.sendReturnsID(cls("NSButton"), "checkboxWithTitle:target:action:", str(remember), null, null);
            sendRect(rememberBox, "setFrame:", rect(0, y, w, 20));
            Foundation.sendReturnsVoid(rememberBox, "setState:", 1L);
            Foundation.sendReturnsVoid(view, "addSubview:", rememberBox);
            y += 26;
        }
        ID secret = newView("NSSecureTextField", rect(0, y, w, 24));
        Foundation.sendReturnsVoid(secret, "setPlaceholderString:", str(secretPlaceholder));
        Foundation.sendReturnsVoid(view, "addSubview:", secret);
        y += 30;
        ID userField = null;
        if (user != null) {
            userField = newView("NSTextField", rect(0, y, w, 24));
            Foundation.sendReturnsVoid(userField, "setStringValue:", str(user));
            Foundation.sendReturnsVoid(userField, "setPlaceholderString:", str("Username"));
            Foundation.sendReturnsVoid(view, "addSubview:", userField);
            y += 30;
        }
        sendSize(view, "setFrameSize:", new NSSize(w, y));
        Foundation.sendReturnsVoid(alert, "setAccessoryView:", view);
        ID window = Foundation.sendReturnsID(alert, "window");
        Foundation.sendReturnsVoid(window, "setInitialFirstResponder:", user != null && user.isEmpty() ? userField : secret);
        return new Form(alert, userField, secret, rememberBox);
    }

    private static void requireMainThread() {
        if (!Foundation.isMainThread()) throw new IllegalStateException("NativePrompt must run on the main thread");
    }

    /** asks a username and a password / token, on the main thread */
    public static Answer userPassword(String url, String user, boolean offerRemember) {
        return userPassword(url, user, offerRemember, null);
    }

    /** @param note why it is asked (e.g. a saved token was rejected), null for none */
    public static Answer userPassword(String url, String user, boolean offerRemember, String note) {
        requireMainThread();
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            Form f = form("Authentication", note != null ? url + "\n\n" + note : url, user != null ? user : "", "Password or personal access token",
                    offerRemember ? "Remember as an account (Keychain)" : null);
            Foundation.sendReturnsID(cls("NSApplication"), "sharedApplication");
            Foundation.sendReturnsVoid(Foundation.sendReturnsID(cls("NSApplication"), "sharedApplication"), "activateIgnoringOtherApps:", true);
            long r = Foundation.send(f.alert(), "runModal", Long.class);
            if (r != FIRST_BUTTON) return null;
            if (f.userText().isBlank() || f.secretText().isEmpty()) return null; // nothing to try
            return new Answer(f.userText().strip(), f.secretText(), f.rememberChecked());
        } finally {
            pool.drain();
        }
    }

    /** asks the passphrase of an ssh key, on the main thread */
    public static String passphrase(String url, String key) {
        requireMainThread();
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            Form f = form("SSH Key", "Passphrase for " + key + "\n" + url, null, "Passphrase", null);
            Foundation.sendReturnsVoid(Foundation.sendReturnsID(cls("NSApplication"), "sharedApplication"), "activateIgnoringOtherApps:", true);
            long r = Foundation.send(f.alert(), "runModal", Long.class);
            return r == FIRST_BUTTON ? f.secretText() : null;
        } finally {
            pool.drain();
        }
    }
}
