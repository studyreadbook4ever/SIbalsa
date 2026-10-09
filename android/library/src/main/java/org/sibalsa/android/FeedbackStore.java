// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.sibalsa.core.AdapterSnapshot;
import org.sibalsa.core.Feedback;
import org.sibalsa.core.Lease;
import org.sibalsa.core.ModelSpec;
import org.sibalsa.core.ServerLimits;
import org.sibalsa.core.UpdatePayload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** App-private credential-encrypted SQLite data; neither backup nor wire serialization is enabled. */
final class FeedbackStore extends SQLiteOpenHelper {
    FeedbackStore(Context context) { super(context, "sibalsa-private.db", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        try (Cursor result = db.rawQuery("PRAGMA secure_delete=ON", null)) { result.moveToFirst(); }
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, model TEXT NOT NULL, spec TEXT NOT NULL, " +
                "dimension INTEGER NOT NULL, prompt TEXT NOT NULL, response TEXT NOT NULL, kind TEXT NOT NULL, " +
                "rating INTEGER NOT NULL, chosen TEXT NOT NULL, rejected TEXT NOT NULL, note TEXT NOT NULL, " +
                "reasons TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX pending_feedback ON feedback(model,spec,dimension,used,id)");
        db.execSQL("CREATE TABLE pending_upload (singleton INTEGER PRIMARY KEY CHECK(singleton=1), data BLOB NOT NULL)");
        db.execSQL("CREATE TABLE adapter (singleton INTEGER PRIMARY KEY CHECK(singleton=1), model TEXT NOT NULL, " +
                "spec TEXT NOT NULL, dimension INTEGER NOT NULL, version INTEGER NOT NULL, data BLOB NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Explicit private data migration required");
    }
    synchronized long insert(Feedback f, int maxFeedback) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("model", f.model.modelId); v.put("spec", f.model.adapterSpec); v.put("dimension", f.model.dimension);
        v.put("prompt", f.prompt); v.put("response", f.response); v.put("kind", f.kind.name());
        v.put("rating", f.rating); v.put("chosen", f.chosen); v.put("rejected", f.rejected); v.put("note", f.note);
        StringBuilder reasons = new StringBuilder();
        for (Feedback.Reason reason : f.reasons) { if (reasons.length() != 0) reasons.append(','); reasons.append(reason.name()); }
        v.put("reasons", reasons.toString());
        db.beginTransaction();
        try {
            long size;
            try (Cursor count = db.rawQuery("SELECT count(*) FROM feedback", null)) { count.moveToFirst(); size = count.getLong(0); }
            if (size >= maxFeedback) {
                // Never evict pending feedback or feedback referenced by a queued upload.
                int deleted = db.delete("feedback", "id IN (SELECT id FROM feedback WHERE used=1 ORDER BY id LIMIT ?)",
                        new String[]{Long.toString(size - maxFeedback + 1)});
                if (deleted < size - maxFeedback + 1) throw new IllegalStateException("Feedback storage full; delete local feedback to continue");
            }
            long id = db.insertOrThrow("feedback", null, v);
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }
    synchronized List<Feedback> pending(ModelSpec model, int limit, Predicate<Feedback> accepts) {
        List<Feedback> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("feedback", null,
                "model=? AND spec=? AND dimension=? AND used=0",
                new String[]{model.modelId, model.adapterSpec, Integer.toString(model.dimension)}, null, null, "id")) {
            while (cursor.moveToNext() && result.size() < limit) {
                Feedback feedback = readFeedback(cursor, model);
                if (accepts.test(feedback)) result.add(feedback);
            }
        }
        return result;
    }
    private static Feedback readFeedback(Cursor c, ModelSpec model) {
        List<Feedback.Reason> reasons = new ArrayList<>();
        String raw = text(c, "reasons");
        if (!raw.isEmpty()) for (String value : raw.split(",")) reasons.add(Feedback.Reason.valueOf(value));
        return new Feedback(c.getLong(c.getColumnIndexOrThrow("id")), model, text(c, "prompt"), text(c, "response"),
                Feedback.Kind.valueOf(text(c, "kind")), c.getInt(c.getColumnIndexOrThrow("rating")),
                text(c, "chosen"), text(c, "rejected"), text(c, "note"), reasons);
    }
    private static String text(Cursor c, String key) { return c.getString(c.getColumnIndexOrThrow(key)); }
    synchronized void delete(long id) {
        // Deleting training evidence also abandons any queued contribution derived from it.
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("pending_upload", null, null);
            db.delete("feedback", "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("pending_upload", null, null); db.delete("feedback", null, null); db.delete("adapter", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Deleted SQLite pages are overwritten before reuse; flash storage does not promise physical erasure.
        try (Cursor result = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { result.moveToFirst(); }
        db.execSQL("VACUUM");
    }
    synchronized void saveQueue(QueuedUpdate queue) throws IOException {
        ContentValues values = new ContentValues();
        values.put("singleton", 1); values.put("data", encodeQueue(queue));
        if (getWritableDatabase().insertWithOnConflict("pending_upload", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IOException("Could not persist upload queue");
    }
    synchronized QueuedUpdate queue() throws IOException {
        byte[] data;
        try (Cursor cursor = getReadableDatabase().query("pending_upload", new String[]{"data"}, null, null, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            data = cursor.getBlob(0);
        }
        // A v1 queue had no server origin: discard that contribution rather than send it to a new operator.
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            clearQueue();
            return null;
        }
        return decodeQueue(data);
    }
    synchronized void clearQueue() { getWritableDatabase().delete("pending_upload", null, null); }
    synchronized void clearAdapter() { getWritableDatabase().delete("adapter", null, null); }
    synchronized void accepted(QueuedUpdate queue) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues used = new ContentValues(); used.put("used", 1);
            for (long id : queue.feedbackIds) db.update("feedback", used, "id=?", new String[]{Long.toString(id)});
            db.delete("pending_upload", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void saveAdapter(AdapterSnapshot snapshot) throws IOException {
        AdapterSnapshot previous = adapter(snapshot.model);
        if (previous != null && snapshot.baseVersion < previous.baseVersion) throw new IOException("Adapter rollback rejected");
        ContentValues values = new ContentValues();
        values.put("singleton", 1); values.put("model", snapshot.model.modelId); values.put("spec", snapshot.model.adapterSpec);
        values.put("dimension", snapshot.model.dimension); values.put("version", snapshot.baseVersion);
        values.put("data", encodeDoubles(snapshot.adapter()));
        if (getWritableDatabase().insertWithOnConflict("adapter", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IOException("Could not persist adapter");
    }
    synchronized AdapterSnapshot adapter(ModelSpec model) throws IOException {
        try (Cursor cursor = getReadableDatabase().query("adapter", new String[]{"version", "data"},
                "model=? AND spec=? AND dimension=?", new String[]{model.modelId, model.adapterSpec, Integer.toString(model.dimension)},
                null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return new AdapterSnapshot(model, cursor.getLong(0), decodeDoubles(cursor.getBlob(1), model.dimension));
        }
    }
    static final class QueuedUpdate {
        final UpdatePayload payload;
        final long[] feedbackIds;
        final String serverOrigin;
        QueuedUpdate(UpdatePayload payload, long[] feedbackIds, String serverOrigin) {
            if (payload == null || feedbackIds == null || serverOrigin == null ||
                    org.sibalsa.core.SiBalsaConfig.builder().server(serverOrigin).build().server == null)
                throw new IllegalArgumentException("Queue requires its HTTPS server origin");
            this.payload = payload; this.feedbackIds = feedbackIds.clone(); this.serverOrigin = serverOrigin;
        }
    }
    private static byte[] encodeDoubles(double[] values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { for (double value : values) out.writeDouble(value); }
        return bytes.toByteArray();
    }
    private static double[] decodeDoubles(byte[] bytes, int dimension) throws IOException {
        if (bytes.length != dimension * 8) throw new IOException("Corrupt local adapter");
        double[] values = new double[dimension];
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < dimension; i++) values[i] = in.readDouble();
        }
        return values;
    }
    private static byte[] encodeQueue(QueuedUpdate queue) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            UpdatePayload p = queue.payload;
            Lease l = p.lease;
            out.writeInt(2); out.writeUTF(queue.serverOrigin); out.writeUTF(l.leaseId); out.writeUTF(l.nonce); out.writeLong(l.expiresAt);
            out.writeUTF(l.snapshot.model.modelId); out.writeUTF(l.snapshot.model.adapterSpec); out.writeInt(l.snapshot.model.dimension);
            out.writeLong(l.snapshot.baseVersion);
            for (double value : l.snapshot.adapter()) out.writeDouble(value);
            out.writeInt(l.limits.maxSamples); out.writeInt(l.limits.maxSteps); out.writeDouble(l.limits.maxL2);
            out.writeDouble(l.limits.maxCoordinate); out.writeInt(l.powBits); out.writeUTF(p.proof);
            out.writeInt(p.sampleCount); out.writeInt(p.steps);
            for (double value : p.delta()) out.writeDouble(value);
            out.writeInt(queue.feedbackIds.length);
            for (long id : queue.feedbackIds) out.writeLong(id);
        }
        return bytes.toByteArray();
    }
    private static QueuedUpdate decodeQueue(byte[] bytes) throws IOException {
        if (bytes.length > 20 * 1024 * 1024) throw new IOException("Oversized local queue");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 2) throw new IOException("Unknown local queue version");
            String origin = in.readUTF();
            String id = in.readUTF(), nonce = in.readUTF(); long expires = in.readLong();
            ModelSpec model = new ModelSpec(in.readUTF(), in.readUTF(), in.readInt());
            long version = in.readLong();
            double[] base = new double[model.dimension];
            for (int i = 0; i < base.length; i++) base[i] = in.readDouble();
            ServerLimits limits = new ServerLimits(in.readInt(), in.readInt(), in.readDouble(), in.readDouble());
            Lease lease = new Lease(id, nonce, expires, new AdapterSnapshot(model, version, base), limits, in.readInt());
            String proof = in.readUTF(); int samples = in.readInt(), steps = in.readInt();
            double[] delta = new double[model.dimension];
            for (int i = 0; i < delta.length; i++) delta[i] = in.readDouble();
            int count = in.readInt();
            if (count < 1 || count > 1024) throw new IOException("Invalid feedback ID count");
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) { ids[i] = in.readLong(); if (ids[i] < 1) throw new IOException("Invalid local ID"); }
            if (in.available() != 0) throw new IOException("Trailing local queue data");
            return new QueuedUpdate(new UpdatePayload(lease, proof, samples, steps, delta), ids, origin);
        } catch (IllegalArgumentException e) { throw new IOException("Corrupt local queue", e); }
    }
}
