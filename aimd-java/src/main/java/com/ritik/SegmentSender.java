/**
 * Copyright (C) 2015-2019 Regents of the University of California.
 *
 * @author: Jeff Thompson <jefft0@remap.ucla.edu>
 * @author: Ritik Kumar <rkumar1@cs.iitr.ac.in>
 * @author: From ndn-cxx util/segment-fetcher https://github.com/named-data/ndn-cxx
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * A copy of the GNU Lesser General Public License is in the file COPYING.
 */

package com.ritik;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SegmentSender implements OnAcknowledgement {

    private static final double MIN_SSTHRESH = 2.0;
    private static final Logger logger_ = Logger.getLogger(SegmentSender.class.getName());
    private final Options options_;
    private final SegmentSender.OnComplete onComplete_;
    private final SegmentSender.OnError onError_;
    private final ObjectInputStream ois;
    private final ObjectOutputStream oos;
    private ResponseListener listener;
    private Packet[] packets;
    private int count;
    private double cwnd_ = 1;
    private double ssThresh_;
    private RttEstimator rttEstimator_;
    private long highData_ = 0;
    private long recPoint_ = 0;
    private long highPacket_ = 0;
    private int nSegmentsInFlight_ = 0;
    private long nSegments_ = -1;
    private Map<Long, PendingSegment> pendingSegments_ = new HashMap();
    private HashSet<Integer> sentSegments_ = new HashSet<>();
    private Queue<Long> retxQueue_ = new LinkedList<>();
    private long nextSegmentNum_ = 0;
    private long timeLastSegmentReceived_ = 0;
    private boolean stop_ = false;
    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor_ =
            (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(5);
    private Runnable rtoTimeoutRunnable_ = new Runnable() {
        public void run() {
            if (shouldStop()) return;

            boolean hasTimeout = false;

            for (Map.Entry<Long, PendingSegment> entry : pendingSegments_.entrySet()) {
                PendingSegment ps = entry.getValue();
                if (ps.state != SegmentState.InRetxQueue) { // skip segments already in the retx queue
                    long timeElapsed = System.currentTimeMillis() - ps.sendTime;
                    if (timeElapsed > ps.rto) { // timer expired?
                        System.out.println("Packet " + entry.getKey() + " timeout");
                        hasTimeout = true;
                        enqueueForRetransmission(entry.getKey());
                    }
                }
            }

            if (hasTimeout) {
                if (!checkMaxTimeout()) return;

                rttEstimator_.backoffRto();
                if (sentSegments_.size() == 0) {
                    // Resend first Packet (until maximum receive timeout exceeded)
                    fetchFirstSegment(true);
                } else {
                    windowDecrease();
                    fetchSegmentsInWindow();
                }
            }

            // schedule the next check after predefined interval
            scheduledThreadPoolExecutor_.schedule(rtoTimeoutRunnable_, options_.rtoCheckInterval, TimeUnit.MILLISECONDS);
        }
    };

    private SegmentSender
            (int[] data, Options options,
             SegmentSender.VerifySegment verifySegment, SegmentSender.OnComplete onComplete,
             SegmentSender.OnError onError, ObjectInputStream ois, ObjectOutputStream oos) {

        Packet[] packets = new Packet[data.length];
        for (int i = 0; i < data.length; i++) {
            Packet p = new Packet(i, data[i]);
            packets[i] = p;
            System.out.println("Packets: " + p.getNumber() + " : " + p.getMessage());
        }
        System.out.println();
        this.packets = packets;
        this.count = data.length;
        this.nSegments_ = count;
        this.ois = ois;
        this.oos = oos;
        this.options_ = options;
        onComplete_ = onComplete;
        onError_ = onError;

        rttEstimator_ = new RttEstimator(options_.rttOptions);
        cwnd_ = options_.initCwnd;
        ssThresh_ = options_.initSsthresh;
        timeLastSegmentReceived_ = System.currentTimeMillis();
    }

    static void fetch
            (int[] data, Options options, SegmentSender.VerifySegment verifySegment,
             SegmentSender.OnComplete onComplete, SegmentSender.OnError onError, ObjectInputStream ois, ObjectOutputStream oos) {
        new SegmentSender(data, options, null, onComplete, onError, ois, oos)
                .run();
    }

    private void run() {
        fetchFirstSegment(false);
        listener = new ResponseListener(ois, this, count);
        listener.t.start();
        scheduledThreadPoolExecutor_.schedule(rtoTimeoutRunnable_, options_.rtoCheckInterval, TimeUnit.MILLISECONDS);
    }

    private void fetchFirstSegment(boolean isRetransmission) {
        Packet packet = packets[0];

//        if (isRetransmission) {
//            packet.refreshNonce();
//        }
        try {
            sendPacket(0, packet, isRetransmission);

        } catch (IOException ex) {
            try {
                onError_.onError
                        (SegmentSender.ErrorCode.IO_ERROR, "I/O error fetching the first segment " + ex);
            } catch (Throwable exception) {
                logger_.log(Level.SEVERE, "Error in onError", exception);
            }
        }
    }

    private void fetchSegmentsInWindow() {
        if (checkAllSegmentsReceived()) {
            // All segments have been retrieved
            finalizeFetch();
            return;
        }

        double availableWindowSize = cwnd_ - nSegmentsInFlight_;
        Map<Long, Boolean> segmentsToSend = new HashMap(); // The boolean indicates whether a retx or not

        while (availableWindowSize > 0) {

            if (!retxQueue_.isEmpty()) {
                Long key = retxQueue_.element();
                retxQueue_.remove();
                segmentsToSend.put(key, true);
            } else if (nSegments_ == -1 || nextSegmentNum_ < nSegments_) {
                if (sentSegments_.contains(nextSegmentNum_)) {
                    // Don't request a segment a second time if received in response to first "discovery" Packet
                    nextSegmentNum_++;
                    continue;
                }
                segmentsToSend.put(nextSegmentNum_++, false);
            } else {
                break;
            }
            availableWindowSize--;
        }

        for (Map.Entry<Long, Boolean> segment : segmentsToSend.entrySet()) {
            // Start with the original Packet to preserve any special selectors.
            Packet packet = packets[segment.getKey().intValue()];

            try {
                sendPacket(segment.getKey(), packet, segment.getValue());
            } catch (IOException ex) {
                ex.printStackTrace();
                try {
                    onError_.onError
                            (SegmentSender.ErrorCode.IO_ERROR, "I/O error fetching the next segment " + ex);
                } catch (Throwable exception) {
                    logger_.log(Level.SEVERE, "Error in onError", exception);
                }
            }
        }
    }

    private void sendPacket(long segmentNum, final Packet packet, boolean isRetransmission) throws IOException {
        int timeout = options_.useConstantPacketTimeout ? options_.maxTimeout : getEstimatedRto();

        if (isRetransmission) {
            PendingSegment pendingSegmentIt = pendingSegments_.get(segmentNum);
            if (pendingSegmentIt == null) return;
            pendingSegmentIt.state = SegmentState.Retransmitted;
            pendingSegmentIt.sendTime = System.currentTimeMillis();
            pendingSegmentIt.rto = timeout;
        } else {
            pendingSegments_.put(segmentNum, new PendingSegment(SegmentState.FirstPacket,
                    System.currentTimeMillis(), timeout));
            highPacket_ = segmentNum;
        }

        System.out.println("Sending packet: " + packet.getNumber() + " : " + packet.getMessage());

        oos.writeObject(packet);
        ++nSegmentsInFlight_;

    }

    private int getEstimatedRto() {
        // We don't want an Packet timeout greater than the maximum allowed timeout between the
        // successful receipt of segments
        return Math.min(options_.maxTimeout, (int) rttEstimator_.getEstimatedRto());
    }

    private Long findFirstEntry() {
        Map.Entry<Long, Long> o = (Map.Entry<Long, Long>) pendingSegments_.entrySet().toArray()[0];
        return o.getKey();
    }

    private boolean checkAllSegmentsReceived() {
        System.out.println("Checking ");
        boolean haveReceivedAllSegments = false;

        if (nSegments_ != -1 && sentSegments_.size() >= nSegments_) {
            System.out.println("in");

            haveReceivedAllSegments = true;
            // Verify that all segments in window have been received. If not, send Packets for missing segments.
            for (long i = 0; i < nSegments_; i++) {
                System.out.println("Checking " + i);
                if (!sentSegments_.contains((int) i)) {
                    System.out.println("noooo:  " + i);
                    retxQueue_.offer(i);
                    return false;
                }
            }
        }
        System.out.println("Checking " + haveReceivedAllSegments);
        return haveReceivedAllSegments;
    }

    private void finalizeFetch() {
        System.out.println("Complete: ");

        // We are finished.
        // Get the total size and concatenate to get content.
//        int totalSize = 0;
//        for (long i = 0; i < nSegments_; ++i) {
//            totalSize += (sentSegments_.get(i)).size();
//        }
//
//        ByteBuffer content = ByteBuffer.allocate(totalSize);
//        for (long i = 0; i < nSegments_; ++i) {
//            if (sentSegments_.get(i).size() != 0)
//                content.put((sentSegments_.get(i)).buf());
//        }
//        content.flip();
        stop();
        clean();

        try {
            onComplete_.onComplete();
        } catch (Throwable ex) {
            logger_.log(Level.SEVERE, "Error in onComplete", ex);
        }
    }

    @Override
    public void onAcknowledgement(Response response) {
        if (shouldStop()) return;

        nSegmentsInFlight_--;

        long segmentNum = response.getPacketNumber();

        // The first received Packet could have any segment ID
        final long pendingSegmentIt;

        if (sentSegments_.size() > 0) {
            if (sentSegments_.contains((int) segmentNum) || !pendingSegments_.containsKey(segmentNum))
                return;
            pendingSegmentIt = segmentNum;
        } else {
            pendingSegmentIt = findFirstEntry();
        }

        // We update the last receive time here instead of in the segment received callback so that the
        // transfer will not fail to terminate if we only received invalid Data packets.
        timeLastSegmentReceived_ = System.currentTimeMillis();

        if (pendingSegments_.get(pendingSegmentIt).state == SegmentState.FirstPacket) {
            rttEstimator_.addMeasurement(
                    timeLastSegmentReceived_ - pendingSegments_.get(pendingSegmentIt).sendTime,
                    Math.max(nSegmentsInFlight_ + 1, 1));
        }

        // Remove from pending segments map
        System.out.println("Removing : " + pendingSegmentIt);

        pendingSegments_.remove(pendingSegmentIt);

        // Copy data in segment to temporary buffer
        System.out.println("sent seg: ");
        sentSegments_.add((int) segmentNum);
        for (Integer integer : sentSegments_) {
            System.out.print(integer + " ");
        }
        System.out.println();
        if (sentSegments_.size() == 1) {
            if (segmentNum == 0) {
                // We received the first segment in response, so we can increment the next segment number
                nextSegmentNum_++;
            }
        }

        if (highData_ < segmentNum) {
            highData_ = segmentNum;
        }

        if (response.isCongestion() && !options_.ignoreCongMarks) {
            windowDecrease();
        } else {
            windowIncrease();
        }

        fetchSegmentsInWindow();
    }


    private void windowIncrease() {
        System.out.println("About to increase window");
        if (options_.useConstantCwnd || cwnd_ == options_.maxWindowSize) {
            return;
        }

        if (cwnd_ < ssThresh_) {
            cwnd_ += options_.aiStep; // additive increase
        } else {
            cwnd_ += options_.aiStep / cwnd_; // congestion avoidance
        }

        System.out.println("New window size: " + cwnd_);
    }

    private void windowDecrease() {
        System.out.println("About to decrease window");
        if (options_.disableCwa || highData_ > recPoint_) {
            recPoint_ = highPacket_;

            if (options_.useConstantCwnd) {
                return;
            }

            // Refer to RFC 5681, Section 3.1 for the rationale behind the code below
            ssThresh_ = Math.max(MIN_SSTHRESH, cwnd_ * options_.mdCoef); // multiplicative decrease
            cwnd_ = options_.resetCwndToInit ? options_.initCwnd : ssThresh_;
            System.out.println("New window size: " + cwnd_);
        }
    }

    public void onNack(Packet packet) {
        if (shouldStop()) return;

        long segmentNum = packet.getNumber();
        if (segmentNum == -1) return;

        afterNackOrTimeout(segmentNum);
    }

    private boolean enqueueForRetransmission(Long segmentNumber) {
        if (pendingSegments_.containsKey(segmentNumber)) {
            // Cancel timeout event and set status to InRetxQueue
            PendingSegment pendingSegmentIt = pendingSegments_.get(segmentNumber);
            pendingSegmentIt.state = SegmentState.InRetxQueue;
            nSegmentsInFlight_--;
        } else return false;

        if (sentSegments_.size() != 0) {
            System.out.println("Added retransmission for: " + segmentNumber);
            retxQueue_.offer(segmentNumber);
        }

        return true;
    }

    private void afterNackOrTimeout(long segmentNum) {
        if (!checkMaxTimeout()) return;

        if (!enqueueForRetransmission(segmentNum))
            return;

        rttEstimator_.backoffRto();
        if (sentSegments_.size() == 0) {
            // Resend first packet (until maximum receive timeout exceeded)
            fetchFirstSegment(true);
        } else {
            System.out.println("434");
            windowDecrease();
            fetchSegmentsInWindow();
        }
    }

    private boolean checkMaxTimeout() {
        if (System.currentTimeMillis() >= timeLastSegmentReceived_ + options_.maxTimeout) {
            // Fail transfer due to exceeding the maximum timeout between the successful receipt of segments
            try {
                onError_.onError
                        (ErrorCode.PACKET_TIMEOUT,
                                "Timeout exceeded");
            } catch (Throwable ex) {
                logger_.log(Level.SEVERE, "Error in onError", ex);
            }
            stop();
            return false;
        }
        return true;
    }

    public boolean isStopped() {
        return stop_;
    }

    /**
     * Stop fetching packets and clear the received data.
     */
    public void stop() {
        stop_ = true;
        listener.t.interrupt();
        listener.stop = true;
        try {
            oos.writeObject(new Packet(-1, -1));
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("error");
        }
    }

    /**
     * Check if we should stop fetching packets.
     *
     * @return The current state of stop_.
     */
    private boolean shouldStop() {
        if (stop_)
            clean();
        return stop_;
    }

    /**
     * Clean the data received
     */
    private void clean() {
        pendingSegments_.clear(); // cancels pending Packets and timeout events
        sentSegments_.clear(); // remove the received segments
    }

    private enum SegmentState {
        /**
         * the first Packet for this segment has been sent
         */
        FirstPacket,
        /**
         * the segment is awaiting Packet retransmission
         */
        InRetxQueue,
        /**
         * one or more retransmitted Packets have been sent for this segment
         */
        Retransmitted
    }

    public enum ErrorCode {
        PACKET_TIMEOUT,
        DATA_HAS_NO_SEGMENT,
        SEGMENT_VERIFICATION_FAILED,
        IO_ERROR,
        NACK_ERROR
    }

    public interface OnComplete {
        void onComplete();
    }

    public interface VerifySegment {
        boolean verifySegment(Object data);
    }

    public interface OnError {
        void onError(SegmentSender.ErrorCode errorCode, String message);
    }

    public static class Options {

        /**
         * if true, window size is kept at `initCwnd`
         */
        public boolean useConstantCwnd = false;
        /**
         * lifetime of sent Packets in milliseconds - independent of Packet timeout
         */
        public int packetLifetime = 4000;
        /**
         * initial congestion window size
         */
        public double initCwnd = 1.0;
        /**
         * maximum allowed time between successful receipt of segments in millisecond
         */
        public int maxTimeout = 60000;
        /**
         * initial slow start threshold
         */
        public double initSsthresh = Double.MAX_VALUE;
        /**
         * additive increase step (in segments)
         */
        public double aiStep = 1.0;
        /**
         * multiplicative decrease coefficient
         */
        public double mdCoef = 0.5;
        /**
         * interval for checking retransmission timer in millisecond
         */
        public int rtoCheckInterval = 30;
        /**
         * disable Conservative Window Adaptation
         */
        public boolean disableCwa = false;
        /**
         * reduce cwnd_ to initCwnd when loss event occurs
         */
        public boolean resetCwndToInit = false;
        /**
         * disable window decrease after congestion mark received
         */
        public boolean ignoreCongMarks = false;
        /**
         * max window size for sending packets
         */
        public int maxWindowSize = Integer.MAX_VALUE;
        /**
         * if true, Packet timeout is kept at `maxTimeout`
         */
        public boolean useConstantPacketTimeout = false;
        /**
         * options for RTT estimator
         */
        public RttEstimator.Options rttOptions = new RttEstimator.Options();

    }

    class ResponseListener implements Runnable {
        Thread t;
        boolean stop = false;
        ObjectInputStream ois;
        int x;
        Response reply;
        OnAcknowledgement onAcknowledgement;

        ResponseListener(ObjectInputStream o, OnAcknowledgement onAcknowledgement, int i) {
            t = new Thread(this);
            ois = o;
            this.onAcknowledgement = onAcknowledgement;
            x = i;
        }

        @Override
        public void run() {
            try {
//                int temp = 0;
                while (!stop) {
                    reply = (Response) ois.readObject();
                    System.out.println("\nReceived reply: " + reply.getPacketNumber());
                    if (reply.getPacketNumber() == -1) break;

                    onAcknowledgement.onAcknowledgement(reply);
                }
//                reply = temp;
            } catch (Exception e) {
//                System.out.println("Exception = > " + e.);
                e.printStackTrace();
            }
        }
    }

    class PendingSegment {

        public SegmentState state;
        public long sendTime;
        public long rto;

        public PendingSegment(SegmentState state, long sendTime, long rto) {
            this.state = state;
            this.sendTime = sendTime;
            this.rto = rto;
        }
    }
}