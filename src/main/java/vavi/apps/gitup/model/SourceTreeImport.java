/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import vavi.apps.gitup.model.Bookmarks.Entry;
import vavi.apps.gitup.model.Bookmarks.Group;
import vavi.apps.gitup.model.Bookmarks.Repo;


/**
 * imports SourceTree's repository browser (bookmarks and groups).
 * <p>
 * SourceTree keeps them in {@code ~/Library/Application Support/SourceTree/browser.plist},
 * an NSKeyedArchiver archive of {@code STBrowserNode} (name, path, children, repositoryType)
 * where a group has no path and repositoryType 255, a git repository has repositoryType 1.
 * the binary plist is converted with {@code plutil}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class SourceTreeImport {

    private SourceTreeImport() {}

    /** repositoryType of a git repository */
    static final long TYPE_GIT = 1;

    /** a CF$UID reference into $objects */
    record Uid(int index) {}

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "SourceTree", "browser.plist");
    }

    /** result of an import */
    public record Result(int added, int skipped) {}

    /** reads SourceTree's bookmarks as a group tree (the root group has no name) */
    public static Group read(Path plist) throws IOException {
        Map<String, Object> archive = parse(plist);
        @SuppressWarnings("unchecked")
        List<Object> objects = (List<Object>) archive.get("$objects");
        @SuppressWarnings("unchecked")
        Map<String, Object> top = (Map<String, Object>) archive.get("$top");
        if (objects == null || top == null) throw new IOException("not an NSKeyedArchiver plist: " + plist);
        Group root = new Group("");
        for (Object n : array(objects, top.get("root"))) addNode(objects, deref(objects, n), root);
        return root;
    }

    private static void addNode(List<Object> objects, Object o, Group parent) {
        if (!(o instanceof Map<?, ?> node)) return;
        String name = string(objects, node.get("name"));
        String path = string(objects, node.get("path"));
        Object type = deref(objects, node.get("repositoryType"));
        List<Object> children = array(objects, node.get("children"));
        if (path == null) {
            Group g = new Group(name != null ? name : "group");
            for (Object c : children) addNode(objects, deref(objects, c), g);
            parent.children().add(g);
        } else if (!(type instanceof Long t) || t == TYPE_GIT) {
            Path p = Path.of(path);
            parent.children().add(new Repo(name != null ? name : String.valueOf(p.getFileName()), p));
        }
    }

    /**
     * merges SourceTree's tree into the bookmarks: groups with the same name at the same level are
     * shared, repositories already bookmarked (anywhere) are skipped.
     */
    public static Result importInto(Bookmarks bookmarks, Group source) {
        int[] counts = new int[2];
        merge(bookmarks, source, bookmarks.root(), counts);
        return new Result(counts[0], counts[1]);
    }

    private static void merge(Bookmarks bookmarks, Group from, Group into, int[] counts) {
        for (Entry e : from.children()) {
            switch (e) {
                case Group g -> {
                    Group target = into.children().stream()
                            .filter(x -> x instanceof Group xg && xg.name().equals(g.name()))
                            .map(x -> (Group) x).findFirst().orElse(null);
                    if (target == null) {
                        target = new Group(g.name());
                        into.children().add(target);
                    }
                    merge(bookmarks, g, target, counts);
                }
                case Repo r -> {
                    if (bookmarks.find(r.path()) != null) {
                        counts[1]++;
                    } else {
                        into.children().add(r);
                        counts[0]++;
                    }
                }
            }
        }
    }

    // plist

    /** binary or xml plist to maps / lists / strings / longs / booleans / {@link Uid} */
    static Map<String, Object> parse(Path plist) throws IOException {
        Process p = new ProcessBuilder("plutil", "-convert", "xml1", "-o", "-", plist.toString()).start();
        try (InputStream in = p.getInputStream()) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            Element plistElement = f.newDocumentBuilder().parse(in).getDocumentElement();
            if (p.waitFor() != 0) throw new IOException("plutil failed: " + plist);
            Object v = value(firstElement(plistElement));
            if (!(v instanceof Map)) throw new IOException("unexpected plist: " + plist);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return m;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static Element firstElement(Node n) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e) return e;
        }
        return null;
    }

    private static Object value(Element e) {
        if (e == null) return null;
        switch (e.getTagName()) {
            case "dict" -> {
                Map<String, Object> map = new HashMap<>();
                String key = null;
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (!(c instanceof Element ce)) continue;
                    if (ce.getTagName().equals("key")) key = ce.getTextContent();
                    else map.put(key, value(ce));
                }
                if (map.size() == 1 && map.get("CF$UID") instanceof Long l) return new Uid(l.intValue());
                return map;
            }
            case "array" -> {
                List<Object> list = new ArrayList<>();
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element ce) list.add(value(ce));
                }
                return list;
            }
            case "string" -> { return e.getTextContent(); }
            case "integer" -> {
                // hash values are unsigned 64 bit
                java.math.BigInteger i = new java.math.BigInteger(e.getTextContent().strip());
                return i.bitLength() < 64 ? (Object) i.longValue() : i;
            }
            case "real" -> { return Double.parseDouble(e.getTextContent().strip()); }
            case "true" -> { return Boolean.TRUE; }
            case "false" -> { return Boolean.FALSE; }
            default -> { return e.getTextContent(); }
        }
    }

    private static Object deref(List<Object> objects, Object o) {
        return o instanceof Uid u && u.index() >= 0 && u.index() < objects.size() ? objects.get(u.index()) : o;
    }

    /** a string, or null for "$null" / missing */
    private static String string(List<Object> objects, Object o) {
        Object v = deref(objects, o);
        return v instanceof String s && !s.equals("$null") ? s : null;
    }

    /** NS.objects of an NSArray, empty when missing */
    private static List<Object> array(List<Object> objects, Object o) {
        Object v = deref(objects, o);
        if (v instanceof Map<?, ?> m && m.get("NS.objects") instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) l;
            return list;
        }
        return List.of();
    }
}
