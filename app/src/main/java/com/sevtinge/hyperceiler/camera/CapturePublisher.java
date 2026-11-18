/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Publication is separate from exposure: every failure keeps the durable private JPEG. */
final class CapturePublisher {
    static void publish(Context context, CaptureRequestQueue queue, CaptureRequestQueue.Request r)
            throws IOException {
        if (!queue.jpeg(r).isFile() || queue.jpeg(r).length() < 4)
            throw new IOException("No durable JPEG to publish");
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = r.mediaUri.isEmpty() ? null : Uri.parse(r.mediaUri);
        boolean published = false;
        // Recover the insert->receipt crash window by the UUID name, never create a second item.
        if (uri == null) {
            try (Cursor c = resolver.query(collection,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING},
                    MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + "=?",
                    new String[]{r.displayName(), Environment.DIRECTORY_DCIM + "/Camera/"}, null)) {
                if (c == null) throw new IOException("Cannot reconcile MediaStore publication");
                if (c.moveToFirst()) {
                    uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0));
                    published = c.getInt(1) == 0;
                    if (c.moveToNext()) throw new IOException("Ambiguous UUID publication; refusing duplicates");
                }
            }
        } else {
            try (Cursor c = resolver.query(uri, new String[]{MediaStore.Images.Media.IS_PENDING}, null, null, null)) {
                if (c == null || !c.moveToFirst()) throw new IOException("Recorded MediaStore item unavailable");
                published = c.getInt(0) == 0;
            }
        }
        if (published) { queue.published(r); return; }
        if (uri == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, r.displayName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.Images.Media.DATE_TAKEN, r.acceptedWall);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("MediaStore insert returned null");
        }
        queue.mediaUri(r, uri.toString());
        try (FileInputStream in = new FileInputStream(queue.jpeg(r));
             OutputStream out = resolver.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("MediaStore output unavailable");
            byte[] chunk = new byte[64 * 1024];
            int size;
            while ((size = in.read(chunk)) != -1) out.write(chunk, 0, size);
            out.flush();
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (resolver.update(uri, ready, null, null) != 1) throw new IOException("MediaStore publication failed");
        // Once published, permission/display/deadline changes must not pretend this JPEG failed.
        queue.published(r);
    }
    private CapturePublisher() {}
}
