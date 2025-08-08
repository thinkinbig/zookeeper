# ZooKeeper 部署方式

本项目提供了多种部署 ZooKeeper 的方式，从简单到复杂：

## 1. Docker Compose 部署 (最简单 - 推荐用于开发测试)

Docker Compose 是最简单的一键部署方式，适合开发和测试环境。

### 前置条件

```bash
# 安装 Docker Compose
# Ubuntu/Debian
sudo apt install docker-compose

# 或者使用 Podman Compose
sudo apt install podman-compose
```

### 一键部署

```bash
# 1. 启动集群
docker-compose up -d

# 2. 检查状态
docker-compose ps

# 3. 查看日志
docker-compose logs -f

# 4. 停止集群
docker-compose down
```

### 使用 Podman Compose

```bash
# 使用 Podman Compose (无需 root)
podman-compose up -d

# 检查状态
podman-compose ps

# 停止集群
podman-compose down
```

## 2. Quadlets 部署 (适合长期部署)

Quadlets 是 Podman 的新一代容器编排方式，类似于 Docker Compose 但更简洁。

### 安装 Quadlets

```bash
# 在 Fedora/RHEL/CentOS 上
sudo dnf install podman-compose

# 在 Ubuntu/Debian 上
sudo apt install podman-compose
```

### 部署 ZooKeeper 集群

```bash
# 1. 复制 Quadlets 文件到用户目录
mkdir -p ~/.config/containers/systemd/
cp quadlets/*.container ~/.config/containers/systemd/
cp quadlets/*.network ~/.config/containers/systemd/

# 2. 重新加载 systemd 用户服务
systemctl --user daemon-reload

# 3. 启动网络
systemctl --user enable zookeeper-cluster.network
systemctl --user start zookeeper-cluster.network

# 4. 启动 ZooKeeper 集群
systemctl --user enable zookeeper-cluster.container
systemctl --user enable zookeeper2.container
systemctl --user enable zookeeper3.container

systemctl --user start zookeeper-cluster.container
systemctl --user start zookeeper2.container
systemctl --user start zookeeper3.container

# 5. 检查状态
systemctl --user status zookeeper-cluster.container
systemctl --user status zookeeper2.container
systemctl --user status zookeeper3.container
```

### 管理集群

```bash
# 停止集群
systemctl --user stop zookeeper-cluster.container zookeeper2.container zookeeper3.container

# 重启集群
systemctl --user restart zookeeper-cluster.container zookeeper2.container zookeeper3.container

# 查看日志
journalctl --user -u zookeeper-cluster.container -f
```

## 3. Helm Chart 部署 (适合生产环境)

### 前置条件

- Kubernetes 集群
- Helm 3.x

### 部署步骤

```bash
# 1. 添加 Helm 仓库（可选）
helm repo add bitnami https://charts.bitnami.com/bitnami

# 2. 安装 ZooKeeper
helm install zookeeper ./helm/zookeeper

# 3. 使用自定义配置
helm install zookeeper ./helm/zookeeper \
  --set zookeeper.replicaCount=5 \
  --set zookeeper.persistence.size=10Gi

# 4. 升级部署
helm upgrade zookeeper ./helm/zookeeper

# 5. 卸载
helm uninstall zookeeper
```

### 配置选项

```yaml
# values.yaml 主要配置
zookeeper:
  replicaCount: 3                    # 节点数量
  image:
    repository: zookeeper
    tag: "3.8"
  persistence:
    enabled: true
    size: 8Gi
  resources:
    requests:
      memory: "512Mi"
      cpu: "250m"
    limits:
      memory: "1Gi"
      cpu: "500m"
```

## 4. 连接测试

### Java 应用程序

```java
// 连接到集群
String hostPort = "localhost:2181,localhost:2182,localhost:2183";
ZooKeeper zk = new ZooKeeper(hostPort, 3000, watcher);
```

### 命令行测试

```bash
# 检查集群状态
echo stat | nc localhost 2181
echo mntr | nc localhost 2181

# 使用 ZooKeeper CLI
podman exec -it zookeeper1 zkCli.sh
```

## 5. 监控和运维

### 集群状态检查

```bash
# 检查所有节点状态
for i in {1..3}; do
  echo "Node $i:"
  echo stat | nc localhost 218$i
done
```

### 日志查看

```bash
# Compose 方式
docker-compose logs -f zookeeper1

# Quadlets 方式
journalctl --user -u zookeeper-cluster.container -f

# Helm 方式
kubectl logs -f statefulset/zookeeper-0
```

## 6. 故障排除

### 常见问题

1. **节点无法启动**
   - 检查端口是否被占用
   - 检查数据目录权限
   - 查看容器日志

2. **集群无法选举 Leader**
   - 确保所有节点网络连通
   - 检查 ZOO_SERVERS 配置
   - 验证节点 ID 唯一性

3. **客户端连接失败**
   - 检查防火墙设置
   - 验证连接字符串格式
   - 确认服务正常运行

### 调试命令

```bash
# 检查容器状态
podman ps -a

# 查看容器日志
podman logs zookeeper1

# 进入容器调试
podman exec -it zookeeper1 /bin/bash

# 检查网络
podman network ls
podman network inspect zookeeper-net
```

## 7. 性能优化

### 系统调优

```bash
# 增加文件描述符限制
echo "* soft nofile 65536" >> /etc/security/limits.conf
echo "* hard nofile 65536" >> /etc/security/limits.conf

# 调整内核参数
echo "vm.max_map_count=262144" >> /etc/sysctl.conf
sysctl -p
```

### ZooKeeper 配置优化

```yaml
# 在 values.yaml 中调整
config:
  zooCfg: |
    tickTime=2000
    initLimit=10
    syncLimit=5
    maxClientCnxns=60
    autopurge.snapRetainCount=3
    autopurge.purgeInterval=1
    snapCount=10000
    maxSessionTimeout=40000
    minSessionTimeout=4000
```

## 8. 备份和恢复

### 数据备份

```bash
# 备份数据目录
tar -czf zookeeper-backup-$(date +%Y%m%d).tar.gz ./data/

# 备份配置
cp quadlets/*.container ./backup/
cp helm/zookeeper/values.yaml ./backup/
```

### 数据恢复

```bash
# 恢复数据
tar -xzf zookeeper-backup-20231201.tar.gz

# 重启服务
systemctl --user restart zookeeper-cluster.container
```
