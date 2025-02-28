/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.domain;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Status for a single transfer (stream, download or upload).
 *
 * @author Sindre Mehus
 */
public class TransferStatus {

    private static final int HISTORY_LENGTH = 200;
    private static final long SAMPLE_INTERVAL_MILLIS = 5000;

    private final UUID id = UUID.randomUUID();
    private final Player player;
    private Path file;
    private final AtomicLong bytesTransferred = new AtomicLong();
    private final AtomicLong bytesSkipped = new AtomicLong();
    private final AtomicLong bytesTotal = new AtomicLong();
    private final SampleHistory history = new SampleHistory();
    private volatile boolean terminated;
    private volatile boolean active = true;

    public TransferStatus(Player player) {
        this.player = player;
    }

    public UUID getId() {
        return id;
    }

    /**
     * Return the number of bytes transferred.
     *
     * @return The number of bytes transferred.
     */
    public long getBytesTransferred() {
        return bytesTransferred.get();
    }

    /**
     * Adds the given byte count to the total number of bytes transferred.
     *
     * @param byteCount The byte count.
     */
    public void addBytesTransferred(long byteCount) {
        bytesTransferred.addAndGet(byteCount);
        createSample(false);
    }

    /**
     * Sets the number of bytes transferred.
     *
     * @param bytesTransferred The number of bytes transferred.
     */
    public void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred.set(bytesTransferred);
        createSample(false);
    }

    private synchronized void createSample(boolean force) {
        long now = System.currentTimeMillis();

        if (history.isEmpty()) {
            history.add(new Sample(bytesTransferred.get(), now));
        } else {
            Sample lastSample = history.getLast();
            if (force || now - lastSample.getTimestamp() > TransferStatus.SAMPLE_INTERVAL_MILLIS) {
                history.add(new Sample(bytesTransferred.get(), now));
            }
        }
    }

    /**
     * Returns the number of milliseconds since the transfer status was last updated.
     *
     * @return Number of milliseconds, or <code>0</code> if never updated.
     */
    public long getMillisSinceLastUpdate() {
        if (history.isEmpty()) {
            return 0L;
        }
        return System.currentTimeMillis() - history.getLast().timestamp;
    }

    /**
     * Returns the total number of bytes, or 0 if unknown.
     *
     * @return The total number of bytes, or 0 if unknown.
     */
    public long getBytesTotal() {
        return bytesTotal.get();
    }

    /**
     * Sets the total number of bytes, or 0 if unknown.
     *
     * @param bytesTotal The total number of bytes, or 0 if unknown.
     */
    public void setBytesTotal(long bytesTotal) {
        this.bytesTotal.set(bytesTotal);
    }

    /**
     * Returns the number of bytes that has been skipped (for instance when
     * resuming downloads).
     *
     * @return The number of skipped bytes.
     */
    public long getBytesSkipped() {
        return bytesSkipped.get();
    }

    /**
     * Sets the number of bytes that has been skipped (for instance when
     * resuming downloads).
     *
     * @param bytesSkipped The number of skipped bytes.
     */
    public void setBytesSkipped(long bytesSkipped) {
        this.bytesSkipped.set(bytesSkipped);
    }


    /**
     * Adds the given byte count to the total number of bytes skipped.
     *
     * @param byteCount The byte count.
     */
    public void addBytesSkipped(long byteCount) {
        bytesSkipped.addAndGet(byteCount);
    }

    /**
     * Returns the file that is currently being transferred.
     *
     * @return The file that is currently being transferred.
     */
    public Path getFile() {
        return file;
    }

    /**
     * Sets the file that is currently being transferred.
     *
     * @param file The file that is currently being transferred.
     */
    public void setFile(Path file) {
        this.file = file;
    }

    /**
     * Returns the remote player for the stream.
     *
     * @return The remote player for the stream.
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Returns a history of samples for the stream
     *
     * @return A (copy of) the history list of samples.
     */
    public SampleHistory getHistory() {
        return new SampleHistory(history);
    }

    /**
     * Returns the history length in milliseconds.
     *
     * @return The history length in milliseconds.
     */
    public long getHistoryLengthMillis() {
        return TransferStatus.SAMPLE_INTERVAL_MILLIS * (TransferStatus.HISTORY_LENGTH - 1);
    }

    /**
     * Indicate that the stream should be terminated.
     */
    public void terminate() {
        terminated = true;
    }

    /**
     * Returns whether this stream has been terminated. Note that the <em>terminated
     * status</em> is cleared by this method.
     *
     * @return Whether this stream has been terminated.
     */
    public boolean terminated() {
        boolean result = terminated;
        terminated = false;
        return result;
    }

    /**
     * Returns whether this transfer is active, i.e., if the connection is still established.
     *
     * @return Whether this transfer is active.
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Sets whether this transfer is active, i.e., if the connection is still established.
     *
     * @param active Whether this transfer is active.
     */
    public synchronized void setActive(boolean active) {
        this.active = active;

        if (active) {
            setBytesSkipped(0L);
            setBytesTotal(0L);
            setBytesTransferred(0L);
        } else {
            createSample(true);
        }
    }

    /**
     * A sample containing a timestamp and the number of bytes transferred up to that point in time.
     */
    public static class Sample {
        private long bytesTransferred;
        private long timestamp;

        /**
         * Creates a new sample.
         *
         * @param bytesTransferred The total number of bytes transferred.
         * @param timestamp        A point in time, in milliseconds.
         */
        public Sample(long bytesTransferred, long timestamp) {
            this.bytesTransferred = bytesTransferred;
            this.timestamp = timestamp;
        }

        /**
         * Returns the number of bytes transferred.
         *
         * @return The number of bytes transferred.
         */
        public long getBytesTransferred() {
            return bytesTransferred;
        }

        /**
         * Returns the timestamp of the sample.
         *
         * @return The timestamp in milliseconds.
         */
        public long getTimestamp() {
            return timestamp;
        }
    }

    @Override
    public String toString() {
        return "TransferStatus-" + hashCode() + " [player: " + player.getId() + ", file: " +
                file + ", terminated: " + terminated + ", active: " + active + "]";
    }

    /**
     * Contains recent history of samples.
     */
    public static class SampleHistory extends CircularFifoQueue<Sample> {

        public SampleHistory() {
            super(HISTORY_LENGTH);
        }

        public SampleHistory(SampleHistory other) {
            super(HISTORY_LENGTH);
            addAll(other);
        }

        public Sample getLast() {
            return this.get(this.size() - 1);
        }
    }
}
