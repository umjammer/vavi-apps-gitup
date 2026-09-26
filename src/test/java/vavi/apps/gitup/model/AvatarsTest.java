/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * AvatarsTest. no network.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
class AvatarsTest {

    @Test
    void githubLogin() {
        assertEquals("octocat", Avatars.githubLogin("583231+octocat@users.noreply.github.com"));
        assertEquals("octocat", Avatars.githubLogin("octocat@users.noreply.github.com"));
        assertNull(Avatars.githubLogin("octocat@example.com"));
    }

    @Test
    void urls() {
        String gravatar = "https://www.gravatar.com/avatar/" + Avatars.hex("MD5", "t@example.com") + "?s=80&d=404";
        assertEquals(List.of(gravatar), Avatars.urls("t@example.com", null));
        assertEquals(List.of("https://avatars.githubusercontent.com/u/1?v=4&s=80", gravatar),
                Avatars.urls("t@example.com", "https://avatars.githubusercontent.com/u/1?v=4"));
        List<String> noreply = Avatars.urls("583231+octocat@users.noreply.github.com", null);
        assertEquals("https://avatars.githubusercontent.com/u/583231?s=80&v=4", noreply.getFirst());
        assertEquals("https://github.com/octocat.png?size=80", Avatars.urls("octocat@users.noreply.github.com", null).getFirst());
    }

    @Test
    void md5() {
        // Gravatar's documented example
        assertEquals("0bc83cb571cd1c50ba6f3e8a78ef1346", Avatars.hex("MD5", "myemailaddress@example.com"));
    }

    @Test
    void gitHubCommit() {
        assertEquals(new Avatars.GitHubCommit("umjammer", "vavi-apps-gitup", "abc"),
                Avatars.gitHubCommit("https://github.com/umjammer/vavi-apps-gitup.git", "abc"));
        assertEquals(new Avatars.GitHubCommit("umjammer", "vavi-apps-gitup", "abc"),
                Avatars.gitHubCommit("git@github.com:umjammer/vavi-apps-gitup.git", "abc"));
        assertEquals(new Avatars.GitHubCommit("o", "r", "abc"), Avatars.gitHubCommit("https://user@github.com/o/r", "abc"));
        assertNull(Avatars.gitHubCommit("https://gitlab.com/o/r.git", "abc"));
        assertNull(Avatars.gitHubCommit(null, "abc"));
    }

    @Test
    void parseCommit() {
        String json = """
                {"sha":"abc","commit":{"author":{"name":"A","email":"A@Example.com","date":"2026-01-01T00:00:00Z"},
                "committer":{"name":"GitHub","email":"noreply@github.com","date":"2026-01-01T00:00:00Z"},"message":"m"},
                "author":{"login":"a","id":1,"avatar_url":"https://avatars.githubusercontent.com/u/1?v=4","type":"User"},
                "committer":{"login":"web-flow","id":2,"avatar_url":"https://avatars.githubusercontent.com/u/2?v=4"},
                "parents":[]}
                """;
        assertEquals(Map.of("a@example.com", "https://avatars.githubusercontent.com/u/1?v=4",
                        "noreply@github.com", "https://avatars.githubusercontent.com/u/2?v=4"),
                Avatars.parseCommit(json));
        // an email GitHub does not know: "author": null
        String unknown = """
                {"commit":{"author":{"name":"A","email":"a@example.com"},"committer":{"name":"A","email":"a@example.com"}},
                "author":null,"committer":null}
                """;
        assertEquals(Map.of(), Avatars.parseCommit(unknown));
    }
}
