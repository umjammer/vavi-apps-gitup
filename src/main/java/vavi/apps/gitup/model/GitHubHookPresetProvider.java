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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vavi.apps.gitup.model.GitHooks.Preset;

import static java.lang.System.getLogger;


/**
 * hook scripts in a GitHub repository, the file tree by the GitHub API, the scripts by raw.githubusercontent.com.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-01 nsano initial version <br>
 */
public abstract class GitHubHookPresetProvider implements HookPresetProvider {

    private static final System.Logger logger = getLogger(GitHubHookPresetProvider.class.getName());

    private static final Pattern BLOB = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"mode\"\\s*:\\s*\"[0-9]+\"\\s*,\\s*\"type\"\\s*:\\s*\"blob\"");

    private final String repository;
    private final String branch;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /** @param repository owner/name */
    protected GitHubHookPresetProvider(String repository, String branch) {
        this.repository = repository;
        this.branch = branch;
    }

    @Override
    public String name() {
        return repository;
    }

    /** @return the hook name of the file, null when it is not a hook script */
    protected abstract String category(String path);

    /** a preset id unique among all presets */
    protected abstract String id(String path);

    @Override
    public List<Preset> presets() throws IOException {
        List<Preset> list = new ArrayList<>();
        for (String path : paths(get("https://api.github.com/repos/" + repository + "/git/trees/" + branch + "?recursive=1"))) {
            String category = category(path);
            if (category == null || !GitHooks.CATEGORIES.contains(category)) continue;
            String url = "https://github.com/" + repository + "/blob/" + branch + "/" + path;
            try {
                String script = get("https://raw.githubusercontent.com/" + repository + "/" + branch + "/" + path);
                list.add(new Preset(id(path), category, title(path), description(script), GitHooks.wrap(script, category), url));
            } catch (IOException | RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "hook preset: " + url + ": " + e.getMessage());
            }
        }
        return list;
    }

    /** the blob paths of a tree api response */
    static List<String> paths(String json) {
        List<String> list = new ArrayList<>();
        Matcher m = BLOB.matcher(json);
        while (m.find()) list.add(m.group(1));
        return list;
    }

    /** the file name without the extension */
    protected String title(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** the first comment line of the script that is not a shebang nor a source */
    static String description(String script) {
        for (String l : script.lines().toList()) {
            if (l.startsWith("#!")) continue;
            if (!l.startsWith("#")) {
                if (l.isBlank()) continue;
                break;
            }
            String s = l.replaceFirst("^#+", "").strip();
            if (s.isEmpty() || s.matches("(?i)(based on|source:|copyright|requirements:|to enable).*")) continue;
            return s;
        }
        return "";
    }

    private String get(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "vavi-apps-gitup").build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException(url + ": " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** https://github.com/CompSciLauren/awesome-git-hooks, {@code <hook>-hooks/<name>.hook} */
    public static class CompSciLauren extends GitHubHookPresetProvider {
        public CompSciLauren() {
            super("CompSciLauren/awesome-git-hooks", "master");
        }
        @Override protected String category(String path) {
            Matcher m = Pattern.compile("([a-z-]+)-hooks/[^/]+\\.hook").matcher(path);
            if (!m.matches()) return null;
            return m.group(1).equals("query-watchman") ? "fsmonitor-watchman" : m.group(1);
        }
        @Override protected String id(String path) {
            return "compscilauren." + title(path);
        }
    }

    /** https://github.com/aitemr/awesome-git-hooks, {@code <hook>/<hook>-<name>} */
    public static class Aitemr extends GitHubHookPresetProvider {
        public Aitemr() {
            super("aitemr/awesome-git-hooks", "master");
        }
        @Override protected String category(String path) {
            Matcher m = Pattern.compile("([a-z-]+)/([^/]+)").matcher(path);
            return m.matches() && m.group(2).startsWith(m.group(1) + "-") ? m.group(1) : null;
        }
        @Override protected String id(String path) {
            return "aitemr." + title(path);
        }
    }
}
