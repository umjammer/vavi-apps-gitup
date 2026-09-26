/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.getLogger;


/**
 * the avatars of commit authors and committers, like SourceTree's commit details.
 * <p>
 * looked up in this order:
 * <ol>
 * <li>GitHub's noreply emails ({@code 123+login@users.noreply.github.com}): the account's avatar</li>
 * <li>a github.com remote: the commit on GitHub ({@code /repos/:owner/:repo/commits/:sha}),
 *  the avatars of the accounts GitHub matched the emails to (a saved github.com account's token is used when there is one)</li>
 * <li>Gravatar (the MD5 of the email, nothing when there is none)</li>
 * </ol>
 * images are kept in {@code ~/Library/Caches/vavi-apps-gitup/avatars} for a week, emails without one for a day.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
public final class Avatars {

    private static final System.Logger logger = getLogger(Avatars.class.getName());

    /** the pixels asked for, a 2x bitmap of SourceTree's 40pt avatar */
    public static final int PIXELS = 80;

    private static final Duration FOUND_TTL = Duration.ofDays(7);
    private static final Duration MISSING_TTL = Duration.ofDays(1);

    /** a commit on a GitHub repository, for the GitHub API */
    public record GitHubCommit(String owner, String repo, String sha) {}

    private final Path dir;
    private final HttpClient http;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "avatars");
        t.setDaemon(true);
        return t;
    });
    /** email (lower case) → image bytes, empty when there is none */
    private final Map<String, CompletableFuture<Optional<byte[]>>> memory = new ConcurrentHashMap<>();
    /** emails GitHub gave avatar URLs for, filled by the commit lookups */
    private final Map<String, String> githubUrls = new ConcurrentHashMap<>();
    /** commits already asked to GitHub */
    private final Map<GitHubCommit, CompletableFuture<Void>> githubCommits = new ConcurrentHashMap<>();

    Avatars(Path dir, HttpClient http) {
        this.dir = dir;
        this.http = http;
    }

    public static Avatars get() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final Avatars INSTANCE = new Avatars(
                Path.of(System.getProperty("user.home"), "Library", "Caches", "vavi-apps-gitup", "avatars"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    /**
     * the image (png / jpeg bytes) of the email's avatar, empty when there is none.
     *
     * @param commit the commit on GitHub the email is taken from, null when the repository is not on github.com
     */
    public CompletableFuture<Optional<byte[]>> avatar(String email, GitHubCommit commit) {
        if (email == null || email.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        String key = email.strip().toLowerCase(Locale.ROOT);
        CompletableFuture<Optional<byte[]>> f = memory.get(key);
        if (f != null) return f;
        // GitHub first when it may know the email, the result is remembered for the email
        CompletableFuture<Void> github = commit != null && githubLogin(key) == null
                ? githubCommits.computeIfAbsent(commit, c -> CompletableFuture.runAsync(() -> askGitHub(c), pool))
                : CompletableFuture.completedFuture(null);
        return memory.computeIfAbsent(key, k -> github.handle((v, t) -> null).thenApplyAsync(v -> load(k), pool));
    }

    /** from the disk cache or the network */
    private Optional<byte[]> load(String email) {
        String name = hex("SHA-256", email);
        Path image = dir.resolve(name + ".img");
        Path missing = dir.resolve(name + ".none");
        try {
            if (fresh(image, FOUND_TTL)) return Optional.of(Files.readAllBytes(image));
            if (fresh(missing, MISSING_TTL)) return Optional.empty();
            for (String url : urls(email, githubUrls.get(email))) {
                byte[] b = fetch(url, null);
                if (b != null) {
                    Files.createDirectories(dir);
                    Files.write(image, b);
                    Files.deleteIfExists(missing);
                    return Optional.of(b);
                }
            }
            Files.createDirectories(dir);
            Files.write(missing, new byte[0]);
        } catch (IOException e) {
            logger.log(System.Logger.Level.DEBUG, "avatar " + email + ": " + e);
        }
        return Optional.empty();
    }

    private static boolean fresh(Path p, Duration ttl) throws IOException {
        return Files.exists(p) && Files.getLastModifiedTime(p).toInstant().plus(ttl).isAfter(Instant.now());
    }

    private static final Pattern NOREPLY = Pattern.compile("(?:(\\d+)\\+)?([^@+]+)@users\\.noreply\\.github\\.com");

    /** the login of a GitHub noreply email, null for another email */
    static String githubLogin(String email) {
        Matcher m = NOREPLY.matcher(email);
        return m.matches() ? m.group(2) : null;
    }

    /** the images to try, in order */
    static List<String> urls(String email, String githubUrl) {
        List<String> urls = new java.util.ArrayList<>();
        if (githubUrl != null) urls.add(sized(githubUrl));
        Matcher m = NOREPLY.matcher(email);
        if (m.matches()) {
            urls.add(m.group(1) != null
                    ? "https://avatars.githubusercontent.com/u/" + m.group(1) + "?s=" + PIXELS + "&v=4"
                    : "https://github.com/" + m.group(2) + ".png?size=" + PIXELS);
        }
        urls.add("https://www.gravatar.com/avatar/" + hex("MD5", email) + "?s=" + PIXELS + "&d=404");
        return urls;
    }

    /** a GitHub avatar URL asking for our size */
    private static String sized(String url) {
        return url + (url.contains("?") ? "&" : "?") + "s=" + PIXELS;
    }

    /** the owner and the repository of a github.com remote URL, null for another host */
    public static GitHubCommit gitHubCommit(String remoteUrl, String sha) {
        if (remoteUrl == null || !"github.com".equalsIgnoreCase(Accounts.host(remoteUrl))) return null;
        Matcher m = Pattern.compile("github\\.com[:/]+([^/]+)/([^/]+?)(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE).matcher(remoteUrl);
        return m.find() ? new GitHubCommit(m.group(1), m.group(2), sha) : null;
    }

    private static final Pattern USER = Pattern.compile("\"(author|committer)\"\\s*:\\s*\\{([^{}]*)}");
    private static final Pattern COMMIT_USER = Pattern.compile("\"(author|committer)\"\\s*:\\s*\\{[^{}]*?\"email\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern AVATAR_URL = Pattern.compile("\"avatar_url\"\\s*:\\s*\"([^\"]+)\"");

    /** remembers the avatars GitHub has for the author and the committer of the commit */
    private void askGitHub(GitHubCommit c) {
        String token = null;
        try {
            Accounts.Account a = Accounts.get().find("https://github.com/", null);
            if (a != null) token = Accounts.get().secret(a);
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.DEBUG, "github token: " + e);
        }
        byte[] b = fetch("https://api.github.com/repos/" + c.owner() + "/" + c.repo() + "/commits/" + c.sha(), token);
        if (b == null) return;
        githubUrls.putAll(parseCommit(new String(b, StandardCharsets.UTF_8)));
    }

    /**
     * the avatar URLs of the author and the committer in a GitHub commit JSON, by their email.
     * {@code commit.author.email} is the git author, the top level {@code author.avatar_url} the account GitHub matched.
     */
    static Map<String, String> parseCommit(String json) {
        Map<String, String> emails = new LinkedHashMap<>(); // role → email
        Matcher m = COMMIT_USER.matcher(json);
        while (m.find()) emails.putIfAbsent(m.group(1), m.group(2).toLowerCase(Locale.ROOT));
        Map<String, String> urls = new LinkedHashMap<>();
        m = USER.matcher(json);
        while (m.find()) {
            Matcher a = AVATAR_URL.matcher(m.group(2));
            String email = emails.get(m.group(1));
            if (a.find() && email != null) urls.putIfAbsent(email, a.group(1));
        }
        return urls;
    }

    /** @return the body of a 200, null otherwise */
    private byte[] fetch(String url, String token) {
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "vavi-apps-gitup");
            if (url.startsWith("https://api.github.com/")) {
                r.header("Accept", "application/vnd.github+json");
                if (token != null && !token.isEmpty()) r.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> res = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200) return res.body();
            logger.log(System.Logger.Level.DEBUG, url + ": " + res.statusCode());
        } catch (IOException | IllegalArgumentException e) {
            logger.log(System.Logger.Level.DEBUG, url + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    static String hex(String algorithm, String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
