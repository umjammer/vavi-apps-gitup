/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import vavi.apps.gitup.model.Avatars;
import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Signature;
import vavi.apps.gitup.model.GitRepo.Signatures;
import vavi.apps.gitup.model.Settings;
import vavi.apps.gitup.ui.icons.IconProvider;


/**
 * SourceTree's commit details above the changed files of a commit.
 * <pre>
 *  (avatar)  Commit:    full sha [short sha]
 *            Parents:   short sha (a link to the parent)
 *            Author:    name &lt;email&gt;
 *            Date:      author date
 *            Committer: (avatar) name &lt;email&gt;
 *            Committed: committer date, when not the author date
 *            Labels:    branch / remote branch / tag labels as in the log
 *
 *  message
 * </pre>
 * the avatars come from GitHub or Gravatar ({@link Avatars}), without one SourceTree's "mystery man".
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
public class CommitInfoPanel extends JPanel implements Scrollable {

    /** SourceTree's avatar size */
    static final int AVATAR = 40;
    static final int SMALL_AVATAR = 16;

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm:ss zzz", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final JLabel avatar = new JLabel();
    private final JPanel fields = new JPanel(new GridBagLayout());
    private final JPanel details = new JPanel(new BorderLayout(10, 0));
    private final JTextArea message = new JTextArea();
    private final Color background;

    /** the commit shown, null for a range */
    private String oid;
    /** the github.com remote of the repository, null when none */
    private String gitHubRemote;
    private Consumer<String> parentListener = p -> {};
    /** where the avatars come from, null for none (tests) */
    Avatars avatars = Avatars.get();

    public CommitInfoPanel() {
        // a plain (non UIResource) color, so the look and feel does not gray out the read-only text
        Color white = UIManager.getColor("TextArea.background");
        background = new Color(white != null ? white.getRGB() : 0xffffff);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(background);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        avatar.setVerticalAlignment(SwingConstants.TOP);
        details.setOpaque(false);
        fields.setOpaque(false);
        details.add(avatar, BorderLayout.WEST);
        details.add(fields, BorderLayout.CENTER);
        details.setAlignmentX(0);
        details.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        add(details);

        message.setEditable(false);
        message.setBackground(background);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        message.setAlignmentX(0);
        add(message);
    }

    /** called with the full sha of a clicked parent */
    public void setParentListener(Consumer<String> l) {
        parentListener = l;
    }

    /** a multiple selection: the commits of the range, oldest parent to newest */
    public void showCommits(List<CommitRow> commits) {
        oid = null;
        details.setVisible(false);
        StringBuilder sb = new StringBuilder(commits.size() + " commits selected, changes from "
                + commits.getLast().shortOid() + "^ to " + commits.getFirst().shortOid() + "\n\n");
        for (CommitRow c : commits) sb.append(c.shortOid()).append("  ").append(c.summary()).append("  (").append(c.author()).append(")\n");
        setMessage(sb.toString());
    }

    /**
     * a commit, the committer and the avatars come with {@link #setSignatures}
     *
     * @param gitHubRemote the URL of a github.com remote of the repository, null when none
     */
    public void showCommit(CommitRow c, List<Ref> refs, String headBranch, String gitHubRemote) {
        oid = c.oid();
        this.gitHubRemote = gitHubRemote;
        rows.clear();
        row("Commit:", selectable(c.oid() + " [" + c.shortOid() + "]"));
        if (!c.parents().isEmpty()) row("Parents:", parents(c.parents()));
        row("Author:", selectable(c.author() + " <" + c.email() + ">"));
        row("Date:", selectable(DATE.format(c.time())));
        if (!refs.isEmpty()) row("Labels:", new Labels(refs, headBranch));
        layoutRows();
        avatar.setIcon(mysteryMan(AVATAR));
        details.setVisible(true);
        setMessage(c.message());
    }

    /** the committer and the avatars */
    public void setSignatures(String oid, Signatures s) {
        if (!oid.equals(this.oid)) return;
        JLabel committer = new JLabel(mysteryMan(SMALL_AVATAR));
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.setOpaque(false);
        p.add(committer, BorderLayout.WEST);
        p.add(selectable(s.committer().name() + " <" + s.committer().email() + ">"), BorderLayout.CENTER);
        rowAfter("Date:", "Committer:", p);
        // rebased, amended, cherry-picked or applied later
        if (!s.committer().time().toInstant().equals(s.author().time().toInstant())) {
            rowAfter("Committer:", "Committed:", selectable(DATE.format(s.committer().time())));
        }
        loadAvatar(oid, s.author(), AVATAR, avatar);
        loadAvatar(oid, s.committer(), SMALL_AVATAR, committer); // the same email is asked once
        revalidate();
        repaint();
    }

    private void loadAvatar(String oid, Signature who, int size, JLabel target) {
        if (avatars == null || !Settings.get().avatars()) return;
        Avatars.GitHubCommit commit = Avatars.gitHubCommit(gitHubRemote, oid);
        avatars.avatar(who.email(), commit).thenAccept(bytes -> {
            BufferedImage image = bytes.flatMap(CommitInfoPanel::decode).orElse(null);
            if (image == null) return;
            SwingUtilities.invokeLater(() -> {
                if (oid.equals(this.oid)) target.setIcon(new AvatarIcon(image, size));
            });
        });
    }

    private static Optional<BufferedImage> decode(byte[] b) {
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(b)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Icon mysteryMan(int size) {
        return IconProvider.get().icon(IconProvider.Key.AVATAR, size);
    }

    private void setMessage(String text) {
        message.setText(text);
        message.setCaretPosition(0);
        revalidate();
        repaint();
        scrollRectToVisible(new Rectangle(0, 0, 1, 1));
    }

    // rows

    /** a row: the key and the value */
    private record Row(String key, JComponent value) {}

    private final List<Row> rows = new java.util.ArrayList<>();

    private void row(String key, JComponent value) {
        rows.add(new Row(key, value));
    }

    /** adds a row after the row of the key (the last when missing) */
    private void rowAfter(String after, String key, JComponent value) {
        int i = 0;
        while (i < rows.size() && !rows.get(i).key().equals(after)) i++;
        rows.add(Math.min(i + 1, rows.size()), new Row(key, value));
        layoutRows();
    }

    /** puts the rows in the grid */
    private void layoutRows() {
        fields.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        for (int i = 0; i < rows.size(); i++) {
            JLabel k = new JLabel(rows.get(i).key());
            k.setForeground(Color.gray);
            k.setFont(k.getFont().deriveFont(Font.BOLD));
            g.gridy = i;
            g.gridx = 0;
            g.weightx = 0;
            g.anchor = GridBagConstraints.NORTHEAST;
            g.fill = GridBagConstraints.NONE;
            g.insets = new Insets(1, 0, 1, 0);
            fields.add(k, g);
            g.gridx = 1;
            g.weightx = 1;
            g.anchor = GridBagConstraints.NORTHWEST;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(1, 6, 1, 0);
            fields.add(rows.get(i).value(), g);
        }
        // the rows stay at the top next to a tall avatar
        g = new GridBagConstraints();
        g.gridy = rows.size();
        g.gridwidth = 2;
        g.weighty = 1;
        fields.add(Box.createGlue(), g);
        fields.revalidate();
    }

    /** the texts of the rows, "key value" per line, for tests */
    List<String> rows() {
        return rows.stream().map(r -> r.key() + " " + text(r.value())).toList();
    }

    private static String text(Component c) {
        if (c instanceof JTextField t) return t.getText();
        if (c instanceof JLabel l) return l.getText() != null ? l.getText() : "";
        if (c instanceof Labels l) return String.join(" ", l.refs.stream().map(Ref::shorthand).toList());
        if (c instanceof java.awt.Container p) {
            StringBuilder sb = new StringBuilder();
            for (Component x : p.getComponents()) sb.append(text(x));
            return sb.toString();
        }
        return "";
    }

    /** a read only text that can be selected and copied, looking like a label */
    private static JTextField selectable(String text) {
        JTextField f = new JTextField(text);
        f.setEditable(false);
        f.setBorder(null);
        f.setOpaque(false);
        f.setFont(UIManager.getFont("Label.font"));
        f.setForeground(UIManager.getColor("Label.foreground"));
        // selectable without a blinking caret, as a label
        f.setCaret(new javax.swing.text.DefaultCaret() {
            @Override public void paint(Graphics g) {}
        });
        f.setCaretPosition(0);
        // the preferred width must not widen the pane
        f.setColumns(1);
        return f;
    }

    /** SourceTree's parent links */
    private JComponent parents(List<String> parents) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < parents.size(); i++) {
            String sha = parents.get(i);
            if (i > 0) p.add(new JLabel(", "));
            JLabel link = new JLabel(sha.substring(0, 7));
            link.setForeground(new Color(0x0969da));
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.setToolTipText("Select " + sha);
            link.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { parentListener.accept(sha); }
            });
            p.add(link);
        }
        return p;
    }

    /** the ref labels of the log, wrapped to the width */
    static class Labels extends JComponent {
        final List<Ref> refs;
        final String headBranch;
        private int lastRows = 1;

        Labels(List<Ref> refs, String headBranch) {
            this.refs = refs;
            this.headBranch = headBranch;
            setFont(UIManager.getFont("Label.font"));
            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) {
                    if (layoutRows(getWidth()) != lastRows) revalidate();
                }
            });
        }

        private int rowHeight() {
            return getFontMetrics(getFont()).getHeight() + 6;
        }

        private boolean head(Ref r) {
            return r.kind() == Ref.Kind.LOCAL && r.shorthand().equals(headBranch);
        }

        /** the x of each label and its row, [x, row] */
        private int[][] positions(Graphics2D g, int width) {
            int[][] pos = new int[refs.size()][2];
            int x = 0, row = 0;
            for (int i = 0; i < refs.size(); i++) {
                int w = LogPanel.labelWidth(g, getFont(), refs.get(i), head(refs.get(i)));
                if (x > 0 && x + w > width) {
                    x = 0;
                    row++;
                }
                pos[i][0] = x;
                pos[i][1] = row;
                x += w + 4;
            }
            return pos;
        }

        private int layoutRows(int width) {
            if (refs.isEmpty()) return 1;
            BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scratch.createGraphics();
            try {
                g.setFont(getFont());
                int[][] pos = positions(g, width <= 0 ? Integer.MAX_VALUE : width);
                return pos[pos.length - 1][1] + 1;
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            lastRows = layoutRows(getWidth());
            return new Dimension(10, lastRows * rowHeight());
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int h = rowHeight();
                int[][] pos = positions(g, getWidth());
                for (int i = 0; i < refs.size(); i++) {
                    Graphics2D r = (Graphics2D) g.create(0, pos[i][1] * h, getWidth(), h);
                    try {
                        LogPanel.paintLabel(this, r, getFont(), refs.get(i), head(refs.get(i)), pos[i][0], h);
                    } finally {
                        r.dispose();
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** an avatar clipped to a circle, drawn at the device resolution */
    record AvatarIcon(BufferedImage image, int size) implements Icon {
        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                Ellipse2D circle = new Ellipse2D.Double(x, y, size, size);
                g.clip(circle);
                g.drawImage(image, x, y, size, size, null);
            } finally {
                g.dispose();
            }
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    // Scrollable: follows the width of the viewport, the message wraps

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 16); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
