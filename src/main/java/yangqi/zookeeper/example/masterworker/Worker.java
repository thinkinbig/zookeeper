package yangqi.zookeeper.example.masterworker;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.Objects;

public class Worker implements Watcher {


    ZooKeeper zk;
    String hostPort;
    String serverId;
    String status;

    public Worker(String hostPort, String serverId) {
        this.hostPort = hostPort;
        this.serverId = serverId;
    }

    public void startZk() throws IOException, InterruptedException {
        zk = new ZooKeeper(hostPort, 200000, this);
    }

    @Override
    public void process(WatchedEvent event) {
        System.out.println(event.toString() + ", " + hostPort);
    }

    void register() {
        zk.create("/workers/worker-" + serverId,
                "Idle".getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL,
                (rc, path, ctx, name) -> {
                    switch (KeeperException.Code.get(rc)) {
                        case CONNECTIONLOSS:
                            register();
                            break;
                        case OK:
                        case NODEEXISTS:
                            break;
                        default:
                            System.out.println(KeeperException.Code.get(rc));
                            break;
                    }
                }, null);
    }

    synchronized private void updateStatus(String status) {
        if (Objects.equals(status, this.status)) {
            zk.setData("/workers/" + serverId, status.getBytes(), -1, new AsyncCallback.StatCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx, Stat stat) {
                    if (Objects.requireNonNull(KeeperException.Code.get(rc)) == KeeperException.Code.CONNECTIONLOSS) {
                        updateStatus(status);
                        return;
                    }
                }
            }, status);
        }
    }

    public void setStatus(String status) {
        this.status = status;
        updateStatus(status);
    }
}
