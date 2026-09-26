/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.View;

import vavi.apps.gitup.objc.SpellChecker;

import static java.lang.System.getLogger;


/**
 * continuous spell checking of a text component with macOS's spell checker, like SourceTree's
 * commit message box: misspelled words are underlined in red, the context menu offers the guesses,
 * "Ignore Spelling" and "Learn Spelling".
 * <p>
 * the checker runs on its own thread (it calls AppKit's main thread, never waited for on the EDT),
 * a while after the last edit.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
final class SpellCheck {

    private static final System.Logger logger = getLogger(SpellCheck.class.getName());

    /** guesses are read for this many misspelled words only */
    private static final int MAX_GUESSED = 30;

    private final JTextComponent text;
    private final BooleanSupplier enabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spell check");
        t.setDaemon(true);
        return t;
    });
    private final Timer timer;
    /** null until created on the checker thread, or when unavailable */
    private SpellChecker checker;
    private boolean unavailable;
    /** the misspelled words of the checked text and their guesses */
    private Map<SpellChecker.Range, List<String>> misspelled = Map.of();
    private final List<Object> highlights = new ArrayList<>();

    /** @param enabled asked before every check (the setting) */
    static SpellCheck attach(JTextComponent text, BooleanSupplier enabled) {
        return new SpellCheck(text, enabled);
    }

    private SpellCheck(JTextComponent text, BooleanSupplier enabled) {
        this.text = text;
        this.enabled = enabled;
        timer = new Timer(400, e -> check());
        timer.setRepeats(false);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        };
        text.addMouseListener(mouse);
    }

    private void changed() {
        clear(); // stale underlines would be at wrong places
        timer.restart();
    }

    /** checks again now (e.g. after the setting changed) */
    void recheck() {
        clear();
        check();
    }

    private void check() {
        if (!enabled.getAsBoolean() || unavailable) {
            clear();
            return;
        }
        String snapshot = text.getText();
        executor.execute(() -> {
            Map<SpellChecker.Range, List<String>> result = new LinkedHashMap<>();
            try {
                if (checker == null) checker = new SpellChecker();
                int n = 0;
                for (SpellChecker.Range r : checker.check(snapshot)) {
                    result.put(r, n++ < MAX_GUESSED ? checker.guesses(snapshot, r) : List.of());
                }
            } catch (Throwable t) {
                logger.log(System.Logger.Level.WARNING, "spell checker unavailable: " + t.getMessage(), t);
                unavailable = true;
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!snapshot.equals(text.getText())) return; // edited meanwhile, checked again later
                show(result);
            });
        });
    }

    private void clear() {
        highlights.forEach(text.getHighlighter()::removeHighlight);
        highlights.clear();
        misspelled = Map.of();
    }

    private void show(Map<SpellChecker.Range, List<String>> result) {
        clear();
        misspelled = result;
        for (SpellChecker.Range r : result.keySet()) {
            try {
                highlights.add(text.getHighlighter().addHighlight(r.start(), r.end(), SQUIGGLE));
            } catch (BadLocationException e) {
                logger.log(System.Logger.Level.DEBUG, e.getMessage());
            }
        }
    }

    /** the misspelled word under the mouse: guesses, ignore, learn, then the usual editing items */
    private void popup(MouseEvent e) {
        int offset = text.viewToModel2D(e.getPoint());
        SpellChecker.Range word = misspelled.keySet().stream().filter(r -> r.start() <= offset && offset <= r.end()).findFirst().orElse(null);
        JPopupMenu menu = new JPopupMenu();
        if (word != null) {
            String w = text.getText().substring(word.start(), word.end());
            List<String> guesses = misspelled.get(word);
            if (guesses.isEmpty()) {
                JMenuItem none = new JMenuItem("No Guesses Found");
                none.setEnabled(false);
                menu.add(none);
            }
            for (String g : guesses.subList(0, Math.min(6, guesses.size()))) {
                JMenuItem i = new JMenuItem(g);
                i.setFont(i.getFont().deriveFont(java.awt.Font.BOLD));
                i.addActionListener(x -> replace(word, w, g));
                menu.add(i);
            }
            menu.addSeparator();
            JMenuItem ignore = new JMenuItem("Ignore Spelling");
            ignore.addActionListener(x -> executor.execute(() -> { checker.ignore(w); SwingUtilities.invokeLater(this::check); }));
            menu.add(ignore);
            JMenuItem learn = new JMenuItem("Learn Spelling");
            learn.setToolTipText("add \"" + w + "\" to the macOS dictionary");
            learn.addActionListener(x -> executor.execute(() -> { checker.learn(w); SwingUtilities.invokeLater(this::check); }));
            menu.add(learn);
            menu.addSeparator();
        }
        editItem(menu, "Cut", DefaultEditorKit.cutAction);
        editItem(menu, "Copy", DefaultEditorKit.copyAction);
        editItem(menu, "Paste", DefaultEditorKit.pasteAction);
        editItem(menu, "Select All", DefaultEditorKit.selectAllAction);
        menu.show(text, e.getX(), e.getY());
    }

    private void editItem(JPopupMenu menu, String label, String action) {
        javax.swing.Action a = text.getActionMap().get(action);
        if (a == null) return;
        JMenuItem i = new JMenuItem(a);
        i.setText(label);
        menu.add(i);
    }

    /** replaces the word when it is still there */
    private void replace(SpellChecker.Range r, String word, String by) {
        String t = text.getText();
        if (r.end() > t.length() || !t.substring(r.start(), r.end()).equals(word)) return;
        text.select(r.start(), r.end());
        text.replaceSelection(by);
    }

    /** a red wavy underline, as macOS's */
    private static final Highlighter.HighlightPainter SQUIGGLE = new javax.swing.text.LayeredHighlighter.LayerPainter() {
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
        }

        @Override
        public Shape paintLayer(Graphics g0, int p0, int p1, Shape bounds, JTextComponent c, View view) {
            Rectangle r;
            try {
                Shape s = view.modelToView(p0, Position.Bias.Forward, p1, Position.Bias.Backward, bounds);
                r = s instanceof Rectangle rect ? rect : s.getBounds();
            } catch (BadLocationException e) {
                return null;
            }
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0xff3b30));
                g.setStroke(new BasicStroke(1f));
                int y = r.y + r.height - 2;
                Path2D wave = new Path2D.Double();
                wave.moveTo(r.x, y);
                for (int x = r.x; x < r.x + r.width; x += 2) {
                    wave.lineTo(x + 1, (x - r.x) % 4 == 0 ? y + 1.5 : y - 0.5);
                }
                g.draw(wave);
            } finally {
                g.dispose();
            }
            return r;
        }
    };
}
