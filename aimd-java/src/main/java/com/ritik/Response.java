package com.ritik;

import java.io.Serializable;

public class Response implements Serializable {

    private boolean successful = false;
    private boolean congestion = false;
    private int packetNumber;

    public Response(boolean successful, int packetNumber) {
        this.successful = successful;
        this.packetNumber = packetNumber;
    }

    public Response(boolean successful, boolean congestion, int packetNumber) {
        this.successful = successful;
        this.congestion = congestion;
        this.packetNumber = packetNumber;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public void setSuccessful(boolean successful) {
        this.successful = successful;
    }

    public int getPacketNumber() {
        return packetNumber;
    }

    public void setPacketNumber(int packetNumber) {
        this.packetNumber = packetNumber;
    }

    public boolean isCongestion() {
        return congestion;
    }

    public void setCongestion(boolean congestion) {
        this.congestion = congestion;
    }
}
