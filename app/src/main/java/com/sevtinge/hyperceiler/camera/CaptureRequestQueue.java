/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Durable, bounded FIFO. No Android dependencies; callers serialize admission with completion.
 * Terminal receipts are retained for deduplication, including after service/process restart.
 * JPEGs of failed publication requests are deliberately retained, never replaced with a new exposure.
 */
final class CaptureRequestQueue {
    static final int MAX_PENDING = 128;
    static final int MAX_RECEIPTS = 4096;
    static final int MAX_ATTEMPTS = 3;
    enum State { QUEUED, CAPTURING, JPEG_READY, PUBLISHED, FAILED }
    enum Admission { ACCEPTED, DUPLICATE, FULL }
    static final class Request {
        final String id;
        final long sequence;
        final long requestedAt;
        final long acceptedWall;
        State state = State.QUEUED;
        int attempts;
        String mediaUri = "";
        String result = "";
        Request(String id, long sequence, long requestedAt, long acceptedWall) {
            this.id = id; this.sequence = sequence; this.requestedAt = requestedAt;
            this.acceptedWall = acceptedWall;
        }
        boolean terminal() { return state == State.PUBLISHED || state == State.FAILED; }
        String displayName() { return "HC_" + id + ".jpg"; }
    }
    private final File directory;
    private final List<Request> requests = new ArrayList<>();
    private long nextSequence;

    CaptureRequestQueue(File directory) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create capture queue");
        File[] records = directory.listFiles((d, name) -> name.endsWith(".request"));
        if (records == null) throw new IOException("Cannot read capture queue");
        // Corrupt receipts are an explicit storage failure, not silently discarded requests.
        for (File file : records) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(file)) { p.load(in); }
            try {
                String id = p.getProperty("id");
                if (!validId(id) || !file.getName().equals(id + ".request"))
                    throw new IllegalArgumentException("Invalid request ID");
                Request r = new Request(id, Long.parseLong(p.getProperty("sequence")),
                        Long.parseLong(p.getProperty("requestedAt")), Long.parseLong(p.getProperty("acceptedWall")));
                r.state = State.valueOf(p.getProperty("state"));
                r.attempts = Integer.parseInt(p.getProperty("attempts"));
                r.mediaUri = p.getProperty("mediaUri", ""); r.result = p.getProperty("result", "");
                nextSequence = Math.max(nextSequence, r.sequence + 1);
                // A JPEG rename may precede its receipt update at process death. Recover bytes first.
                if (!r.terminal() && jpeg(r).isFile()) r.state = State.JPEG_READY;
                else if (r.state == State.CAPTURING) r.state = State.QUEUED;
                if (!r.terminal() && r.attempts >= MAX_ATTEMPTS) {
                    r.state = State.FAILED; r.result = "Interrupted at retry limit; JPEG retained if present";
                }
                requests.add(r);
            } catch (RuntimeException e) { throw new IOException("Corrupt capture receipt " + file.getName(), e); }
        }
        requests.sort(Comparator.comparingLong(r -> r.sequence));
    }
    static boolean validId(String id) {
        if (id == null) return false;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException e) { return false; }
    }
    synchronized Admission accept(String id, long requestedAt, long wall) throws IOException {
        if (!validId(id)) throw new IOException("Malformed request UUID");
        for (Request r : requests) if (r.id.equals(id)) return Admission.DUPLICATE;
        if (size() >= MAX_PENDING || requests.size() >= MAX_RECEIPTS) return Admission.FULL;
        Request r = new Request(id, nextSequence++, requestedAt, wall);
        persist(r); // A request is accepted only after a synced receipt and atomic rename.
        requests.add(r);
        return Admission.ACCEPTED;
    }
    synchronized boolean contains(String id) { return requests.stream().anyMatch(r -> r.id.equals(id)); }
    synchronized int size() { return (int) requests.stream().filter(r -> !r.terminal()).count(); }
    synchronized Request head() { return requests.stream().filter(r -> !r.terminal()).findFirst().orElse(null); }
    synchronized void begin(Request r) throws IOException {
        if (head() != r || r.terminal()) throw new IOException("Request is not FIFO head");
        if (r.attempts >= MAX_ATTEMPTS) throw new IOException("Retry limit reached");
        r.attempts++;
        r.state = jpeg(r).isFile() ? State.JPEG_READY : State.CAPTURING;
        persist(r);
    }
    synchronized void storeJpeg(Request r, ByteBuffer bytes) throws IOException {
        if (r.terminal() || jpeg(r).exists()) throw new IOException("Refusing duplicate JPEG/terminal write");
        int start = bytes.position(), end = bytes.limit();
        if (end - start < 4 || (bytes.get(start) & 255) != 255 || (bytes.get(start + 1) & 255) != 216
                || (bytes.get(end - 2) & 255) != 255 || (bytes.get(end - 1) & 255) != 217)
            throw new IOException("Empty/truncated JPEG");
        File part = new File(directory, r.id + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            byte[] chunk = new byte[64 * 1024];
            while (bytes.hasRemaining()) {
                int length = Math.min(chunk.length, bytes.remaining());
                bytes.get(chunk, 0, length); out.write(chunk, 0, length);
            }
            out.flush(); out.getFD().sync();
        }
        move(part, jpeg(r));
        r.state = State.JPEG_READY;
        persist(r);
    }
    synchronized void mediaUri(Request r, String uri) throws IOException { r.mediaUri = uri; persist(r); }
    synchronized void published(Request r) throws IOException {
        State previous = r.state;
        r.state = State.PUBLISHED; r.result = "JPEG published to DCIM/Camera";
        try { persist(r); }
        catch (IOException e) { r.state = previous; throw e; }
        // Failure to remove a published spool is harmless; the durable terminal receipt
        // prevents a second exposure/publication. Never change a successful photo to failed.
        jpeg(r).delete();
    }
    synchronized boolean failedAttempt(Request r, String reason, boolean retryable) throws IOException {
        r.result = reason;
        boolean retry = retryable && r.attempts < MAX_ATTEMPTS;
        r.state = retry ? (jpeg(r).isFile() ? State.JPEG_READY : State.QUEUED) : State.FAILED;
        persist(r);
        return retry;
    }
    synchronized void failPending(String reason) throws IOException {
        for (Request r : requests) if (!r.terminal()) { r.state = State.FAILED; r.result = reason; persist(r); }
    }
    File jpeg(Request r) { return new File(directory, r.id + ".jpg"); }
    private void persist(Request r) throws IOException {
        Properties p = new Properties();
        p.setProperty("id", r.id); p.setProperty("sequence", Long.toString(r.sequence));
        p.setProperty("requestedAt", Long.toString(r.requestedAt));
        p.setProperty("acceptedWall", Long.toString(r.acceptedWall));
        p.setProperty("state", r.state.name()); p.setProperty("attempts", Integer.toString(r.attempts));
        p.setProperty("mediaUri", r.mediaUri); p.setProperty("result", r.result);
        File temp = new File(directory, r.id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { p.store(out, "Screen-off capture receipt"); out.getFD().sync(); }
        move(temp, new File(directory, r.id + ".request"));
    }
    private static void move(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
