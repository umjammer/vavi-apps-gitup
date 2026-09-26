/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Signature;
import vavi.apps.gitup.model.GitRepo.Signatures;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * CommitInfoPanelTest. no avatars (network).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
class CommitInfoPanelTest {

    static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    static final String PARENT = "fedcba9876543210fedcba9876543210fedcba98";
    static final Instant T = Instant.parse("2026-01-02T03:04:05Z");

    CommitInfoPanel panel() {
        CommitInfoPanel p = new CommitInfoPanel();
        p.avatars = null;
        return p;
    }

    CommitRow row() {
        return new CommitRow(SHA, List.of(PARENT), "summary", "summary\n\nbody\n", "A", "a@example.com", T, 0, new String[0], new String[0]);
    }

    @Test
    void sourceTreeRows() {
        CommitInfoPanel p = panel();
        List<Ref> refs = List.of(new Ref("refs/heads/main", "main", Ref.Kind.LOCAL, SHA),
                new Ref("refs/remotes/origin/main", "origin/main", Ref.Kind.REMOTE, SHA));
        p.showCommit(row(), refs, "main", null);
        String date = CommitInfoPanel.DATE.format(T);
        assertEquals(List.of("Commit: " + SHA + " [0123456]", "Parents: fedcba9", "Author: A <a@example.com>",
                "Date: " + date, "Labels: main origin/main"), p.rows());

        // the committer comes later, with the commit date when not the author date
        Signature author = new Signature("A", "a@example.com", T.atZone(ZoneOffset.UTC));
        Signature committer = new Signature("GitHub", "noreply@github.com", T.plusSeconds(60).atZone(ZoneOffset.UTC));
        p.setSignatures(SHA, new Signatures(author, committer));
        List<String> expected = new ArrayList<>(List.of("Commit: " + SHA + " [0123456]", "Parents: fedcba9", "Author: A <a@example.com>",
                "Date: " + date, "Committer: GitHub <noreply@github.com>", "Committed: " + CommitInfoPanel.DATE.format(T.plusSeconds(60)),
                "Labels: main origin/main"));
        assertEquals(expected, p.rows());
    }

    @Test
    void sameDateNoCommittedRow() {
        CommitInfoPanel p = panel();
        p.showCommit(row(), List.of(), null, null);
        Signature author = new Signature("A", "a@example.com", T.atZone(ZoneOffset.UTC));
        p.setSignatures(SHA, new Signatures(author, author));
        assertEquals(List.of("Commit: " + SHA + " [0123456]", "Parents: fedcba9", "Author: A <a@example.com>",
                "Date: " + CommitInfoPanel.DATE.format(T), "Committer: A <a@example.com>"), p.rows());
    }

    @Test
    void staleSignaturesIgnored() {
        CommitInfoPanel p = panel();
        p.showCommit(row(), List.of(), null, null);
        Signature s = new Signature("B", "b@example.com", T.atZone(ZoneOffset.UTC));
        p.setSignatures(PARENT, new Signatures(s, s));
        assertEquals(4, p.rows().size());
    }

    @Test
    void parentLink() {
        CommitInfoPanel p = panel();
        List<String> clicked = new ArrayList<>();
        p.setParentListener(clicked::add);
        p.showCommit(row(), List.of(), null, null);
        javax.swing.JLabel link = find(p, "fedcba9");
        for (var l : link.getMouseListeners()) l.mouseClicked(new java.awt.event.MouseEvent(link, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
        assertEquals(List.of(PARENT), clicked);
    }

    static javax.swing.JLabel find(java.awt.Container c, String text) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JLabel l && text.equals(l.getText())) return l;
            if (x instanceof java.awt.Container k) {
                javax.swing.JLabel l = find(k, text);
                if (l != null) return l;
            }
        }
        return null;
    }
}
