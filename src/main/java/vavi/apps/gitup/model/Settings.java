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

    /** the colors of the diff view, null means the theme's default */
    public enum DiffColor { ADDED, REMOVED, HUNK_HEADER, SELECTION }

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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
