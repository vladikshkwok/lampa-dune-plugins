package org.duckdns.dunevoice.phone;

import android.content.Context;
import java.io.*;
import java.util.zip.*;

final class ModelFiles {
    private ModelFiles() {}
    static synchronized String prepare(Context context) throws IOException {
        File model = new File(context.getFilesDir(), "vosk-small-ru-0.22");
        File ready = new File(model, ".ready");
        if (ready.isFile()) return model.getAbsolutePath();
        remove(model);
        if (!model.mkdirs()) throw new IOException("model directory");
        boolean complete = false;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("model-ru.zip"))) {
            ZipEntry entry; long total = 0; byte[] buffer = new byte[32768];
            String root = model.getCanonicalPath() + File.separator;
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                String name = entry.getName(); int slash = name.indexOf('/');
                if (slash < 0) continue;
                name = name.substring(slash + 1); if (name.isEmpty()) continue;
                File target = new File(model, name);
                if (!target.getCanonicalPath().startsWith(root)) throw new IOException("model path");
                if (entry.isDirectory()) { if (!target.isDirectory() && !target.mkdirs()) throw new IOException("mkdir"); continue; }
                File parent = target.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir");
                try (OutputStream out = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count; if (total > 300L * 1024 * 1024) throw new IOException("model limit");
                        out.write(buffer, 0, count);
                    }
                }
            }
            if (!new File(model, "am/final.mdl").isFile() || !new File(model, "conf/model.conf").isFile()) throw new IOException("model incomplete");
            if (!ready.createNewFile()) throw new IOException("ready marker");
            complete = true; return model.getAbsolutePath();
        } finally { if (!complete) remove(model); }
    }
    private static void remove(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists()) file.delete();
    }
}
