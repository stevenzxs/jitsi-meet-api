# jitsi-meet-api

Jitsi Meet Electron 桌面端的数据服务，使用 Spring Boot + MyBatis Plus + SQLite。

## 启动

```bash
mvn spring-boot:run
```

默认端口：`9000`

默认 API 前缀：`/mapi`

默认数据库：`data/jitsi-meet-api.sqlite.db`

可通过环境变量覆盖：

```bash
SERVER_PORT=9000
API_CONTEXT_PATH=/mapi
JITSI_MEET_DB_URL=jdbc:sqlite:data/jitsi-meet-api.sqlite.db
JITSI_TOKEN_APP_ID=zhsz
JITSI_TOKEN_APP_SECRET=change-me
JITSI_TOKEN_DOMAIN=192.168.199.203
JITSI_TOKEN_TTL_HOURS=168
```

## 接口

- `GET /mapi/open/desktop/meetings`：查询桌面端会议列表
- `POST /mapi/open/desktop/meetings`：创建即时会议记录
- `POST /mapi/open/desktop/meetings/schedule`：预定会议
- `POST /mapi/open/desktop/meetings/{roomName}/join-check`：加入会议前校验会议状态和密码
- `DELETE /mapi/open/desktop/meetings/{roomName}`：取消会议
- `POST /mapi/open/desktop/meetings/{roomName}/host-token`：校验主持人密钥并签发 Jitsi JWT
