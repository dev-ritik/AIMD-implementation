package com.ritik;
/*Server Program*/

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

public class Server {
    public static void main(String args[]) throws Exception {
        ServerSocket server = new ServerSocket(6262);
        System.out.println("Server established.");
        Socket client = server.accept();
        ObjectOutputStream oos = new ObjectOutputStream(client.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(client.getInputStream());
        System.out.println("Client is now connected.");
        int x = (Integer) ois.readObject();
        System.out.println("Received x: " + x);
//        return;
        Random r = new Random();
        while (true) {
            Packet k = (Packet) ois.readObject();
            if (k.getNumber() == -1) {
                break;
            }
            System.out.println("Received k: " + k.getNumber() + " : " + k.getMessage());
            System.out.println();

            int mod = r.nextInt(4) + 2;
            if ((k.getNumber() + 1) % mod == 0) {
                System.out.println("Error found. Acknowledgement not sent. ");
            } else if ((k.getNumber()+1) % 500 == 0) {
                System.out.println("Error found in packet. Negative acknowledgement sent. ");
            } else {
                System.out.println("Acknowledgement sent for: " + k.getNumber());
                oos.writeObject(new Response(true, k.getNumber()));
            }
            System.out.println();
        }
        System.out.println("Client finished sending data.Exiting");
//        oos.writeObject(new Response(-1, -1));
    }
}