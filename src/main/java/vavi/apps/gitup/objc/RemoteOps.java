/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;

import com.sun.jna.Pointer;
import org.rococoa.Foundation;
import org.rococoa.ID;
import org.rococoa.ObjCObject;
import org.rococoa.ObjCObjectByReference;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSArray;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;
import org.rococoa.cocoa.foundation.NSError;
import org.rococoa.cocoa.foundation.NSUInteger;

import vavi.apps.gitup.model.GitException;
import vavi.apps.gitup.objc.GCRepository.GCBranch;
import vavi.apps.gitup.objc.GCRepository.GCRemote;


/**
 * fetch / push through GitUpKit's GCRepository, which brings GitUp's
 * transport and ssh handling. credentials are asked through {@link Prompter}.
 * <p>
 * call from the git thread; prompts are expected to block until answered.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class RemoteOps implements AutoCloseable {

    /** asks the user for credentials, returns null when cancelled */
    public interface Prompter {
        /** @return {username, password} */
        String[] userPassword(String url, String user);
        String passphrase(String url, String privateKeyPath);
    }

    private final GCRepository repo;
    /** GCRepository.delegate is weak, keep the proxy here */
    private final ObjCObject delegateProxy;
    private final Delegate delegate;
    private vavi.apps.gitup.model.CommandLog commandLog = new vavi.apps.gitup.model.CommandLog();

    /** where the equivalent git commands go */
    public void setCommandLog(vavi.apps.gitup.model.CommandLog commandLog) {
        this.commandLog = commandLog;
    }

    public RemoteOps(Path workdir, Prompter prompter, Consumer<String> progress) {
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            ObjCObjectByReference e = new ObjCObjectByReference();
            GCRepository r = GCRepository.CLASS.alloc().initWithExistingLocalRepository_error(workdir.toString(), e);
            if (r == null) throw error("open", e);
            repo = r;
            delegate = new Delegate(prompter, progress);
            delegateProxy = Rococoa.proxy(delegate);
            repo.setDelegate(delegateProxy.id());
        } finally {
            pool.drain();
        }
    }

    private static GitException error(String what, ObjCObjectByReference e) {
        NSError error = e.getValueAs(NSError.class);
        return new GitException(what + ": " + (error != null ? error.localizedDescription() : "unknown error"));
    }

    /**
     * the error of a transfer. when credentials were asked, libgit2 1.4 often reports a later
     * unrelated error (e.g. "config value 'http.followRedirects' was not found"), so say what happened.
     */
    private GitException transferError(String what, ObjCObjectByReference e) {
        GitException ge = error(what, e);
        if (delegate.authAsked > 0) {
            String hint = delegate.authUrl != null && delegate.authUrl.contains("github.com")
                    ? " GitHub does not accept account passwords, use a personal access token as the password." : "";
            return new GitException(what + ": authentication failed for " + delegate.authUrl + "." + hint
                    + " (" + ge.getMessage().substring(what.length() + 2) + ")");
        }
        return ge;
    }

    /** fetches the default branches of every remote (with prune) */
    public void fetchAll() {
        delegate.reset();
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            ObjCObjectByReference e = new ObjCObjectByReference();
            NSArray remotes = repo.listRemotes(e);
            if (remotes == null) throw error("list remotes", e);
            for (int i = 0; i < remotes.count(); i++) {
                GCRemote remote = Rococoa.cast(remotes.objectAtIndex(i), GCRemote.class);
                commandLog.add("git fetch --prune " + vavi.apps.gitup.model.CommandLog.quote(remote.name()), "GitUpKit transport");
                if (!repo.fetchDefaultRemoteBranchesFromRemote_tagMode_prune_updatedTips_error(remote, 0, true, null, e)) {
                    throw transferError("fetch " + remote.name(), e);
                }
            }
        } finally {
            pool.drain();
        }
    }

    /** pushes a local branch to its upstream, or to "origin" setting the upstream when there is none */
    public void push(String localBranch, boolean hasUpstream) {
        delegate.reset();
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            ObjCObjectByReference e = new ObjCObjectByReference();
            GCBranch branch = repo.findLocalBranchWithName_error(localBranch, e);
            if (branch == null) throw error("branch " + localBranch, e);
            commandLog.add(hasUpstream ? "git push origin " + vavi.apps.gitup.model.CommandLog.quote(localBranch)
                    : "git push -u origin " + vavi.apps.gitup.model.CommandLog.quote(localBranch), "GitUpKit transport, to the upstream");
            boolean ok;
            if (hasUpstream) {
                ok = repo.pushLocalBranchToUpstream_force_usedRemote_error(branch, false, null, e);
            } else {
                GCRemote origin = repo.lookupRemoteWithName_error("origin", e);
                if (origin == null) throw error("remote origin", e);
                ok = repo.pushLocalBranch_toRemote_force_setUpstream_error(branch, origin, false, true, e);
            }
            if (!ok) throw transferError("push " + localBranch, e);
        } finally {
            pool.drain();
        }
    }

    /** deletes a branch on its remote, e.g. "origin/topic" */
    public void deleteRemoteBranch(String remoteBranch) {
        delegate.reset();
        int slash = remoteBranch.indexOf('/');
        commandLog.add("git push " + vavi.apps.gitup.model.CommandLog.quote(remoteBranch.substring(0, Math.max(slash, 0)))
                + " --delete " + vavi.apps.gitup.model.CommandLog.quote(remoteBranch.substring(slash + 1)), "GitUpKit transport");
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            ObjCObjectByReference e = new ObjCObjectByReference();
            GCBranch branch = repo.findRemoteBranchWithName_error(remoteBranch, e);
            if (branch == null) throw error("remote branch " + remoteBranch, e);
            if (!repo.deleteRemoteBranchFromRemote_error(branch, e)) throw transferError("delete " + remoteBranch, e);
        } finally {
            pool.drain();
        }
    }

    @Override
    public void close() {
        repo.setDelegate(null); // the rococoa wrapper releases repo when collected
    }

    /** GCRepositoryDelegate, methods are called by name through the rococoa proxy */
    public static class Delegate {

        private final Prompter prompter;
        private final Consumer<String> progress;

        /** credentials asked in the current operation (by GitUpKit, on the main thread) */
        volatile int authAsked;
        volatile String authUrl;

        void reset() {
            authAsked = 0;
            authUrl = null;
        }

        Delegate(Prompter prompter, Consumer<String> progress) {
            this.prompter = prompter;
            this.progress = progress;
        }

        private static String str(ID id) {
            return id == null || id.isNull() ? null : Foundation.toString(Foundation.sendReturnsID(id, "description"));
        }

        /** writes an autoreleased NSString to an {@code NSString**} */
        private static void out(long pointer, String value) {
            if (pointer == 0 || value == null) return;
            ID s = Foundation.cfString(value);
            Foundation.sendReturnsID(s, "autorelease");
            new Pointer(pointer).setLong(0, s.longValue());
        }

        public void repository_willStartTransferWithURL(ID repository, ID url) {
            progress.accept("transferring " + str(url));
        }

        public void repository_updateTransferProgress_transferredBytes(ID repository, float p, NSUInteger bytes) {
            progress.accept(String.format("transferring %d%% (%,d bytes)", Math.round(p * 100), bytes.longValue()));
        }

        public void repository_didFinishTransferWithURL_success(ID repository, ID url, boolean success) {
            progress.accept(success ? "done" : "failed");
        }

        // NSString** parameters are received as raw addresses

        public boolean repository_requiresPlainTextAuthenticationForURL_user_username_password(ID repository, ID url, ID user, long username, long password) {
            authAsked++;
            authUrl = str(url);
            String[] up = prompter.userPassword(str(url), str(user));
            if (up == null) return false;
            out(username, up[0]);
            out(password, up[1]);
            return true;
        }

        public boolean repository_requiresSSHAuthenticationForURL_user_username_publicKeyPath_privateKeyPath_passphrase(
                ID repository, ID url, ID user, long username, long publicKeyPath, long privateKeyPath, long passphrase) {
            authAsked++;
            authUrl = str(url);
            Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
            Path key = null;
            for (String name : new String[] {"id_ed25519", "id_ecdsa", "id_rsa"}) {
                if (Files.isRegularFile(ssh.resolve(name))) {
                    key = ssh.resolve(name);
                    break;
                }
            }
            if (key == null) return false;
            String pass = "";
            if (isEncrypted(key)) {
                pass = prompter.passphrase(str(url), key.toString());
                if (pass == null) return false;
            }
            String u = str(user);
            out(username, u != null ? u : "git");
            out(publicKeyPath, key + ".pub");
            out(privateKeyPath, key.toString());
            out(passphrase, pass);
            return true;
        }

        /** PEM "ENCRYPTED" or openssh-key-v1 with a cipher other than "none" */
        static boolean isEncrypted(Path key) {
            try {
                String s = Files.readString(key, StandardCharsets.US_ASCII);
                if (s.contains("ENCRYPTED")) return true;
                if (!s.contains("OPENSSH PRIVATE KEY")) return false;
                String b64 = s.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
                byte[] b = Base64.getDecoder().decode(b64);
                int p = "openssh-key-v1".length() + 1;
                int len = ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
                return !"none".equals(new String(b, p + 4, len, StandardCharsets.US_ASCII));
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
    }
}
