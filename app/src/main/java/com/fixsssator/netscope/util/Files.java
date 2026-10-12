package com.fixsssator.netscope.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;

public final class Files {
    private Files() {}

    /** Saves bytes into the public Downloads folder (no storage permission needed on API 29+). */
    public static boolean saveToDownloads(Context ctx, String name, String mime, byte[] data) {
        try {
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) return false;
                out.write(data);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
