/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;


/**
 * the application settings (diff colors, context lines, external tools), in the user preferences.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public final class Settings {

    /** the colors of the diff view, null means the theme's default. ADDED / REMOVED are backgrounds, *_TEXT the text */
    public enum DiffColor { ADDED, REMOVED, ADDED_TEXT, REMOVED_TEXT, HUNK_HEADER, SELECTION }

    public static final int DEFAULT_CONTEXT_LINES = 3;

    private final Preferences prefs;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Settings(Preferences prefs) {
        this.prefs = prefs;
    }

    public static Settings get() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final Settings INSTANCE = new Settings(Preferences.userNodeForPackage(Settings.class).node("settings"));
    }

    /** called on the thread that changed a setting */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    public Color diffColor(DiffColor key) {
        String s = prefs.get("diff.color." + key.name().toLowerCase(), null);
        if (s == null) return null;
        try {
            return new Color(Integer.parseUnsignedInt(s, 16), s.length() > 6);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @param color null to use the default */
    public void setDiffColor(DiffColor key, Color color) {
        String k = "diff.color." + key.name().toLowerCase();
        if (color == null) prefs.remove(k);
        else prefs.put(k, String.format("%08x", color.getRGB()));
        changed();
    }

    /** lines of context around changes in diffs */
    public int contextLines() {
        return prefs.getInt("diff.contextLines", DEFAULT_CONTEXT_LINES);
    }

    public void setContextLines(int n) {
        prefs.putInt("diff.contextLines", Math.max(0, Math.min(n, 50)));
        changed();
    }

    public String diffToolId() {
        return prefs.get("tool.diff", "filemerge");
    }

    public String mergeToolId() {
        return prefs.get("tool.merge", "filemerge");
    }

    public String customDiffCommand() {
        return prefs.get("tool.diff.custom", "");
    }

    public String customMergeCommand() {
        return prefs.get("tool.merge.custom", "");
    }

    public void setDiffTool(String id, String customCommand) {
        prefs.put("tool.diff", id);
        prefs.put("tool.diff.custom", customCommand == null ? "" : customCommand);
        changed();
    }

    public void setMergeTool(String id, String customCommand) {
        prefs.put("tool.merge", id);
        prefs.put("tool.merge.custom", customCommand == null ? "" : customCommand);
        changed();
    }

    /** @return the command of the chosen diff tool, null when none */
    public String diffCommand() {
        String id = diffToolId();
        return id.equals(ExternalTool.CUSTOM) ? blankToNull(customDiffCommand()) : ExternalTool.preset(id).diff();
    }

    /** @return the command of the chosen merge tool, null when none */
    public String mergeCommand() {
        String id = mergeToolId();
        return id.equals(ExternalTool.CUSTOM) ? blankToNull(customMergeCommand()) : ExternalTool.preset(id).merge();
    }

    /** the font family of the diff view, null means the default (SourceTree's Menlo) */
    public String diffFontName() {
        return blankToNull(prefs.get("diff.font.name", null));
    }

    /** the font size of the diff view, 0 means the default */
    public int diffFontSize() {
        return prefs.getInt("diff.font.size", 0);
    }

    /** @param name null for the default, @param size 0 for the default */
    public void setDiffFont(String name, int size) {
        if (name == null) prefs.remove("diff.font.name");
        else prefs.put("diff.font.name", name);
        if (size <= 0) prefs.remove("diff.font.size");
        else prefs.putInt("diff.font.size", size);
        changed();
    }

    /** SourceTree's "Check default remotes for updates every N minutes", 0: never (default 10) */
    public int fetchInterval() {
        return prefs.getInt("remote.fetchInterval", 10);
    }

    public void setFetchInterval(int minutes) {
        prefs.putInt("remote.fetchInterval", Math.max(0, minutes));
        changed();
    }

    /** checks the spelling of commit messages (macOS's spell checker), default true */
    public boolean spellCheck() {
        return prefs.getBoolean("commit.spellCheck", true);
    }

    public void setSpellCheck(boolean check) {
        prefs.putBoolean("commit.spellCheck", check);
        changed();
    }

    /** true (default): amend, history rewrites, reset and undo of commits already pushed ask for an explicit override */
    public boolean protectPushed() {
        return prefs.getBoolean("history.protectPushed", true);
    }

    public void setProtectPushed(boolean protect) {
        prefs.putBoolean("history.protectPushed", protect);
        changed();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
