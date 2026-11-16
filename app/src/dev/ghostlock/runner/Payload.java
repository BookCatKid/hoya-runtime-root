package dev.ghostlock.runner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Ships ghostpw inside the APK assets and places it in the app's private files dir. */
final class Payload {

    static final String NAME = "ghostpw";

    private Payload() {
    }

    static File dir(Context ctx) {
        return ctx.getFilesDir();
    }

    static File binary(Context ctx) throws IOException {
        File out = new File(dir(ctx), NAME);
        long assetSize = assetSize(ctx);
        boolean fresh = !out.exists() || out.length() != assetSize;
        if (fresh) {
            copy(ctx, NAME, out);
        }
        // Exec from the app data dir only works with targetSdk <= 28, which this app uses.
        if (!out.setExecutable(true, true)) {
            throw new IOException("cannot chmod +x " + out);
        }
        return out;
    }

    private static long assetSize(Context ctx) {
        try {
            return ctx.getAssets().openFd(NAME).getLength();
        } catch (IOException e) {
            return -1;
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
