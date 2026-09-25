/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.UIManager;

import com.sun.jna.Memory;
import org.rococoa.Foundation;
import org.rococoa.ID;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;

import static java.lang.System.getLogger;


/**
 * icons of the installed SourceTree.app, read from its bundle at runtime.
 * <p>
 * loose images ({@code Contents/Resources/*.tiff}) are read with ImageIO, the asset catalog ones
 * through {@code -[NSBundle imageForResource:]} and {@code -[NSImage TIFFRepresentation]}.
 * both hold 1x and 2x bitmaps, they become a multi resolution image.
 * SourceTree's images are not distributed with this application.
 * <p>
 * the app is looked up at {@code -Dgitup.sourcetree}, the usual Applications folders,
 * then by its bundle identifier with Spotlight.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class SourceTreeIconProvider implements IconProvider {

    private static final System.Logger logger = getLogger(SourceTreeIconProvider.class.getName());

    static final String BUNDLE_ID = "com.torusknot.SourceTreeNotMAS";

    private final Path app;
    private final Map<String, Image> cache = new ConcurrentHashMap<>();

    SourceTreeIconProvider(Path app) {
        this.app = app;
    }

    /** @return the provider, null when SourceTree.app is not installed */
    public static SourceTreeIconProvider find() {
        Path app = locate();
        return app != null ? new SourceTreeIconProvider(app) : null;
    }

    static Path locate() {
        List<Path> candidates = new ArrayList<>();
        String p = System.getProperty("gitup.sourcetree");
        if (p != null) candidates.add(Path.of(p));
        for (String dir : new String[] {"/Applications", System.getProperty("user.home") + "/Applications"}) {
            candidates.add(Path.of(dir, "Sourcetree.app"));
            candidates.add(Path.of(dir, "SourceTree.app"));
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("Contents/Resources"))) return c;
        }
        try {
            Process proc = new ProcessBuilder("mdfind", "kMDItemCFBundleIdentifier == '" + BUNDLE_ID + "'").start();
            try (InputStream in = proc.getInputStream()) {
                for (String line : new String(in.readAllBytes()).split("\n")) {
                    if (!line.isBlank() && Files.isDirectory(Path.of(line, "Contents/Resources"))) return Path.of(line);
                }
            }
        } catch (IOException e) {
            logger.log(System.Logger.Level.DEBUG, e.getMessage());
        }
        return null;
    }

    @Override
    public String name() {
        return "sourcetree";
    }

    private static boolean dark() {
        Color bg = UIManager.getColor("Panel.background");
        return bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
    }

    /** a loose file name (".tiff") or an asset catalog name */
    private String resource(Key key) {
        boolean dark = dark();
        return switch (key) {
            case COMMIT -> dark ? "Repo - Commit - Dark" : "Repo - Commit";
            case PULL -> dark ? "Repo - Pull - Dark" : "Repo - Pull";
            case PUSH -> dark ? "Repo - Push - Dark" : "Repo - Push";
            case FETCH -> "Repo - Fetch";
            case BRANCH -> "Repo - Branch";
            case STASH -> "Repo - Stash";
            case DISCARD -> "Discard";
            case REFRESH -> "Repo - Refresh";
            case TAG -> "Repo - Tag";
            case MERGE -> "Repo - Merge";
            case BRANCHES -> "Branches";
            case REMOTES -> "Remotes";
            case TAGS -> "Tags";
            case STASHES -> "Stashes";
            case LOCAL_BRANCH, REMOTE_BRANCH -> "i0039_devtools-branch.tiff";
            case TAG_ITEM -> "i0058_devtools-tag.tiff";
            case STASH_ITEM -> "stash.tiff";
            case FOLDER -> "Folder";
            case REPOSITORY -> "git_16x16.tiff";
            case LABEL_BRANCH -> "LogViewBranch";
            case LABEL_HEAD -> "Current Checkout";
            case LABEL_TAG -> "LogViewTag";
            case PREFS_ACCOUNTS -> "Prefs - Accounts";
            case PREFS_DIFF -> "Prefs - Diff";
            case PREFS_HISTORY -> "Prefs - Git";
            case FILE_ADDED -> dark ? "added-dark.tiff" : "added.tiff";
            case FILE_MODIFIED -> dark ? "modified-dark.tiff" : "modified.tiff";
            case FILE_DELETED -> dark ? "deleted-dark.tiff" : "deleted.tiff";
            case FILE_RENAMED -> dark ? "renamed-dark.tiff" : "renamed.tiff";
            case FILE_UNTRACKED -> dark ? "unknown-dark.tiff" : "unknown.tiff";
            case FILE_CONFLICTED -> dark ? "conflict-dark.tiff" : "conflict.tiff";
            case FILE_TYPECHANGE -> dark ? "typechanged-dark.tiff" : "typechanged.tiff";
        };
    }

    @Override
    public Icon icon(Key key, int size) {
        String name = resource(key);
        Image image = cache.get(name);
        if (image == null) {
            // an asset is read through AppKit on the main thread: never wait for it on the EDT,
            // AppKit may be waiting for the EDT at the same time (deadlock). see preload()
            if (!name.endsWith(".tiff") && javax.swing.SwingUtilities.isEventDispatchThread()) return null;
            image = cache.computeIfAbsent(name, this::load);
        }
        if (image == NONE) return null;
        return new ImageIcon(scaled(image, size));
    }

    /** reads every image (light and dark variants) now, call before the UI starts, not on the EDT */
    public void preload() {
        for (String dark : new String[] {"", " - Dark"}) {
            for (String n : new String[] {"Repo - Commit", "Repo - Pull", "Repo - Push"}) cache.computeIfAbsent(n + dark, this::load);
        }
        for (String n : new String[] {"Repo - Fetch", "Repo - Branch", "Repo - Stash", "Discard", "Repo - Refresh", "Repo - Tag",
                "Repo - Merge", "Branches", "Remotes", "Tags", "Stashes", "Folder",
                "LogViewBranch", "Current Checkout", "LogViewTag", "Prefs - Accounts", "Prefs - Diff", "Prefs - Git"}) {
            cache.computeIfAbsent(n, this::load);
        }
    }

    private static final Image NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private Image load(String name) {
        try {
            byte[] tiff = name.endsWith(".tiff")
                    ? Files.readAllBytes(app.resolve("Contents/Resources").resolve(name))
                    : assetTiff(name);
            Image i = tiff != null ? readAll(tiff) : null;
            return i != null ? i : NONE;
        } catch (IOException | RuntimeException e) {
            logger.log(System.Logger.Level.DEBUG, "SourceTree icon " + name + ": " + e.getMessage());
            return NONE;
        }
    }

    /** the TIFF of an asset catalog image, null when missing */
    private byte[] assetTiff(String name) {
        return Foundation.callOnMainThread(() -> {
            NSAutoreleasePool pool = NSAutoreleasePool.new_();
            try {
                ID bundle = Foundation.sendReturnsID(Foundation.getClass("NSBundle"), "bundleWithPath:", Foundation.cfString(app.toString()));
                if (bundle == null || bundle.isNull()) return null;
                ID image = Foundation.sendReturnsID(bundle, "imageForResource:", Foundation.cfString(name));
                if (image == null || image.isNull()) return null;
                ID data = Foundation.sendReturnsID(image, "TIFFRepresentation");
                if (data == null || data.isNull()) return null;
                long length = Foundation.send(data, "length", Long.class);
                if (length <= 0) return null;
                Memory m = new Memory(length);
                Foundation.send(data, "getBytes:length:", void.class, m, length);
                return m.getByteArray(0, (int) length);
            } finally {
                pool.drain();
            }
        });
    }

    /** every page of the TIFF (1x, 2x...) as a multi resolution image, smallest first */
    static Image readAll(byte[] tiff) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(tiff))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader r = readers.next();
            try {
                r.setInput(in);
                int n = r.getNumImages(true);
                for (int i = 0; i < n; i++) images.add(r.read(i));
            } finally {
                r.dispose();
            }
        }
        if (images.isEmpty()) return null;
        // one image per width
        images.sort(Comparator.comparingInt(BufferedImage::getWidth));
        List<BufferedImage> distinct = new ArrayList<>();
        for (BufferedImage b : images) {
            if (distinct.isEmpty() || distinct.getLast().getWidth() != b.getWidth()) distinct.add(b);
        }
        return distinct.size() == 1 ? distinct.getFirst() : new BaseMultiResolutionImage(distinct.toArray(Image[]::new));
    }

    /** a logical size x size image keeping the high resolution variants */
    private static Image scaled(Image image, int size) {
        if (image instanceof BaseMultiResolutionImage mr) {
            List<Image> variants = mr.getResolutionVariants();
            int base = variants.getFirst().getWidth(null);
            if (base == size) return image;
            double f = (double) size / base;
            return new BaseMultiResolutionImage(variants.stream()
                    .map(v -> v.getScaledInstance((int) Math.round(v.getWidth(null) * f), (int) Math.round(v.getHeight(null) * f), Image.SCALE_SMOOTH))
                    .toArray(Image[]::new));
        }
        return image.getWidth(null) == size ? image : image.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    }
}
