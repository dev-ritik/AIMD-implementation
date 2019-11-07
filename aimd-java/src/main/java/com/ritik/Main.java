package com.ritik;

public class Main {
    public static void main(String[] argv) throws Exception {

        System.out.println("Start!!");
        System.out.println(getVersion());
        Client.run();

    }

    private static int getVersion() {
        String version = System.getProperty("java.version");
        if(version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if(dot != -1) { version = version.substring(0, dot); }
        } return Integer.parseInt(version);
    }

}