/*Server Program*/
import java.net.*;
import java.io.*;
import java.util.*;

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
//        int k = (Integer) ois.readObject();
//        System.out.println("Received k: " + k);
        int j = 0;
//        int i = (Integer) ois.readObject();
//        System.out.println("Received i: " + i);
        boolean flag = true;
        Random r = new Random(6);
        int mod = r.nextInt(6);
        while (mod == 1 || mod == 0)
            mod = r.nextInt(6);
        while (true) {
            int k = (Integer) ois.readObject();
            System.out.println("Received k: " + k);
            if (k == -1)
                break;
            int data = (Integer) ois.readObject();
            System.out.println("Received data: " + data);
            int c = k;
            System.out.println();
            System.out.println();
            if (k == j) {
                System.out.println("Frame " + k + " recieved" + "\n" + "Data: " + data);
                j++;
                System.out.println();
            } else
                System.out.println("Frames recieved not in correct order" + "\n" + "Expected farme: " + j + "\n" + " Recieved frame no: "+k);
            System.out.println();
            if (j % mod == 0 && flag) {
                System.out.println("Error found.Acknowledgement not sent. ");
                flag = !flag;
                j--;
            } else if (k == j - 1) {
                oos.writeObject(k);
                System.out.println("Acknowledgement sent");
            }
            System.out.println();
            if (j % mod == 0)
                flag = !flag;
        }
        System.out.println("Client finished sending data.Exiting");
        oos.writeObject(-1);
    }
}