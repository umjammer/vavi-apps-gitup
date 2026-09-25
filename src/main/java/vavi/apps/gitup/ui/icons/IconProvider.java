/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import javax.swing.Icon;

import static java.lang.System.getLogger;


/**
 * supplies the icons of the application.
 * <p>
 * {@code -Dgitup.icons=sourcetree} uses the icons of an installed SourceTree.app, read from
 * its bundle at runtime (nothing of SourceTree is distributed with this application),
 * {@code builtin} the application's own drawn icons. by default SourceTree's are used when
 * SourceTree.app is found.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public interface IconProvider {

    /** the icons the application asks for */
    enum Key {
        // toolbar
        COMMIT, PULL, PUSH, FETCH, BRANCH, STASH, DISCARD, REFRESH, TAG, MERGE,
        // sidebar
        BRANCHES, REMOTES, TAGS, STASHES, LOCAL_BRANCH, REMOTE_BRANCH, TAG_ITEM, STASH_ITEM,
        // repository browser
        FOLDER, REPOSITORY,
        // file status
        FILE_ADDED, FILE_MODIFIED, FILE_DELETED, FILE_RENAMED, FILE_UNTRACKED, FILE_CONFLICTED, FILE_TYPECHANGE
    }

    /** @return the icon of about size x size, null when this provider has none */
    Icon icon(Key key, int size);

    String name();

    /** loads what must not be loaded on the EDT, call before the UI starts */
    default void preload() {}

    /** the provider chosen by {@code gitup.icons} */
    static IconProvider get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private Holder() {}

        static final IconProvider INSTANCE = create();

        private static IconProvider create() {
            String p = System.getProperty("gitup.icons", "auto");
            if (!p.equals("builtin")) {
                try {
                    IconProvider st = SourceTreeIconProvider.find();
                    if (st != null) return new FallbackIconProvider(st, new BuiltinIconProvider());
                } catch (Throwable t) {
                    getLogger(IconProvider.class.getName()).log(System.Logger.Level.WARNING, "SourceTree icons: " + t.getMessage(), t);
                }
            }
            return new BuiltinIconProvider();
        }
    }

    /** asks the first, then the second */
    record FallbackIconProvider(IconProvider first, IconProvider second) implements IconProvider {
        @Override public Icon icon(Key key, int size) {
            Icon i = first.icon(key, size);
            return i != null ? i : second.icon(key, size);
        }

        @Override public String name() {
            return first.name() + "+" + second.name();
        }

        @Override public void preload() {
            first.preload();
            second.preload();
        }
    }
}
