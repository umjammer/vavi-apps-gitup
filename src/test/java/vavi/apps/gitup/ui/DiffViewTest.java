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
