/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.themes.FlatMacLightLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.model.FileChange;
import vavi.apps.gitup.model.GitRepo;
import vavi.apps.gitup.model.LazyPatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * DiffViewTest. paints the view offscreen inside a viewport, saves target/diffview*.png.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class DiffViewTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    void sh(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    /** renders a 100k line change, only the visible rows are fetched from native */
    @Test
    void paintVisibleRowsOnly() throws Exception {
        String base = IntStream.range(0, 100_000).mapToObj(i -> "\tline " + i + "\n").collect(Collectors.joining());
        Files.writeString(dir.resolve("f.txt"), base);
        sh("init", "-q", "-b", "main");
        sh("add", "f.txt");
        sh("commit", "-q", "-m", "one");
        Files.writeString(dir.resolve("f.txt"), base.replace("\tline 5\n", "\tline five\n").replace("\tline 50000\n", "\tLINE 50000\n\tadded\n"));

        try (GitRepo repo = new GitRepo(dir)) {
            FileChange f = repo.status().unstaged().getFirst();
            try (LazyPatch patch = repo.openPatch(f)) {
                assertEquals(2, patch.hunks().size());
                BufferedImage[] images = new BufferedImage[2];
                SwingUtilities.invokeAndWait(() -> {
                    FlatMacLightLaf.setup();
                    DiffView view = new DiffView();
                    view.setPatch(patch, DiffView.Mode.UNSTAGED, null);
                    JScrollPane scroll = new JScrollPane(view);
                    scroll.setSize(new Dimension(900, 260));
                    scroll.doLayout();
                    scroll.getViewport().doLayout();
                    images[0] = paint(scroll);
                    // jump near the second hunk, far below
                    scroll.getViewport().setViewPosition(new Point(0, view.getPreferredSize().height - 200));
                    images[1] = paint(scroll);
                });
                ImageIO.write(images[1], "png", Path.of("target/diffview2.png").toFile());
                ImageIO.write(images[0], "png", Path.of("target/diffview.png").toFile());
                assertTrue(patch.fetchCount() < 40, "fetched " + patch.fetchCount());
            }
        }
    }

    /** a diff font smaller than 12pt keeps the hunk headers 12pt high, the code rows follow the font */
    @Test
    void smallFontHeaderHeight() throws Exception {
        String base = IntStream.range(0, 100).mapToObj(i -> "line " + i + "\n").collect(Collectors.joining());
        Files.writeString(dir.resolve("f.txt"), base);
        sh("init", "-q", "-b", "main");
        sh("add", "f.txt");
        sh("commit", "-q", "-m", "one");
        Files.writeString(dir.resolve("f.txt"), base.replace("line 5\n", "line five\n").replace("line 50\n", "LINE 50\nadded\n").replace("line 90\n", ""));

        try (GitRepo repo = new GitRepo(dir);
             LazyPatch patch = repo.openPatch(repo.status().unstaged().getFirst())) {
            assertEquals(3, patch.hunks().size());
            BufferedImage[] image = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                FlatMacLightLaf.setup();
                DiffView view = new DiffView();
                java.awt.Font small = new java.awt.Font(DiffView.defaultFontName(), java.awt.Font.PLAIN, 8);
                view.setFont(small);
                view.setPatch(patch, DiffView.Mode.UNSTAGED, null);
                int line = view.getFontMetrics(small).getHeight() + 2;
                int header = view.getFontMetrics(small.deriveFont(12f)).getHeight() + 2;
                assertTrue(header > line);
                int h1 = patch.hunkRow(1);
                assertEquals(header, view.rowHeight(h1));
                assertEquals(line, view.rowHeight(h1 + 1));
                for (int r = 0; r < patch.rowCount(); r++) {
                    int y = view.rowY(r);
                    assertEquals(r, view.rowAt(y), "top of " + r);
                    assertEquals(r, view.rowAt(y + view.rowHeight(r) - 1), "bottom of " + r);
                    if (r + 1 < patch.rowCount()) assertEquals(y + view.rowHeight(r), view.rowY(r + 1));
                }
                int last = patch.rowCount() - 1;
                assertEquals(view.rowY(last) + view.rowHeight(last), view.getPreferredSize().height);

                JScrollPane scroll = new JScrollPane(view);
                scroll.setSize(new Dimension(900, 400));
                scroll.doLayout();
                scroll.getViewport().doLayout();
                image[0] = paint(scroll);
            });
            ImageIO.write(image[0], "png", Path.of("target/diffview-small.png").toFile());
        }
    }

    private static BufferedImage paint(JScrollPane scroll) {
        BufferedImage image = new BufferedImage(scroll.getWidth(), scroll.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        scroll.paint(g);
        g.dispose();
        return image;
    }

    @Test
    void defaultFont() {
        String name = DiffView.defaultFontName();
        List<String> installed = List.of(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        if (installed.contains("Menlo")) org.junit.jupiter.api.Assertions.assertEquals("Menlo", name, "SourceTree's");
        else org.junit.jupiter.api.Assertions.assertEquals(installed.contains("Monaco") ? "Monaco" : java.awt.Font.MONOSPACED, name);
    }
}
