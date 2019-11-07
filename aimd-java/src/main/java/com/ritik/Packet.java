package com.ritik;

import java.io.Serializable;

public class Packet implements Serializable {

    //class attributes, constructors, setters and getters as shown above

    private int number;
    private int message;
    private boolean isRetransmission = false;

    public Packet(int number, int message) {
        super();
        this.number = number;
        this.message = message;
    }

    public Packet() {
        super();
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public int getMessage() {
        return message;
    }

    public void setMessage(int message) {
        this.message = message;
    }

    public boolean isRetransmission() {
        return isRetransmission;
    }

    public void setRetransmission(boolean retransmission) {
        isRetransmission = retransmission;
    }

}