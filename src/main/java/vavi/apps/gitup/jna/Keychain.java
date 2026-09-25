/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import java.nio.charset.StandardCharsets;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;


/**
 * internet passwords in the login keychain (the kind git's osxkeychain credential helper uses:
 * server, account, protocol https), so an account saved here also works for the git command.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public final class Keychain {

    private Keychain() {}

    interface Security extends Library {
        Security INSTANCE = Native.load("Security", Security.class);

        int SecKeychainAddInternetPassword(Pointer keychain, int serverNameLength, byte[] serverName,
                                           int securityDomainLength, byte[] securityDomain, int accountNameLength, byte[] accountName,
                                           int pathLength, byte[] path, short port, int protocol, int authenticationType,
                                           int passwordLength, byte[] passwordData, PointerByReference itemRef);

        int SecKeychainFindInternetPassword(Pointer keychainOrArray, int serverNameLength, byte[] serverName,
                                            int securityDomainLength, byte[] securityDomain, int accountNameLength, byte[] accountName,
                                            int pathLength, byte[] path, short port, int protocol, int authenticationType,
                                            IntByReference passwordLength, PointerByReference passwordData, PointerByReference itemRef);

        int SecKeychainItemModifyAttributesAndData(Pointer itemRef, Pointer attrList, int length, byte[] data);

        int SecKeychainItemFreeContent(Pointer attrList, Pointer data);

        int SecKeychainItemDelete(Pointer itemRef);
    }

    interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);

        void CFRelease(Pointer cf);
    }

    /** 'htps' */
    private static final int PROTOCOL_HTTPS = 0x68747073;
    /** 'dflt' */
    private static final int AUTH_DEFAULT = 0x64666c74;
    private static final int ERR_NOT_FOUND = -25300;
    private static final int ERR_DUPLICATE = -25299;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** @return the password, null when there is none */
    public static String find(String server, String account) {
        byte[] s = b(server), a = b(account);
        IntByReference len = new IntByReference();
        PointerByReference data = new PointerByReference();
        int rc = Security.INSTANCE.SecKeychainFindInternetPassword(null, s.length, s, 0, null, a.length, a, 0, null,
                (short) 0, PROTOCOL_HTTPS, AUTH_DEFAULT, len, data, null);
        if (rc == ERR_NOT_FOUND) return null;
        if (rc != 0) throw new IllegalStateException("keychain: " + rc);
        try {
            return new String(data.getValue().getByteArray(0, len.getValue()), StandardCharsets.UTF_8);
        } finally {
            Security.INSTANCE.SecKeychainItemFreeContent(null, data.getValue());
        }
    }

    /** adds or updates */
    public static void save(String server, String account, String password) {
        byte[] s = b(server), a = b(account), p = b(password);
        int rc = Security.INSTANCE.SecKeychainAddInternetPassword(null, s.length, s, 0, null, a.length, a, 0, null,
                (short) 0, PROTOCOL_HTTPS, AUTH_DEFAULT, p.length, p, null);
        if (rc == ERR_DUPLICATE) {
            PointerByReference item = new PointerByReference();
            rc = Security.INSTANCE.SecKeychainFindInternetPassword(null, s.length, s, 0, null, a.length, a, 0, null,
                    (short) 0, PROTOCOL_HTTPS, AUTH_DEFAULT, null, null, item);
            if (rc == 0) {
                try {
                    rc = Security.INSTANCE.SecKeychainItemModifyAttributesAndData(item.getValue(), null, p.length, p);
                } finally {
                    CoreFoundation.INSTANCE.CFRelease(item.getValue());
                }
            }
        }
        if (rc != 0) throw new IllegalStateException("keychain: " + rc);
    }

    /** @return false when there was nothing */
    public static boolean delete(String server, String account) {
        byte[] s = b(server), a = b(account);
        PointerByReference item = new PointerByReference();
        int rc = Security.INSTANCE.SecKeychainFindInternetPassword(null, s.length, s, 0, null, a.length, a, 0, null,
                (short) 0, PROTOCOL_HTTPS, AUTH_DEFAULT, null, null, item);
        if (rc == ERR_NOT_FOUND) return false;
        if (rc != 0) throw new IllegalStateException("keychain: " + rc);
        try {
            rc = Security.INSTANCE.SecKeychainItemDelete(item.getValue());
            if (rc != 0) throw new IllegalStateException("keychain: " + rc);
            return true;
        } finally {
            CoreFoundation.INSTANCE.CFRelease(item.getValue());
        }
    }
}
