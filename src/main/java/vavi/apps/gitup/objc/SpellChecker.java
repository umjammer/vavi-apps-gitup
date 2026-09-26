/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.util.ArrayList;
import java.util.List;

import org.rococoa.Foundation;
import org.rococoa.ObjCClass;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSArray;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;
import org.rococoa.cocoa.foundation.NSObject;
import org.rococoa.cocoa.foundation.NSRange;

import vavi.apps.gitup.jna.LibGit2;


/**
 * macOS's spell checker (AppKit's NSSpellChecker), as SourceTree's commit message box
 * (an NSTextView) uses: the system languages, the user's learned words.
 * <p>
 * calls go to the main thread, do not call from the EDT (AppKit may be waiting for it).
 * offsets are UTF-16 indices, the same as Java's.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public final class SpellChecker {

    static {
        // AppKit comes with GitUpKit, loaded on the main thread
        LibGit2.INSTANCE.hashCode();
    }

    /** NSSpellChecker */
    public abstract static class NSSpellChecker extends NSObject {
        public static final _Class CLASS = Rococoa.createClass("NSSpellChecker", _Class.class);

        public interface _Class extends ObjCClass {
            NSSpellChecker sharedSpellChecker();
            long uniqueSpellDocumentTag();
        }

        /** @param language null: automatic */
        public abstract NSRange checkSpellingOfString_startingAt_language_wrap_inSpellDocumentWithTag_wordCount(
                String string, long start, String language, boolean wrap, long tag, com.sun.jna.Pointer wordCount);

        public abstract void ignoreWord_inSpellDocumentWithTag(String word, long tag);

        public abstract void learnWord(String word);

        public abstract boolean hasLearnedWord(String word);

        public abstract void unlearnWord(String word);

        public abstract void closeSpellDocumentWithTag(long tag);

        /**
         * deprecated in 10.6 but works. guessesForWordRange:inString:language:inSpellDocumentWithTag: gives nil,
         * rococoa does not pass its NSRange argument by value
         */
        public abstract NSArray guessesForWord(String word);

        /** the current language, e.g. "en" */
        public abstract String language();
    }

    /** a misspelled word */
    public record Range(int start, int end) {}

    /** the ignored words of this document ("Ignore Spelling") */
    private final long tag;

    public SpellChecker() {
        tag = Foundation.callOnMainThread(NSSpellChecker.CLASS::uniqueSpellDocumentTag);
    }

    private static NSSpellChecker checker() {
        return NSSpellChecker.CLASS.sharedSpellChecker();
    }

    /** @return the misspelled words of the text */
    public List<Range> check(String text) {
        return Foundation.callOnMainThread(() -> {
            NSAutoreleasePool pool = NSAutoreleasePool.new_();
            try {
                List<Range> list = new ArrayList<>();
                NSSpellChecker c = checker();
                int start = 0;
                while (start < text.length()) {
                    NSRange r = c.checkSpellingOfString_startingAt_language_wrap_inSpellDocumentWithTag_wordCount(text, start, null, false, tag, null);
                    long location = r.getLocation(), length = r.getLength();
                    if (length <= 0 || location < start || location >= text.length()) break; // NSNotFound
                    list.add(new Range((int) location, (int) (location + length)));
                    start = (int) (location + length);
                }
                return list;
            } finally {
                pool.drain();
            }
        });
    }

    /** @return suggestions for the word at the range of the text, best first */
    public List<String> guesses(String text, Range range) {
        return Foundation.callOnMainThread(() -> {
            NSAutoreleasePool pool = NSAutoreleasePool.new_();
            try {
                NSArray a = checker().guessesForWord(text.substring(range.start(), range.end()));
                List<String> list = new ArrayList<>();
                if (a != null) {
                    for (int i = 0; i < a.count(); i++) list.add(a.objectAtIndex(i).toString());
                }
                return list;
            } finally {
                pool.drain();
            }
        });
    }

    /** "Ignore Spelling": for this document (this checker) only */
    public void ignore(String word) {
        Foundation.runOnMainThread(() -> checker().ignoreWord_inSpellDocumentWithTag(word, tag));
    }

    /** "Learn Spelling": into the user's dictionary, shared with every application */
    public void learn(String word) {
        Foundation.runOnMainThread(() -> checker().learnWord(word));
    }
}
