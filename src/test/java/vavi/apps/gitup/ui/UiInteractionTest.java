/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import com.formdev.flatlaf.themes.FlatMacLightLaf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.model.CommitLog;
import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.FileChange;
import vavi.apps.gitup.model.GitRepo;
import vavi.apps.gitup.model.LazyPatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * headless tests of mouse / checkbox / drag and drop interactions, and the graph rendering.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class UiInteractionTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @BeforeAll
    static void laf() throws Exception {
        SwingUtilities.invokeAndWait(FlatMacLightLaf::setup);
    }

    @TempDir
    Path dir;

    String sh(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    /** a repository with a two hunk change in f.txt */
    GitRepo repoWithChange() throws Exception {
        String base = IntStream.rangeClosed(1, 40).mapToObj(i -> "line " + i + "\n").collect(Collectors.joining());
        Files.writeString(dir.resolve("f.txt"), base);
        sh("init", "-q", "-b", "main");
        sh("add", "f.txt");
        sh("commit", "-q", "-m", "one");
        Files.writeString(dir.resolve("f.txt"), base.replace("line 3\n", "LINE 3\n").replace("line 30\n", "LINE 30\nextra\n"));
        return new GitRepo(dir);
    }

    static void click(JComponent c, int x, int y, int modifiers) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers | InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers, x, y, 1, false, MouseEvent.BUTTON1));
    }

    record Fired(DiffView.Action action, BitSet rows) {}

    @Test
    void diffSelectionAndButtons() throws Exception {
        try (GitRepo repo = repoWithChange();
             LazyPatch patch = repo.openPatch(repo.status().unstaged().getFirst())) {
            List<Fired> fired = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                DiffView view = new DiffView();
                view.setListener((a, p, rows) -> fired.add(new Fired(a, rows)));
                view.setPatch(patch, DiffView.Mode.UNSTAGED, null);
                JScrollPane scroll = new JScrollPane(view);
                scroll.setSize(new Dimension(900, 600));
                scroll.doLayout();
                scroll.getViewport().doLayout();
                paint(scroll); // lays out the header buttons
                int rowH = view.getScrollableUnitIncrement(null, javax.swing.SwingConstants.VERTICAL, 1);
                int h1 = patch.hunkRow(1);

                // header click selects the change lines of the hunk
                click(view, 300, h1 * rowH + rowH / 2, 0);
                BitSet sel = view.getSelection();
                assertEquals(3, sel.cardinality(), "-line 30, +LINE 30, +extra");
                for (int r = sel.nextSetBit(0); r >= 0; r = sel.nextSetBit(r + 1)) assertTrue(patch.row(r).isChange());

                // click a line, shift-click another: range of change lines
                int first = sel.nextSetBit(0);
                click(view, 300, first * rowH + rowH / 2, 0);
                assertEquals(1, view.getSelection().cardinality());
                click(view, 300, (first + 2) * rowH + rowH / 2, InputEvent.SHIFT_DOWN_MASK);
                assertEquals(3, view.getSelection().cardinality());

                // cmd-click toggles one line off
                click(view, 300, (first + 1) * rowH + rowH / 2, Keys.menu());
                assertEquals(2, view.getSelection().cardinality());

                // header of hunk 0 now has "Stage hunk" (no selection there), rightmost button
                paint(scroll);
                int h0y = patch.hunkRow(0) * rowH + rowH / 2;
                click(view, scroll.getViewport().getWidth() - 20, h0y, 0);
                // the hunk with the selection has "Stage lines"
                paint(scroll);
                click(view, scroll.getViewport().getWidth() - 20, h1 * rowH + rowH / 2, 0);
            });
            assertEquals(2, fired.size());
            assertEquals(DiffView.Action.STAGE, fired.get(0).action());
            assertTrue(fired.get(0).rows().get(patch.hunkRow(0)), "whole hunk 0 by its header row");
            assertEquals(DiffView.Action.STAGE, fired.get(1).action());
            assertEquals(2, fired.get(1).rows().cardinality(), "the selected lines");
        }
    }

    record Moved(FileTable source, List<FileChange> files) {}

    @Test
    void fileTableCheckboxAndDrop() throws Exception {
        FileChange a = new FileChange("a.txt", "a.txt", FileChange.Kind.MODIFIED, false);
        FileChange b = new FileChange("b.txt", "b.txt", FileChange.Kind.UNTRACKED, false);
        List<Moved> moved = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            FileTable staged = new FileTable(true, true);
            FileTable unstaged = new FileTable(false, true);
            FileTable.Listener l = new FileTable.Listener() {
                @Override public void move(FileTable source, List<FileChange> files) { moved.add(new Moved(source, files)); }
                @Override public void discard(List<FileChange> files) {}
                @Override public void stopTracking(List<FileChange> files) {}
                @Override public void ignore(List<FileChange> files) {}
                @Override public void trash(List<FileChange> files) {}
                @Override public void resolve(List<FileChange> files, boolean ours) {}
                @Override public void externalDiff(List<FileChange> files) {}
                @Override public void externalMerge(FileChange file) {}
            };
            staged.setListener(l);
            unstaged.setListener(l);
            unstaged.setFiles(List.of(a, b));

            // checking the box of an unstaged row stages it
            unstaged.setValueAt(true, 1, 0);
            assertEquals(1, moved.size());
            assertEquals(List.of(b), moved.getFirst().files());
            assertEquals(unstaged, moved.getFirst().source());

            // drag both rows onto the staged table
            unstaged.setRowSelectionInterval(0, 1);
            Transferable t = exportTransferable(unstaged);
            TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(staged, t);
            assertTrue(staged.getTransferHandler().canImport(support));
            assertTrue(staged.getTransferHandler().importData(support));
            // dropping onto the source table is refused
            assertTrue(!unstaged.getTransferHandler().canImport(new TransferHandler.TransferSupport(unstaged, t)));
        });
        assertEquals(2, moved.size());
        assertEquals(List.of(a, b), moved.get(1).files());
    }

    private static Transferable exportTransferable(FileTable table) {
        return ((FileTable.Handler) table.getTransferHandler()).createTransferable(table);
    }

    /** the "Uncommitted changes" node and HEAD are connected: paints lane 0 continuously */
    @Test
    void graphConnectsWorkingCopyToHead() throws Exception {
        try (GitRepo repo = repoWithChange()) {
            sh("checkout", "-q", "-b", "topic");
            sh("commit", "-q", "--allow-empty", "-m", "topic work");
            sh("checkout", "-q", "main");
            List<CommitRow> rows;
            try (CommitLog log = repo.log(true)) {
                rows = log.next(10);
            }
            BufferedImage[] image = new BufferedImage[1];
            int[] geometry = new int[3]; // table top in panel, row height, lane 0 x
            SwingUtilities.invokeAndWait(() -> {
                LogPanel panel = new LogPanel();
                panel.reset(true, repo.refsByTarget(), "main");
                panel.append(rows, false);
                JScrollPane scroll = (JScrollPane) panel.getComponent(0);
                panel.setSize(new Dimension(700, 200));
                panel.doLayout();
                scroll.doLayout();
                scroll.getViewport().doLayout();
                image[0] = paint(panel);
                javax.swing.JTable table = panel.getTable();
                geometry[0] = SwingUtilities.convertPoint(table, 0, 0, panel).y;
                geometry[1] = table.getRowHeight();
                geometry[2] = SwingUtilities.convertPoint(table, 0, 0, panel).x + 14 / 2 + 2;
            });
            ImageIO.write(image[0], "png", Path.of("target/graph.png").toFile());
            // lane 0 from the uncommitted node (row 0) down to HEAD's node (row 2) is painted
            int top = geometry[0], rowH = geometry[1], x = geometry[2];
            for (int y = top + rowH / 2 + 6; y < top + rowH * 2 + rowH / 2 - 5; y++) {
                assertTrue(isColored(image[0].getRGB(x, y)), "lane 0 painted at y=" + y);
            }
        }
    }

    private static boolean isColored(int rgb) {
        Color c = new Color(rgb);
        int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        return max - min > 40 || max < 160; // a lane color or gray line, not the white background
    }

    private static BufferedImage paint(java.awt.Container c) {
        BufferedImage image = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        c.paint(g);
        g.dispose();
        return image;
    }
}
