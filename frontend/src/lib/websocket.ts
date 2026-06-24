import { Client, IFrame, IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";

const WS_URL = "http://localhost:8080/ws";

export function createStompClient(
  token: string,
  onConnect: () => void,
  onError?: (frame: IFrame) => void
): Client {
  const client = new Client({
    webSocketFactory: () => new SockJS(WS_URL) as unknown as WebSocket,
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect,
    onStompError:
      onError ??
      ((frame) => {
        console.error("STOMP error:", frame.headers["message"], frame.body);
      }),
  });

  client.activate();
  return client;
}

export function subscribeToSession(
  client: Client,
  sessionId: string,
  onMessage: (msg: unknown) => void
) {
  return client.subscribe(`/topic/game/${sessionId}`, (message: IMessage) => {
    try {
      onMessage(JSON.parse(message.body));
    } catch {
      console.error("Failed to parse WS message:", message.body);
    }
  });
}

export function subscribeToDm(
  client: Client,
  sessionId: string,
  onMessage: (msg: unknown) => void
) {
  return client.subscribe(
    `/topic/game/${sessionId}/dm`,
    (message: IMessage) => {
      try {
        onMessage(JSON.parse(message.body));
      } catch {
        console.error("Failed to parse DM message:", message.body);
      }
    }
  );
}

export function subscribeToErrors(
  client: Client,
  onError: (error: string) => void
) {
  return client.subscribe("/user/queue/errors", (message: IMessage) => {
    try {
      const data = JSON.parse(message.body);
      onError(data.error ?? "Unknown error");
    } catch {
      onError(message.body);
    }
  });
}

export function sendAction(
  client: Client,
  sessionId: string,
  action: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/action`,
    body: JSON.stringify({ action }),
  });
}
