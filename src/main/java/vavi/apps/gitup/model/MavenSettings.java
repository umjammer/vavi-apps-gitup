/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;


/**
 * reads the {@code <servers>} of maven's settings.xml, to import them as accounts.
 * <p>
 * a server has a username and password, or (GitLab style) a token in an http header of its
 * configuration. {@code ${env.NAME}} is resolved, an encrypted password ({@code {...}}) is
 * reported as such (it needs maven's master password).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public final class MavenSettings {

    private MavenSettings() {}

    public enum SecretKind { PASSWORD, TOKEN, ENCRYPTED, NONE }

    public record Server(String id, String username, String secret, SecretKind kind) {
        public boolean usable() { return secret != null && (kind == SecretKind.PASSWORD || kind == SecretKind.TOKEN); }
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    }

    private static final Pattern ENV = Pattern.compile("\\$\\{env\\.([A-Za-z0-9_]+)}");

    public static List<Server> read(Path file) throws IOException {
        return read(file, System::getenv);
    }

    static List<Server> read(Path file, UnaryOperator<String> env) throws IOException {
        List<Server> list = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            Element root = f.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            Element servers = child(root, "servers");
            if (servers == null) return list;
            for (Node n = servers.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element s) || !name(s).equals("server")) continue;
                String id = text(s, "id");
                String username = resolve(text(s, "username"), env);
                String password = text(s, "password");
                if (password != null && !password.isBlank()) {
                    if (password.strip().matches("^\\{.*}$")) {
                        list.add(new Server(id, username, null, SecretKind.ENCRYPTED));
                    } else {
                        String p = resolve(password.strip(), env);
                        list.add(new Server(id, username, p, p == null ? SecretKind.NONE : SecretKind.PASSWORD));
                    }
                    continue;
                }
                String token = headerToken(s, env);
                list.add(new Server(id, username, token, token != null ? SecretKind.TOKEN : SecretKind.NONE));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        return list;
    }

    /** configuration/httpHeaders/property with a token-ish name */
    private static String headerToken(Element server, UnaryOperator<String> env) {
        Element headers = child(child(server, "configuration"), "httpHeaders");
        if (headers == null) return null;
        for (Node n = headers.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element p) || !name(p).equals("property")) continue;
            String name = text(p, "name");
            String value = resolve(text(p, "value"), env);
            if (name == null || value == null) continue;
            if (name.equalsIgnoreCase("Authorization")) return value.replaceFirst("(?i)^(Bearer|token)\\s+", "");
            if (name.toLowerCase().contains("token")) return value;
        }
        return null;
    }

    /** ${env.NAME} to its value, null when unset */
    private static String resolve(String s, UnaryOperator<String> env) {
        if (s == null) return null;
        Matcher m = ENV.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            if (v == null) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString().strip();
    }

    private static String name(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    private static Element child(Element e, String name) {
        if (e == null) return null;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && name(c).equals(name)) return c;
        }
        return null;
    }

    private static String text(Element e, String name) {
        Element c = child(e, name);
        return c == null ? null : c.getTextContent().strip();
    }
}
