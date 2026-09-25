/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vavi.apps.gitup.jna.Keychain;


/**
 * hosting accounts (SourceTree's "Accounts"). the list is kept in the preferences,
 * passwords / tokens in the macOS Keychain (never in the preferences).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public final class Accounts {

    public enum Service {
        GITHUB("GitHub", "github.com"), GITLAB("GitLab", "gitlab.com"), BITBUCKET("Bitbucket", "bitbucket.org"), OTHER("Other", "");

        public final String label;
        public final String defaultHost;

        Service(String label, String defaultHost) {
            this.label = label;
            this.defaultHost = defaultHost;
        }

        @Override public String toString() { return label; }

        /** from a name like a maven server id or a host */
        public static Service guess(String s) {
            String l = s == null ? "" : s.toLowerCase();
            if (l.contains("github")) return GITHUB;
            if (l.contains("gitlab")) return GITLAB;
            if (l.contains("bitbucket")) return BITBUCKET;
            return OTHER;
        }
    }

    public enum Protocol { HTTPS, SSH }

    public record Account(Service service, String host, String username, Protocol protocol) {
        @Override public String toString() { return username + "@" + host; }
    }

    /** where secrets go, replaceable in tests */
    public interface SecretStore {
        String find(String host, String user);
        void save(String host, String user, String secret);
        void delete(String host, String user);
    }

    /** the macOS login keychain */
    public static final SecretStore KEYCHAIN = new SecretStore() {
        @Override public String find(String host, String user) { return Keychain.find(host, user); }
        @Override public void save(String host, String user, String secret) { Keychain.save(host, user, secret); }
        @Override public void delete(String host, String user) { Keychain.delete(host, user); }
    };

    private final Preferences prefs;
    private final SecretStore secrets;
    private final List<Account> accounts = new ArrayList<>();

    public Accounts(Preferences prefs, SecretStore secrets) {
        this.prefs = prefs;
        this.secrets = secrets;
        for (String line : prefs.get("accounts", "").split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 4) continue;
            try {
                accounts.add(new Account(Service.valueOf(f[0]), f[1], f[2], Protocol.valueOf(f[3])));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** the application's accounts */
    public static Accounts get() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final Accounts INSTANCE = new Accounts(Preferences.userNodeForPackage(Accounts.class).node("settings"), KEYCHAIN);
    }

    public synchronized List<Account> list() {
        return List.copyOf(accounts);
    }

    /** adds or replaces (same host and user), the secret is stored when not null */
    public synchronized void put(Account a, String secret) {
        accounts.removeIf(x -> x.host().equalsIgnoreCase(a.host()) && x.username().equals(a.username()));
        accounts.add(a);
        if (secret != null && !secret.isEmpty()) secrets.save(a.host(), a.username(), secret);
        save();
    }

    public synchronized void remove(Account a, boolean deleteSecret) {
        accounts.remove(a);
        if (deleteSecret) secrets.delete(a.host(), a.username());
        save();
    }

    public String secret(Account a) {
        return secrets.find(a.host(), a.username());
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Account a : accounts) {
            sb.append(a.service().name()).append('\t').append(a.host()).append('\t').append(a.username()).append('\t').append(a.protocol().name()).append('\n');
        }
        prefs.put("accounts", sb.toString());
    }

    private static final Pattern SCP_LIKE = Pattern.compile("^(?:([^@/]+)@)?([^:/]+):(?!//).*");

    /** the host of a remote URL: https://user@host/path, ssh://git@host/path, git@host:path */
    public static String host(String url) {
        if (url == null) return null;
        try {
            if (url.contains("://")) return URI.create(url).getHost();
        } catch (IllegalArgumentException ignored) {
        }
        Matcher m = SCP_LIKE.matcher(url);
        return m.matches() ? m.group(2) : null;
    }

    /** @return the account for the url (and user when given), null when none */
    public synchronized Account find(String url, String user) {
        String host = host(url);
        if (host == null) return null;
        for (Account a : accounts) {
            if (a.host().equalsIgnoreCase(host) && (user == null || user.isEmpty() || a.username().equals(user))) return a;
        }
        return null;
    }
}
