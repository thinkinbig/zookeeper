package yangqi.code;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

/**
 * ClusterExecutor.java - Demonstrate how to connect to a ZooKeeper cluster
 * 
 * @author zeyuli 2025-08-08
 */

public class ClusterExecutor implements Watcher, Runnable, DataMonitorListener {

    String      znode;
    DataMonitor dm;
    ZooKeeper   zk;
    String      exec[];
    Process     child;

    public ClusterExecutor(String hostPort, String znode, String exec[]) throws KeeperException, IOException {
        this.exec = exec;
        // Connect to the cluster of ZooKeeper nodes
        // Make sure the cluster is running
        zk = new ZooKeeper(hostPort, 3000, this);
        dm = new DataMonitor(zk, znode, null, this);
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        // Connect to the cluster of ZooKeeper nodes
        // Make sure the cluster is running
        String hostPort = "localhost:2181,localhost:2182,localhost:2183";
        String znode = "/yangqi_test";
        String exec[] = new String[] { "date" };
        
        try {
            new ClusterExecutor(hostPort, znode, exec).run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /***************************************************************************
     * We do process any events ourselves, we just need to forward them on.
     * 
     * @see org.apache.zookeeper.Watcher#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    public void process(WatchedEvent event) {
        dm.process(event);
    }

    public void run() {
        try {
            synchronized (this) {
                while (!dm.dead) {
                    System.out.println("===========CLUSTER EXECUTOR START TO WAIT===========");
                    wait();
                    System.out.println("===========CLUSTER EXECUTOR STOP WAIT===========");
                }
            }
        } catch (InterruptedException e) {
        }
    }

    public void closing(int rc) {
        synchronized (this) {
            System.out.println("===========CLUSTER EXECUTOR START TO NOTIFY ALL===========");
            notifyAll();
            System.out.println("===========CLUSTER EXECUTOR START TO NOTIFY ALL===========");
        }
    }

    static class StreamWriter extends Thread {

        OutputStream os;
        InputStream  is;

        StreamWriter(InputStream is, OutputStream os) {
            this.is = is;
            this.os = os;
            start();
        }

        public void run() {
            byte b[] = new byte[80];
            int rc;
            try {
                System.out.println("===========START TO WRITE===========");
                while ((rc = is.read(b)) > 0) {
                    os.write(b, 0, rc);
                }
                System.out.println("===========STOP TO WRITE===========");
            } catch (IOException e) {
            }
        }
    }

    public void exists(byte[] data) {
        if (data == null) {
            if (child != null) {
                System.out.println("Killing process");
                child.destroy();
                try {
                    child.waitFor();
                } catch (InterruptedException e) {
                }
            }
            child = null;
        } else {
            if (child != null) {
                System.out.println("Stopping child");
                child.destroy();
                try {
                    child.waitFor();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("===SHOW DATA FROM CLUSTER===");
            System.out.println(new String(data));
            try {
                System.out.println("Starting child");
                child = Runtime.getRuntime().exec(exec);
                new StreamWriter(child.getInputStream(), System.out);
                new StreamWriter(child.getErrorStream(), System.err);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
