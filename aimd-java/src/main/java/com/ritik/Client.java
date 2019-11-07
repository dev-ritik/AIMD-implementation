package com.ritik;
/*Client Program*/

import java.io.*;
import java.net.Socket;
import java.util.Random;

public class Client {
    public static void run() throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Enter no.of frames to be sent:");
        int count = 20;
//        int count = Integer.parseInt(br.readLine());
        int[] data = new int[count];
        int h = 0;
        for (int i = 0; i < count; i++) {
            System.out.print("Enter data for frame no " + i + " => ");
            Random random = new Random();
//            data[i] = Integer.parseInt(br.readLine());
            data[i] = random.nextInt(50);
        }
        System.out.println(h);
        Socket client = new Socket("localhost", 6262);
        ObjectInputStream ois = new ObjectInputStream(client.getInputStream());
        ObjectOutputStream oos = new ObjectOutputStream(client.getOutputStream());
        System.out.println("Connected with server.");
        oos.writeObject(count);

        SegmentSender.OnComplete onComplete = new SegmentSender.OnComplete() {
            @Override
            public void onComplete() {
                System.out.println("Sending finished successfully!");
            }
        };

        SegmentSender.OnError onError = new SegmentSender.OnError() {
            @Override
            public void onError(SegmentSender.ErrorCode errorCode, String message) {
                System.out.println("onError:  " + message);
            }
        };

        SegmentSender.VerifySegment verifySegment = new SegmentSender.VerifySegment() {
            @Override
            public boolean verifySegment(Object data) {
                return true;
            }
        };

        SegmentSender.fetch(data, new SegmentSender.Options(), verifySegment, onComplete, onError, ois, oos);
    }
}
