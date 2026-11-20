package dev.ghostlock.runner;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Ships ghostpw as an APK asset into the app's files dir. */
final class Payload {

    static final String NAME = "ghostpw";

    private Payload() {
    }

    static File dir(Context ctx) {
        return ctx.getFilesDir();
    }

    static File binary(Context ctx) throws IOException {
        File out = new File(dir(ctx), NAME);
        boolean fresh = !out.exists();
        if (!fresh) {
            // Size alone misses a same-size replacement or corruption.
            fresh = !Arrays.equals(assetSha256(ctx), fileSha256(out));
        }
        if (fresh) {
            copy(ctx, NAME, out);
        }
        if (!out.setExecutable(true, true)) {
            throw new IOException("cannot chmod +x " + out);
        }
        return out;
    }

    /**
     * SHA-256 of the asset. Streams it instead of using openFd, which
     * fails on compressed assets and would force a re-extract every run.
     */
    private static byte[] assetSha256(Context ctx) throws IOException {
        InputStream in = ctx.getAssets().open(NAME);
        try {
            return sha256(in);
        } finally {
            in.close();
        }
    }

    private static byte[] fileSha256(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return sha256(in);
        } finally {
            in.close();
        }
    }

    private static byte[] sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void copy(Context ctx, String asset, File out) throws IOException {
        File tmp = new File(out.getParentFile(), asset + ".tmp");
        InputStream in = ctx.getAssets().open(asset);
        try {
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
        if (out.exists() && !out.delete()) {
            throw new IOException("cannot replace " + out);
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("cannot rename " + tmp);
        }
    }
}
