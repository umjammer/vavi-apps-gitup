/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.Keychain;
import vavi.apps.gitup.model.Accounts.Account;
import vavi.apps.gitup.model.Accounts.Protocol;
import vavi.apps.gitup.model.Accounts.Service;
import vavi.apps.gitup.model.MavenSettings.SecretKind;
import vavi.apps.gitup.model.MavenSettings.Server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * settings, accounts (with an in memory secret store), maven settings.xml import, external tools.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
class SettingsTest {

    @TempDir
    Path dir;

    Preferences prefs;

    @BeforeEach
    void setup() {
        prefs = Preferences.userRoot().node("vavi-apps-gitup-test-" + System.nanoTime());
    }

    @AfterEach
    void teardown() throws Exception {
        prefs.removeNode();
    }

    @Test
    void settings() {
        Settings s = new Settings(prefs);
        int[] changes = {0};
        s.addListener(() -> changes[0]++);
        assertNull(s.diffColor(Settings.DiffColor.ADDED));
        s.setDiffColor(Settings.DiffColor.ADDED, new Color(0x12, 0x34, 0x56));
        assertEquals(new Color(0x12, 0x34, 0x56), new Settings(prefs).diffColor(Settings.DiffColor.ADDED));
        s.setDiffColor(Settings.DiffColor.ADDED, null);
        assertNull(s.diffColor(Settings.DiffColor.ADDED));
        assertEquals(3, s.contextLines());
        s.setContextLines(10);
        assertEquals(10, s.contextLines());
        assertEquals("opendiff \"$LOCAL\" \"$REMOTE\"", s.diffCommand());
        s.setDiffTool(ExternalTool.CUSTOM, "mytool $LOCAL $REMOTE");
        assertEquals("mytool $LOCAL $REMOTE", s.diffCommand());
        s.setMergeTool(ExternalTool.CUSTOM, "  ");
        assertNull(s.mergeCommand());
        assertEquals(5, changes[0]);
    }

    @Test
    void accounts() {
        Map<String, String> store = new HashMap<>();
        Accounts.SecretStore mem = new Accounts.SecretStore() {
            @Override public String find(String h, String u) { return store.get(h + "/" + u); }
            @Override public void save(String h, String u, String s) { store.put(h + "/" + u, s); }
            @Override public void delete(String h, String u) { store.remove(h + "/" + u); }
        };
        Accounts a = new Accounts(prefs, mem);
        Account gh = new Account(Service.GITHUB, "github.com", "alice", Protocol.HTTPS);
        a.put(gh, "token1");
        a.put(new Account(Service.OTHER, "git.example.com", "bob", Protocol.SSH), null);
        a.put(new Account(Service.GITHUB, "github.com", "alice", Protocol.HTTPS), "token2"); // replaces
        Accounts b = new Accounts(prefs, mem);
        assertEquals(2, b.list().size());
        assertEquals("token2", b.secret(gh));
        assertFalse(prefs.get("accounts", "").contains("token"), "no secret in the preferences");

        assertEquals(gh, b.find("https://github.com/umjammer/x.git", null));
        assertEquals(gh, b.find("https://alice@github.com/umjammer/x.git", "alice"));
        assertNull(b.find("https://github.com/x.git", "carol"));
        assertEquals("git.example.com", b.find("git@git.example.com:team/repo.git", null).host());
        assertEquals("git.example.com", Accounts.host("ssh://git@git.example.com:22/team/repo.git"));
        assertNull(Accounts.host("/local/path"));

        b.remove(gh, true);
        assertNull(store.get("github.com/alice"));
        assertEquals(Service.GITLAB, Service.guess("gitlab-maven"));
    }

    @Test
    void mavenSettings() throws Exception {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <servers>
                    <server><id>github</id><username>alice</username><password>ghp_plain</password></server>
                    <server><id>central</id><username>bob</username><password>{COQLCE6DU6GtcS5P=}</password></server>
                    <server><id>env</id><username>${env.GITUP_TEST_USER}</username><password>${env.GITUP_TEST_PASS}</password></server>
                    <server>
                      <id>gitlab-maven</id>
                      <configuration><httpHeaders><property><name>Private-Token</name><value>glpat-xyz</value></property></httpHeaders></configuration>
                    </server>
                    <server><id>empty</id></server>
                  </servers>
                </settings>
                """);
        List<Server> s = MavenSettings.read(xml, k -> Map.of("GITUP_TEST_USER", "eve", "GITUP_TEST_PASS", "secret").get(k));
        assertEquals(5, s.size());
        assertEquals(new Server("github", "alice", "ghp_plain", SecretKind.PASSWORD), s.get(0));
        assertEquals(SecretKind.ENCRYPTED, s.get(1).kind());
        assertFalse(s.get(1).usable());
        assertEquals(new Server("env", "eve", "secret", SecretKind.PASSWORD), s.get(2));
        assertEquals(new Server("gitlab-maven", null, "glpat-xyz", SecretKind.TOKEN), s.get(3));
        assertEquals(SecretKind.NONE, s.get(4).kind());
    }

    @Test
    void externalToolEnvironment() throws Exception {
        Path out = dir.resolve("out.txt");
        Path local = dir.resolve("a file.txt");
        Process p = ExternalTool.launch("printf '%s|%s' \"$LOCAL\" \"$REMOTE\" > \"$MERGED\"",
                Map.of("LOCAL", local.toString(), "REMOTE", "b; rm -rf /", "MERGED", out.toString()), dir);
        assertEquals(0, p.waitFor());
        assertEquals(local + "|b; rm -rf /", Files.readString(out), "paths are data, never code");
        assertTrue(ExternalTool.preset("custom").installed());
    }

    /** writes to the login keychain, run with -Dvavi.test.keychain=true */
    @Test
    @EnabledIfSystemProperty(named = "vavi.test.keychain", matches = "true")
    void keychain() {
        String host = "vavi-apps-gitup-test.invalid";
        try {
            assertNull(Keychain.find(host, "tester"));
            Keychain.save(host, "tester", "s3cret");
            assertEquals("s3cret", Keychain.find(host, "tester"));
            Keychain.save(host, "tester", "changed");
            assertEquals("changed", Keychain.find(host, "tester"));
        } finally {
            Keychain.delete(host, "tester");
        }
        assertNull(Keychain.find(host, "tester"));
    }
}
